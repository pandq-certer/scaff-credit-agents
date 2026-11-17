package com.bai.docparse;

import com.bai.common.BaiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.StringJoiner;

/**
 * 真实引擎提供者：复用统一 AI 推理网关的 OCR 能力（POST /v1/ocr/recognize，PaddleOCR 内核，
 * 部署于行内 x86/信创服务器，数据不出行），把图像正文转文本后交给 doc-parse 现有
 * 分类（classifyReal）与 KV 正则白名单（FIELD_PATTERNS）真实抽取链路。
 * <p>降级策略：网关不可达/超时 → DCP_2001 MODEL_DEGRADED（真实模式不伪造值，报错稍后重试或转人工）；
 * 网关业务错误码原样透传给上层。
 */
public class RealDocParseProvider implements DocParseProvider {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final Duration timeout;

    public RealDocParseProvider(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public String ocrImage(byte[] content, String filename, String requestId) {
        ObjectNode req = mapper.createObjectNode();
        req.put("content_base64", Base64.getEncoder().encodeToString(content));
        req.put("filename", filename == null ? "" : filename);
        req.put("doc_type", "AUTO");
        JsonNode resp = post("/v1/ocr/recognize", req, requestId);
        StringJoiner joiner = new StringJoiner("\n");
        for (JsonNode n : resp.path("blocks")) {
            String text = n.path("text").asText("").trim();
            if (!text.isEmpty()) {
                joiner.add(text);
            }
        }
        return joiner.toString();
    }

    private JsonNode post(String path, ObjectNode body, String requestId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-Request-Id", requestId == null ? "" : requestId)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw gatewayError(resp);
        }
        try {
            return mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new BaiException(DocParseErrorCodes.INTERNAL_ERROR,
                    "DCP_2000 推理网关响应非法（非 JSON），requestId=" + requestId);
        }
    }

    private BaiException unavailable() {
        return new BaiException(DocParseErrorCodes.MODEL_DEGRADED,
                "DCP_2001 推理网关不可达（" + baseUrl + "），图像解析已降级：稍后重试或转人工兜底");
    }

    private BaiException gatewayError(HttpResponse<String> resp) {
        try {
            JsonNode err = mapper.readTree(resp.body()).path("error");
            String code = err.path("code").asText("");
            String message = err.path("message").asText("");
            if (code.isBlank()) {
                return new BaiException(DocParseErrorCodes.INTERNAL_ERROR,
                        "DCP_2000 推理网关 HTTP " + resp.statusCode());
            }
            return new BaiException(code, "文档解析网关错误: " + message);
        } catch (Exception e) {
            return new BaiException(DocParseErrorCodes.INTERNAL_ERROR,
                    "DCP_2000 推理网关 HTTP " + resp.statusCode());
        }
    }
}
