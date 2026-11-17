package com.bai.ocr;

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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实引擎提供者：HTTP 调统一 AI 推理网关（Python/PaddleOCR 内核，部署于行内 x86/信创服务器，数据不出行）。
 * <p>降级策略：网关不可达/超时 → OCR_2001 MODEL_UNAVAILABLE（调用方稍后重试或走人工兜底）；
 * 网关业务错误码（OCR_xxxx）原样透传给上层。
 */
public class RealOcrProvider implements OcrProvider {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final Duration timeout;

    public RealOcrProvider(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public OcrDtos.OcrResult recognize(byte[] content, String filename, String docType, String requestId) {
        String type = docType == null || docType.isBlank() ? "AUTO" : docType.trim().toUpperCase();
        ObjectNode req = mapper.createObjectNode();
        req.put("content_base64", Base64.getEncoder().encodeToString(content));
        req.put("filename", filename == null ? "" : filename);
        req.put("doc_type", type);
        JsonNode resp = post("/v1/ocr/recognize", req, requestId);
        return new OcrDtos.OcrResult(
                resp.path("ocr_id").asText(""),
                resp.path("doc_type").asText(type),
                resp.path("status").asText("SUCCESS"),
                resp.path("quality_score").asDouble(0),
                blocks(resp.path("blocks")),
                fields(resp.path("fields")),
                resp.path("elapsed_ms").asLong(0));
    }

    @Override
    public RefResult recognizeRef(String imageRef, String docType, String requestId) {
        ObjectNode req = mapper.createObjectNode();
        req.put("image_ref", imageRef);
        req.put("doc_type", docType);
        JsonNode resp = post("/v1/ocr/ref", req, requestId);
        Map<String, String> fields = new LinkedHashMap<>();
        JsonNode fn = resp.path("fields");
        fn.fieldNames().forEachRemaining(k -> {
            JsonNode v = fn.get(k);
            fields.put(k, v.isObject() ? v.path("value").asText("") : v.asText(""));
        });
        return new RefResult(fields, resp.path("confidence").asDouble(0));
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
                        n.hasNonNull("flag") ? n.get("flag").asText() : null,
                        n.hasNonNull("flag_reason") ? n.get("flag_reason").asText() : null));
            }
        }
        return List.copyOf(blocks);
    }

    private Map<String, OcrDtos.Field> fields(JsonNode obj) {
        Map<String, OcrDtos.Field> fields = new LinkedHashMap<>();
        if (obj.isObject()) {
            obj.fieldNames().forEachRemaining(k -> {
                JsonNode v = obj.get(k);
                if (v.isObject()) {
                    fields.put(k, new OcrDtos.Field(
                            v.path("value").asText(""),
                            v.path("confidence").asDouble(0),
                            v.hasNonNull("flag") ? v.get("flag").asText() : null,
                            v.hasNonNull("flag_reason") ? v.get("flag_reason").asText() : null));
                } else {
                    fields.put(k, new OcrDtos.Field(v.asText(""), 1.0, null, null));
                }
            });
        }
        return fields;
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
            throw new BaiException(OcrErrorCodes.INTERNAL_ERROR,
                    "OCR_2000 推理网关响应非法（非 JSON），requestId=" + requestId);
        }
    }

    private BaiException unavailable() {
        return new BaiException(OcrErrorCodes.MODEL_UNAVAILABLE,
                "OCR_2001 推理网关不可达（" + baseUrl + "），已降级：稍后重试或走人工兜底");
    }

    private BaiException gatewayError(HttpResponse<String> resp) {
        try {
            JsonNode err = mapper.readTree(resp.body()).path("error");
            String code = err.path("code").asText("");
            String message = err.path("message").asText("");
            if (code.isBlank()) {
                return new BaiException(OcrErrorCodes.INTERNAL_ERROR,
                        "OCR_2000 推理网关 HTTP " + resp.statusCode());
            }
            return new BaiException(code, "OCR 网关错误: " + message);
        } catch (Exception e) {
            return new BaiException(OcrErrorCodes.INTERNAL_ERROR,
                    "OCR_2000 推理网关 HTTP " + resp.statusCode());
        }
    }
}
