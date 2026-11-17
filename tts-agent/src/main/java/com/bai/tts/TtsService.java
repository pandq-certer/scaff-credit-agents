package com.bai.tts;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文字转语音服务（BIA-TTS-003）。信贷语音交互的音频输出原子：接收纯文本或 SSML，完成文本规整
 * （金额/日期/时间/手机号确定性规则优先，FR-08）、场景化音色路由（FR-03）、韵律参数注入与确定性
 * 合成仿真，输出 wav/mp3/opus 音频元信息与分段，供外呼播报/IVR/数字人/语音通知场景使用。
 * <p>处理链：SSML 剥离 → 文本校验（空/纯符号 TTS_1007、超长 TTS_1005）→ 场景/音色/语气/语速解析
 * （请求显式参数优先于场景默认值）→ 文本规整 → 委托引擎内核合成（mock=SHA-256 指纹驱动时长/分段，
 * 同入参复现；real=推理网关声学模型）。
 * <p>并发治理：幂等（同 requestId 缓存 24h，设计 4.6）；&gt;5000 字或 texts 批量自动转异步（设计 4.2.3）。
 * <p>引擎内核经 {@link TtsProvider} 注入（mock=确定性仿真 | real=统一 AI 推理网关，开关 bai.tts.provider）；
 * 合成记录留痕、完成后发布 tts.completed 事件。
 */
