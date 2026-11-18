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
 * 真实引擎提供者：HTTP 调 SYS-01 OCR 服务（POST /v1/ocr，multipart 文件上传），
 * 把图像正文按 block 顺序拼接后交给 doc-parse 现有分类（classifyReal）与
 * KV 正则白名单（FIELD_PATTERNS）真实抽取链路。
 * <p>说明：SYS-01 解析服务（8102 /v1/parse）不接收图像格式（40001），
 * 图像类文件的正文抽取只能走 OCR 服务（8101），故本适配器指向 8101。
 * <p>降级策略：服务不可达/超时 → DCP_2001 MODEL_DEGRADED（真实模式不伪造值）；
 * SYS-01 业务错误按 error_code 映射为 DCP 错误码。
 */
public class RealDocParseProvider implements DocParseProvider {

    private static final String OCR_PATH = "/v1/ocr";
    private static final String BOUNDARY = "----Sys01DocParseBoundary";

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final Duration timeout;

    public RealDocParseProvider(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public String ocrImage(byte[] content, String filename, String requestId) {
        JsonNode resp = post(OCR_PATH, filename, content, requestId);
        StringJoiner joiner = new StringJoiner("\n");
        for (JsonNode n : resp.path("blocks")) {
            String text = n.path("text").asText("").trim();
            if (!text.isEmpty()) {
                joiner.add(text);
            }
        }
        return joiner.toString();
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
            throw new BaiException(DocParseErrorCodes.MODEL_DEGRADED,
                    "DCP_2001 SYS-01 图像识别超时（" + timeout.toSeconds() + "s，已降级：稍后重试或转人工兜底");
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

    private BaiException unavailable() {
        return new BaiException(DocParseErrorCodes.MODEL_DEGRADED,
                "DCP_2001 SYS-01 服务不可达（" + baseUrl + "），图像解析已降级：稍后重试或转人工兜底");
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
