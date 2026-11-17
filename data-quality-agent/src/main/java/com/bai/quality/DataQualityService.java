package com.bai.quality;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据质量治理服务（BIA-DTA-022）。五维度规则集为确定性仿真（标注：仿真，用于联调；
 * SQL 表达式仅 SELECT，真实执行应下发质检引擎），每条规则基于 ruleId+assetId+asOf 哈希
 * 确定性产出违规行数，违规率=违规行/总行 与阈值比对得通过判定。
 * <p>维度权重：完整性 0.25/准确性 0.30/一致性 0.20/及时性 0.10/唯一性 0.15；
 * qualityScore=加权维度分（0-100，2 位小数），&lt;80 触发降级标记 degraded=true；
 * ERROR 违规生成工单号（内存模拟工单库，GET /issues 可查）。
 * <p>幂等：requestId+assetId+asOf，缓存 24h；限流 check 50 QPS / batch 10 QPS
 */
@Service
public class DataQualityService {

    private static final Logger log = LoggerFactory.getLogger(DataQualityService.class);

    private static final int MAX_RULES = 50;
    private static final int MAX_BATCH_RULES = 200;
    private static final String RULE_VERSION = "2026.09.01";
    /** 资产质量白名单（未注册 → DQL_1006） */
    private static final List<String> ASSETS = List.of(
            "dwd_sum.loan_daily", "ods_core.loan", "dwd_sum.repay_daily", "ods_credit.loan_account",
            "ods_core.customer_base", "dwd_credit.contract", "dwd_credit.loan_file", "dwd_risk.provision",
            "dwd_fin.statement", "dws_mkt.prov_stat", "ods_pay.txn_recon", "dwd_ai.training_set",
            "dws_cb.report_data", "dwd_credit.collateral");
    private static final double W_COMPLETENESS = 0.25;
    private static final double W_ACCURACY = 0.30;
    private static final double W_CONSISTENCY = 0.20;
    private static final double W_TIMELINESS = 0.10;
    private static final double W_UNIQUENESS = 0.15;

    /** 规则库（确定性仿真；sql 仅 SELECT，用于联调） */
    private static final Map<String, QRule> RULES = buildRules();

    private final IdempotencyStore idempotency = new IdempotencyStore();
    /** 内存模拟工单库：issueId → Issue */
    private final Map<String, DataQualityDtos.Issue> issueStore = new LinkedHashMap<>();
    private final AtomicLong checkSeq = new AtomicLong();
    private final AtomicLong batchSeq = new AtomicLong();
    private final AtomicLong issueSeq = new AtomicLong();

    /** 资产质检（幂等：requestId+assetId+asOf 相同返回首次结果，缓存 24h） */
    public DataQualityDtos.CheckResult check(DataQualityDtos.CheckRequest req, String requestId) {
        long start = System.currentTimeMillis();
        if (req == null || req.assetId() == null || req.assetId().isBlank()) {
            throw new BaiException(DataQualityErrorCodes.PARAM_INVALID, "DQL_1001 参数缺失：assetId 必填");
        }
        if (!ASSETS.contains(req.assetId())) {
            throw new BaiException(DataQualityErrorCodes.ASSET_NOT_REGISTERED, "DQL_1006 资产未注册质量白名单：" + req.assetId());
        }
        String asOf = resolveAsOf(req.asOf());
        List<QRule> rules = selectRules(req.ruleIds());
        String key = requestId + ":" + req.assetId() + ":" + asOf;
        DataQualityDtos.CheckResult cached = idempotency.get("check", key);
        if (cached != null) {
            return cached;
        }
        DataQualityDtos.CheckResult result = runRules(req.assetId(), asOf, rules, "DQL" + dayStamp()
                + String.format("%07d", checkSeq.incrementAndGet()), start);
        idempotency.put("check", key, result);
        return result;
    }

