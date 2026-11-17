package com.bai.asr;

import com.bai.common.BaiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock 引擎：确定性仿真转写（音频指纹哈希驱动句级结果，不解码音频）。
 * <p>输出结构与真实引擎一致，便于下游联调；行为与改造前仿真版完全一致（回归基线）。
 */
public class MockAsrProvider implements AsrProvider {

    private static final Logger log = LoggerFactory.getLogger(MockAsrProvider.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> SCENES = Set.of("collection", "customer-service", "voice-command",
            "recording", "general");
    private static final Set<String> LANGS = Set.of("zh", "zh-dialect", "zh-en");
    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern ID_CARD = Pattern.compile("\\d{17}[0-9Xx]");
    private static final Pattern BANK_CARD = Pattern.compile("\\d{16,19}");

    /** 按场景内置确定性转写语料（含手机号/金额/日期，便于演示 ITN 规整与脱敏） */
    private static final Map<String, List<String>> SCRIPTS = Map.of(
            "collection", List.of(
                    "张先生您好，这里是某银行贷后管理中心。您名下贷款本期应还一万两千元整，已逾期十五天。",
                    "我这周三之前把钱还上，可以吗？我的手机号是13812345678，麻烦登记一下。",
                    "好的，已为您登记承诺还款，请按时履约，逾期将影响征信记录。"),
            "customer-service", List.of(
                    "您好，智能客服为您服务，请问需要办理什么业务？",
                    "我想查一下我的还款计划，贷款合同号是HT-2026-000123。",
                    "已为您查询到本期应还金额 5,800 元，还款日为 2026-11-15。"),
            "voice-command", List.of(
                    "查询我的还款计划。",
                    "好的，正在为您查询名下贷款还款计划。"),
            "recording", List.of(
                    "本次双录开始，请客户确认贷款金额与期限条款。",
                    "我确认申请金额二十万元，期限三十六期，利率已知悉。",
                    "客户已当面确认，双录影像与转写文本同步归档。"),
            "general", List.of(
                    "这是一段信贷业务通用语音（仿真）。",
                    "转写引擎按确定性指纹输出句级结果，仅供联调。"));

    private final AtomicLong seq = new AtomicLong();

    @Override
    public AsrDtos.AsrResult recognize(byte[] content, String filename, String scene, String lang,
                                       boolean speakerDiarization, boolean maskSensitive, String requestId) {
        return simulateRef(fingerprint(content), scene, lang, speakerDiarization, maskSensitive, requestId);
    }

    /**
     * 仿真转写引擎（确定性）：音频指纹/audioRef → 随机种子 → 句级时间戳/置信度/说话人；
     * lang 缺省自动探测；置信度&lt;0.45 标 REJECT，整体 PARTIAL/REJECT 按占比判定；
     * maskSensitive 脱敏手机号/证件号/卡号。服务层 JSON 直连通道（audioRef）复用本方法。
     */
    public AsrDtos.AsrResult simulateRef(String fingerprint, String scene, String lang, boolean speakerDiarization,
                                         boolean maskSensitive, String requestId) {
        long begin = System.currentTimeMillis();
        if (scene == null || scene.isBlank()) {
            scene = "general";
        }
        scene = scene.toLowerCase();
        if (!SCENES.contains(scene)) {
            throw new BaiException(AsrErrorCodes.PARAM_INVALID,
                    "ASR_1001 参数非法：scene 仅支持 collection/customer-service/voice-command/recording/general");
        }
        if (lang != null && !lang.isBlank() && !LANGS.contains(lang)) {
            throw new BaiException(AsrErrorCodes.PARAM_INVALID,
                    "ASR_1001 参数非法：lang 仅支持 zh/zh-dialect/zh-en");
        }
        int h = Math.abs(fingerprint.hashCode());
        Random rnd = new Random(h);
        String detected = lang == null || lang.isBlank() ? (h % 10 < 8 ? "zh" : "zh-en") : lang;
        // VAD 切分 + 识别（确定性伪输出）
        List<AsrDtos.Segment> segments = new ArrayList<>();
        List<String> script = SCRIPTS.get(scene);
        long cursor = 800 + rnd.nextInt(1200);
        int rejectCount = 0;
        boolean forceReject = h % 4 == 0;    // 每 4 次有 1 次含低置信句（演示拒识路径）
        for (int i = 0; i < script.size(); i++) {
            long duration = 2800L + rnd.nextInt(3200);
            double confidence = Math.round((0.90 + rnd.nextInt(100) / 1000.0) * 100.0) / 100.0;
            String text = script.get(i);
            String status = "OK";
            String reason = null;
            if (forceReject && i == script.size() - 1) {
                confidence = 0.38;
                status = "REJECT";
                reason = "LOW_CONF";
                rejectCount++;
            }
            segments.add(new AsrDtos.Segment(i + 1, speakerDiarization ? (i % 2 == 0 ? "A" : "B") : null,
                    cursor, cursor + duration, text, confidence, status, reason));
            cursor += duration + 400 + rnd.nextInt(600);    // 片段间静音≥0.5s 分片
        }
        long durationMs = cursor + 1000L + rnd.nextInt(3000);   // 尾部静音≥1s 截断
        // 置信度聚合：整体 PARTIAL/REJECT 按拒识占比
        String overall = rejectCount == 0 ? "SUCCESS"
                : (double) rejectCount / segments.size() > 0.6 ? "REJECT" : "PARTIAL";
        // 敏感脱敏（默认 true）：手机号/银行卡号/身份证号保留前 3 后 4
        List<AsrDtos.Segment> out = maskSensitive
                ? segments.stream().map(s -> new AsrDtos.Segment(s.index(), s.speaker(), s.startMs(), s.endMs(),
                        mask(s.text()), s.confidence(), s.status(), s.reason())).toList()
                : segments;
        String fullText = buildFullText(out, speakerDiarization);
        long elapsed = System.currentTimeMillis() - begin;
        String asrId = "ASR" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        log.info("语音转写: requestId={}, asrId={}, scene={}, lang={}, durationMs={}, status={}, segments={}, mask={}",
                requestId, asrId, scene, detected, durationMs, overall, out.size(), maskSensitive);
        return new AsrDtos.AsrResult(asrId, scene, detected, durationMs, overall, out, fullText, elapsed);
    }

    /** 全文合并（按说话人分段） */
    private String buildFullText(List<AsrDtos.Segment> segments, boolean diarization) {
        StringBuilder sb = new StringBuilder();
        for (AsrDtos.Segment s : segments) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            if (diarization && s.speaker() != null) {
                sb.append(s.speaker()).append('：');
            }
            sb.append(s.text());
        }
        return sb.toString();
    }

    /** 敏感脱敏：手机号/银行卡号/身份证号保留前 3 后 4 */
    private static String mask(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = maskPattern(text, PHONE);
        out = maskPattern(out, ID_CARD);
        out = maskPattern(out, BANK_CARD);
        return out;
    }

    private static String maskPattern(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = m.group();
            String masked = v.length() <= 7
                    ? v.charAt(0) + "****"
                    : v.substring(0, 3) + "****" + v.substring(v.length() - 4);
            m.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 音频指纹（确定性：前 64 字节 UUID + 长度，与服务层 fingerprint 逻辑一致） */
    private static String fingerprint(byte[] content) {
        return UUID.nameUUIDFromBytes(Arrays.copyOf(content, Math.min(content.length, 64)))
                + ":" + content.length;
    }
}
