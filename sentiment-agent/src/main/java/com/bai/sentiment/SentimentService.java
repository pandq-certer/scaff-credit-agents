package com.bai.sentiment;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 情绪分析服务。MVP 阶段为确定性仿真引擎（标注：仿真，用于联调），以信号/情绪关键词规则表模拟中文 BERT 系情绪模型；
 * SEN_2001 模型不可用降级走的信号规则引擎兜底即本表（仅输出信号）。
 * <p>处理链：文本归一 → 情绪分类 → 强度判定（威胁/辱骂词 +2 级）→ 信号检测（命中即强制输出并置强度 ≥4）
 * → 规则融合（source=RULE）→ 轨迹聚合（≤50 点，首尾 3 点均值判趋势）→ 转人工事件与落库（明文不落库）
 */
@Service
public class SentimentService {

    private static final Logger log = LoggerFactory.getLogger(SentimentService.class);

    private static final int MAX_TEXT = 5000;
    private static final int MAX_BATCH = 100000;
    private static final int MAX_TRAJECTORY = 50;

    /** 信号检测规则（阈值与置信度按条目：COMPLAINT_THREATEN 0.80/REFUSE_CONTACT 0.85/DEBT_DISPUTE 0.80/PAY_DIFFICULTY 0.80） */
    private record SignalRule(String type, double threshold, double confidence, String[] keywords) {}

    private static final List<SignalRule> SIGNAL_RULES = List.of(
            new SignalRule("COMPLAINT_THREATEN", 0.80, 0.96,
                    new String[]{"投诉", "银保监", "举报", "曝光", "媒体", "监管"}),
            new SignalRule("REFUSE_CONTACT", 0.85, 0.90,
                    new String[]{"别再打", "不要再打", "拒接", "别打电话", "别联系", "不要联系", "不接"}),
            new SignalRule("DEBT_DISPUTE", 0.80, 0.91,
                    new String[]{"不是我的", "不认识", "没借过", "冒名", "凭什么还", "不认"}),
            new SignalRule("PAY_DIFFICULTY", 0.80, 0.89,
                    new String[]{"没钱还", "无力偿还", "还不上", "生意亏", "失业", "周转不开", "真没钱", "困难"}));

    private static final String[] HOSTILE_WORDS = {"威胁", "恐吓", "辱骂", "骂人", "打人", "找你领导"};
    private static final String[] POSITIVE_WORDS = {"谢谢", "感谢", "满意", "好的", "辛苦", "配合"};
    private static final String[] NEGATIVE_WORDS = {"不满", "太慢", "太差", "失望", "拖延", "敷衍", "生气", "很烂",
            "骗人", "投诉", "差评", "坑人", "乱收费", "没人管"};

    /** 会话轨迹存储（MVP 内存实现，生产替换为缓存中间件） */
    private final Map<String, List<SentimentDtos.TrajectoryPoint>> sessionTrajectories = new ConcurrentHashMap<>();
    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final AtomicLong seq = new AtomicLong();

    /** 同步单条分析（幂等：requestId 相同返回首次结果，缓存 24h） */
    public SentimentDtos.SentimentResult analyze(SentimentDtos.AnalyzeRequest req, String requestId) {
        SentimentDtos.SentimentResult cached = idempotency.get("analyze", requestId);
        if (cached != null) {
            return cached;
        }
        if (req == null || req.text() == null) {
            throw new BaiException(SentimentErrorCodes.PARAM_INVALID, "SEN_1001 参数缺失：text 必填");
        }
        String text = req.text();
        if (text.isBlank() || isPureSymbol(text)) {
            throw new BaiException(SentimentErrorCodes.EMPTY_TEXT, "SEN_1006 空文本/纯符号，请修正输入");
        }
        if (text.length() > MAX_TEXT) {
            throw new BaiException(SentimentErrorCodes.TEXT_TOO_LONG, "SEN_1005 文本超过 5000 字，请截断或分批");
        }

        long start = System.currentTimeMillis();
        boolean enableSignal = req.enableSignal() == null || req.enableSignal();
        String scene = req.scene() == null || req.scene().isBlank() ? "default" : req.scene();

        // 2. 信号检测（命中阈值即强制输出并附证据；仿真为关键词规则）
        List<SentimentDtos.Signal> signals = enableSignal ? detectSignals(text) : List.of();

        // 3. 情绪分类与强度判定（信号命中优先于情绪类别；威胁/辱骂词强度 +2 级）
        SentimentDtos.Emotion emotion = detectEmotion(text, signals);
        SentimentDtos.Intensity intensity = detectIntensity(text, signals, emotion);

        // 4. 轨迹聚合（sessionId 传入时维护会话轨迹）
        SentimentDtos.Trajectory trajectory = req.sessionId() == null || req.sessionId().isBlank()
                ? null : appendTrajectory(req.sessionId(), req.turnSeq(), emotion.category(), intensity.level());

        // 5. 处置：强度 4—5 且情绪为消极/投诉/对抗 → 触发转人工事件（仿真为审计日志）
        if (intensity.level() >= 4
                && ("NEGATIVE".equals(emotion.category()) || "COMPLAINT".equals(emotion.category())
                || "HOSTILE".equals(emotion.category()))) {
            log.warn("触发转人工事件(仿真): requestId={}, sessionId={}, level={}, category={}, signals={}",
                    requestId, req.sessionId(), intensity.level(), emotion.category(),
                    signals.stream().map(SentimentDtos.Signal::type).toList());
        }

        long elapsed = System.currentTimeMillis() - start;
        SentimentDtos.SentimentResult result = new SentimentDtos.SentimentResult(
                nextId("SEN"), scene, sha256Hex(text), emotion, intensity,
                signals.isEmpty() ? null : signals, trajectory, elapsed);
        idempotency.put("analyze", requestId, result);

        log.info("情绪分析审计(仿真): requestId={}, scene={}, sessionId={}, textHash={}, emotion={}, level={}, signals={}, elapsed={}ms",
                requestId, scene, req.sessionId(), result.textHash(), emotion.category(), intensity.level(),
                signals.stream().map(SentimentDtos.Signal::type).toList(), elapsed);
        return result;
    }

