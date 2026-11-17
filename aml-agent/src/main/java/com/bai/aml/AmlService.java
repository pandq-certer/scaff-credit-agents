package com.bai.aml;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 反洗钱服务（BIA-RSK-050）。履行反洗钱法定义务的监测原子。
 * <p>处理链：交易汇总（笔数/金额/对手/时间分布）→ 四类规则监测（大额/频繁/拆分/快进快出）→ 名单联动加权
 * → 可疑评分 0-100 → 结论判定（≥75 可疑建议上报、60-75 存疑转人工、&lt;60 正常）→ SAR 草稿生成 → 留痕。
 * <p>阈值按人行口径：现金 5 万/转账 20 万；频繁 T1D 不少于 5 笔；快进快出 T1D 进出不少于 2 次且金额比不低于 80%。
 * <p>MVP 为确定性内存仿真（规则确定性计算 + 哈希模拟模型评分），生产模型推理由 model-infer-agent 承担。
 */
@Service
public class AmlService {

    private static final Logger log = LoggerFactory.getLogger(AmlService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_TXNS = 1000;                 // 交易清单上限，超长 AML_1006
    private static final double LARGE_TRANSFER = 200_000d;    // 转账大额阈值 20 万（人行口径）
    private static final double LARGE_CASH = 50_000d;         // 现金大额阈值 5 万（人行口径）
    private static final double LARGE_CUMULATIVE = 1_000_000d; // 窗口累计大额阈值 100 万
    private static final List<String> WINDOWS = List.of("T1H", "T1D", "T7D", "T30D");
    private static final List<String> SCENES = List.of("ADMISSION", "TRADING", "REPAYMENT", "ALL");
    private static final Map<String, Integer> FREQ_LIMIT = Map.of("T1H", 3, "T1D", 5, "T7D", 20, "T30D", 50);

    private final IdempotencyStore idempotency = new IdempotencyStore();

    /** 监测记录：monitorId → 记录 */
    private final Map<String, Monitor> monitors = new ConcurrentHashMap<>();
    /** 报告：reportId → 报告；monitorId+type 唯一防重复 */
    private final Map<String, AmlDtos.ReportResult> reports = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    private static final class Monitor {
        final String monitorId;
        final String requestId;
        final String window;
        final String scene;
        final List<AmlDtos.Trigger> triggers;
        final double modelScore;
        final String riskLevel;
        final boolean suspicious;
        final double totalAmount;
        final String createdAt;
        String feedback;

        Monitor(String monitorId, String requestId, String window, String scene, List<AmlDtos.Trigger> triggers,
                double modelScore, String riskLevel, boolean suspicious, double totalAmount, String createdAt) {
            this.monitorId = monitorId;
            this.requestId = requestId;
            this.window = window;
            this.scene = scene;
            this.triggers = triggers;
            this.modelScore = modelScore;
            this.riskLevel = riskLevel;
            this.suspicious = suspicious;
            this.totalAmount = totalAmount;
            this.createdAt = createdAt;
        }
    }

    /** 窗口监测：四类可疑模式 + 名单联动 + 模型评分 + 结论判定（幂等键 requestId+窗口+交易哈希） */
    public AmlDtos.MonitorResult monitor(Map<String, Object> body, String requestId) {
        if (body == null || isBlank(body.get("window")) || body.get("transactions") == null) {
            throw new BaiException(AmlErrorCodes.PARAM_INVALID,
                    "AML_1001 参数缺失：window 与 transactions（交易清单）必填");
        }
        String window = str(body.get("window")).toUpperCase();
        if (!WINDOWS.contains(window)) {
            throw new BaiException(AmlErrorCodes.WINDOW_INVALID,
                    "AML_1007 窗口非法：" + window + "（仅支持 T1H/T1D/T7D/T30D）");
        }
        String scene = strOr(body.get("scene"), "ALL").toUpperCase();
        if (!SCENES.contains(scene)) {
            throw new BaiException(AmlErrorCodes.WINDOW_INVALID,
                    "AML_1007 场景非法：" + scene + "（仅支持 ADMISSION/TRADING/REPAYMENT/ALL）");
        }
        if (!(body.get("transactions") instanceof List<?> raw) || raw.isEmpty()) {
            throw new BaiException(AmlErrorCodes.PARAM_INVALID, "AML_1001 参数缺失：transactions 不能为空");
        }
        if (raw.size() > MAX_TXNS) {
            throw new BaiException(AmlErrorCodes.TOO_LONG,
                    "AML_1006 交易清单超长：" + raw.size() + " 笔（≤" + MAX_TXNS + " 笔/次），请缩减输入");
        }
        String txnHash = UUID.nameUUIDFromBytes(String.valueOf(raw).getBytes()).toString();
        AmlDtos.MonitorResult cached = idempotency.get("monitor", requestId + ":" + window + ":" + txnHash);
        if (cached != null) {
            return cached;
        }
        long begin = System.nanoTime();
        List<Txn> txns = parseTxns(raw);
        // 交易汇总
        int count = txns.size();
        double total = txns.stream().mapToDouble(t -> t.amount).sum();
        long distinctCounterparties = txns.stream().map(t -> t.counterparty).distinct().count();
        List<AmlDtos.Trigger> triggers = new ArrayList<>();
        // 规则 1：大额触发（单笔/累计超阈值，大额交易零漏报）
        for (Txn t : txns) {
            double threshold = t.cash ? LARGE_CASH : LARGE_TRANSFER;
            if (t.amount >= threshold) {
                triggers.add(new AmlDtos.Trigger("R_LARGE_001", "LARGE",
                        (t.cash ? "现金单笔≥5万" : "转账单笔≥20万"),
                        String.format("单笔 %.2f 元", t.amount), "单笔超大额报告阈值"));
            }
        }
        if (total >= LARGE_CUMULATIVE) {
            triggers.add(new AmlDtos.Trigger("R_LARGE_002", "LARGE", "窗口累计≥100万",
                    String.format("累计 %.2f 元/%d 笔", total, count), "窗口累计金额超大额报告阈值"));
        }
        // 规则 2：频繁触发（单位时间笔数超限）
        int freqLimit = FREQ_LIMIT.getOrDefault(window, 5);
        if (count >= freqLimit) {
            triggers.add(new AmlDtos.Trigger("R_FREQ_001", "FREQUENT", window + " ≥" + freqLimit + " 笔",
                    count + " 笔", "单位时间交易笔数超限"));
        }
        // 规则 3：拆分触发（多笔近阈值规避大额报告）
        long nearThreshold = txns.stream().filter(t -> !t.cash && t.amount >= LARGE_TRANSFER * 0.9
                && t.amount < LARGE_TRANSFER).count();
        if (nearThreshold >= 3) {
            triggers.add(new AmlDtos.Trigger("R_SPLIT_007", "SPLIT", "3笔/日且单笔接近大额阈值",
                    nearThreshold + "笔×接近20万", "规避大额报告阈值的拆分特征"));
        }
        // 规则 4：快进快出（资金短时进出无沉淀，T1D 进出≥2 次且金额比≥80%）
        double inSum = txns.stream().filter(t -> "IN".equals(t.direction)).mapToDouble(t -> t.amount).sum();
        double outSum = txns.stream().filter(t -> "OUT".equals(t.direction)).mapToDouble(t -> t.amount).sum();
        if (inSum > 0 && outSum > 0) {
            double ratio = Math.min(inSum, outSum) / Math.max(inSum, outSum);
            if (ratio >= 0.8) {
                triggers.add(new AmlDtos.Trigger("R_FLASH_002", "FLASH",
                        "进出≥2 次且金额比≥80%", String.format("进 %.2f/出 %.2f（比率 %.0f%%）", inSum, outSum, ratio * 100),
                        "资金短时进出无沉淀"));
            }
        }
        // 可疑评分（确定性：规则权重 + 输入指纹扰动），名单联动由 screening-agent 高危名单加权（仿真 +5）
        double score = triggers.stream().mapToDouble(t -> switch (t.ruleType()) {
            case "LARGE" -> 25;
            case "SPLIT" -> 25;
            case "FLASH" -> 20;
            case "FREQUENT" -> 15;
            default -> 10;
        }).sum() + (Math.abs(txnHash.hashCode()) % 100) / 10.0;
        if (!triggers.isEmpty() && distinctCounterparties >= 3) {
            score += 5; // 多对手分散特征（名单联动仿真加权）
        }
        score = Math.min(99.9, Math.round(score * 10) / 10.0);
        String riskLevel = score >= 85 ? "CRITICAL" : score >= 70 ? "HIGH" : score >= 50 ? "MEDIUM" : "LOW";
        boolean suspicious = score >= 75;
        boolean reportRequired = suspicious;
        boolean escalated = score >= 60 && score < 75;
        String monitorId = "AML" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        Monitor m = new Monitor(monitorId, requestId, window, scene, List.copyOf(triggers), score, riskLevel,
                suspicious, total, LocalDateTime.now().toString());
        monitors.put(monitorId, m);
        int elapsedMs = (int) ((System.nanoTime() - begin) / 1_000_000);
        log.info("反洗钱监测: monitorId={}, window={}, scene={}, triggers={}, score={}, suspicious={}",
                monitorId, window, scene, triggers.size(), score, suspicious);
        AmlDtos.MonitorResult result = new AmlDtos.MonitorResult(monitorId, window, scene, m.triggers, score,
                riskLevel, suspicious, reportRequired, escalated, count, total, elapsedMs);
        idempotency.put("monitor", requestId + ":" + window + ":" + txnHash, result);
        return result;
    }

    /** 报告生成：可疑报告（SAR）草稿/大额报告，人工复核后报送（同记录同类型防重复） */
    public AmlDtos.ReportResult report(Map<String, Object> body) {
        if (body == null || isBlank(body.get("monitorId"))) {
            throw new BaiException(AmlErrorCodes.PARAM_INVALID, "AML_1001 参数缺失：monitorId 必填");
        }
        String monitorId = str(body.get("monitorId"));
        String reportType = strOr(body.get("reportType"), "SUSPICIOUS").toUpperCase();
        Monitor m = monitors.get(monitorId);
        if (m == null) {
            throw new BaiException(AmlErrorCodes.NOT_FOUND, "AML_1004 监测记录不存在：" + monitorId);
        }
        if ("SUSPICIOUS".equals(reportType) && !m.suspicious) {
            throw new BaiException(AmlErrorCodes.CONFLICT,
                    "AML_1005 报告冲突：监测记录 " + monitorId + " 非可疑结论（score=" + m.modelScore + "），不可生成 SAR");
        }
        String dedupeKey = monitorId + ":" + reportType;
        AmlDtos.ReportResult existing = reports.get(dedupeKey);
        if (existing != null) {
            throw new BaiException(AmlErrorCodes.CONFLICT,
                    "AML_1005 重复提交：报告已存在 reportId=" + existing.reportId());
        }
        String reportId = "SAR" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        String summary = ("SUSPICIOUS".equals(reportType) ? "可疑交易报告（SAR）草稿" : "大额交易报告草稿")
                + "：monitorId=" + monitorId + "，窗口 " + m.window + "，触发规则 " + m.triggers.size()
                + " 项，模型评分 " + m.modelScore + "，待人工复核后报送";
        AmlDtos.ReportResult result = new AmlDtos.ReportResult(reportId, monitorId, reportType,
                "DRAFT_PENDING_REVIEW", summary, LocalDateTime.now().toString());
        reports.put(dedupeKey, result);
        log.info("反洗钱报告生成: reportId={}, monitorId={}, type={}", reportId, monitorId, reportType);
        return result;
    }

    /** 监测记录查询（monitorId/requestId/suspicious 过滤） */
    public List<AmlDtos.MonitorRecord> query(String monitorId, String requestId, String suspicious) {
        if (monitorId != null && !monitorId.isBlank() && !monitors.containsKey(monitorId)) {
            throw new BaiException(AmlErrorCodes.NOT_FOUND, "AML_1004 监测记录不存在：" + monitorId);
        }
        return monitors.values().stream()
                .filter(m -> monitorId == null || monitorId.isBlank() || m.monitorId.equals(monitorId))
                .filter(m -> requestId == null || requestId.isBlank() || m.requestId.equals(requestId))
                .filter(m -> suspicious == null || suspicious.isBlank()
                        || String.valueOf(m.suspicious).equalsIgnoreCase(suspicious))
                .sorted(java.util.Comparator.comparing(m -> m.monitorId))
                .map(m -> new AmlDtos.MonitorRecord(m.monitorId, m.requestId, m.window, m.scene, m.riskLevel,
                        m.modelScore, m.suspicious, m.suspicious, m.modelScore >= 60 && m.modelScore < 75,
                        m.feedback, m.triggers, m.createdAt))
                .toList();
    }

    /** 人工结论回流（SUSPICIOUS/NORMAL），优化阈值与模型重训（同记录防重复回流） */
    public AmlDtos.FeedbackResult feedback(Map<String, Object> body) {
        if (body == null || isBlank(body.get("monitorId")) || isBlank(body.get("conclusion"))) {
            throw new BaiException(AmlErrorCodes.PARAM_INVALID, "AML_1001 参数缺失：monitorId/conclusion 必填");
        }
        String monitorId = str(body.get("monitorId"));
        String conclusion = str(body.get("conclusion")).toUpperCase();
        if (!List.of("SUSPICIOUS", "NORMAL").contains(conclusion)) {
            throw new BaiException(AmlErrorCodes.PARAM_INVALID, "AML_1001 参数非法：conclusion 仅支持 SUSPICIOUS/NORMAL");
        }
        Monitor m = monitors.get(monitorId);
        if (m == null) {
            throw new BaiException(AmlErrorCodes.NOT_FOUND, "AML_1004 监测记录不存在：" + monitorId);
        }
        if (m.feedback != null) {
            throw new BaiException(AmlErrorCodes.CONFLICT,
                    "AML_1005 重复提交：监测记录 " + monitorId + " 已有人工结论 " + m.feedback);
        }
        m.feedback = conclusion;
        log.info("人工结论回流: monitorId={}, conclusion={}", monitorId, conclusion);
        return new AmlDtos.FeedbackResult(monitorId, conclusion, "ACCEPTED", LocalDateTime.now().toString());
    }

    private record Txn(double amount, String counterparty, String direction, boolean cash) {}

    private List<Txn> parseTxns(List<?> raw) {
        List<Txn> txns = new ArrayList<>();
        for (Object o : raw) {
            Map<String, Object> t = cast(o);
            if (t == null || t.get("amount") == null) {
                throw new BaiException(AmlErrorCodes.PARAM_INVALID,
                        "AML_1001 参数非法：transactions 每笔须含 amount（金额/对手/时间/用途）");
            }
            double amount = doubleOf(t.get("amount"));
            if (amount <= 0) {
                throw new BaiException(AmlErrorCodes.PARAM_INVALID, "AML_1001 参数非法：交易金额须大于 0");
            }
            String purpose = strOr(t.get("purpose"), "").toUpperCase();
            boolean cash = purpose.contains("CASH") || purpose.contains("现金");
            String direction = strOr(t.get("direction"), "OUT").toUpperCase();
            txns.add(new Txn(amount, strOr(t.get("counterparty"), "-"), direction, cash));
        }
        return txns;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
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

    private static double doubleOf(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
