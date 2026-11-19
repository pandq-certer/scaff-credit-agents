package com.bai.docparse;

import com.bai.common.BaiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 真实引擎提供者：图像走 SYS-01 OCR 服务（8101 /v1/ocr），扫描 PDF 走 SYS-01 解析服务（8102 /v1/parse），
 * 均以 multipart 上传；图像经 ocrImageBlocks 返回 block 级结构（text/confidence/bbox）供版面还原，
 * 正文按 block 顺序拼接后交给 doc-parse 现有分类（classifyReal）与
 * KV 正则白名单（FIELD_PATTERNS）真实抽取链路。
 * <p>说明：8102 /v1/parse 不接收图像格式（40001），图像类仍走 8101；无文本层 PDF 才走 8102。
 * <p>降级策略：服务不可达/超时 → DCP_2001 MODEL_DEGRADED（真实模式不伪造值）；
 * SYS-01 业务错误按 error_code 映射为 DCP 错误码。
 */
public class RealDocParseProvider implements DocParseProvider {

    private static final String OCR_PATH = "/v1/ocr";
    private static final String PARSE_PATH = "/v1/parse";
    private static final String BOUNDARY = "----Sys01DocParseBoundary";

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String parseBaseUrl;
    private final Duration timeout;
    private final Duration pdfTimeout;

    /** 兼容构造：扫描 PDF 使用本机 8102、15 分钟超时（多页扫描件 OCR 较慢） */
    public RealDocParseProvider(String baseUrl, Duration timeout) {
        this(baseUrl, "http://localhost:8102", timeout, Duration.ofMinutes(15));
    }

    public RealDocParseProvider(String baseUrl, String parseBaseUrl, Duration timeout, Duration pdfTimeout) {
        this.baseUrl = trimSlash(baseUrl);
        this.parseBaseUrl = trimSlash(parseBaseUrl);
        this.timeout = timeout;
        this.pdfTimeout = pdfTimeout;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public String ocrImage(byte[] content, String filename, String requestId) {
        ImageContent ic = ocrImageBlocks(content, filename, requestId);
        return ic == null ? "" : ic.text();
    }

    /** 图像结构化 OCR：blocks 保留 text/confidence/bbox（0-1 相对坐标 x1/y1/x2/y2），供 doc-parse 还原版面与阅读顺序 */
    @Override
    public ImageContent ocrImageBlocks(byte[] content, String filename, String requestId) {
        JsonNode resp = post(baseUrl, OCR_PATH, filename, content, requestId, timeout);
        StringJoiner joiner = new StringJoiner("\n");
        List<DocParseProvider.OcrBlock> blocks = new ArrayList<>();
        int maxPage = 1;
        for (JsonNode n : resp.path("blocks")) {
            String text = n.path("text").asText("").trim();
            if (text.isEmpty()) {
                continue;
            }
            joiner.add(text);
            int page = n.path("page").asInt(1);
            maxPage = Math.max(maxPage, page);
            JsonNode bbox = n.path("bbox");
            blocks.add(new DocParseProvider.OcrBlock(
                    page, text, n.path("confidence").asDouble(0.0),
                    bbox.size() >= 4 ? bbox.get(0).asDouble(0) : 0,
                    bbox.size() >= 4 ? bbox.get(1).asDouble(0) : 0,
                    bbox.size() >= 4 ? bbox.get(2).asDouble(0) : 0,
                    bbox.size() >= 4 ? bbox.get(3).asDouble(0) : 0));
        }
        return new ImageContent(joiner.toString(), List.copyOf(blocks), maxPage);
    }

    /** 扫描 PDF：调 8102 /v1/parse，取 blocks 正文与 tables 矩阵（表格文字并入正文供 KV 抽取） */
    @Override
    public PdfContent parsePdf(byte[] content, String filename, String requestId) {
        JsonNode resp = post(parseBaseUrl, PARSE_PATH, filename, content, requestId, pdfTimeout);
        StringJoiner joiner = new StringJoiner("\n");
        for (JsonNode n : resp.path("blocks")) {
            String text = n.path("text").asText("").trim();
            if (!text.isEmpty()) {
                joiner.add(text);
            }
        }
        List<DocParseDtos.Table> tables = new ArrayList<>();
        for (JsonNode t : resp.path("tables")) {
            int page = t.path("page").asInt(1);
            int rows = t.path("rows").asInt(0);
            int cols = t.path("cols").asInt(0);
            List<String> header = new ArrayList<>();
            List<DocParseDtos.Cell> cells = new ArrayList<>();
            JsonNode grid = t.path("cells");
            for (int r = 0; r < grid.size(); r++) {
                JsonNode row = grid.get(r);
                for (int c = 0; c < row.size(); c++) {
                    String value = row.get(c).asText("");
                    if (r == 0) {
                        header.add(value);
                    }
                    cells.add(new DocParseDtos.Cell(r, c, value, 0));
                    if (!value.isBlank()) {
                        joiner.add(value);
                    }
                }
            }
            tables.add(new DocParseDtos.Table(page, rows, cols, header, cells, 0.95));
        }
        return new PdfContent(joiner.toString(), tables, resp.path("page_count").asInt(0));
    }

    private JsonNode post(String targetBase, String path, String filename, byte[] content,
                          String requestId, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(targetBase + path))
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("X-Trace-Id", requestId == null ? "" : requestId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(filename, content)))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new BaiException(DocParseErrorCodes.MODEL_DEGRADED,
                    "DCP_2001 SYS-01 解析超时（" + timeout.toSeconds() + "s），已降级：稍后重试或转人工兜底");
        } catch (IOException e) {
            throw unavailable(targetBase);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(targetBase);
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw serviceError(resp);
        }
        try {
            return mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new BaiException(DocParseErrorCodes.INTERNAL_ERROR,
                    "DCP_2000 SYS-01 响应非法（非 JSON），requestId=" + requestId);
        }
    }

    private byte[] multipart(String filename, byte[] content) {
        String safe = filename == null || filename.isBlank()
                ? "upload.bin" : filename.replace("\"", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 512);
        String head = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + safe + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private BaiException unavailable(String targetBase) {
        return new BaiException(DocParseErrorCodes.MODEL_DEGRADED,
                "DCP_2001 SYS-01 服务不可达（" + targetBase + "），文档解析已降级：稍后重试或转人工兜底");
    }

    /** SYS-01 错误码映射：40001 格式不支持、40003 质量不合格（附 refuse_reasons）、其余内部错误 */
    private BaiException serviceError(HttpResponse<String> resp) {
        int code = resp.statusCode();
        String message = "";
        List<String> reasons = new ArrayList<>();
        try {
            JsonNode err = mapper.readTree(resp.body());
            code = err.path("error_code").asInt(code);
            message = err.path("message").asText("");
            err.path("refuse_reasons").forEach(r -> reasons.add(r.asText("")));
        } catch (Exception ignored) {
            // 非 JSON 错误体：按 HTTP 状态兜底
        }
        String suffix = reasons.isEmpty() ? "" : "，refuse_reasons=" + reasons;
        return switch (code) {
            case 40001 -> new BaiException(DocParseErrorCodes.FORMAT_INVALID,
                    "DCP_1006 SYS-01 拒绝文件：" + message);
            case 40003 -> new BaiException(DocParseErrorCodes.UNPARSABLE,
                    "DCP_1007 图像质量不合格：" + message + suffix);
            default -> new BaiException(DocParseErrorCodes.INTERNAL_ERROR,
                    "DCP_2000 SYS-01 图像解析失败（HTTP " + resp.statusCode() + "）：" + message + suffix);
        };
    }
}
