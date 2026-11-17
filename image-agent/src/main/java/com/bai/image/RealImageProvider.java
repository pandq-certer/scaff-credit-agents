package com.bai.image;

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

/**
 * 真实引擎提供者：HTTP 调统一 AI 推理网关（GPU 质检内核）。
 * <p>降级策略：网关不可达/超时 → IMG_2001 MODEL_DEGRADED（稍后重试或传统算法兜底）；
 * 网关业务错误码（IMG_xxxx）原样透传给上层。
 */
public class RealImageProvider implements ImageProvider {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final Duration timeout;

    public RealImageProvider(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public ImageDtos.QualityInfo check(byte[] content, String requestId) {
        ObjectNode req = mapper.createObjectNode();
        req.put("content_base64", Base64.getEncoder().encodeToString(content));
        req.putArray("checks").add("clarity").add("brightness").add("contrast")
                .add("blur").add("occlusion");
        JsonNode resp = post("/v1/image/check", req, requestId);
        double score = resp.path("quality_score").asDouble(0);
        boolean usable = resp.hasNonNull("passed") ? resp.get("passed").asBoolean() : score >= 0.60;
        // 五维明细网关可选返回；缺省以综合分填充（判定只依赖 score/passed）
        double clarity = resp.path("clarity").asDouble(score);
        double brightness = resp.path("brightness").asDouble(score);
        double contrast = resp.path("contrast").asDouble(score);
        double blur = resp.path("blur").asDouble(0);
        double occlusion = resp.path("occlusion").asDouble(0);
        String suggestion = score >= 0.80 ? "ACCEPT" : score >= 0.60 ? "RETRY" : "REJECT";
        return new ImageDtos.QualityInfo(round2(clarity), round2(brightness), round2(contrast),
                round2(blur), round2(occlusion), score, usable, suggestion);
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
            throw new BaiException(ImageErrorCodes.INTERNAL_ERROR,
                    "IMG_2000 推理网关响应非法（非 JSON），requestId=" + requestId);
        }
    }

    private BaiException unavailable() {
        return new BaiException(ImageErrorCodes.MODEL_DEGRADED,
                "IMG_2001 推理网关不可达（" + baseUrl + "），已降级：稍后重试或传统算法兜底");
    }

    private BaiException gatewayError(HttpResponse<String> resp) {
        try {
            JsonNode err = mapper.readTree(resp.body()).path("error");
            String code = err.path("code").asText("");
            String message = err.path("message").asText("");
            if (code.isBlank()) {
                return new BaiException(ImageErrorCodes.INTERNAL_ERROR,
                        "IMG_2000 推理网关 HTTP " + resp.statusCode());
            }
            return new BaiException(code, "图像质检网关错误: " + message);
        } catch (Exception e) {
            return new BaiException(ImageErrorCodes.INTERNAL_ERROR,
                    "IMG_2000 推理网关 HTTP " + resp.statusCode());
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