    /** 批量质检（单资产 + 规则集≤200 条） */
    public DataQualityDtos.BatchResult batch(DataQualityDtos.BatchRequest req, String requestId) {
        long start = System.currentTimeMillis();
        if (req == null || req.assetId() == null || req.assetId().isBlank()) {
            throw new BaiException(DataQualityErrorCodes.PARAM_INVALID, "DQL_1001 参数缺失：batch.assetId 必填");
        }
        if (req.ruleIds() == null || req.ruleIds().isEmpty()) {
            throw new BaiException(DataQualityErrorCodes.PARAM_INVALID, "DQL_1001 参数缺失：batch.ruleIds 必填");
        }
        if (req.ruleIds().size() > MAX_BATCH_RULES) {
            throw new BaiException(DataQualityErrorCodes.OVER_LIMIT,
                    "DQL_1005 批量超 " + MAX_BATCH_RULES + " 规则（实际 " + req.ruleIds().size() + "），请拆分");
        }
        if (!ASSETS.contains(req.assetId())) {
            throw new BaiException(DataQualityErrorCodes.ASSET_NOT_REGISTERED, "DQL_1006 资产未注册质量白名单：" + req.assetId());
        }
        String asOf = resolveAsOf(null);
        List<QRule> rules = selectRules(req.ruleIds());
        DataQualityDtos.CheckResult result = runRules(req.assetId(), asOf, rules, "DQL" + dayStamp()
                + String.format("%07d", checkSeq.incrementAndGet()), start);
        long passed = result.results().stream().filter(DataQualityDtos.RuleResult::passed).count();
        String batchId = "QCB" + dayStamp() + String.format("%07d", batchSeq.incrementAndGet());
        log.info("数据质量批量受理: requestId={}, batchId={}, assetId={}, total={}", requestId, batchId, req.assetId(), rules.size());
        return new DataQualityDtos.BatchResult(batchId, req.assetId(), result.results().size(), (int) passed,
                result.results().size() - (int) passed, result.degraded(), result.results());
    }

    /** 规则库（GET /rules，sql 仅 SELECT 仿真） */
    public DataQualityDtos.RulesResult rules() {
        List<DataQualityDtos.RuleDef> defs = RULES.values().stream()
                .map(r -> new DataQualityDtos.RuleDef(r.ruleId(), r.ruleName(), r.dimension(), r.severity(),
                        r.threshold(), r.sql()))
                .toList();
        return new DataQualityDtos.RulesResult(RULE_VERSION, defs);
    }

    /** 工单列表（GET /issues，内存模拟工单库） */
    public synchronized DataQualityDtos.IssuesResult issues() {
        return new DataQualityDtos.IssuesResult(issueStore.size(), List.copyOf(issueStore.values()));
    }

