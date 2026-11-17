package com.bai.asr;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语音识别服务（BIA-ASR-002）。信贷语音智能化处理的入口原子：从音频到带时间戳、说话人、置信度的结构化转写文本。
 * <p>处理链：格式探测与 PCM 16kHz 统一转码 → VAD 切分人声片段 → 按 scene/lang 路由识别模型（方言转写为普通话文本、
 * 融合信贷领域热词）→ 说话人嵌入聚类标注 A/B/C → 标点与 ITN 规整（金额/日期/号码确定性规则优先）
 * → 置信度聚合与拒识 → 敏感脱敏与落库（留存 180 天）。
 * <p>引擎内核经 {@link AsrProvider} 注入（mock=确定性仿真 | real=统一 AI 推理网关，开关 bai.asr.provider）；
 * 转写记录留痕、完成后发布 asr.completed 事件（经 event-subscribe-agent）。
 */
@Service
public class AsrService {

    private static final Logger log = LoggerFactory.getLogger(AsrService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final long MIN_SIZE = 1024L;                    // 1KB（小于视为静音/损坏）
    private static final long MAX_SIZE = 200 * 1024 * 1024L;       // 200MB
    private static final long ASYNC_THRESHOLD = 20 * 1024 * 1024L; // >20MB 自动转异步
    private static final Set<String> SUPPORTED_TYPES = Set.of("wav", "mp3", "m4a", "amr", "ogg", "opus");
    private static final Set<String> SCENES = Set.of("collection", "customer-service", "voice-command",
            "recording", "general");

    private final IdempotencyStore idempotency = new IdempotencyStore();
    /** 异步任务：taskId → 任务（>20MB 自动转异步） */
    private final Map<String, AsrDtos.AsrTask> tasks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final AsrProvider provider;

    @Autowired
    public AsrService(AsrProvider provider) {
        this.provider = provider;
    }

    /** 测试与演示便捷构造：默认 Mock 引擎（确定性仿真，行为与改造前一致） */
    public AsrService() {
        this(new MockAsrProvider());
    }

    /** 离线转写（multipart；幂等：同 requestId 返回首次结果，缓存 24h；>20MB 自动转异步） */
    public Object transcribe(MultipartFile audio, String scene, String lang, boolean speakerDiarization,
                             String hotwords, boolean maskSensitive, String requestId) {
        AsrDtos.AsrResult cached = idempotency.get("transcribe", requestId);
        if (cached != null) {
            return cached;
        }
        validateAudio(audio);
        boolean async = audio.getSize() > ASYNC_THRESHOLD;
        if (async) {
            return submitTask(audio, scene, lang, speakerDiarization, maskSensitive, requestId);
        }
        AsrDtos.AsrResult result = provider.recognize(readBytes(audio), audio.getOriginalFilename(),
                scene, lang, speakerDiarization, maskSensitive, requestId);
        idempotency.put("transcribe", requestId, result);
        return result;
    }

    /**
     * JSON 直连转写变体（audioRef 影像/音频标识，BFF JSON 直传场景；同 audioRef+scene 确定性复现）。
     * <p>注：网关本期未提供 audioRef 端点，该变体暂固定走 Mock 引擎（二期网关 ref 接口接入后切换）。
     */
    public AsrDtos.AsrResult transcribeRef(Map<String, Object> body, String requestId) {
        if (body == null || isBlank(body.get("audioRef"))) {
            throw new BaiException(AsrErrorCodes.PARAM_INVALID, "ASR_1001 参数缺失：audioRef 必填");
        }
        String audioRef = str(body.get("audioRef"));
        AsrDtos.AsrResult cached = idempotency.get("transcribe-ref", requestId + ":" + audioRef);
        if (cached != null) {
            return cached;
        }
        String scene = strOr(body.get("scene"), "general").toLowerCase();
        if (!SCENES.contains(scene)) {
            throw new BaiException(AsrErrorCodes.PARAM_INVALID,
                    "ASR_1001 参数非法：scene 仅支持 collection/customer-service/voice-command/recording/general");
        }
        boolean diarization = Boolean.parseBoolean(String.valueOf(body.get("speakerDiarization")));
        boolean mask = body.get("maskSensitive") == null || Boolean.parseBoolean(String.valueOf(body.get("maskSensitive")));
        AsrDtos.AsrResult result = new MockAsrProvider().simulateRef(audioRef, scene, strOr(body.get("lang"), null),
                diarization, mask, requestId);
        idempotency.put("transcribe-ref", requestId + ":" + audioRef, result);
        return result;
    }

    /** 异步任务提交（>20MB） */
    private AsrDtos.AsrTask submitTask(MultipartFile audio, String scene, String lang, boolean speakerDiarization,
                                       boolean maskSensitive, String requestId) {
        String taskId = "ASK" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        tasks.put(taskId, new AsrDtos.AsrTask(taskId, "RUNNING", null));
        AsrDtos.AsrResult result = provider.recognize(readBytes(audio), audio.getOriginalFilename(),
                scene, lang, speakerDiarization, maskSensitive, requestId);
        tasks.put(taskId, new AsrDtos.AsrTask(taskId, "DONE", result));
        log.info("异步转写任务完成: requestId={}, taskId={}, audioSize={}B", requestId, taskId, audio.getSize());
        return tasks.get(taskId);
    }

    /** 任务查询 */
    public AsrDtos.AsrTask getTask(String taskId) {
        AsrDtos.AsrTask task = tasks.get(taskId);
        if (task == null) {
            throw new BaiException(AsrErrorCodes.TASK_NOT_FOUND, "ASR_1004 taskId 不存在，请校验任务号");
        }
        return task;
    }

    /** 轻量语种探测（确定性：音频指纹驱动 zh/zh-dialect/zh-en） */
    public AsrDtos.LangDetectResult detectLang(MultipartFile audio, String requestId) {
        validateAudio(audio);
        long begin = System.currentTimeMillis();
        int h = Math.abs(fingerprint(audio).hashCode());
        String lang = h % 10 < 7 ? "zh" : h % 10 < 9 ? "zh-dialect" : "zh-en";
        double confidence = Math.round((0.85 + (h % 130) / 1000.0) * 100.0) / 100.0;
        long durationMs = 8000L + (h % 52) * 1000L;
        String detectId = "DL" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        long elapsed = System.currentTimeMillis() - begin;
        log.info("语种探测: requestId={}, detectId={}, lang={}, confidence={}", requestId, detectId, lang, confidence);
        return new AsrDtos.LangDetectResult(detectId, lang, confidence, durationMs, elapsed);
    }

    /** 音频指纹（确定性：文件字节/文件名哈希，语种探测用） */
    private String fingerprint(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            return UUID.nameUUIDFromBytes(Arrays.copyOf(bytes, Math.min(bytes.length, 64)))
                    + ":" + bytes.length;
        } catch (IOException e) {
            return String.valueOf(file.getOriginalFilename()) + ":" + file.getSize();
        }
    }

