package com.bai.ocr;

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
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 真实引擎提供者：HTTP 调 SYS-01 智能文档处理服务的 OCR 接口（POST /v1/ocr，multipart 文件上传）。
 * <p>契约映射（SYS-01 修正稿 5.3 → 本服务 OcrResult）：
 * trace_id→ocrId、quality.score→qualityScore、blocks[].bbox(0-1)→Block.bbox、elapsed 由调用方计时；
 * SYS-01 不做 KV 结构化，fields 固定为空表。
 * <p>降级策略：服务不可达/超时 → OCR_2001 MODEL_UNAVAILABLE（稍后重试或人工兜底）；
 * SYS-01 业务错误按 error_code 映射为本服务错误码。
 */
public class RealOcrProvider implements OcrProvider {

    private static final String OCR_PATH = "/v1/ocr";
    private static final String BOUNDARY = "----Sys01OcrBoundary";

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final Duration timeout;

    public RealOcrProvider(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public OcrDtos.OcrResult recognize(byte[] content, String filename, String docType, String requestId) {
        String type = docType == null || docType.isBlank() ? "AUTO" : docType.trim().toUpperCase();
        long start = System.currentTimeMillis();
        JsonNode resp = post(OCR_PATH, filename, content, requestId);
        long elapsed = System.currentTimeMillis() - start;
        return new OcrDtos.OcrResult(
                resp.path("trace_id").asText(""),
                type,
                "SUCCESS",
                resp.path("quality").path("score").asDouble(0),
                blocks(resp.path("blocks")),
                new LinkedHashMap<>(),
                elapsed);
    }

    /**
     * imageRef 直连识别：SYS-01 只提供文件上传识别，无影像标识直连接口。
     * <p>不伪造字段表，按 MODEL_UNAVAILABLE 返回，调用方改走文件通道或人工兜底。
     */
    @Override
    public OcrProvider.RefResult recognizeRef(String imageRef, String docType, String requestId) {
        throw new BaiException(OcrErrorCodes.MODEL_UNAVAILABLE,
                "OCR_2001 SYS-01 未提供 imageRef 直连识别接口（imageRef=" + imageRef
                        + "），请改用文件上传识别通道");
    }

    private List<OcrDtos.Block> blocks(JsonNode arr) {
        List<OcrDtos.Block> blocks = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                List<Double> bbox = new ArrayList<>();
                n.path("bbox").forEach(b -> bbox.add(b.asDouble()));
                blocks.add(new OcrDtos.Block(
                        n.path("text").asText(""),
                        n.path("confidence").asDouble(0),
                        bbox.isEmpty() ? null : List.copyOf(bbox),
                        null,
                        null));
            }
        }
        return List.copyOf(blocks);
    }

    private JsonNode post(String path, String filename, byte[] content, String requestId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("X-Trace-Id", requestId == null ? "" : requestId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(filename, content)))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new BaiException(OcrErrorCodes.MODEL_UNAVAILABLE,
                    "OCR_2001 SYS-01 识别超时（" + timeout.toSeconds() + "s，" + baseUrl + "），已降级：稍后重试或走人工兜底");
        } catch (IOException e) {
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw serviceError(resp);
        }
        try {
            return mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new BaiException(OcrErrorCodes.INTERNAL_ERROR,
                    "OCR_2000 SYS-01 响应非法（非 JSON），requestId=" + requestId);
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

    private BaiException unavailable() {
        return new BaiException(OcrErrorCodes.MODEL_UNAVAILABLE,
                "OCR_2001 SYS-01 服务不可达（" + baseUrl + "），已降级：稍后重试或走人工兜底");
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
            case 40001 -> new BaiException(OcrErrorCodes.FORMAT_UNSUPPORTED,
                    "OCR_1006 SYS-01 拒绝文件：" + message);
            case 40003 -> new BaiException(OcrErrorCodes.IMAGE_CORRUPTED,
                    "OCR_1007 图像质量不合格：" + message + suffix);
            default -> new BaiException(OcrErrorCodes.INTERNAL_ERROR,
                    "OCR_2000 SYS-01 识别失败（HTTP " + resp.statusCode() + "）：" + message + suffix);
        };
    }
}
