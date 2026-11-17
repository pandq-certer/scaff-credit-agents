package com.bai.face;

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
import java.util.Set;

/**
 * 真实引擎提供者：HTTP 调统一 AI 推理网关（GPU 人脸核验内核）。
 * <p>网关契约：POST /v1/face/verify，请求 {content_base64, content_base64_ref, scene, verify_type}
 * → 响应 {similarity, passed, liveness_score}，比对阈值 0.75；SEARCH 检索为二期能力不接网关（real 下 search=null）。
 * <p>降级策略：网关不可达/超时 → FAC_2001 MODEL_UNAVAILABLE（稍后重试或转柜面核身）；
 * 网关业务错误码（FAC_xxxx）原样透传给上层。
 */
public class RealFaceVerifyProvider implements FaceVerifyProvider {

    private static final double COMPARE_THRESHOLD = 0.75;

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final Duration timeout;

    public RealFaceVerifyProvider(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public String modelVersion() {
        return "gw-face-v1.0.0";
    }

    @Override
    public FaceOutcome verifyFace(byte[] content, byte[] idPhoto, String idPhotoRef, String scene,
                                  Set<String> steps, String livenessAction, String requestId) {
        ObjectNode req = baseRequest(content, idPhoto, scene);
        req.put("verify_type", "VERIFY");
        JsonNode resp = post(req, requestId);
        FaceVerifyDtos.Quality quality = neutralQuality();
        FaceVerifyDtos.Liveness liveness = resp.hasNonNull("liveness_score")
                ? new FaceVerifyDtos.Liveness(round2(resp.get("liveness_score").asDouble()), "NONE", livenessAction)
                : null;
        FaceVerifyDtos.Compare compare = steps.contains("compare") ? toCompare(resp, idPhotoRef) : null;
        return new FaceOutcome(quality, liveness, compare, null);
    }

    @Override
    public FaceOutcome compareFace(byte[] content, byte[] idPhoto, String idPhotoRef, String scene, String requestId) {
        ObjectNode req = baseRequest(content, idPhoto, scene);
        req.put("verify_type", "COMPARE");
        JsonNode resp = post(req, requestId);
        return new FaceOutcome(neutralQuality(), null, toCompare(resp, idPhotoRef), null);
    }

    /** 网关响应 {similarity, passed} → 比对结论（passed 缺省 similarity>=0.75） */
    private FaceVerifyDtos.Compare toCompare(JsonNode resp, String idPhotoRef) {
        double score = round2(resp.path("similarity").asDouble(0));
        boolean pass = resp.hasNonNull("passed") ? resp.get("passed").asBoolean() : score >= COMPARE_THRESHOLD;
        return new FaceVerifyDtos.Compare(score, COMPARE_THRESHOLD, pass,
                idPhotoRef != null ? idPhotoRef : "PHOTO_001");
    }

    /** 网关不返回质量明细，以中性满分填充（分级判定只依赖比对/活体分数与原因码） */
    private FaceVerifyDtos.Quality neutralQuality() {
        return new FaceVerifyDtos.Quality(1.0, 1.0, 1.0, 1.0, 0.0, true);
    }

    private ObjectNode baseRequest(byte[] content, byte[] idPhoto, String scene) {
        ObjectNode req = mapper.createObjectNode();
        req.put("content_base64", Base64.getEncoder().encodeToString(content));
        if (idPhoto != null) {
            req.put("content_base64_ref", Base64.getEncoder().encodeToString(idPhoto));
        }
        req.put("scene", scene);
        return req;
    }

    private JsonNode post(ObjectNode body, String requestId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/face/verify"))
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
            throw new BaiException(FaceVerifyErrorCodes.INTERNAL_ERROR,
                    "FAC_2000 推理网关响应非法（非 JSON），requestId=" + requestId);
        }
    }

    private BaiException unavailable() {
        return new BaiException(FaceVerifyErrorCodes.MODEL_UNAVAILABLE,
                "FAC_2001 推理网关不可达（" + baseUrl + "），已降级：稍后重试或转柜面核身");
    }

    private BaiException gatewayError(HttpResponse<String> resp) {
        try {
            JsonNode err = mapper.readTree(resp.body()).path("error");
            String code = err.path("code").asText("");
            String message = err.path("message").asText("");
            if (code.isBlank()) {
                return new BaiException(FaceVerifyErrorCodes.INTERNAL_ERROR,
                        "FAC_2000 推理网关 HTTP " + resp.statusCode());
            }
            return new BaiException(code, "人脸核验网关错误: " + message);
        } catch (Exception e) {
            return new BaiException(FaceVerifyErrorCodes.INTERNAL_ERROR,
                    "FAC_2000 推理网关 HTTP " + resp.statusCode());
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
