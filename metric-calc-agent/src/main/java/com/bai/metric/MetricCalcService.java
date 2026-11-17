package com.bai.metric;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 指标计算服务（BIA-DTA-020）。指标字典与计算引擎为确定性仿真（标注：仿真，用于联调），
 * value 基于口径版本+参数哈希生成稳定伪值（同参数复现一致）；真实链路应经 data-query 取数
 * 并按口径 SQL 加工，本服务为统一出口桩。
 * <p>处理链：校验入参（metricId 注册、version 缺省最新、params.orgId 必填、params.date 格式 yyyy-MM-dd）
 * → 口径解析 → 指标计算（分母为零等校验失败 MTC_1006，可用 flag=ZERO_DENOM 仿真触发）
 * → 指纹（口径版本+参数 SHA-256）→ 输出 value(2 位)/lineage/elapsedMs
 * <p>幂等：requestId+metricId+version+params，缓存 24h；限流 calc 50 QPS / batch 10 QPS
 */
@Service
public class MetricCalcService {

    private static final Logger log = LoggerFactory.getLogger(MetricCalcService.class);

    private static final int MAX_BATCH = 2000;
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** 口径版本（末位为最新生效版本） */
    private static final List<String> VERSIONS = List.of("2026-09-01", "2026-10-01");
    private static final String LATEST_VERSION = "2026-10-01";

    /** 指标字典（确定性仿真，用于联调） */
    private static final Map<String, MetricCalcDtos.MetricDef> METRICS = buildMetrics();

    /** 复合指标（value 为 Map<String,Double> 键值组；params 以入参直算，不要求 orgId/date） */
    private static final Set<String> COMPOSITE_METRICS = Set.of(
            "PROJECT_EVAL", "SOLVENCY_SET", "ASSET_QUALITY_SET", "DRIFT_SET", "FIELD_DIST", "SCORE_DIST");

    /** 伪值基线与浮动区间：metricId → [base, span]（value = base + hash%10000/10000*span，2 位小数） */
    private static final Map<String, double[]> VALUE_BASE = Map.ofEntries(
            Map.entry("BAD_LOAN_RATE", new double[]{2.35, 1.20}),
            Map.entry("NPL_BALANCE", new double[]{45200.00, 20000.00}),
            Map.entry("LOAN_BALANCE", new double[]{125.60, 60.00}),
            Map.entry("APPROVAL_RATE", new double[]{86.50, 8.00}),
            Map.entry("OVERDUE_RATE", new double[]{1.82, 1.00}),
            Map.entry("DEPOSIT_BALANCE", new double[]{310.20, 150.00}),
            Map.entry("CAPITAL_ADEQUACY", new double[]{14.36, 2.50}),
            Map.entry("COST_INCOME_RATIO", new double[]{28.74, 6.00}),
            Map.entry("NIM", new double[]{1.86, 0.50}),
            Map.entry("PROVISION_COVERAGE", new double[]{236.50, 60.00}));

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final AtomicLong calcSeq = new AtomicLong();
    private final AtomicLong batchSeq = new AtomicLong();

    /** 单指标计算（幂等：requestId+metricId+version+params 相同返回首次结果，缓存 24h） */
    public MetricCalcDtos.CalcResult calc(MetricCalcDtos.CalcRequest req, String requestId) {
        MetricCalcDtos.MetricDef def = validate(req);
        String version = resolveVersion(req.version());
        String key = requestId + ":" + req.metricId() + ":" + version + ":" + req.params();
        MetricCalcDtos.CalcResult cached = idempotency.get("calc", key);
        if (cached != null) {
            return cached;
        }
        MetricCalcDtos.CalcResult result = compute(def, version, req.params(), requestId);
        idempotency.put("calc", key, result);
        return result;
    }