    /** 流式情绪判定（供 /stream 仿真通道复用；无文本输入时输出中性） */
    public SentimentDtos.Emotion emotionOf(String text) {
        if (text == null || text.isBlank()) {
            return new SentimentDtos.Emotion("NEUTRAL", null, 0.62, "MODEL");
        }
        return detectEmotion(text, detectSignals(text));
    }

    /** 批量分析（≤100000 条，异步；条目未定义批量任务查询接口，结果由调度器落库） */
    public SentimentDtos.BatchResult batch(SentimentDtos.BatchRequest req, String requestId) {
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BaiException(SentimentErrorCodes.PARAM_INVALID, "SEN_1001 参数缺失：items 批量项必填");
        }
        if (req.items().size() > MAX_BATCH) {
            throw new BaiException(SentimentErrorCodes.TEXT_TOO_LONG, "SEN_1005 批量超 100000 条，请截断或分批");
        }
        for (int i = 0; i < req.items().size(); i++) {
            SentimentDtos.AnalyzeRequest item = req.items().get(i);
            if (item == null || item.text() == null || item.text().isBlank() || isPureSymbol(item.text())) {
                throw new BaiException(SentimentErrorCodes.EMPTY_TEXT, "SEN_1006 批量第 " + (i + 1) + " 条为空文本/纯符号，请修正后提交");
            }
            if (item.text().length() > MAX_TEXT) {
                throw new BaiException(SentimentErrorCodes.TEXT_TOO_LONG, "SEN_1005 批量第 " + (i + 1) + " 条超 5000 字，请截断或分批");
            }
        }
        String batchId = "SENB" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%05d", seq.incrementAndGet());
        log.info("情绪批量任务受理(仿真): requestId={}, batchId={}, total={}（异步执行，结果落库 B_sentiment_T）",
                requestId, batchId, req.items().size());
        return new SentimentDtos.BatchResult(batchId, "ACCEPTED", req.items().size());
    }

    /** 情绪体系与信号体系（仿真内置快照） */
    public SentimentDtos.SentimentSchemas schemas() {
        return new SentimentDtos.SentimentSchemas("v2026.09", List.of(
                new SentimentDtos.SchemaCategory("POSITIVE", "积极", List.of("grateful", "satisfied")),
                new SentimentDtos.SchemaCategory("NEUTRAL", "中性", List.of()),
                new SentimentDtos.SchemaCategory("NEGATIVE", "消极", List.of("anxious", "angry", "dispute")),
                new SentimentDtos.SchemaCategory("COMPLAINT", "投诉倾向", List.of("threaten", "complain")),
                new SentimentDtos.SchemaCategory("HOSTILE", "对抗", List.of("insult", "threat"))),
                SIGNAL_RULES.stream()
                        .map(r -> new SentimentDtos.SchemaSignal(r.type(), signalName(r.type()), r.threshold()))
                        .toList(),
                List.of("ESCALATING", "DE-ESCALATING", "STABLE"));
    }

    // ---------------- 内部处理链 ----------------

    private List<SentimentDtos.Signal> detectSignals(String text) {
        List<SentimentDtos.Signal> signals = new ArrayList<>();
        for (SignalRule rule : SIGNAL_RULES) {
            List<String> hits = new ArrayList<>();
            for (String k : rule.keywords()) {
                if (text.contains(k)) {
                    hits.add(k);
                }
            }
            if (!hits.isEmpty()) {
                signals.add(new SentimentDtos.Signal(rule.type(), rule.confidence(), hits.stream().map(h -> {
                    int idx = text.indexOf(h);
                    return truncate(text.substring(Math.max(0, idx - 4), Math.min(text.length(), idx + h.length() + 4)), 20);
                }).toList()));
            }
        }
        return signals;
    }

    /** 情绪判定：信号命中优先（COMPLAINT/HOSTILE/NEGATIVE），其次正面词，缺省中性（细粒度置信度 <0.55 仅输出大类） */
    private SentimentDtos.Emotion detectEmotion(String text, List<SentimentDtos.Signal> signals) {
        boolean threaten = signals.stream().anyMatch(s -> "COMPLAINT_THREATEN".equals(s.type()));
        if (threaten) {
            return new SentimentDtos.Emotion("COMPLAINT", "threaten", 0.94, "RULE");
        }
        if (containsAny(text, HOSTILE_WORDS)) {
            return new SentimentDtos.Emotion("HOSTILE", "insult", 0.92, "RULE");
        }
        if (signals.stream().anyMatch(s -> "DEBT_DISPUTE".equals(s.type()))) {
            return new SentimentDtos.Emotion("NEGATIVE", "dispute", 0.90, "RULE");
        }
        if (signals.stream().anyMatch(s -> "PAY_DIFFICULTY".equals(s.type()))) {
            return new SentimentDtos.Emotion("NEGATIVE", "anxious", 0.88, "RULE");
        }
        if (containsAny(text, NEGATIVE_WORDS)) {
            return new SentimentDtos.Emotion("NEGATIVE", "angry", 0.85, "RULE");
        }
        if (containsAny(text, POSITIVE_WORDS)) {
            return new SentimentDtos.Emotion("POSITIVE", "grateful", 0.90, "MODEL");
        }
        return new SentimentDtos.Emotion("NEUTRAL", null, 0.62, "MODEL");
    }

    /** 强度判定：基线 2 级；威胁/辱骂词 +2 级；信号命中强制 ≥4 级 */
    private SentimentDtos.Intensity detectIntensity(String text, List<SentimentDtos.Signal> signals,
                                                    SentimentDtos.Emotion emotion) {
        int level = 2;
        if (containsAny(text, HOSTILE_WORDS)) {
            level += 2;
        }
        if (!signals.isEmpty()) {
            level = Math.max(level, 4);
        }
        if ("COMPLAINT".equals(emotion.category()) && signals.stream().anyMatch(s -> "COMPLAINT_THREATEN".equals(s.type()))) {
            level = Math.max(level, 4);
        }
        String label = switch (level) {
            case 1, 2 -> "LOW";
            case 3 -> "MEDIUM";
            case 4 -> "HIGH";
            default -> "CRITICAL";
        };
        return new SentimentDtos.Intensity(Math.min(level, 5), label);
    }

    /** 轨迹聚合：≤50 点/会话（超窗丢弃最旧点聚合），末 3 点 vs 首 3 点均值判趋势并标记极值 */
    private SentimentDtos.Trajectory appendTrajectory(String sessionId, Integer turnSeq,
                                                      String category, int level) {
        List<SentimentDtos.TrajectoryPoint> points =
                sessionTrajectories.computeIfAbsent(sessionId, k -> new ArrayList<>());
        int seqNo = turnSeq == null ? points.size() + 1 : turnSeq;
        SentimentDtos.TrajectoryPoint point = new SentimentDtos.TrajectoryPoint(seqNo, category, level);
        synchronized (points) {
            points.add(point);
            while (points.size() > MAX_TRAJECTORY) {
                points.remove(0); // 超窗聚合：丢弃最旧点
            }
            // 趋势：末 3 点 vs 首 3 点强度均值
            int n = points.size();
            double head = avgLevel(points.subList(0, Math.min(3, n)));
            double tail = avgLevel(points.subList(Math.max(0, n - 3), n));
            String trend = tail - head >= 1 ? "ESCALATING" : tail - head <= -1 ? "DE-ESCALATING" : "STABLE";
            SentimentDtos.TrajectoryPoint peak = points.get(0);
            for (SentimentDtos.TrajectoryPoint p : points) {
                if (p.level() > peak.level()) {
                    peak = p;
                }
            }
            return new SentimentDtos.Trajectory(List.copyOf(points), trend,
                    new SentimentDtos.Peak(peak.seq(), peak.category(), peak.level()));
        }
    }

    private double avgLevel(List<SentimentDtos.TrajectoryPoint> points) {
        return points.stream().mapToInt(SentimentDtos.TrajectoryPoint::level).average().orElse(0);
    }

    private String signalName(String type) {
        return switch (type) {
            case "COMPLAINT_THREATEN" -> "投诉威胁";
            case "REFUSE_CONTACT" -> "拒绝联系";
            case "DEBT_DISPUTE" -> "债务异议";
            default -> "还款困难";
        };
    }

    private boolean containsAny(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private boolean isPureSymbol(String text) {
        return text.codePoints().noneMatch(Character::isLetterOrDigit);
    }

    private String nextId(String prefix) {
        return prefix + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%05d", seq.incrementAndGet());
    }

    private String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(text.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
