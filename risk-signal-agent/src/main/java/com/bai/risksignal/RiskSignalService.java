package com.bai.risksignal;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 风险信号识别服务（BIA-RSK-043）。基于资金流水/司法涉诉/工商变更/税务舆情/公开信息五类信号源，
 * 做风险事件检测与四级分级，按客户维度加权聚合输出 0-100 聚合分；聚合分≥80 或任一 CRITICAL 触发预警。
 * <p>MVP 为确定性仿真：信号检测用事件关键词规则（信号模型的确定性路径），
 * 权重 JUDICIAL.35/FLOW.25/INDUSTRY.15/TAX.15/PUBLIC.10，聚合分=Σ(权重/Σ权重)×信号分。
 */
@Service
public class RiskSignalService {

    private static final Logger log = LoggerFactory.getLogger(RiskSignalService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_EVENTS = 100;                          // 事件/批量超长 RSG_1006
    private static final int MAX_BATCH = 100;
    private static final Set<String> SOURCES = Set.of("FLOW", "JUDICIAL", "INDUSTRY", "TAX", "PUBLIC");
    private static final Set<String> ENTITY_TYPES = Set.of("PERSON", "ORG");
    private static final Map<String, Double> WEIGHTS = Map.of(
            "JUDICIAL", 0.35, "FLOW", 0.25, "INDUSTRY", 0.15, "TAX", 0.15, "PUBLIC", 0.10);
    private static final Map<String, Integer> LEVEL_SCORES = Map.of(
            "LOW", 35, "MEDIUM", 60, "HIGH", 80, "CRITICAL", 92);
    private static final double ALERT_SCORE = 80.0;

    private final IdempotencyStore idempotency = new IdempotencyStore();

    /** 检测记录：signalId → 结论（供 /query、/feedback） */
    private final Map<String, RiskSignalDtos.DetectResult> records = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 单主体检测（幂等键 requestId+entity+sources；同键复用首次结论） */
    public RiskSignalDtos.DetectResult detect(Map<String, Object> body, String requestId) {
        if (!(body.get("entity") instanceof Map<?, ?> entityRaw) || entityRaw.isEmpty()) {
            throw new BaiException(RiskSignalErrorCodes.PARAM_INVALID,
                    "RSG_1001 参数缺失：entity {entityType, entityId} 必填");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) entityRaw;
        String entityType = str(entity.get("entityType"));
        String entityId = str(entity.get("entityId"));
        if (isBlank(entityId)) {
            throw new BaiException(RiskSignalErrorCodes.PARAM_INVALID, "RSG_1001 参数缺失：entityId 必填");
        }
        if (entityType == null || !ENTITY_TYPES.contains(entityType.toUpperCase())) {
            throw new BaiException(RiskSignalErrorCodes.PARAM_INVALID,
                    "RSG_1001 参数非法：entityType 仅支持 PERSON/ORG");
        }
        List<String> sources = body.get("sources") instanceof List<?> l
                ? l.stream().map(x -> String.valueOf(x).toUpperCase()).toList() : List.of();
        if (sources.isEmpty() || !SOURCES.containsAll(sources)) {
            List<String> bad = sources.stream().filter(s -> !SOURCES.contains(s)).toList();
            throw new BaiException(RiskSignalErrorCodes.SOURCE_INVALID,
                    "RSG_1007 信号源非法：仅支持 FLOW/JUDICIAL/INDUSTRY/TAX/PUBLIC"
                            + (bad.isEmpty() ? "，sources 不能为空" : "，非法值：" + bad));
        }
        if (!(body.get("events") instanceof List<?> events) || events.isEmpty()) {
            throw new BaiException(RiskSignalErrorCodes.PARAM_INVALID,
                    "RSG_1001 参数缺失：events 外部事件清单必填");
        }
        if (events.size() > MAX_EVENTS) {
            throw new BaiException(RiskSignalErrorCodes.TOO_LONG,
                    "RSG_1006 事件超长（" + events.size() + " > " + MAX_EVENTS + " 条），请缩减输入");
        }
        String scene = str(body.get("scene"));
        if (isBlank(scene)) {
            scene = "LOAN_MONITOR";
        }
        String idemKey = requestId + "|" + entityJson(entity) + "|" + sources;
        RiskSignalDtos.DetectResult cached = idempotency.get("detect", idemKey);
        if (cached != null) {
            return cached;
        }
        long begin = System.nanoTime();
        RiskSignalDtos.DetectResult result = doDetect(entityType.toUpperCase(), entityId, scene, sources, events);
        records.put(result.signalId(), result);
        idempotency.put("detect", idemKey, result);
        log.info("风险检测完成: signalId={}, entity={}/{}, scene={}, sources={}, signals={}, alert={}",
                result.signalId(), entityType, entityId, scene, sources,
                result.signals().size(), result.alertTriggered());
        log.debug("检测耗时仿真: {}ms", (System.nanoTime() - begin) / 1_000_000);
        return result;
    }

    /** 批量检测（异步；items ≤ 100） */
    public RiskSignalDtos.BatchDetectResult batchDetect(Map<String, Object> body, String requestId) {
        if (!(body.get("items") instanceof List<?> items) || items.isEmpty()) {
            throw new BaiException(RiskSignalErrorCodes.PARAM_INVALID, "RSG_1001 参数缺失：items 必填");
        }
        if (items.size() > MAX_BATCH) {
            throw new BaiException(RiskSignalErrorCodes.TOO_LONG,
                    "RSG_1006 批量超长（" + items.size() + " > " + MAX_BATCH + " 条），请分批提交");
        }
        List<RiskSignalDtos.DetectResult> results = new ArrayList<>();
        int i = 0;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new BaiException(RiskSignalErrorCodes.PARAM_INVALID,
                        "RSG_1001 参数非法：items[" + i + "] 必须为 object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> itemBody = (Map<String, Object>) m;
            String itemRequestId = requestId + "-" + i++;
            results.add(detect(itemBody, itemRequestId));
        }
        log.info("批量检测完成: total={}, requestId={}", results.size(), requestId);
        return new RiskSignalDtos.BatchDetectResult(results.size(), List.copyOf(results));
    }

    /** 检测记录查询 */
    public RiskSignalDtos.DetectResult query(String signalId) {
        RiskSignalDtos.DetectResult result = records.get(signalId);
        if (result == null) {
            throw new BaiException(RiskSignalErrorCodes.NOT_FOUND, "RSG_1004 检测记录不存在：" + signalId);
        }
        return result;
    }

    /** 人工反馈回流（确认/误报，优化信号权重） */
    public RiskSignalDtos.FeedbackResult feedback(String signalId, Map<String, Object> body, String requestId) {
        if (!records.containsKey(signalId)) {
            throw new BaiException(RiskSignalErrorCodes.NOT_FOUND, "RSG_1004 检测记录不存在：" + signalId);
        }
        String label = str(body.get("label"));
        log.info("信号反馈回流: signalId={}, label={}, operator={}, requestId={}",
                signalId, label, body.get("operator"), requestId);
        return new RiskSignalDtos.FeedbackResult(signalId, true, "已回流至信号阈值优化样本库");
    }

    /** 确定性检测主流程：事件关键词 → 信号分级 → 加权聚合 → 预警判定 */
    private RiskSignalDtos.DetectResult doDetect(String entityType, String entityId, String scene,
                                                 List<String> sources, List<?> events) {
        List<String> texts = events.stream().map(e -> {
            if (e instanceof Map<?, ?> m) {
                Object d = m.get("description");
                if (d == null) {
                    d = m.get("content");
                }
                if (d == null) {
                    d = m.get("title");
                }
                return str(d == null ? "" : d);
            }
            return String.valueOf(e);
        }).toList();
        // 1. 逐信号源检测：该源事件 + 关键词 → 级别/强度/证据
        List<RiskSignalDtos.Signal> signals = new ArrayList<>();
        for (String source : sources) {
            List<String> evidence = new ArrayList<>();
            String level = null;
            double strength = 0;
            for (String text : texts) {
                SignalRule rule = matchRule(source, text);
                if (rule == null) {
                    continue;
                }
                evidence.add(text);
                if (level == null || severity(rule.level()) > severity(level)) {
                    level = rule.level();
                    strength = rule.strength();
                }
            }
            if (level != null) {
                signals.add(new RiskSignalDtos.Signal(source, level, strength, List.copyOf(evidence)));
            }
        }
        // 2. 加权聚合：Σ(权重/Σ权重)×信号分，输出 0-100 聚合分
        double totalWeight = signals.stream().mapToDouble(s -> WEIGHTS.get(s.signalType())).sum();
        double aggScore = signals.stream()
                .mapToDouble(s -> WEIGHTS.get(s.signalType()) / totalWeight * LEVEL_SCORES.get(s.level()))
                .sum();
        aggScore = Math.round(aggScore * 10) / 10.0;
        List<RiskSignalDtos.Component> components = signals.stream()
                .map(s -> new RiskSignalDtos.Component(s.signalType(), WEIGHTS.get(s.signalType()),
                        LEVEL_SCORES.get(s.level())))
                .toList();
        boolean anyCritical = signals.stream().anyMatch(s -> "CRITICAL".equals(s.level()));
        String aggLevel = aggScore >= 90 ? "CRITICAL" : aggScore >= ALERT_SCORE ? "HIGH"
                : aggScore >= 60 ? "MEDIUM" : "LOW";
        boolean alertTriggered = aggScore >= ALERT_SCORE || anyCritical;
        String signalId = "RSG" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet() % 10000);
        int elapsedMs = Math.max(1, entityId.length() % 30 + 15);
        return new RiskSignalDtos.DetectResult(signalId, List.copyOf(signals),
                new RiskSignalDtos.Aggregated(aggLevel, aggScore, components), alertTriggered, elapsedMs);
    }