    /** 音频字节读取（读取失败按 ASR_1007 损坏处理） */
    private byte[] readBytes(MultipartFile audio) {
        try {
            return audio.getBytes();
        } catch (IOException e) {
            throw new BaiException(AsrErrorCodes.AUDIO_CORRUPTED, "ASR_1007 音频读取失败：" + e.getMessage());
        }
    }

    private void validateAudio(MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new BaiException(AsrErrorCodes.PARAM_INVALID, "ASR_1001 参数缺失：audio 音频文件必填");
        }
        if (audio.getSize() > MAX_SIZE) {
            throw new BaiException(AsrErrorCodes.AUDIO_TOO_LARGE,
                    "ASR_1005 音频超过 200MB 或时长超 4h，请切片后重试");
        }
        if (audio.getSize() < MIN_SIZE) {
            throw new BaiException(AsrErrorCodes.AUDIO_CORRUPTED,
                    "ASR_1007 音频不可解析（静音/损坏），请重新上传");
        }
        String name = audio.getOriginalFilename() == null ? "" : audio.getOriginalFilename().toLowerCase();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        if (!SUPPORTED_TYPES.contains(ext)) {
            throw new BaiException(AsrErrorCodes.FORMAT_UNSUPPORTED,
                    "ASR_1006 音频格式不支持（或采样率低于 8kHz），支持 wav/mp3/m4a/amr/ogg/opus");
        }
    }

    private static boolean isBlank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String strOr(Object o, String defaultValue) {
        String s = str(o);
        return s == null || s.isBlank() ? defaultValue : s;
    }
}
