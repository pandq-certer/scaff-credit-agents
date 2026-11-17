package com.bai.tts;

import com.bai.common.BaiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Mock 引擎：确定性合成仿真（文本+音色+场景+语气+语速+格式+采样率 SHA-256 指纹驱动时长/分段，
 * 同入参复现）。
 * <p>输出结构与真实引擎一致，便于下游联调；行为与改造前仿真版完全一致（回归基线）。
 */
public class MockTtsProvider implements TtsProvider {

    private static final Logger log = LoggerFactory.getLogger(MockTtsProvider.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** 分句：按句末标点切分（标点保留在前句） */
    private static final Pattern SENTENCE = Pattern.compile("(?<=[。！？；!?;])\\s*");

    private final AtomicLong seq = new AtomicLong();

    @Override
    public TtsDtos.TtsResult synthesize(String norm, String voice, String scene, String style,
                                        double rate, String format, int sampleRate, String requestId) {
        long begin = System.currentTimeMillis();
        // 确定性指纹：文本+音色+场景+语气+语速+格式+采样率 → SHA-256（同入参复现）
        String fingerprint = sha256(norm + "|" + voice + "|" + scene + "|" + style + "|" + rate + "|" + format + "|" + sampleRate);
        int h = Math.abs(fingerprint.hashCode());
        long perCharMs = 170 + h % 90;             // 每字 170—259ms（语速 1.0 基线）
        String ttsId = "TTS" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet() % 10000);
        // 分句合成（FR-10 分段元信息）
        List<TtsDtos.Segment> segments = new ArrayList<>();
        long cursor = 300L;
        for (String part : SENTENCE.split(norm)) {
            String segText = part.trim();
            if (segText.isEmpty()) {
                continue;
            }
            long segMs = Math.round(segText.length() * perCharMs / rate) + 200L;
            segments.add(new TtsDtos.Segment(segments.size() + 1, segText, cursor, cursor + segMs));
            cursor += segMs + 150L;                // 段间停顿 ≥150ms
        }
        if (segments.isEmpty()) {
            throw new BaiException(TtsErrorCodes.TEXT_UNSYNTHESIZABLE, "TTS_1007 文本不可合成（无有效内容）");
        }
        long durationMs = cursor + 500L;           // 尾部静音 ≥500ms 收尾
        String audioUrl = "https://tts-audio.oss.example.com/tts/" + ttsId + "." + format
                + "?expires=" + (System.currentTimeMillis() + 30 * 60 * 1000L);   // 音频下载 30 分钟有效
        long elapsed = System.currentTimeMillis() - begin;
        TtsDtos.TtsResult result = new TtsDtos.TtsResult(ttsId, voice, scene, format, sampleRate, durationMs,
                audioUrl, segments, elapsed);
        log.info("语音合成: requestId={}, ttsId={}, voiceId={}, scene={}, style={}, rate={}, format={}, "
                        + "sampleRate={}, textLen={}, durationMs={}, segments={}",
                requestId, ttsId, voice, scene, style, rate, format, sampleRate, norm.length(), durationMs, segments.size());
        return result;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