    /** 事件关键词规则（确定性：source + 关键词 → 级别/强度） */
    private SignalRule matchRule(String source, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return switch (source) {
            case "JUDICIAL" -> text.contains("被执行") ? new SignalRule("CRITICAL", 0.92)
                    : text.contains("失信") || text.contains("黑名单") ? new SignalRule("HIGH", 0.85)
                    : text.contains("立案") || text.contains("诉讼") || text.contains("开庭") ? new SignalRule("MEDIUM", 0.70)
                    : null;
            case "FLOW" -> text.contains("流出") ? new SignalRule("HIGH", 0.75)
                    : text.contains("突增") || text.contains("突降") ? new SignalRule("MEDIUM", 0.65)
                    : text.contains("回款") ? new SignalRule("MEDIUM", 0.60)
                    : new SignalRule("LOW", 0.50);
            case "INDUSTRY" -> text.contains("经营异常") ? new SignalRule("HIGH", 0.80)
                    : text.contains("股权变更") || text.contains("股权") ? new SignalRule("MEDIUM", 0.60)
                    : text.contains("变更") ? new SignalRule("LOW", 0.50)
                    : null;
            case "TAX" -> text.contains("欠税") || text.contains("税务处罚") ? new SignalRule("MEDIUM", 0.65)
                    : text.contains("税务") ? new SignalRule("LOW", 0.50)
                    : null;
            case "PUBLIC" -> text.contains("行政处罚") ? new SignalRule("MEDIUM", 0.60)
                    : text.contains("舆情") || text.contains("负面") ? new SignalRule("LOW", 0.50)
                    : null;
            default -> null;
        };
    }

    /** 内部信号规则模型 */
    private record SignalRule(String level, double strength) {}

    private static int severity(String level) {
        return switch (level) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    private static String entityJson(Map<String, Object> entity) {
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("entityType", entity.get("entityType"));
        copy.put("entityId", entity.get("entityId"));
        return String.valueOf(copy);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
