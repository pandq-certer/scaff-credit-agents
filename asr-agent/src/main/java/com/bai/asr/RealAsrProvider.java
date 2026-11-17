package com.bai.asr;

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
import java.util.List;
import java.util.Locale;

/**
 * 真实引擎提供者：HTTP 调统一 AI 推理网关（/v1/asr/recognize，GPU 声学模型内核，数据不出行）。
 * <p>降级策略：网关不可达/超时 → ASR_2001 MODEL_UNAVAILABLE；网关业务错误码（ASR_xxxx）原样透传；
 * 非 2xx 无业务码/响应非 JSON → ASR_2000 INTERNAL_ERROR 包装。
 */
public class RealAsrProvider implements AsrProvider {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final Duration timeout;

    public RealAsrProvider(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public AsrDtos.AsrResult recognize(byte[] content, String filename, String scene, String lang,
                                       boolean speakerDiarization, boolean maskSensitive, String requestId) {
        ObjectNode req = mapper.createObjectNode();
        req.put("content_base64", Base64.getEncoder().encodeToString(content));
        req.put("audio_format", extOf(filename));
        req.put("sample_rate", 16000);
        req.put("scene", scene == null || scene.isBlank() ? "general" : scene);
        req.put("lang", lang == null || lang.isBlank() ? "" : lang);
        req.put("speaker_diarization", speakerDiarization);
        req.put("mask_sensitive", maskSensitive);
        JsonNode resp = post("/v1/asr/recognize", req, requestId);
        List<AsrDtos.Segment> segments = new ArrayList<>();
        JsonNode arr = resp.path("segments");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                segments.add(new AsrDtos.Segment(
                        n.path("index").asInt(0),
                        n.hasNonNull("speaker") ? n.get("speaker").asText() : null,
                        n.path("start_ms").asLong(0),
                        n.path("end_ms").asLong(0),
                        n.path("text").asText(""),
                        n.path("confidence").asDouble(0),
                        n.path("status").asText("OK"),
                        n.hasNonNull("reason") ? n.get("reason").asText() : null));
            }
        }
        return new AsrDtos.AsrResult(
                resp.path("asr_id").asText(""),
                resp.path("scene").asText(scene == null || scene.isBlank() ? "general" : scene),
                resp.path("lang").asText(lang == null || lang.isBlank() ? "zh" : lang),
                resp.path("duration_ms").asLong(0),
                resp.path("status").asText("SUCCESS"),
                List.copyOf(segments),
                resp.path("full_text").asText(""),
                resp.path("elapsed_ms").asLong(0));
    }

    private static String extOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /** 网关调用：X-Request-Id 透传 + 请求超时；IOException/中断 → ASR_2001 降级 */
    private JsonNode post(String path, ObjectNode body, String requestId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-Request-Id", requestId == null ? "" : requestId)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw unavailable(path, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(path, "interrupted");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw gatewayError(path, response.statusCode(), response.body());
        }
        try {
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new BaiException(AsrErrorCodes.INTERNAL_ERROR,
                    "ASR_2000 网关响应非 JSON：" + path);
        }
    }

    private BaiException unavailable(String path, String detail) {
        return new BaiException(AsrErrorCodes.MODEL_UNAVAILABLE,
                "ASR_2001 模型服务不可用（降级中），稍后重试或人工听录兜底：网关 " + path + " " + detail);
    }

    /** 非 2xx：网关业务错误码（error.code）原样透传，缺省包装 ASR_2000 */
    private BaiException gatewayError(String path, int statusCode, String body) {
        try {
            JsonNode err = mapper.readTree(body == null ? "" : body).path("error");
            String code = err.path("code").asText("");
            String message = err.path("message").asText("");
            if (!code.isBlank()) {
                return new BaiException(code, message.isBlank() ? path + " HTTP " + statusCode : message);
            }
        } catch (IOException ignored) {
            // 响应体非 JSON，走下方统一包装
        }
        return new BaiException(AsrErrorCodes.INTERNAL_ERROR,
                "ASR_2000 网关调用失败：" + path + " HTTP " + statusCode);
    }
}
