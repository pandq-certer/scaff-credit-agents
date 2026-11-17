package com.bai.tts;

import com.bai.common.BaiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 真实引擎提供者：HTTP 调统一 AI 推理网关（/v1/tts/synthesize，GPU 声学模型+声码器内核）。
 * <p>降级策略：网关不可达/超时 → TTS_2001 MODEL_UNAVAILABLE；网关业务错误码（TTS_xxxx）原样透传；
 * 非 2xx 无业务码/响应非 JSON → TTS_2000 INTERNAL_ERROR 包装。
 * <p>网关返回 {content_base64, audio_format, duration_ms}；ttsId/audioUrl/segments 等业务元信息由本侧填充
 * （分段按确定性指纹逐字均估，仅供字幕/高亮对齐参考）。
 */
public class RealTtsProvider implements TtsProvider {

    private static final Logger log = LoggerFactory.getLogger(RealTtsProvider.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern SENTENCE = Pattern.compile("(?<=[。！？；!?;])\\s*");

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final Duration timeout;
    private final AtomicLong seq = new AtomicLong();

    public RealTtsProvider(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public TtsDtos.TtsResult synthesize(String normText, String voiceId, String scene, String style,
                                        double rate, String format, int sampleRate, String requestId) {
        long begin = System.currentTimeMillis();
        ObjectNode req = mapper.createObjectNode();
        req.put("text", normText);
        req.put("voice", voiceId);
        req.put("speed", rate);
        req.put("format", format);
        req.put("sample_rate", sampleRate);
        req.put("scene", scene);
        req.put("style", style);
        JsonNode resp = post("/v1/tts/synthesize", req, requestId);
        String ttsId = "TTS" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet() % 10000);
        long durationMs = resp.path("duration_ms").asLong(0);
        String fmt = resp.path("audio_format").asText(format);
        String audioUrl = "https://tts-audio.oss.example.com/tts/" + ttsId + "." + fmt
                + "?expires=" + (System.currentTimeMillis() + 30 * 60 * 1000L);   // 音频下载 30 分钟有效
        long elapsed = System.currentTimeMillis() - begin;
        TtsDtos.TtsResult result = new TtsDtos.TtsResult(ttsId, voiceId, scene, fmt, sampleRate,
                durationMs, audioUrl, estimateSegments(normText, rate), elapsed);
        log.info("语音合成(real): requestId={}, ttsId={}, voiceId={}, scene={}, format={}, durationMs={}",
                requestId, ttsId, voiceId, scene, fmt, durationMs);
        return result;
    }

    /** 分段元信息按确定性指纹逐字均估（网关不返回分段，仅供字幕/高亮对齐参考） */
    private List<TtsDtos.Segment> estimateSegments(String normText, double rate) {
        String fingerprint = sha256(normText);
        int h = Math.abs(fingerprint.hashCode());
        long perCharMs = 170 + h % 90;
        List<TtsDtos.Segment> segments = new ArrayList<>();
        long cursor = 300L;
        for (String part : SENTENCE.split(normText)) {
            String segText = part.trim();
            if (segText.isEmpty()) {
                continue;
            }
            long segMs = Math.round(segText.length() * perCharMs / rate) + 200L;
            segments.add(new TtsDtos.Segment(segments.size() + 1, segText, cursor, cursor + segMs));
            cursor += segMs + 150L;
        }
        return List.copyOf(segments);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 网关调用：X-Request-Id 透传 + 请求超时；IOException/中断 → TTS_2001 降级 */
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
            throw new BaiException(TtsErrorCodes.INTERNAL_ERROR,
                    "TTS_2000 网关响应非 JSON：" + path);
        }
    }

    private BaiException unavailable(String path, String detail) {
        return new BaiException(TtsErrorCodes.MODEL_UNAVAILABLE,
                "TTS_2001 模型服务不可用（降级中），稍后重试或备用音色：网关 " + path + " " + detail);
    }

    /** 非 2xx：网关业务错误码（error.code）原样透传，缺省包装 TTS_2000 */
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
        return new BaiException(TtsErrorCodes.INTERNAL_ERROR,
                "TTS_2000 网关调用失败：" + path + " HTTP " + statusCode);
    }
}