    /** 规则选择：缺省全量；超 50 → DQL_1005；未知 → DQL_1004 */
    private List<QRule> selectRules(List<String> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return List.copyOf(RULES.values());
        }
        if (ruleIds.size() > MAX_RULES) {
            throw new BaiException(DataQualityErrorCodes.OVER_LIMIT,
                    "DQL_1005 ruleIds 超 " + MAX_RULES + " 条（实际 " + ruleIds.size() + "），请拆分");
        }
        List<QRule> rules = new ArrayList<>();
        for (String rid : ruleIds) {
            QRule r = RULES.get(rid);
            if (r == null) {
                throw new BaiException(DataQualityErrorCodes.NOT_FOUND, "DQL_1004 ruleId 不存在：" + rid);
            }
            rules.add(r);
        }
        return rules;
    }

    /** 规则执行（确定性仿真）+ 维度聚合 + 加权得分 + 降级标记 + ERROR 工单 */
    private DataQualityDtos.CheckResult runRules(String assetId, String asOf, List<QRule> rules,
                                                 String checkId, long start) {
        int totalRows = 1_000_000 + ((assetId + "|" + asOf).hashCode() & 0x7fffffff) % 500_000;
        List<DataQualityDtos.RuleResult> results = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        Map<String, Double> dimScores = new LinkedHashMap<>();
        for (String dim : List.of("completeness", "accuracy", "consistency", "timeliness", "uniqueness")) {
            dimScores.put(dim, 100.0);
        }
        for (QRule rule : rules) {
            long affected = ((rule.ruleId() + "|" + assetId + "|" + asOf).hashCode() & 0x7fffffff) % (totalRows / 20 + 1);
            double rate = affected / (double) totalRows;
            boolean passed = rate <= rule.threshold();
            String detail = "违规行 " + affected + "/" + totalRows + "（违规率 " + String.format("%.4f", rate)
                    + "，阈值 " + rule.threshold() + "，SQL 仿真，用于联调）";
            results.add(new DataQualityDtos.RuleResult(rule.ruleId(), rule.ruleName(), rule.dimension(),
                    rule.severity(), passed, affected, detail));
            if (!passed) {
                // 维度扣分：ERROR 权重 20/WARN 权重 10，按超阈值倍数线性放大
                double penalty = Math.min(100.0, (rate / rule.threshold()) * ("ERROR".equals(rule.severity()) ? 20.0 : 10.0));
                dimScores.merge(rule.dimension(), -penalty, Double::sum);
                if ("ERROR".equals(rule.severity())) {
                    String issueId = "DQI" + dayStamp() + String.format("%07d", issueSeq.incrementAndGet());
                    synchronized (issueStore) {
                        issueStore.put(issueId, new DataQualityDtos.Issue(issueId, assetId, rule.ruleId(), asOf,
                                rule.severity(), "OPEN", LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
                    }
                    issues.add(issueId);
                }
            }
        }
        DataQualityDtos.DimensionScores scores = new DataQualityDtos.DimensionScores(
                round2(Math.max(0, dimScores.get("completeness"))),
                round2(Math.max(0, dimScores.get("accuracy"))),
                round2(Math.max(0, dimScores.get("consistency"))),
                round2(Math.max(0, dimScores.get("timeliness"))),
                round2(Math.max(0, dimScores.get("uniqueness"))));
        double qualityScore = round2(W_COMPLETENESS * scores.completeness() + W_ACCURACY * scores.accuracy()
                + W_CONSISTENCY * scores.consistency() + W_TIMELINESS * scores.timeliness()
                + W_UNIQUENESS * scores.uniqueness());
        boolean degraded = qualityScore < 80;
        long elapsed = System.currentTimeMillis() - start;
        log.info("资产质检: checkId={}, assetId={}, asOf={}, qualityScore={}, degraded={}, issues={}",
                checkId, assetId, asOf, qualityScore, degraded, issues);
        return new DataQualityDtos.CheckResult(checkId, assetId, asOf, qualityScore, scores, results, degraded, issues, elapsed);
    }

    /** asOf 解析：缺省当天；非法格式 → DQL_1001 */
    private String resolveAsOf(String asOf) {
        if (asOf == null || asOf.isBlank()) {
            return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        try {
            return LocalDate.parse(asOf).format(DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw new BaiException(DataQualityErrorCodes.PARAM_INVALID, "DQL_1001 参数非法：asOf 格式须为 yyyy-MM-dd");
        }
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String dayStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    /** 质检规则（五维度；sql 仅 SELECT 仿真，用于联调） */
    private record QRule(String ruleId, String ruleName, String dimension, String severity, double threshold, String sql) {}

    private static Map<String, QRule> buildRules() {
        Map<String, QRule> r = new LinkedHashMap<>();
        r.put("R_CMP_001", new QRule("R_CMP_001", "完整性-核心字段空值", "completeness", "ERROR", 0.01,
                "SELECT COUNT(*) FROM {asset} WHERE cust_id IS NULL OR loan_balance IS NULL"));
        r.put("R_CMP_002", new QRule("R_CMP_002", "完整性-机构字段回填", "completeness", "WARN", 0.005,
                "SELECT COUNT(*) FROM {asset} WHERE org_id IS NULL"));
        r.put("R_ACC_001", new QRule("R_ACC_001", "准确性-金额取值范围", "accuracy", "ERROR", 0.01,
                "SELECT COUNT(*) FROM {asset} WHERE loan_balance < 0"));
        r.put("R_ACC_002", new QRule("R_ACC_002", "准确性-日期格式规范", "accuracy", "WARN", 0.005,
                "SELECT COUNT(*) FROM {asset} WHERE stat_date NOT REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'"));
        r.put("R_CON_001", new QRule("R_CON_001", "一致性-借贷平衡", "consistency", "ERROR", 0.01,
                "SELECT COUNT(*) FROM {asset} a LEFT JOIN ods_core.loan b ON a.loan_no = b.loan_no WHERE ABS(a.balance - b.balance) > 0.01"));
        r.put("R_CON_002", new QRule("R_CON_002", "一致性-跨表主键一致", "consistency", "ERROR", 0.02,
                "SELECT COUNT(*) FROM {asset} a LEFT JOIN dwd_sum.loan_daily b ON a.loan_no = b.loan_no WHERE b.loan_no IS NULL"));
        r.put("R_TML_001", new QRule("R_TML_001", "及时性-数据到位时效", "timeliness", "WARN", 0.05,
                "SELECT COUNT(*) FROM {asset} WHERE etl_time > DATE_ADD('{asOf}', INTERVAL 1 DAY)"));
        r.put("R_TML_002", new QRule("R_TML_002", "及时性-调度链路时效", "timeliness", "WARN", 0.02,
                "SELECT COUNT(*) FROM dwd_etl_log WHERE biz_date = '{asOf}' AND status <> 'SUCCESS'"));
        r.put("R_UNQ_001", new QRule("R_UNQ_001", "唯一性-主键唯一", "uniqueness", "ERROR", 0.001,
                "SELECT COUNT(*) FROM (SELECT loan_no FROM {asset} GROUP BY loan_no HAVING COUNT(*) > 1) t"));
        r.put("R_UNQ_002", new QRule("R_UNQ_002", "唯一性-证件号唯一", "uniqueness", "WARN", 0.005,
                "SELECT COUNT(*) FROM (SELECT cert_no FROM {asset} GROUP BY cert_no HAVING COUNT(*) > 1) t"));
        return Map.copyOf(r);
    }
}