    /** 批量计算（≤2000 条，逐条校验/逐条幂等，fail-fast） */
    public MetricCalcDtos.BatchCalcResult batch(MetricCalcDtos.BatchCalcRequest req, String requestId) {
        long start = System.currentTimeMillis();
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BaiException(MetricCalcErrorCodes.PARAM_INVALID, "MTC_1001 参数缺失：batch.items 必填");
        }
        if (req.items().size() > MAX_BATCH) {
            throw new BaiException(MetricCalcErrorCodes.OVER_LIMIT,
                    "MTC_1005 批量超 " + MAX_BATCH + " 条（实际 " + req.items().size() + "），请分批");
        }
        List<MetricCalcDtos.CalcResult> results = new ArrayList<>();
        for (MetricCalcDtos.CalcRequest item : req.items()) {
            MetricCalcDtos.MetricDef def = validate(item);
            String version = resolveVersion(item.version());
            String key = requestId + ":" + item.metricId() + ":" + version + ":" + item.params();
            MetricCalcDtos.CalcResult r = idempotency.get("calc", key);
            if (r == null) {
                r = compute(def, version, item.params(), requestId);
                idempotency.put("calc", key, r);
            }
            results.add(r);
        }
        String taskId = "MTB" + dayStamp() + String.format("%07d", batchSeq.incrementAndGet());
        log.info("指标批量计算受理: requestId={}, taskId={}, total={}", requestId, taskId, results.size());
        return new MetricCalcDtos.BatchCalcResult(taskId, results.size(), results, System.currentTimeMillis() - start);
    }

    /** 指标字典（确定性仿真快照） */
    public MetricCalcDtos.IndicatorsResult indicators() {
        return new MetricCalcDtos.IndicatorsResult(LATEST_VERSION, List.copyOf(METRICS.values()));
    }

    /** 口径版本列表 */
    public MetricCalcDtos.VersionsResult versions() {
        return new MetricCalcDtos.VersionsResult(LATEST_VERSION, VERSIONS);
    }

    /** 入参校验：metricId 注册、params.orgId/params.date 必填且日期格式合法（复合指标以 params 直算，豁免 orgId/date） */
    private MetricCalcDtos.MetricDef validate(MetricCalcDtos.CalcRequest req) {
        if (req == null || req.metricId() == null || req.metricId().isBlank()) {
            throw new BaiException(MetricCalcErrorCodes.PARAM_INVALID, "MTC_1001 参数缺失：metricId 必填");
        }
        if (req.params() == null || req.params().isEmpty()) {
            throw new BaiException(MetricCalcErrorCodes.PARAM_INVALID, "MTC_1001 参数缺失：params 必填（orgId/date/granularity 等）");
        }
        MetricCalcDtos.MetricDef def = METRICS.get(req.metricId());
        if (def == null) {
            throw new BaiException(MetricCalcErrorCodes.METRIC_NOT_FOUND, "MTC_1004 指标未注册：" + req.metricId());
        }
        if (COMPOSITE_METRICS.contains(req.metricId())) {
            return def;
        }
        if (req.params().get("orgId") == null || String.valueOf(req.params().get("orgId")).isBlank()) {
            throw new BaiException(MetricCalcErrorCodes.PARAM_INVALID, "MTC_1001 参数缺失：params.orgId 为空");
        }
        Object date = req.params().get("date");
        if (date == null || String.valueOf(date).isBlank()) {
            throw new BaiException(MetricCalcErrorCodes.PARAM_INVALID, "MTC_1001 参数缺失：params.date 必填");
        }
        if (!DATE.matcher(String.valueOf(date)).matches()) {
            throw new BaiException(MetricCalcErrorCodes.PARAM_INVALID, "MTC_1001 参数非法：params.date 格式须为 yyyy-MM-dd");
        }
        return def;
    }

    /** 口径解析：version 缺省最新，非注册版本 MTC_1004 */
    private String resolveVersion(String version) {
        if (version == null || version.isBlank()) {
            return LATEST_VERSION;
        }
        if (!VERSIONS.contains(version)) {
            throw new BaiException(MetricCalcErrorCodes.METRIC_NOT_FOUND,
                    "MTC_1004 口径版本不存在：" + version + "（可用 " + VERSIONS + "）");
        }
        return version;
    }

    /** 计算（确定性仿真：value=base+hash 浮动，2 位小数；flag=ZERO_DENOM 触发分母为零 MTC_1006） */
    private MetricCalcDtos.CalcResult compute(MetricCalcDtos.MetricDef def, String version,
                                              Map<String, Object> params, String requestId) {
        long start = System.currentTimeMillis();
        if ("ZERO_DENOM".equals(String.valueOf(params.get("flag")))) {
            throw new BaiException(MetricCalcErrorCodes.CALC_FAILED,
                    "MTC_1006 计算校验失败：分母为零（flag=ZERO_DENOM，仿真触发），请修正入参");
        }
        if (COMPOSITE_METRICS.contains(def.metricId())) {
            return computeComposite(def, version, params, requestId, start);
        }
        String orgId = String.valueOf(params.get("orgId"));
        String date = String.valueOf(params.get("date"));
        Object g = params.get("granularity");
        String granularity = g == null || String.valueOf(g).isBlank() ? def.granularity() : String.valueOf(g);
        // 可选维度（如场景圈客条件 condition）参与确定性基底：同条件复现一致，不同条件结果可区分；
        // 未传该键的场景基底与历史版本完全一致，行为向后兼容
        Object c = params.get("condition");
        String basis = version + "|" + def.metricId() + "|" + orgId + "|" + date + "|" + granularity
                + (c == null || String.valueOf(c).isBlank() ? "" : "|" + c);
        int h = basis.hashCode() & 0x7fffffff;
        double[] base = VALUE_BASE.getOrDefault(def.metricId(), new double[]{100.00, 50.00});
        double value = round2(base[0] + (h % 10000) / 10000.0 * base[1]);
        String fingerprint = sha256(basis);
        String calcId = "MTC" + dayStamp() + String.format("%07d", calcSeq.incrementAndGet());
        long elapsed = System.currentTimeMillis() - start;
        log.info("指标计算: requestId={}, calcId={}, metricId={}, version={}, value={}(仿真，用于联调), elapsed={}ms",
                requestId, calcId, def.metricId(), version, value, elapsed);
        return new MetricCalcDtos.CalcResult(calcId, def.metricId(), def.metricName(), version, def.unit(),
                granularity, value, fingerprint, def.lineage(), elapsed);
    }

    /**
     * 复合指标计算（params 入参直算，确定性：同参数复现一致；value 为 Map&lt;String,Double&gt;，
     * 2 位小数；无解/分母为零的分量置 0）
     */
    private MetricCalcDtos.CalcResult computeComposite(MetricCalcDtos.MetricDef def, String version,
                                                       Map<String, Object> params, String requestId, long start) {
        Map<String, Double> values = switch (def.metricId()) {
            case "PROJECT_EVAL" -> projectEval(params);
            case "SOLVENCY_SET" -> solvencySet(params);
            case "ASSET_QUALITY_SET" -> assetQualitySet(params);
            case "DRIFT_SET" -> driftSet(params);
            case "FIELD_DIST" -> fieldDist(params);
            case "SCORE_DIST" -> scoreDist(params);
            default -> new LinkedHashMap<>();
        };
        String fingerprint = sha256(version + "|" + def.metricId() + "|" + params);
        String calcId = "MTC" + dayStamp() + String.format("%07d", calcSeq.incrementAndGet());
        long elapsed = System.currentTimeMillis() - start;
        log.info("复合指标计算: requestId={}, calcId={}, metricId={}, values={}(入参直算，用于联调), elapsed={}ms",
                requestId, calcId, def.metricId(), values, elapsed);
        return new MetricCalcDtos.CalcResult(calcId, def.metricId(), def.metricName(), version, def.unit(),
                def.granularity(), values, fingerprint, def.lineage(), elapsed);
    }

    /** PROJECT_EVAL：IRR（二分迭代，无解置 0）/DSCR/PAYBACK/BEP */
    private Map<String, Double> projectEval(Map<String, Object> params) {
        requireParams(params, "PROJECT_EVAL", "initialInvestment", "annualCashflows");
        double initial = dv(params.get("initialInvestment"));
        List<Double> flows = dList(params.get("annualCashflows"));
        if (flows.isEmpty()) {
            throw new BaiException(MetricCalcErrorCodes.PARAM_INVALID, "MTC_1001 参数非法：annualCashflows 须为非空数值列表");
        }
        double revenue = dv(params.get("annualRevenue"));
        double fixed = dv(params.get("annualFixedCost"));
        double vcr = dv(params.get("variableCostRate"));

        double irr = irrOf(initial, flows);
        double opCash = flows.get(0);                        // 经营现金流（首年）
        double debtService = flows.size() > 0 ? initial / flows.size() : 0; // 当期本息合计（按现金流年期摊还）
        double dscr = debtService <= 0 ? 0.0 : opCash / debtService;
        double avgFlow = flows.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double payback = avgFlow <= 0 ? 0.0 : initial / avgFlow;
        double bepDenom = revenue * (1 - vcr);
        double bep = bepDenom <= 0 ? 0.0 : fixed / bepDenom * 100;

        Map<String, Double> v = new LinkedHashMap<>();
        v.put("IRR", round2(irr * 100));                     // 内部收益率（%，无解置 0）
        v.put("DSCR", round2(dscr));                          // 偿债备付率
        v.put("PAYBACK", round2(payback));                    // 静态回收期（年）
        v.put("BEP", round2(bep));                            // 盈亏平衡点（%）
        return v;
    }

    /** IRR 二分迭代：现金流 [-initial, flows...]，区间 [-0.99, 10]，无解返回 0 */
    private double irrOf(double initial, List<Double> flows) {
        double[] cf = new double[flows.size() + 1];
        cf[0] = -initial;
        for (int i = 0; i < flows.size(); i++) {
            cf[i + 1] = flows.get(i);
        }
        java.util.function.DoubleUnaryOperator npv = r -> {
            double s = 0;
            for (int i = 0; i < cf.length; i++) {
                s += cf[i] / Math.pow(1 + r, i);
            }
            return s;
        };
        double lo = -0.99, hi = 10.0;
        double fLo = npv.applyAsDouble(lo), fHi = npv.applyAsDouble(hi);
        if (Double.isNaN(fLo) || Double.isNaN(fHi) || fLo * fHi > 0) {
            return 0.0;
        }
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            double fMid = npv.applyAsDouble(mid);
            if (Math.abs(fMid) < 1e-9) {
                return mid;
            }
            if (fLo * fMid < 0) {
                hi = mid;
                fHi = fMid;
            } else {
                lo = mid;
                fLo = fMid;
            }
        }
        return (lo + hi) / 2;
    }

    /** SOLVENCY_SET：资产负债率（%）/流动比率 */
    private Map<String, Double> solvencySet(Map<String, Object> params) {
        requireParams(params, "SOLVENCY_SET", "totalAssets", "totalLiabilities");
        double assets = dv(params.get("totalAssets"));
        double liabilities = dv(params.get("totalLiabilities"));
        double currentAssets = dv(params.get("currentAssets"));
        double currentLiabilities = dv(params.get("currentLiabilities"));
        Map<String, Double> v = new LinkedHashMap<>();
        v.put("DEBT_RATIO", round2(assets <= 0 ? 0.0 : liabilities / assets * 100));
        v.put("CURRENT_RATIO", round2(currentLiabilities <= 0 ? 0.0 : currentAssets / currentLiabilities));
        return v;
    }

    /** ASSET_QUALITY_SET：不良率（%）/迁徙率（%）/综合收益率（%） */
    private Map<String, Double> assetQualitySet(Map<String, Object> params) {
        requireParams(params, "ASSET_QUALITY_SET", "nplBalance", "loanBalance");
        double npl = dv(params.get("nplBalance"));
        double loan = dv(params.get("loanBalance"));
        double migration = dv(params.get("migrationAmount"));
        double loanPrev = dv(params.get("loanBalancePrev"));
        double interest = dv(params.get("interestIncome"));
        double avgAssets = dv(params.get("avgAssets"));
        Map<String, Double> v = new LinkedHashMap<>();
        v.put("NPL_RATE", round2(loan <= 0 ? 0.0 : npl / loan * 100));
        v.put("MIGRATION_RATE", round2(loanPrev <= 0 ? 0.0 : migration / loanPrev * 100));
        v.put("YIELD_RATE", round2(avgAssets <= 0 ? 0.0 : interest / avgAssets * 100));
        return v;
    }

    /** DRIFT_SET：PSI/KL 散度/badRateMoM（pct） */
    private Map<String, Double> driftSet(Map<String, Object> params) {
        requireParams(params, "DRIFT_SET", "expectedDist", "actualDist");
        Map<String, Double> expected = dMap(params.get("expectedDist"));
        Map<String, Double> actual = dMap(params.get("actualDist"));
        if (expected.isEmpty() || actual.isEmpty()) {
            throw new BaiException(MetricCalcErrorCodes.PARAM_INVALID,
                    "MTC_1001 参数非法：expectedDist/actualDist 须为非空数值 Map");
        }
        double eps = 1e-6;
        java.util.TreeSet<String> keys = new java.util.TreeSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        double psi = 0, kl = 0;
        for (String k : keys) {
            double e = Math.max(expected.getOrDefault(k, 0.0), eps);
            double a = Math.max(actual.getOrDefault(k, 0.0), eps);
            psi += (a - e) * Math.log(a / e);
            kl += a * Math.log(a / e);
        }
        double badNow = dv(params.get("badRateNow"));
        double badPrev = dv(params.get("badRatePrev"));
        Map<String, Double> v = new LinkedHashMap<>();
        v.put("PSI", round2(psi));
        v.put("KL", round2(kl));
        v.put("BAD_RATE_MOM", round2(badNow - badPrev));
        return v;
    }

    /** FIELD_DIST：各字段问题数 + 问题率（%） */
    private Map<String, Double> fieldDist(Map<String, Object> params) {
        requireParams(params, "FIELD_DIST", "total", "issueByField");
        double total = dv(params.get("total"));
        Map<String, Double> issues = dMap(params.get("issueByField"));
        Map<String, Double> v = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : issues.entrySet()) {
            v.put(e.getKey(), round2(e.getValue()));
            v.put(e.getKey() + "_RATE", round2(total <= 0 ? 0.0 : e.getValue() / total * 100));
        }
        return v;
    }

    /** SCORE_DIST：各分桶数量/占比（%）+ 拒绝率（%） */
    private Map<String, Double> scoreDist(Map<String, Object> params) {
        requireParams(params, "SCORE_DIST", "total", "buckets");
        double total = dv(params.get("total"));
        double rejected = dv(params.get("rejected"));
        Map<String, Double> buckets = dMap(params.get("buckets"));
        Map<String, Double> v = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : buckets.entrySet()) {
            v.put(e.getKey(), round2(e.getValue()));
            v.put(e.getKey() + "_RATE", round2(total <= 0 ? 0.0 : e.getValue() / total * 100));
        }
        v.put("REJECT_RATE", round2(total <= 0 ? 0.0 : rejected / total * 100));
        return v;
    }

    /** 复合指标必填参数校验（缺失 → MTC_1001） */
    private void requireParams(Map<String, Object> params, String metricId, String... keys) {
        for (String k : keys) {
            if (params.get(k) == null) {
                throw new BaiException(MetricCalcErrorCodes.PARAM_INVALID,
                        "MTC_1001 参数缺失：" + metricId + " 需 params." + k);
            }
        }
    }

    /** 数值取值：Number 直取，字符串可解析则取，否则 0 */
    private double dv(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    /** 数值列表（JSON 数组 → List<Double>，非法元素跳过） */
    private List<Double> dList(Object o) {
        List<Double> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Number) {
                    out.add(((Number) item).doubleValue());
                }
            }
        }
        return out;
    }

    /** 数值 Map（JSON 对象 → Map<String,Double>，非法值跳过） */
    private Map<String, Double> dMap(Object o) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() instanceof Number n) {
                    out.put(String.valueOf(e.getKey()), n.doubleValue());
                }
            }
        }
        return out;
    }

    private static Map<String, MetricCalcDtos.MetricDef> buildMetrics() {
        Map<String, MetricCalcDtos.MetricDef> m = new LinkedHashMap<>();
        m.put("BAD_LOAN_RATE", new MetricCalcDtos.MetricDef("BAD_LOAN_RATE", "不良贷款率", "%", "ORG/MONTH",
                "SUM(不良贷款余额)/SUM(贷款总额)*100", LATEST_VERSION, List.of("ods_core.loan", "dwd_sum.loan_daily")));
        m.put("NPL_BALANCE", new MetricCalcDtos.MetricDef("NPL_BALANCE", "不良余额", "万元", "ORG/MONTH",
                "SUM(CASE WHEN 五级分类 IN (次级,可疑,损失) THEN balance END)/10000", LATEST_VERSION,
                List.of("ods_core.loan", "dwd_sum.loan_daily")));
        m.put("LOAN_BALANCE", new MetricCalcDtos.MetricDef("LOAN_BALANCE", "贷款余额", "亿元", "ORG/MONTH",
                "SUM(balance)/100000000", LATEST_VERSION, List.of("ods_core.loan", "dwd_sum.loan_daily")));
        m.put("APPROVAL_RATE", new MetricCalcDtos.MetricDef("APPROVAL_RATE", "审批通过率", "%", "ORG/MONTH",
                "审批通过件数/申请进件件数*100", LATEST_VERSION, List.of("ods_credit.apply", "dwd_sum.apply_daily")));
        m.put("OVERDUE_RATE", new MetricCalcDtos.MetricDef("OVERDUE_RATE", "逾期率", "%", "ORG/MONTH",
                "SUM(逾期余额)/SUM(贷款余额)*100", LATEST_VERSION, List.of("ods_core.loan", "dwd_sum.repay_daily")));
        m.put("DEPOSIT_BALANCE", new MetricCalcDtos.MetricDef("DEPOSIT_BALANCE", "存款余额", "亿元", "ORG/MONTH",
                "SUM(deposit_balance)/100000000", LATEST_VERSION, List.of("ods_core.deposit", "dwd_sum.deposit_daily")));
        m.put("CAPITAL_ADEQUACY", new MetricCalcDtos.MetricDef("CAPITAL_ADEQUACY", "资本充足率", "%", "BANK/MONTH",
                "资本净额/风险加权资产*100", LATEST_VERSION, List.of("reg_rep.capital", "dwd_sum.rwa_daily")));
        m.put("COST_INCOME_RATIO", new MetricCalcDtos.MetricDef("COST_INCOME_RATIO", "成本收入比", "%", "BANK/MONTH",
                "业务及管理费/营业收入*100", LATEST_VERSION, List.of("ods_core.gl", "dwd_sum.income_monthly")));
        m.put("NIM", new MetricCalcDtos.MetricDef("NIM", "净息差", "%", "BANK/MONTH",
                "净利息收入/生息资产平均余额*100", LATEST_VERSION, List.of("ods_core.gl", "dwd_sum.interest_daily")));
        m.put("PROVISION_COVERAGE", new MetricCalcDtos.MetricDef("PROVISION_COVERAGE", "拨备覆盖率", "%", "BANK/MONTH",
                "贷款损失准备余额/不良贷款余额*100", LATEST_VERSION, List.of("ods_core.provision", "dwd_sum.loan_daily")));
        m.put("PROJECT_EVAL", new MetricCalcDtos.MetricDef("PROJECT_EVAL", "项目效益评价（IRR/DSCR/回收期/盈亏平衡）", "复合",
                "ORG/PROJECT", "IRR 二分迭代（无解置 0）；DSCR=经营现金流/当期本息合计；PAYBACK=初始投资/年均净现金流；BEP=年固定成本/(年收入-变动成本)*100",
                LATEST_VERSION, List.of("ods_fin.project", "dwd_fin.project_cashflow")));
        m.put("SOLVENCY_SET", new MetricCalcDtos.MetricDef("SOLVENCY_SET", "偿债能力指标组（资产负债率/流动比率）", "复合",
                "ORG/MONTH", "资产负债率=负债总额/资产总额*100；流动比率=流动资产/流动负债", LATEST_VERSION,
                List.of("dwd_fin.statement")));
        m.put("ASSET_QUALITY_SET", new MetricCalcDtos.MetricDef("ASSET_QUALITY_SET", "资产质量指标组（不良率/迁徙率/综合收益率）", "复合",
                "ORG/MONTH", "不良率=不良余额/贷款余额*100；迁徙率=迁徙金额/期初贷款余额*100；综合收益率=利息收入/平均资产*100",
                LATEST_VERSION, List.of("dwd_credit.loan_file", "dwd_risk.provision")));
        m.put("DRIFT_SET", new MetricCalcDtos.MetricDef("DRIFT_SET", "分布漂移指标组（PSI/KL/坏账率环比）", "复合",
                "MODEL/MONTH", "PSI=Σ(actual-expected)*ln(actual/expected)；KL=Σ actual*ln(actual/expected)；badRateMoM=badRateNow-badRatePrev（pct）",
                LATEST_VERSION, List.of("dwd_ai.training_set", "dwd_ai.score_dist")));
        m.put("FIELD_DIST", new MetricCalcDtos.MetricDef("FIELD_DIST", "字段质量问题分布", "复合", "ORG/DAY",
                "各字段问题数及问题率=字段问题数/total*100", LATEST_VERSION, List.of("dws_cb.report_data")));
        m.put("SCORE_DIST", new MetricCalcDtos.MetricDef("SCORE_DIST", "评分分布指标组（分桶占比/拒绝率）", "复合",
                "MODEL/MONTH", "各分桶数量及占比=桶数量/total*100；拒绝率=rejected/total*100", LATEST_VERSION,
                List.of("dwd_ai.score_dist")));
        return Map.copyOf(m);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String dayStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }
}