@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_TEXT_LEN = 10000;       // 单次 ≤10000 字（FR-10）
    private static final int ASYNC_THRESHOLD = 5000;     // >5000 字自动转异步（设计 4.2.3）
    private static final int MAX_REQUEST_ID = 32;        // requestId 32 位以内（表 4-2）
    private static final Set<String> FORMATS = Set.of("wav", "mp3", "opus");
    private static final Set<Integer> SAMPLE_RATES = Set.of(24000, 16000);
    private static final Set<String> SCENES = Set.of("collection", "broadcast", "digital-human", "ivr", "general");
    private static final Set<String> STYLES = Set.of("AFFINITY", "SERIOUS", "PROFESSIONAL", "CHEERFUL");

    /** 文本规整规则（表 6-3 节选：金额/日期/时间/手机号/百分数） */
    private static final Pattern TAG = Pattern.compile("<[^>]+>");                 // SSML 标签剥离
    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");           // 手机号逐位读
    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Pattern AMOUNT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(元|万元|亿元)");
    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)%");

    /** 场景参数路由（表 7-1）：scene → [默认音色, 默认语气, 默认语速] */
    private static final Map<String, String[]> SCENE_ROUTE = Map.of(
            "collection", new String[]{"v_collection_f", "PROFESSIONAL", "1.0"},
            "broadcast", new String[]{"v_broadcast_f", "SERIOUS", "1.05"},
            "digital-human", new String[]{"v_dh_f", "AFFINITY", "0.98"},
            "ivr", new String[]{"v_ivr_f", "PROFESSIONAL", "1.0"},
            "general", new String[]{"v_general_f", "PROFESSIONAL", "1.0"});

    /** 音色注册表（表 7-2 预置 8 个授权音色，≥8 验收要点） */
    private static final List<TtsDtos.Voice> VOICES = List.of(
            new TtsDtos.Voice("v_collection_f", "外呼女声-专业", "female",
                    List.of("PROFESSIONAL", "AFFINITY"), List.of("collection", "general"), List.of(16000, 24000)),
            new TtsDtos.Voice("v_collection_m", "外呼男声-稳", "male",
                    List.of("PROFESSIONAL", "SERIOUS"), List.of("collection", "general"), List.of(16000, 24000)),
            new TtsDtos.Voice("v_broadcast_f", "播报女声-标准", "female",
                    List.of("SERIOUS", "PROFESSIONAL"), List.of("broadcast", "general"), List.of(16000, 24000)),
            new TtsDtos.Voice("v_broadcast_m", "播报男声-沉稳", "male",
                    List.of("SERIOUS", "PROFESSIONAL"), List.of("broadcast", "general"), List.of(16000, 24000)),
            new TtsDtos.Voice("v_dh_f", "数字人-亲和", "female",
                    List.of("AFFINITY", "CHEERFUL"), List.of("digital-human", "general"), List.of(24000)),
            new TtsDtos.Voice("v_ivr_f", "IVR-标准", "female",
                    List.of("PROFESSIONAL"), List.of("ivr", "general"), List.of(16000, 24000)),
            new TtsDtos.Voice("v_general_f", "通用女声", "female",
                    List.of("PROFESSIONAL", "AFFINITY"),
                    List.of("general", "collection", "broadcast", "digital-human", "ivr"), List.of(16000, 24000)),
            new TtsDtos.Voice("v_general_m", "通用男声", "male",
                    List.of("PROFESSIONAL", "SERIOUS"), List.of("general"), List.of(16000, 24000)));
    private static final Set<String> VOICE_IDS = VOICES.stream()
            .map(TtsDtos.Voice::voiceId).collect(Collectors.toSet());

    private static final String[] D = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};

    private final ObjectMapper mapper;
    private final TtsProvider provider;
    /** 幂等缓存：同 requestId 重复提交返回首次结果（缓存 24h，设计 4.6） */
    private final IdempotencyStore idempotency = new IdempotencyStore();
    /** 异步任务：taskId → 任务（>5000 字/批量自动转异步） */
    private final Map<String, TtsDtos.TtsTask> tasks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    public TtsService(TtsProvider provider, ObjectMapper mapper) {
        this.provider = provider;
        this.mapper = mapper;
    }

    /** 测试与演示便捷构造：默认 Mock 引擎（确定性仿真，行为与改造前一致） */
    public TtsService(ObjectMapper mapper) {
        this(new MockTtsProvider(), mapper);
    }

    /** 语音合成（幂等：同 requestId 返回首次结果；>5000 字或 texts 批量自动转异步返回 {taskId, status:PENDING}） */
    public Object synthesize(String text, String texts, String voiceId, String scene, String style,
                             Double rate, String format, Integer sampleRate, String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > MAX_REQUEST_ID) {
            throw new BaiException(TtsErrorCodes.PARAM_INVALID, "TTS_1001 参数缺失/非法：requestId 必填且 32 位以内");
        }
        Object cached = idempotency.get("synthesize", requestId);
        if (cached != null) {
            return cached;
        }
        // 批量（texts 数组，JSON）→ 自动异步（设计 4.2.3）
        if (texts != null && !texts.isBlank()) {
            TtsDtos.TtsTaskSubmit submit = submitTask(batchSynthesize(texts, voiceId, scene, style,
                    rate, format, sampleRate, requestId), requestId);
            idempotency.put("synthesize", requestId, submit);
            return submit;
        }
        if (text == null) {
            throw new BaiException(TtsErrorCodes.PARAM_INVALID, "TTS_1001 参数缺失：text 必填");
        }
        // 文本 >5000 字自动转异步（设计 4.2.3）
        if (TAG.matcher(text).replaceAll("").trim().length() > ASYNC_THRESHOLD) {
            TtsDtos.TtsTaskSubmit submit = submitTask(
                    doSynthesize(text, voiceId, scene, style, rate, format, sampleRate, requestId), requestId);
            idempotency.put("synthesize", requestId, submit);
            return submit;
        }
        TtsDtos.TtsResult result = doSynthesize(text, voiceId, scene, style, rate, format, sampleRate, requestId);
        idempotency.put("synthesize", requestId, result);
        return result;
    }

    /** 异步任务查询（status: PENDING/RUNNING/DONE，含结果） */
    public TtsDtos.TtsTask getTask(String taskId) {
        TtsDtos.TtsTask task = tasks.get(taskId);
        if (task == null) {
            throw new BaiException(TtsErrorCodes.NOT_FOUND, "TTS_1004 taskId 不存在，请校验任务号");
        }
        return task;
    }

    /** 音色列表（表 7-2 注册表，设计 4.4） */
    public List<TtsDtos.Voice> listVoices() {
        return VOICES;
    }

    /** 流式合成参数预校验（WS 连接建立时调用） */
    public void validateStreamParams(String voiceId, String scene, String format, Integer sampleRate) {
        if (scene != null && !scene.isBlank() && !SCENES.contains(scene.trim().toLowerCase())) {
            throw new BaiException(TtsErrorCodes.PARAM_INVALID,
                    "TTS_1001 参数非法：scene 仅支持 collection/broadcast/digital-human/ivr/general");
        }
        if (voiceId != null && !voiceId.isBlank() && !VOICE_IDS.contains(voiceId.trim())) {
            throw new BaiException(TtsErrorCodes.NOT_FOUND, "TTS_1004 音色不存在：" + voiceId);
        }
        if (format != null && !format.isBlank() && !FORMATS.contains(format.trim().toLowerCase())) {
            throw new BaiException(TtsErrorCodes.FORMAT_UNSUPPORTED,
                    "TTS_1006 音频格式参数非法：仅支持 wav/mp3/opus");
        }
        if (sampleRate != null && !SAMPLE_RATES.contains(sampleRate)) {
            throw new BaiException(TtsErrorCodes.FORMAT_UNSUPPORTED,
                    "TTS_1006 采样率参数非法：仅支持 24000/16000");
        }
    }

    /** 流式合成（WS /stream：跳过幂等与异步分支，直接确定性合成，供 TtsStreamHandler 调用） */
    public TtsDtos.TtsResult synthesizeStream(String text, String voiceId, String scene, String format,
                                              Integer sampleRate, String requestId) {
        return doSynthesize(text, voiceId, scene, null, null, format, sampleRate, requestId);
    }

    /**
     * 合成编排（核心）：SSML 剥离 → 文本校验 → 场景/音色/语气/语速解析（显式参数优先）
     * → 文本规整 → 委托引擎内核（mock=确定性指纹仿真 | real=推理网关）。
     */
    private TtsDtos.TtsResult doSynthesize(String text, String voiceId, String scene, String style,
                                           Double rate, String format, Integer sampleRate, String requestId) {
        if (text == null) {
            throw new BaiException(TtsErrorCodes.PARAM_INVALID, "TTS_1001 参数缺失：text 必填");
        }
        text = TAG.matcher(text).replaceAll("").trim();   // SSML 标签剥离（FR-01，非法标签忽略）
        if (text.isEmpty()) {
            throw new BaiException(TtsErrorCodes.TEXT_UNSYNTHESIZABLE, "TTS_1007 文本不可合成（空文本）");
        }
        if (text.chars().allMatch(c -> !Character.isLetterOrDigit(c) && !isCjk(c))) {
            throw new BaiException(TtsErrorCodes.TEXT_UNSYNTHESIZABLE, "TTS_1007 文本不可合成（纯符号）");
        }
        if (text.length() > MAX_TEXT_LEN) {
            throw new BaiException(TtsErrorCodes.TEXT_TOO_LONG,
                    "TTS_1005 文本超过 " + MAX_TEXT_LEN + " 字，请分段提交");
        }
        String sc = scene == null || scene.isBlank() ? "general" : scene.trim().toLowerCase();
        if (!SCENES.contains(sc)) {
            throw new BaiException(TtsErrorCodes.PARAM_INVALID,
                    "TTS_1001 参数非法：scene 仅支持 collection/broadcast/digital-human/ivr/general");
        }
        String[] route = SCENE_ROUTE.get(sc);
        String voice = voiceId == null || voiceId.isBlank() ? route[0] : voiceId.trim();
        if (!VOICE_IDS.contains(voice)) {
            throw new BaiException(TtsErrorCodes.NOT_FOUND, "TTS_1004 音色不存在：" + voice);
        }
        String st = style == null || style.isBlank() ? route[1] : style.trim().toUpperCase();
        if (!STYLES.contains(st)) {
            throw new BaiException(TtsErrorCodes.PARAM_INVALID,
                    "TTS_1001 参数非法：style 仅支持 AFFINITY/SERIOUS/PROFESSIONAL/CHEERFUL");
        }
        double rt = rate == null ? Double.parseDouble(route[2]) : rate;
        rt = Math.max(0.5, Math.min(2.0, rt));    // 语速超出 0.5—2.0 截断（表 5-1）
        String fmt = format == null || format.isBlank() ? "wav" : format.trim().toLowerCase();
        if (!FORMATS.contains(fmt)) {
            throw new BaiException(TtsErrorCodes.FORMAT_UNSUPPORTED,
                    "TTS_1006 音频格式参数非法：仅支持 wav/mp3/opus");
        }
        int sr = sampleRate == null ? 24000 : sampleRate;
        if (!SAMPLE_RATES.contains(sr)) {
            throw new BaiException(TtsErrorCodes.FORMAT_UNSUPPORTED,
                    "TTS_1006 采样率参数非法：仅支持 24000/16000");
        }
        // 文本规整（FR-08：金额/日期/时间/手机号确定性规则优先）
        String norm = normalize(text);
        // 引擎内核委托（mock=确定性指纹仿真 | real=推理网关声学模型）
        return provider.synthesize(norm, voice, sc, st, rt, fmt, sr, requestId);
    }

    /** 批量合成（texts JSON 数组 → 拼接为单音频，自动转异步） */
    private TtsDtos.TtsResult batchSynthesize(String textsJson, String voiceId, String scene, String style,
                                              Double rate, String format, Integer sampleRate, String requestId) {
        try {
            List<String> items = mapper.readValue(textsJson, new TypeReference<List<String>>() { });
            if (items.isEmpty()) {
                throw new BaiException(TtsErrorCodes.PARAM_INVALID, "TTS_1001 参数缺失：texts 数组不能为空");
            }
            List<String> joined = new ArrayList<>();
            for (String item : items) {
                String t = TAG.matcher(item == null ? "" : item).replaceAll("").trim();
                if (t.isEmpty()) {
                    throw new BaiException(TtsErrorCodes.PARAM_INVALID,
                            "TTS_1001 参数非法：texts 数组存在空文本");
                }
                joined.add(t);
            }
            return doSynthesize(String.join("。", joined), voiceId, scene, style, rate, format,
                    sampleRate, requestId);
        } catch (BaiException e) {
            throw e;
        } catch (Exception e) {
            throw new BaiException(TtsErrorCodes.PARAM_INVALID,
                    "TTS_1001 参数非法：texts 须为 JSON 字符串数组（如 [\"文本一\",\"文本二\"]）");
        }
    }

    /** 异步任务提交（仿真：立即完成置 DONE，回执按设计返回 PENDING） */
    private TtsDtos.TtsTaskSubmit submitTask(TtsDtos.TtsResult result, String requestId) {
        String taskId = "TTSK" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet() % 10000);
        tasks.put(taskId, new TtsDtos.TtsTask(taskId, "PENDING", null));
        tasks.put(taskId, new TtsDtos.TtsTask(taskId, "DONE", result));
        log.info("异步合成任务完成: requestId={}, taskId={}, ttsId={}", requestId, taskId, result.ttsId());
        return new TtsDtos.TtsTaskSubmit(taskId, "PENDING");
    }

    /** 文本规整（表 6-3 节选）：手机号逐位读、日期/时间/金额/百分数中文读法 */
    private static String normalize(String text) {
        String t = PHONE.matcher(text).replaceAll(m -> spellDigits(m.group()));
        t = DATE.matcher(t).replaceAll(m -> chineseDate(m.group(1), m.group(2), m.group(3)));
        t = TIME.matcher(t).replaceAll(m -> chineseTime(m.group(1), m.group(2)));
        t = AMOUNT.matcher(t).replaceAll(m -> chineseAmount(m.group(1), m.group(2)));
        t = PERCENT.matcher(t).replaceAll(m -> "百分之" + chineseDecimal(m.group(1)));
        return t;
    }

    private static String chineseDate(String year, String month, String day) {
        StringBuilder sb = new StringBuilder();
        for (char c : year.toCharArray()) {
            sb.append(D[c - '0']);
        }
        sb.append('年').append(chineseNumber(Long.parseLong(month))).append('月')
                .append(chineseNumber(Long.parseLong(day))).append('日');
        return sb.toString();
    }

    private static String chineseTime(String hour, String minute) {
        long min = Long.parseLong(minute);
        return chineseNumber(Long.parseLong(hour)) + "点" + (min == 0 ? "整" : chineseNumber(min) + "分");
    }

    private static String chineseAmount(String number, String unit) {
        return (number.contains(".") ? chineseDecimal(number) : chineseNumber(Long.parseLong(number))) + unit;
    }

    private static String chineseDecimal(String s) {
        String[] parts = s.split("\\.");
        StringBuilder frac = new StringBuilder();
        for (char c : parts[1].toCharArray()) {
            frac.append(D[c - '0']);
        }
        return chineseNumber(Long.parseLong(parts[0])) + "点" + frac;
    }

    /** 逐位朗读（手机号/证件号，表 6-3） */
    private static String spellDigits(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= '0' && c <= '9') {
                sb.append(D[c - '0']);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 中文数字（0—99999999，万级） */
    private static String chineseNumber(long n) {
        if (n == 0) {
            return "零";
        }
        if (n < 10000) {
            return chineseBelowWan(n);
        }
        long wan = n / 10000;
        long rest = n % 10000;
        String s = chineseBelowWan(wan) + "万";
        if (rest != 0) {
            if (rest < 1000) {
                s += "零";
            }
            s += chineseBelowWan(rest);
        }
        return s;
    }

    private static String chineseBelowWan(long n) {
        if (n == 0) {
            return "零";
        }
        StringBuilder sb = new StringBuilder();
        if (n >= 1000) {
            sb.append(D[(int) (n / 1000)]).append('千');
            n %= 1000;
            if (n > 0 && n < 100) {
                sb.append('零');
            }
        }
        if (n >= 100) {
            sb.append(D[(int) (n / 100)]).append('百');
            n %= 100;
            if (n > 0 && n < 10) {
                sb.append('零');
            }
        }
        if (n >= 10) {
            int tens = (int) (n / 10);
            if (tens > 1 || sb.length() > 0) {
                sb.append(D[tens]);
            }
            sb.append('十');
            n %= 10;
        }
        if (n > 0) {
            sb.append(D[(int) n]);
        }
        return sb.toString();
    }

    private static boolean isCjk(int c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }
}
