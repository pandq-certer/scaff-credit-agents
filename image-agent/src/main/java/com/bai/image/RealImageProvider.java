package com.bai.image;

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

/**
 * 真实引擎提供者：HTTP 调 SYS-01 OCR 服务的质量明细接口（POST /v1/quality，multipart 上传）。
 * <p>契约映射：quality_score→score、passed→usable、clarity/brightness/contrast/blur 直接取用；
 * SYS-01 未实现遮挡检测（响应以 unsupported_metrics=["occlusion"] 显式声明），occlusion 按 0 处理。
 * <p>降级策略：服务不可达/超时 → IMG_2001 MODEL_DEGRADED（稍后重试或传统算法兜底）。
 */
public class RealImageProvider implements ImageProvider {

    private static final String QUALITY_PATH = "/v1/quality";
    private static final String BOUNDARY = "----Sys01ImageBoundary";

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final Duration timeout;

    public RealImageProvider(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public ImageDtos.QualityInfo check(byte[] content, String requestId) {
        JsonNode resp = post(QUALITY_PATH, content, requestId);
        double score = resp.path("quality_score").asDouble(0);
        boolean usable = resp.hasNonNull("passed") ? resp.get("passed").asBoolean() : score >= 0.60;
        double clarity = resp.path("clarity").asDouble(score);
        double brightness = resp.path("brightness").asDouble(score);
        double contrast = resp.path("contrast").asDouble(score);
        double blur = resp.path("blur").asDouble(0);
        double occlusion = resp.path("occlusion").asDouble(0);
        String suggestion = score >= 0.80 ? "ACCEPT" : score >= 0.60 ? "RETRY" : "REJECT";
        return new ImageDtos.QualityInfo(round2(clarity), round2(brightness), round2(contrast),
                round2(blur), round2(occlusion), score, usable, suggestion);
    }

    private JsonNode post(String path, byte[] content, String requestId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("X-Trace-Id", requestId == null ? "" : requestId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(content)))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
            throw new BaiException(ImageErrorCodes.INTERNAL_ERROR,
                    "IMG_2000 SYS-01 响应非法（非 JSON），requestId=" + requestId);
        }
    }

    private byte[] multipart(byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 256);
        String head = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"image.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private BaiException unavailable() {
        return new BaiException(ImageErrorCodes.MODEL_DEGRADED,
                "IMG_2001 SYS-01 服务不可达（" + baseUrl + "），已降级：稍后重试或传统算法兜底");
    }

    /** SYS-01 错误码映射：40001 格式不支持、40002 解码失败、其余内部错误 */
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
            case 40001 -> new BaiException(ImageErrorCodes.FORMAT_INVALID,
                    "IMG_1006 SYS-01 拒绝文件：" + message);
            case 40002 -> new BaiException(ImageErrorCodes.UNPROCESSABLE,
                    "IMG_1007 图像不可解析：" + message);
            default -> new BaiException(ImageErrorCodes.INTERNAL_ERROR,
                    "IMG_2000 SYS-01 质检失败（HTTP " + resp.statusCode() + "）：" + message + suffix);
        };
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
