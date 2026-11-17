package com.bai.finstmt;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 财务报表分析服务（BIA-CAL-065）。
 * <p>处理链：校验主体/报表/场景 → 勾稽校验 |totalAssets−(totalLiab+totalEquity)|>0.01 抛 FST_4101（返回差异）→
 * 四维指标（流动比率/资产负债率/毛利率/净利率/ROE/净现比，缺失科目按确定性仿真补齐、用于联调）→
 * 造假信号：净现比<0.5、应收激增（receivables/revenue>0.3）、存货异常（inventory/revenue>0.4）、
 * 货币资金与短期借款双高（短期借款未单列，以负债率>0.6 代理）——命中规则分累计，每条 25 分；
 * fraudScore = min(100, 0.7×ruleScore + 0.3×aiScore)（aiScore 仿真 30），<40 LOW / 40~70 MEDIUM / >70 HIGH，
 * HIGH 抛 FST_4103 阻断（不返回自动结论，告警提示转人工）→
 * 四流验证：taxRevenue 与 revenue 偏差 >±20% ABNORMAL / >±5% DEVIATION / 否则 CONSISTENT → 生成分析编号落审计。
 * <p>幂等：requestId 为键、分析要素哈希防冲突，同键返回首次结果（缓存 24h）；冲突抛 FST_1005。
 */
@Service
public class FinStmtService {

    private static final Logger log = LoggerFactory.getLogger(FinStmtService.class);

    private static final Set<String> STATEMENT_TYPES = Set.of("BALANCE", "INCOME", "CASHFLOW");
    private static final Set<String> SCENARIOS = Set.of("DUE_DILIGENCE", "REVIEW", "ANNUAL_CHECK", "LOAN_AFTER");
    /** 勾稽容差 */
    private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.01");
    /** 造假信号规则分：每命中一条 +25 */
    private static final BigDecimal RULE_SCORE_PER_HIT = new BigDecimal("25");
    /** AI 模型分（仿真，用于联调） */
    private static final BigDecimal AI_SCORE = new BigDecimal("30");
    /** 造假评分权重：0.7×规则分 + 0.3×AI 分 */
    private static final BigDecimal W_RULE = new BigDecimal("0.7");
    private static final BigDecimal W_AI = new BigDecimal("0.3");
    private static final BigDecimal FRAUD_LOW = new BigDecimal("40");
    private static final BigDecimal FRAUD_HIGH = new BigDecimal("70");

    /** 解析报表类型（设计文档 4.2：BALANCE_SHEET/INCOME_STATEMENT/CASH_FLOW） */
    private static final Set<String> PARSE_STATEMENT_TYPES = Set.of("BALANCE_SHEET", "INCOME_STATEMENT", "CASH_FLOW");
    /** 会计制度：ASBE/SMALL_ASBE（默认 ASBE） */
    private static final Set<String> STANDARDS = Set.of("ASBE", "SMALL_ASBE");
    /** 行业基准版本（基准库版本化，当前 V1.0 生效） */
    private static final Set<String> BENCHMARK_VERSIONS = Set.of("V1.0");
    /** 造假信号规则版本（十大信号规则 V1.0 起） */
    private static final Set<String> SIGNAL_RULE_VERSIONS = Set.of("V1.0");
    /** 解析质量阈值：accuracy<95% 抛 FST_4102 */
    private static final BigDecimal PARSE_QUALITY_THRESHOLD = new BigDecimal("0.95");
    /** 解析质量预警：accuracy<97% 提示人工复核 */
    private static final BigDecimal PARSE_QUALITY_WARN = new BigDecimal("0.97");
    /** 四流偏差：默认容差 5%，超 15% 判 ABNORMAL（设计文档 7.2） */
    private static final BigDecimal CROSS_DEFAULT_TOLERANCE = new BigDecimal("0.05");
    private static final BigDecimal CROSS_ABNORMAL_THRESHOLD = new BigDecimal("0.15");
    /** 趋势波动 ±30% 标记异常（贷后预警口径） */
    private static final BigDecimal TREND_ABNORMAL_RATE = new BigDecimal("0.30");
    /** 造假信号规则分：强 25 / 中 15 / 弱 10（信号强度分级） */
    private static final BigDecimal FRAUD_RULE_STRONG = new BigDecimal("25");
    private static final BigDecimal FRAUD_RULE_MEDIUM = new BigDecimal("15");
    private static final BigDecimal FRAUD_RULE_WEAK = new BigDecimal("10");

    /** 指标编码 → 名称（metric/trend 共用） */
    private static final Map<String, String> METRIC_NAMES = Map.ofEntries(
            Map.entry("CUR_RATIO", "流动比率"),
            Map.entry("QUICK_RATIO", "速动比率"),
            Map.entry("CASH_RATIO", "现金比率"),
            Map.entry("DEBT_RATIO", "资产负债率"),
            Map.entry("EQ_RATIO", "产权比率"),
            Map.entry("INTEREST_COVER", "利息保障倍数"),
            Map.entry("GROSS_MARGIN", "毛利率"),
            Map.entry("NET_MARGIN", "净利率"),
            Map.entry("ROE", "净资产收益率"),
            Map.entry("ROA", "总资产收益率"),
            Map.entry("EBITDA_MARGIN", "EBITDA 率"),
            Map.entry("ASSET_TURNOVER", "总资产周转率"),
            Map.entry("INVENTORY_TURNOVER", "存货周转率"),
            Map.entry("RECV_TURNOVER", "应收账款周转率"),
            Map.entry("PAY_TURNOVER", "应付账款周转率"),
            Map.entry("REV_GROWTH", "营业收入增长率"),
            Map.entry("PROFIT_GROWTH", "净利润增长率"),
            Map.entry("ASSET_GROWTH", "总资产增长率"),
            Map.entry("EQ_GROWTH", "净资产增长率"),
            Map.entry("OCF_RATIO", "净现比"));

    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MONEY_SCALE = 2;

    /** 分析编号序号（FST+yyyyMMdd+4 位序号） */
    private final AtomicInteger analysisSeq = new AtomicInteger();
    /** 业务流水号序号（PRS/RCL/MET/TRD/FRD/CRS+yyyyMMdd+4 位序号） */
    private final AtomicInteger noSeq = new AtomicInteger();
    private final IdempotencyStore idempotency = new IdempotencyStore();

    /** 报表分析（幂等：同 requestId 返回首次结果，缓存 24h） */
    public FinStmtDtos.AnalyzeResult analyze(FinStmtDtos.AnalyzeRequest req, String requestId) {
        validate(req);

        String hash = sha256(String.join("|",
                str(req.entityId()), str(req.period()), str(req.scenario()),
                String.valueOf(req.statements()), String.valueOf(req.externalEvidence())));
        String prevHash = idempotency.get("analyze-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(FinStmtErrorCodes.DUPLICATE_CONFLICT,
                        "FST_1005 重复提交/报表要素冲突，请使用原 requestId 查询首次分析结果");
            }
            return idempotency.get("analyze", requestId);
        }

        FinStmtDtos.AnalyzeResult result = compute(req);
        idempotency.put("analyze-hash", requestId, hash);
        idempotency.put("analyze", requestId, result);
        log.info("报表分析完成: requestId={}, entityId={}, period={}, fraudScore={}, fraudLevel={}, verification={}",
                requestId, req.entityId(), req.period(), result.fraudScore(), result.fraudLevel(), result.verification());
        return result;
    }

    // ------------------------------------------------------------ 报表解析（4.2）

    /** 三大报表解析：标准化科目 + 合计平衡校验 + 解析质量；幂等（同 requestId 返回首次结果，缓存 24h） */
    public FinStmtDtos.ParseResult parse(FinStmtDtos.ParseRequest req, String requestId) {
        validateParse(req);

        String hash = sha256(String.join("|", str(req.entityId()), str(req.statementType()), str(req.period()),
                str(req.standard()), String.valueOf(req.ocrResult()), String.valueOf(req.docRefs())));
        String prevHash = idempotency.get("parse-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(FinStmtErrorCodes.DUPLICATE_CONFLICT,
                        "FST_1005 重复提交/报表要素冲突，请使用原 requestId 查询首次解析结果");
            }
            return idempotency.get("parse", requestId);
        }

        FinStmtDtos.ParseResult result = computeParse(req);
        idempotency.put("parse-hash", requestId, hash);
        idempotency.put("parse", requestId, result);
        log.info("报表解析完成: requestId={}, entityId={}, statementType={}, period={}, statementId={}, parseNo={}, accuracy={}",
                requestId, req.entityId(), req.statementType(), req.period(), result.statementId(),
                result.parseNo(), result.quality().accuracy());
        return result;
    }

    private void validateParse(FinStmtDtos.ParseRequest req) {
        if (req == null || isBlank(req.entityId())) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：entityId 必填");
        }
        if (req.statementType() == null || !PARSE_STATEMENT_TYPES.contains(req.statementType())) {
            throw new BaiException(FinStmtErrorCodes.ELEMENT_ILLEGAL,
                    "FST_1007 报表类型非法：statementType 须为 BALANCE_SHEET/INCOME_STATEMENT/CASH_FLOW，当前 " + req.statementType());
        }
        if (isBlank(req.period())) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：period 必填");
        }
        if (req.standard() != null && !STANDARDS.contains(req.standard())) {
            throw new BaiException(FinStmtErrorCodes.ELEMENT_ILLEGAL,
                    "FST_1007 会计制度非法：standard 须为 ASBE/SMALL_ASBE，当前 " + req.standard());
        }
    }

    private FinStmtDtos.ParseResult computeParse(FinStmtDtos.ParseRequest req) {
        long seed = simSeed(str(req.entityId()) + "|" + req.statementType() + "|" + str(req.period()));
        // 金额单位识别：元/万元/千元统一转为元
        String unit = req.ocrResult() == null ? "元" : str(req.ocrResult().getOrDefault("unit", "元"));
        BigDecimal unitFactor = switch (unit) {
            case "万元" -> BigDecimal.valueOf(10_000);
            case "千元" -> BigDecimal.valueOf(1_000);
            default -> BigDecimal.ONE;
        };
        // ocrResult.items 优先取值，缺失科目按确定性仿真补齐（用于联调）
        Map<String, Object> ocrItems = new HashMap<>();
        if (req.ocrResult() != null && req.ocrResult().get("items") instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                ocrItems.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        // 解析质量：显式 accuracy 或确定性仿真 0.96~0.99；<95% 抛 FST_4102
        BigDecimal accuracy;
        Object acc = req.ocrResult() == null ? null : req.ocrResult().get("accuracy");
        if (acc != null) {
            try {
                accuracy = new BigDecimal(String.valueOf(acc).trim());
            } catch (NumberFormatException e) {
                accuracy = BigDecimal.valueOf(0.96);
            }
        } else {
            accuracy = BigDecimal.valueOf(0.96 + (seed % 4) / 100.0);
        }
        accuracy = accuracy.setScale(2, RoundingMode.HALF_UP);
        if (accuracy.compareTo(PARSE_QUALITY_THRESHOLD) < 0) {
            throw new BaiException(FinStmtErrorCodes.PARSE_QUALITY_LOW,
                    "FST_4102 解析质量过低（accuracy=" + accuracy + " <95%），请重新识别或人工补录");
        }

        List<FinStmtDtos.ParseItem> items = buildParseItems(req, ocrItems, unitFactor, seed);
        FinStmtDtos.BalanceCheck balanceCheck = buildBalanceCheck(items, req.statementType());

        List<String> warnings = new ArrayList<>();
        if (req.ocrResult() == null) {
            warnings.add("未提供 ocrResult，科目金额按确定性仿真补齐（用于联调）");
        }
        if (!"元".equals(unit)) {
            warnings.add("金额单位 " + unit + " 已统一换算为元");
        }
        if (accuracy.compareTo(PARSE_QUALITY_WARN) < 0) {
            warnings.add("识别置信度 " + accuracy + " 偏低，建议人工复核");
        }

        String statementId = "ST" + sha256(str(req.entityId()) + "|" + req.statementType() + "|" + str(req.period()))
                .substring(0, 8).toUpperCase();
        String parseNo = String.format("PRS%s%04d", LocalDate.now().format(NO_DATE), noSeq.incrementAndGet());
        return new FinStmtDtos.ParseResult("SUCCESS", "三大报表解析完成", statementId, items, balanceCheck,
                new FinStmtDtos.ParseQuality(accuracy, unit, warnings), parseNo);
    }

    /** 标准科目组装：ocrItems 命中（code/中文名）取值并乘单位系数，否则确定性仿真 */
    private List<FinStmtDtos.ParseItem> buildParseItems(FinStmtDtos.ParseRequest req, Map<String, Object> ocrItems,
                                                        BigDecimal unitFactor, long seed) {
        List<FinStmtDtos.ParseItem> items = new ArrayList<>();
        String period = req.period();
        switch (req.statementType()) {
            case "BALANCE_SHEET" -> {
                BigDecimal ta = BigDecimal.valueOf(10_000_000L + seed % 5_000_000L);
                BigDecimal tl = ta.multiply(BigDecimal.valueOf(0.45 + (seed % 20) / 100.0));
                BigDecimal te = ta.subtract(tl);
                BigDecimal ca = ta.multiply(BigDecimal.valueOf(0.50 + (seed % 15) / 100.0));
                BigDecimal fa = ta.subtract(ca);
                addParseItem(items, ocrItems, unitFactor, "BS-A100", "货币资金",
                        ca.multiply(BigDecimal.valueOf(0.20 + (seed % 10) / 100.0)), period);
                addParseItem(items, ocrItems, unitFactor, "BS-A200", "应收账款",
                        ca.multiply(BigDecimal.valueOf(0.12 + (seed % 10) / 100.0)), period);
                addParseItem(items, ocrItems, unitFactor, "BS-A300", "存货",
                        ca.multiply(BigDecimal.valueOf(0.10 + (seed % 8) / 100.0)), period);
                addParseItem(items, ocrItems, unitFactor, "BS-A400", "流动资产合计", ca, period);
                addParseItem(items, ocrItems, unitFactor, "BS-A500", "固定资产", fa, period);
                addParseItem(items, ocrItems, unitFactor, "BS-A600", "资产总计", ta, period);
                addParseItem(items, ocrItems, unitFactor, "BS-L100", "短期借款",
                        tl.multiply(BigDecimal.valueOf(0.20 + (seed % 10) / 100.0)), period);
                addParseItem(items, ocrItems, unitFactor, "BS-L200", "应付账款",
                        tl.multiply(BigDecimal.valueOf(0.25 + (seed % 10) / 100.0)), period);
                addParseItem(items, ocrItems, unitFactor, "BS-L300", "流动负债合计",
                        tl.multiply(BigDecimal.valueOf(0.50 + (seed % 10) / 100.0)), period);
                addParseItem(items, ocrItems, unitFactor, "BS-L400", "负债合计", tl, period);
                addParseItem(items, ocrItems, unitFactor, "BS-E100", "实收资本",
                        te.multiply(BigDecimal.valueOf(0.70)), period);
                addParseItem(items, ocrItems, unitFactor, "BS-E200", "未分配利润",
                        te.multiply(BigDecimal.valueOf(0.30)), period);
                addParseItem(items, ocrItems, unitFactor, "BS-E300", "所有者权益合计", te, period);
            }
            case "INCOME_STATEMENT" -> {
                BigDecimal revenue = BigDecimal.valueOf(10_000_000L + seed % 5_000_000L);
                BigDecimal cost = revenue.multiply(BigDecimal.valueOf(0.62 + (seed % 15) / 100.0));
                addParseItem(items, ocrItems, unitFactor, "IS-R100", "营业收入", revenue, period);
                addParseItem(items, ocrItems, unitFactor, "IS-R200", "营业成本", cost, period);
                addParseItem(items, ocrItems, unitFactor, "IS-R300", "毛利", revenue.subtract(cost), period);
                addParseItem(items, ocrItems, unitFactor, "IS-P100", "净利润",
                        revenue.multiply(BigDecimal.valueOf(0.04 + (seed % 8) / 100.0)), period);
                addParseItem(items, ocrItems, unitFactor, "IS-P200", "利息费用",
                        revenue.multiply(BigDecimal.valueOf(0.015 + (seed % 3) / 100.0)), period);
            }
            default -> {
                BigDecimal net = BigDecimal.valueOf(1_000_000L + seed % 800_000L);
                BigDecimal ocf = net.multiply(BigDecimal.valueOf(0.6 + (seed % 60) / 100.0));
                BigDecimal icf = net.multiply(BigDecimal.valueOf(-0.2 - (seed % 20) / 100.0));
                BigDecimal fcf = net.multiply(BigDecimal.valueOf(0.1 + (seed % 15) / 100.0));
                BigDecimal ncf = ocf.add(icf).add(fcf);
                addParseItem(items, ocrItems, unitFactor, "CF-O100", "经营活动现金流量净额", ocf, period);
                addParseItem(items, ocrItems, unitFactor, "CF-I100", "投资活动现金流量净额", icf, period);
                addParseItem(items, ocrItems, unitFactor, "CF-F100", "筹资活动现金流量净额", fcf, period);
                addParseItem(items, ocrItems, unitFactor, "CF-N100", "现金及现金等价物净增加额", ncf, period);
                addParseItem(items, ocrItems, unitFactor, "CF-B100", "期初现金及现金等价物",
                        BigDecimal.valueOf(500_000L + seed % 300_000L), period);
                addParseItem(items, ocrItems, unitFactor, "CF-E100", "期末现金及现金等价物",
                        BigDecimal.valueOf(500_000L + seed % 300_000L).add(ncf), period);
            }
        }
        return items;
    }

    private static void addParseItem(List<FinStmtDtos.ParseItem> items, Map<String, Object> ocrItems,
                                     BigDecimal unitFactor, String code, String name, BigDecimal simulated, String period) {
        BigDecimal amount = simulated;
        if (ocrItems != null) {
            Object v = ocrItems.get(code);
            if (v == null) {
                v = ocrItems.get(name);
            }
            if (v != null) {
                try {
                    amount = new BigDecimal(String.valueOf(v).trim()).multiply(unitFactor);
                } catch (NumberFormatException ignored) {
                    // 非法值回退确定性仿真
                }
            }
        }
        items.add(new FinStmtDtos.ParseItem(code, name, amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP), period));
    }

    /** 合计平衡校验：资产负债表 资产=负债+权益；利润表 毛利=收入−成本；现金流量表 净增加额=经营+投资+筹资 */
    private static FinStmtDtos.BalanceCheck buildBalanceCheck(List<FinStmtDtos.ParseItem> items, String statementType) {
        BigDecimal diff;
        switch (statementType) {
            case "BALANCE_SHEET" -> diff = amountOf(items, "BS-A600").subtract(
                    amountOf(items, "BS-L400").add(amountOf(items, "BS-E300")));
            case "INCOME_STATEMENT" -> diff = amountOf(items, "IS-R300").subtract(
                    amountOf(items, "IS-R100").subtract(amountOf(items, "IS-R200")));
            default -> diff = amountOf(items, "CF-N100").subtract(
                    amountOf(items, "CF-O100").add(amountOf(items, "CF-I100")).add(amountOf(items, "CF-F100")));
        }
        diff = diff.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new FinStmtDtos.BalanceCheck(diff.abs().compareTo(BALANCE_TOLERANCE) <= 0, diff);
    }

    private static BigDecimal amountOf(List<FinStmtDtos.ParseItem> items, String code) {
        for (FinStmtDtos.ParseItem it : items) {
            if (code.equals(it.code())) {
                return it.amount();
            }
        }
        return BigDecimal.ZERO;
    }

    // ------------------------------------------------------------ 勾稽校验（4.3）

    /** 勾稽关系校验：恒等式/留存收益/现金流/货币资金四类检查项，overall=PASS/WARN/FAIL；幂等 */
    public FinStmtDtos.ReconcileResult reconcile(FinStmtDtos.ReconcileRequest req, String requestId) {
        if (req == null) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：请求体必填");
        }
        requireEntity(req.entityId());
        requireStatements(req.statements());
        if (req.periods() == null || req.periods().isEmpty()) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：periods 期次列表必填");
        }

        String hash = sha256(String.join("|", str(req.entityId()), String.valueOf(req.statements()),
                String.valueOf(req.periods())));
        String prevHash = idempotency.get("reconcile-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(FinStmtErrorCodes.DUPLICATE_CONFLICT,
                        "FST_1005 重复提交/报表要素冲突，请使用原 requestId 查询首次校验结果");
            }
            return idempotency.get("reconcile", requestId);
        }

        FinStmtDtos.ReconcileResult result = computeReconcile(req);
        idempotency.put("reconcile-hash", requestId, hash);
        idempotency.put("reconcile", requestId, result);
        log.info("勾稽校验完成: requestId={}, entityId={}, overall={}, checks={}, reconcileNo={}",
                requestId, req.entityId(), result.overall(), result.checks().size(), result.reconcileNo());
        return result;
    }

    private FinStmtDtos.ReconcileResult computeReconcile(FinStmtDtos.ReconcileRequest req) {
        Map<String, Object> items = mergeItems(req.statements());
        long seed = simSeed(str(req.entityId()) + "|" + String.valueOf(req.statements()));
        BigDecimal totalAssets = num(items, "totalAssets");
        BigDecimal totalLiab = num(items, "totalLiab");
        BigDecimal totalEquity = num(items, "totalEquity");

        List<FinStmtDtos.ReconcileCheck> checks = new ArrayList<>();
        // RC-01 恒等式：资产=负债+所有者权益（容差 0.01 元）
        BigDecimal diff1 = totalAssets.subtract(totalLiab.add(totalEquity)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        checks.add(new FinStmtDtos.ReconcileCheck("RC-01", "资产=负债+所有者权益（恒等式）",
                diff1.abs().compareTo(BALANCE_TOLERANCE) > 0 ? "FAIL" : "PASS", diff1,
                diff1.abs().compareTo(BALANCE_TOLERANCE) > 0 ? "差异超容差 0.01 元，需核对" : "差异在容差 0.01 元内"));
        // RC-02 留存收益勾稽：期末留存=期初留存+本期净利润−本期分配
        BigDecimal retained = optNum(items, "retainedEarnings");
        BigDecimal netProfit = optNum(items, "netProfit");
        if (retained == null || netProfit == null) {
            BigDecimal simRetained = totalEquity.multiply(BigDecimal.valueOf(0.20 + (seed % 20) / 100.0));
            BigDecimal simProfit = totalAssets.multiply(BigDecimal.valueOf(0.04 + (seed % 4) / 100.0));
            BigDecimal diff2 = simRetained.subtract(simRetained.multiply(BigDecimal.valueOf(0.80))
                    .add(simProfit).subtract(simProfit.multiply(BigDecimal.valueOf(0.30))))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            checks.add(new FinStmtDtos.ReconcileCheck("RC-02", "留存收益勾稽",
                    diff2.abs().compareTo(BALANCE_TOLERANCE) > 0 ? "FAIL" : "PASS", diff2,
                    "期初留存/本期分配科目缺失，按确定性仿真补齐（用于联调）"));
        } else {
            BigDecimal prevRetained = optNum(items, "prevRetainedEarnings");
            if (prevRetained == null) {
                prevRetained = retained.subtract(netProfit); // 仿真：本期无分配
            }
            BigDecimal dividends = orZero(optNum(items, "dividends"));
            BigDecimal diff2 = retained.subtract(prevRetained.add(netProfit).subtract(dividends))
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            checks.add(new FinStmtDtos.ReconcileCheck("RC-02", "留存收益勾稽",
                    diff2.abs().compareTo(BALANCE_TOLERANCE) > 0 ? "FAIL" : "PASS", diff2,
                    diff2.abs().compareTo(BALANCE_TOLERANCE) > 0 ? "期末留存≠期初留存+净利润−分配" : "留存收益勾稽一致"));
        }
        // RC-03 现金流勾稽：期末现金−期初现金=现金净增加额
        BigDecimal cashBegin = optNum(items, "cashBegin");
        BigDecimal cashEnd = optNum(items, "cashEnd");
        BigDecimal cashNet = optNum(items, "cashNetIncrease");
        if (cashBegin == null || cashEnd == null || cashNet == null) {
            BigDecimal simNet = totalAssets.multiply(BigDecimal.valueOf(0.02 + (seed % 5) / 100.0));
            checks.add(new FinStmtDtos.ReconcileCheck("RC-03", "现金流量表勾稽（期末−期初=净增加额）",
                    "WARN", simNet, "现金科目缺失，按确定性仿真补齐（用于联调）"));
        } else {
            BigDecimal diff3 = cashEnd.subtract(cashBegin).subtract(cashNet).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            checks.add(new FinStmtDtos.ReconcileCheck("RC-03", "现金流量表勾稽（期末−期初=净增加额）",
                    diff3.abs().compareTo(BALANCE_TOLERANCE) > 0 ? "FAIL" : "PASS", diff3,
                    diff3.abs().compareTo(BALANCE_TOLERANCE) > 0 ? "净增加额与期初期末差额不一致" : "现金流勾稽一致"));
        }
        // RC-04 货币资金一致：资产负债表货币资金 = 现金流量表期末现金及等价物
        BigDecimal monetaryFund = optNum(items, "monetaryFund");
        if (monetaryFund == null || cashEnd == null) {
            BigDecimal simMf = totalAssets.multiply(BigDecimal.valueOf(0.08 + (seed % 10) / 100.0));
            checks.add(new FinStmtDtos.ReconcileCheck("RC-04", "货币资金一致（货币资金=期末现金及等价物）",
                    "WARN", simMf, "货币资金科目缺失，按确定性仿真补齐（用于联调）"));
        } else {
            BigDecimal diff4 = monetaryFund.subtract(cashEnd).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            checks.add(new FinStmtDtos.ReconcileCheck("RC-04", "货币资金一致（货币资金=期末现金及等价物）",
                    diff4.abs().compareTo(BALANCE_TOLERANCE) > 0 ? "FAIL" : "PASS", diff4,
                    diff4.abs().compareTo(BALANCE_TOLERANCE) > 0 ? "两表货币资金口径不一致" : "货币资金口径一致"));
        }

        String overall = checks.stream().anyMatch(c -> "FAIL".equals(c.conclusion())) ? "FAIL"
                : checks.stream().anyMatch(c -> "WARN".equals(c.conclusion())) ? "WARN" : "PASS";
        String reconcileNo = String.format("RCL%s%04d", LocalDate.now().format(NO_DATE), noSeq.incrementAndGet());
        return new FinStmtDtos.ReconcileResult(checks, overall, reconcileNo);
    }

    // ------------------------------------------------------------ 指标测算（4.4）

    /** 财务指标测算：四维 20 项指标（偿债/盈利/营运/成长）+ 异常标记；幂等 */
    public FinStmtDtos.MetricResult metric(FinStmtDtos.MetricRequest req, String requestId) {
        if (req == null) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：请求体必填");
        }
        requireEntity(req.entityId());
        requireStatements(req.statements());
        if (req.benchmarkVersion() != null && !BENCHMARK_VERSIONS.contains(req.benchmarkVersion())) {
            throw new BaiException(FinStmtErrorCodes.BENCHMARK_NOT_FOUND,
                    "FST_4104 基准版本不存在：benchmarkVersion 须为 " + BENCHMARK_VERSIONS + "，当前 " + req.benchmarkVersion());
        }

        String hash = sha256(String.join("|", str(req.entityId()), String.valueOf(req.statements()),
                str(req.industryCode()), str(req.benchmarkVersion())));
        String prevHash = idempotency.get("metric-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(FinStmtErrorCodes.DUPLICATE_CONFLICT,
                        "FST_1005 重复提交/报表要素冲突，请使用原 requestId 查询首次测算结果");
            }
            return idempotency.get("metric", requestId);
        }

        FinStmtDtos.MetricResult result = computeMetric(req);
        idempotency.put("metric-hash", requestId, hash);
        idempotency.put("metric", requestId, result);
        log.info("指标测算完成: requestId={}, entityId={}, metrics={}, abnormals={}, metricNo={}",
                requestId, req.entityId(), result.metrics().size(), result.abnormals().size(), result.metricNo());
        return result;
    }

    private FinStmtDtos.MetricResult computeMetric(FinStmtDtos.MetricRequest req) {
        Map<String, Object> items = mergeItems(req.statements());
        long seed = simSeed(str(req.entityId()) + "|" + String.valueOf(req.statements()) + "|" + str(req.industryCode()));
        BaseItems b = baseItems(items, seed);

        List<FinStmtDtos.Metric> metrics = new ArrayList<>();
        // 偿债能力
        addMetric(metrics, "CUR_RATIO", "流动比率", ratio(b.currentAssets, b.currentLiab), new BigDecimal("2.00"), false, seed);
        addMetric(metrics, "QUICK_RATIO", "速动比率", ratio(b.currentAssets.subtract(b.inventory), b.currentLiab),
                new BigDecimal("1.00"), false, seed);
        addMetric(metrics, "CASH_RATIO", "现金比率", ratio(b.monetaryFund, b.currentLiab), new BigDecimal("0.50"), false, seed);
        addMetric(metrics, "DEBT_RATIO", "资产负债率", ratio(b.totalLiab, b.totalAssets), new BigDecimal("0.60"), true, seed);
        addMetric(metrics, "EQ_RATIO", "产权比率", ratio(b.totalLiab, b.totalEquity), new BigDecimal("1.50"), true, seed);
        addMetric(metrics, "INTEREST_COVER", "利息保障倍数", ratio(b.netProfit.add(b.interestExpense), b.interestExpense),
                new BigDecimal("2.00"), false, seed);
        // 盈利能力
        addMetric(metrics, "GROSS_MARGIN", "毛利率", ratio(b.grossProfit, b.revenue), new BigDecimal("0.30"), false, seed);
        addMetric(metrics, "NET_MARGIN", "净利率", ratio(b.netProfit, b.revenue), new BigDecimal("0.10"), false, seed);
        addMetric(metrics, "ROE", "净资产收益率", ratio(b.netProfit, b.totalEquity), new BigDecimal("0.10"), false, seed);
        addMetric(metrics, "ROA", "总资产收益率", ratio(b.netProfit, b.totalAssets), new BigDecimal("0.05"), false, seed);
        addMetric(metrics, "EBITDA_MARGIN", "EBITDA 率", ratio(b.netProfit.add(b.interestExpense), b.revenue),
                new BigDecimal("0.12"), false, seed);
        // 营运能力（平均余额以期末值确定性仿真代替，用于联调）
        addMetric(metrics, "ASSET_TURNOVER", "总资产周转率", ratio(b.revenue, b.totalAssets), new BigDecimal("0.80"), false, seed);
        addMetric(metrics, "INVENTORY_TURNOVER", "存货周转率", ratio(b.costOfSales, b.inventory), new BigDecimal("3.00"), false, seed);
        addMetric(metrics, "RECV_TURNOVER", "应收账款周转率", ratio(b.revenue, b.receivables), new BigDecimal("4.00"), false, seed);
        addMetric(metrics, "PAY_TURNOVER", "应付账款周转率", ratio(b.costOfSales, b.payables), new BigDecimal("3.00"), false, seed);
        // 成长能力（同比增速确定性仿真 −10%~+30%，用于联调）
        BigDecimal growth = BigDecimal.valueOf((seed % 41) / 100.0 - 0.10);
        addMetric(metrics, "REV_GROWTH", "营业收入增长率", growth, new BigDecimal("0.10"), false, seed);
        addMetric(metrics, "PROFIT_GROWTH", "净利润增长率", growth, new BigDecimal("0.10"), false, seed);
        addMetric(metrics, "ASSET_GROWTH", "总资产增长率", growth, new BigDecimal("0.10"), false, seed);
        addMetric(metrics, "EQ_GROWTH", "净资产增长率", growth, new BigDecimal("0.10"), false, seed);

        List<FinStmtDtos.Abnormal> abnormals = new ArrayList<>();
        for (FinStmtDtos.Metric m : metrics) {
            if ("劣于基准".equals(m.status())) {
                abnormals.add(new FinStmtDtos.Abnormal(m.code(), "低于行业基准，标记异常（确定性仿真）"));
            }
        }
        String metricNo = String.format("MET%s%04d", LocalDate.now().format(NO_DATE), noSeq.incrementAndGet());
        return new FinStmtDtos.MetricResult(metrics, abnormals, metricNo);
    }

    /** 指标测算基础科目：必填恒等式三科目，其余缺失按确定性仿真补齐（用于联调） */
    private record BaseItems(BigDecimal totalAssets, BigDecimal totalLiab, BigDecimal totalEquity, BigDecimal revenue,
                             BigDecimal netProfit, BigDecimal operatingCashFlow, BigDecimal currentAssets, BigDecimal currentLiab,
                             BigDecimal grossProfit, BigDecimal costOfSales, BigDecimal receivables, BigDecimal inventory,
                             BigDecimal monetaryFund, BigDecimal payables, BigDecimal interestExpense,
                             BigDecimal relatedPartyRevenue, BigDecimal otherReceivables, BigDecimal prepayments,
                             BigDecimal constructionInProgress, BigDecimal goodwill, BigDecimal intangibleAssets, String auditOpinion) {}

    private static BaseItems baseItems(Map<String, Object> items, long seed) {
        BigDecimal totalAssets = num(items, "totalAssets");
        BigDecimal totalLiab = num(items, "totalLiab");
        BigDecimal totalEquity = num(items, "totalEquity");
        BigDecimal revenue = optNum(items, "revenue");
        if (revenue == null) {
            revenue = BigDecimal.valueOf(10_000_000L + seed % 5_000_000L);
        }
        BigDecimal netProfit = optNum(items, "netProfit");
        if (netProfit == null) {
            netProfit = revenue.multiply(BigDecimal.valueOf(0.05 + (seed % 10) / 100.0));
        }
        BigDecimal operatingCashFlow = optNum(items, "operatingCashFlow");
        if (operatingCashFlow == null) {
            operatingCashFlow = netProfit.multiply(BigDecimal.valueOf(0.30 + (seed % 70) / 100.0));
        }
        BigDecimal currentAssets = optNum(items, "currentAssets");
        if (currentAssets == null) {
            currentAssets = totalAssets.multiply(BigDecimal.valueOf(0.35 + (seed % 30) / 100.0));
        }
        BigDecimal currentLiab = optNum(items, "currentLiab");
        if (currentLiab == null) {
            currentLiab = totalLiab.multiply(BigDecimal.valueOf(0.45 + (seed % 25) / 100.0));
        }
        BigDecimal grossProfit = optNum(items, "grossProfit");
        if (grossProfit == null) {
            grossProfit = revenue.multiply(BigDecimal.valueOf(0.15 + (seed % 20) / 100.0));
        }
        BigDecimal costOfSales = optNum(items, "costOfSales");
        if (costOfSales == null) {
            costOfSales = revenue.subtract(grossProfit);
        }
        BigDecimal receivables = orZero(optNum(items, "receivables"));
        BigDecimal inventory = orZero(optNum(items, "inventory"));
        BigDecimal monetaryFund = orZero(optNum(items, "monetaryFund"));
        BigDecimal payables = orZero(optNum(items, "payables"));
        BigDecimal interestExpense = optNum(items, "interestExpense");
        if (interestExpense == null) {
            interestExpense = revenue.multiply(BigDecimal.valueOf(0.01 + (seed % 3) / 100.0));
        }
        BigDecimal relatedPartyRevenue = optNum(items, "relatedPartyRevenue");
        if (relatedPartyRevenue == null) {
            relatedPartyRevenue = revenue.multiply(BigDecimal.valueOf(0.05 + (seed % 30) / 100.0));
        }
        BigDecimal otherReceivables = orZero(optNum(items, "otherReceivables"));
        BigDecimal prepayments = orZero(optNum(items, "prepayments"));
        BigDecimal constructionInProgress = orZero(optNum(items, "constructionInProgress"));
        BigDecimal goodwill = orZero(optNum(items, "goodwill"));
        BigDecimal intangibleAssets = orZero(optNum(items, "intangibleAssets"));
        String auditOpinion = optString(items, "auditOpinion");
        return new BaseItems(totalAssets, totalLiab, totalEquity, revenue, netProfit, operatingCashFlow,
                currentAssets, currentLiab, grossProfit, costOfSales, receivables, inventory, monetaryFund,
                payables, interestExpense, relatedPartyRevenue, otherReceivables, prepayments,
                constructionInProgress, goodwill, intangibleAssets, auditOpinion);
    }

    // ------------------------------------------------------------ 趋势分析（4.5）

    /** 指标趋势分析：各期值 + 环比/同比 + 趋势判断，波动超 ±30% 标记异常；幂等 */
    public FinStmtDtos.TrendResult trend(FinStmtDtos.TrendRequest req, String requestId) {
        if (req == null) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：请求体必填");
        }
        requireEntity(req.entityId());
        if (req.metricCodes() == null || req.metricCodes().isEmpty()) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：metricCodes 指标清单必填");
        }
        for (String code : req.metricCodes()) {
            if (code == null || !METRIC_NAMES.containsKey(code)) {
                throw new BaiException(FinStmtErrorCodes.ELEMENT_ILLEGAL,
                        "FST_1007 指标编码非法：metricCodes 须为四维指标编码，当前 " + code);
            }
        }
        if (req.periods() == null || req.periods().size() < 2) {
            throw new BaiException(FinStmtErrorCodes.REPORT_DATA_INCOMPLETE,
                    "FST_4002 报表期次缺失：指标趋势分析至少需要两期数据");
        }

        String hash = sha256(String.join("|", str(req.entityId()), String.valueOf(req.metricCodes()),
                String.valueOf(req.periods()), str(req.industryCode())));
        String prevHash = idempotency.get("trend-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(FinStmtErrorCodes.DUPLICATE_CONFLICT,
                        "FST_1005 重复提交/报表要素冲突，请使用原 requestId 查询首次趋势结果");
            }
            return idempotency.get("trend", requestId);
        }

        FinStmtDtos.TrendResult result = computeTrend(req);
        idempotency.put("trend-hash", requestId, hash);
        idempotency.put("trend", requestId, result);
        log.info("趋势分析完成: requestId={}, entityId={}, metrics={}, abnormalTrends={}, trendNo={}",
                requestId, req.entityId(), result.trends().size(), result.abnormalTrends().size(), result.trendNo());
        return result;
    }

    private FinStmtDtos.TrendResult computeTrend(FinStmtDtos.TrendRequest req) {
        List<FinStmtDtos.Trend> trends = new ArrayList<>();
        List<String> abnormalTrends = new ArrayList<>();
        for (String code : req.metricCodes()) {
            List<FinStmtDtos.TrendPoint> series = new ArrayList<>();
            for (String period : req.periods()) {
                long pSeed = simSeed(str(req.entityId()) + "|" + code + "|" + period);
                BigDecimal value = BigDecimal.valueOf(0.5 + (pSeed % 100) / 50.0); // 指标值确定性仿真（用于联调）
                series.add(new FinStmtDtos.TrendPoint(period, value.setScale(MONEY_SCALE, RoundingMode.HALF_UP)));
            }
            BigDecimal prev = series.get(series.size() - 2).value();
            BigDecimal last = series.get(series.size() - 1).value();
            BigDecimal first = series.get(0).value();
            BigDecimal qoq = rate(last.subtract(prev), prev);
            BigDecimal yoy = rate(last.subtract(first), first);
            String status = qoq.compareTo(TREND_ABNORMAL_RATE) > 0 ? "上升"
                    : qoq.compareTo(TREND_ABNORMAL_RATE.negate()) < 0 ? "下降" : "平稳";
            trends.add(new FinStmtDtos.Trend(code, METRIC_NAMES.get(code), series, qoq, yoy, status));
            if (qoq.abs().compareTo(TREND_ABNORMAL_RATE) > 0) {
                abnormalTrends.add(code);
            }
        }
        String trendNo = String.format("TRD%s%04d", LocalDate.now().format(NO_DATE), noSeq.incrementAndGet());
        return new FinStmtDtos.TrendResult(trends, abnormalTrends, trendNo);
    }

    // ------------------------------------------------------------ 造假信号识别（4.6）

    /** 造假信号识别：十大信号规则命中累计分，fraudScore=0.7×规则分+0.3×AI 分（AI 仿真 30），
     * <p>fraudLevel=LOW/MEDIUM/HIGH（本接口按 4.6 契约返回 HIGH 结果；analyze 组合时才阻断 FST_4103）；幂等 */
    public FinStmtDtos.FraudResult fraud(FinStmtDtos.FraudRequest req, String requestId) {
        if (req == null) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：请求体必填");
        }
        requireEntity(req.entityId());
        requireStatements(req.statements());
        if (req.signalRuleVersion() != null && !SIGNAL_RULE_VERSIONS.contains(req.signalRuleVersion())) {
            throw new BaiException(FinStmtErrorCodes.ELEMENT_ILLEGAL,
                    "FST_1007 信号规则版本非法：signalRuleVersion 须为 V1.0，当前 " + req.signalRuleVersion());
        }

        String hash = sha256(String.join("|", str(req.entityId()), String.valueOf(req.statements()),
                String.valueOf(req.metrics()), String.valueOf(req.externalEvidence()), str(req.signalRuleVersion())));
        String prevHash = idempotency.get("fraud-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(FinStmtErrorCodes.DUPLICATE_CONFLICT,
                        "FST_1005 重复提交/报表要素冲突，请使用原 requestId 查询首次识别结果");
            }
            return idempotency.get("fraud", requestId);
        }

        FinStmtDtos.FraudResult result = computeFraud(req);
        idempotency.put("fraud-hash", requestId, hash);
        idempotency.put("fraud", requestId, result);
        log.info("造假信号识别完成: requestId={}, entityId={}, signals={}, fraudScore={}, fraudLevel={}, fraudNo={}",
                requestId, req.entityId(), result.signals().size(), result.fraudScore(), result.fraudLevel(), result.fraudNo());
        return result;
    }

    private FinStmtDtos.FraudResult computeFraud(FinStmtDtos.FraudRequest req) {
        Map<String, Object> items = mergeItems(req.statements());
        long seed = simSeed(str(req.entityId()) + "|" + String.valueOf(req.statements()));
        BaseItems b = baseItems(items, seed);

        List<FinStmtDtos.FraudSignal> signals = new ArrayList<>();
        BigDecimal ruleScore = BigDecimal.ZERO;
        BigDecimal ocfRatio = ratio(b.operatingCashFlow, b.netProfit.signum() > 0 ? b.netProfit : BigDecimal.ONE);
        // S01 收入与现金流长期背离：净现比<0.5（强）
        if (b.netProfit.signum() > 0 && ocfRatio.compareTo(new BigDecimal("0.5")) < 0) {
            ruleScore = ruleScore.add(FRAUD_RULE_STRONG);
            signals.add(new FinStmtDtos.FraudSignal("S01", "收入与现金流长期背离", "强",
                    "净现比 " + ocfRatio.setScale(2, RoundingMode.HALF_UP) + " <0.5，经营现金流与净利润背离",
                    "核实收入真实性与回款，必要时现场核查"));
        }
        // S02 应收账款异常激增：应收/收入>0.3（强）
        if (b.revenue.signum() > 0 && ratio(b.receivables, b.revenue).compareTo(new BigDecimal("0.3")) > 0) {
            ruleScore = ruleScore.add(FRAUD_RULE_STRONG);
            signals.add(new FinStmtDtos.FraudSignal("S02", "应收账款异常激增", "强",
                    "应收账款/营业收入 " + ratio(b.receivables, b.revenue).setScale(2, RoundingMode.HALF_UP) + " >0.3",
                    "核查赊销政策、账龄与回款流水"));
        }
        // S03 存货异常增长：存货/收入>0.4（中）
        if (b.revenue.signum() > 0 && ratio(b.inventory, b.revenue).compareTo(new BigDecimal("0.4")) > 0) {
            ruleScore = ruleScore.add(FRAUD_RULE_MEDIUM);
            signals.add(new FinStmtDtos.FraudSignal("S03", "存货异常增长", "中",
                    "存货/营业收入 " + ratio(b.inventory, b.revenue).setScale(2, RoundingMode.HALF_UP) + " >0.4",
                    "盘点存货并核实跌价准备与周转"));
        }
        // S04 毛利率行业背离：<10% 或 >60% 视为偏离行业 P50 ±30%（中，仿真）
        BigDecimal gm = ratio(b.grossProfit, b.revenue);
        if (gm.compareTo(new BigDecimal("0.10")) < 0 || gm.compareTo(new BigDecimal("0.60")) > 0) {
            ruleScore = ruleScore.add(FRAUD_RULE_MEDIUM);
            signals.add(new FinStmtDtos.FraudSignal("S04", "毛利率行业背离", "中",
                    "毛利率 " + gm.setScale(2, RoundingMode.HALF_UP) + " 偏离行业 P50 ±30%（仿真）",
                    "与行业基准对比并索取定价依据"));
        }
        // S05 关联交易占比过高：关联收入/收入>30%（中）
        if (b.revenue.signum() > 0 && ratio(b.relatedPartyRevenue, b.revenue).compareTo(new BigDecimal("0.3")) > 0) {
            ruleScore = ruleScore.add(FRAUD_RULE_MEDIUM);
            signals.add(new FinStmtDtos.FraudSignal("S05", "关联交易占比过高", "中",
                    "关联收入/营业收入 " + ratio(b.relatedPartyRevenue, b.revenue).setScale(2, RoundingMode.HALF_UP) + " >30%",
                    "穿透核查关联方与定价公允性"));
        }
        // S06 其他应收/预付异常：占总资产>10%（中）
        if (b.totalAssets.signum() > 0
                && ratio(b.otherReceivables.add(b.prepayments), b.totalAssets).compareTo(new BigDecimal("0.10")) > 0) {
            ruleScore = ruleScore.add(FRAUD_RULE_MEDIUM);
            signals.add(new FinStmtDtos.FraudSignal("S06", "其他应收/预付异常", "中",
                    "其他应收款/预付款占总资产 " + ratio(b.otherReceivables.add(b.prepayments), b.totalAssets)
                            .setScale(2, RoundingMode.HALF_UP) + " >10%",
                    "核实挂账对象与商业实质"));
        }
        // S07 资本化异常：在建工程/总资产>30% 长期挂账（强）
        if (b.totalAssets.signum() > 0
                && ratio(b.constructionInProgress, b.totalAssets).compareTo(new BigDecimal("0.30")) > 0) {
            ruleScore = ruleScore.add(FRAUD_RULE_STRONG);
            signals.add(new FinStmtDtos.FraudSignal("S07", "资本化异常", "强",
                    "在建工程/总资产 " + ratio(b.constructionInProgress, b.totalAssets).setScale(2, RoundingMode.HALF_UP)
                            + " >30%，长期挂账无转固",
                    "核对工程进度与现金流出匹配"));
        }
        // S08 商誉无形资产异常：占总资产>15%（弱）
        if (b.totalAssets.signum() > 0
                && ratio(b.goodwill.add(b.intangibleAssets), b.totalAssets).compareTo(new BigDecimal("0.15")) > 0) {
            ruleScore = ruleScore.add(FRAUD_RULE_WEAK);
            signals.add(new FinStmtDtos.FraudSignal("S08", "商誉无形资产异常", "弱",
                    "商誉/无形资产占总资产 " + ratio(b.goodwill.add(b.intangibleAssets), b.totalAssets)
                            .setScale(2, RoundingMode.HALF_UP) + " >15%",
                    "评估商誉/无形资产减值充分性"));
        }
        // S09 审计意见异常：非标准无保留意见/频繁更换审计师（强）
        if (!isBlank(b.auditOpinion) && !"UNQUALIFIED".equals(b.auditOpinion)) {
            ruleScore = ruleScore.add(FRAUD_RULE_STRONG);
            signals.add(new FinStmtDtos.FraudSignal("S09", "审计意见异常", "强",
                    "审计意见 " + b.auditOpinion + "（非标准无保留），或近 3 年更换审计师",
                    "索取审计报告并复核关键审计事项"));
        }
        // S10 报表勾稽不平：恒等式差异超容差（强）
        BigDecimal diff = b.totalAssets.subtract(b.totalLiab.add(b.totalEquity)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (diff.abs().compareTo(BALANCE_TOLERANCE) > 0) {
            ruleScore = ruleScore.add(FRAUD_RULE_STRONG);
            signals.add(new FinStmtDtos.FraudSignal("S10", "报表勾稽不平", "强",
                    "总资产 " + b.totalAssets + " ≠ 负债 " + b.totalLiab + " + 权益 " + b.totalEquity + "（差异 " + diff + "）",
                    "核对报表数据并查明差异原因"));
        }

        BigDecimal fraudScore = W_RULE.multiply(ruleScore).add(W_AI.multiply(AI_SCORE))
                .min(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
        String fraudLevel = fraudScore.compareTo(FRAUD_LOW) < 0 ? "LOW"
                : fraudScore.compareTo(FRAUD_HIGH) <= 0 ? "MEDIUM" : "HIGH";
        String fraudNo = String.format("FRD%s%04d", LocalDate.now().format(NO_DATE), noSeq.incrementAndGet());
        return new FinStmtDtos.FraudResult(signals, fraudScore, fraudLevel, fraudNo);
    }

    // ------------------------------------------------------------ 四流合一交叉验证（4.7）

    /** 四流合一交叉验证：主体一致 + 金额偏差（≤容差 CONSISTENT/≤15% DEVIATION/>15% ABNORMAL）；幂等 */
    public FinStmtDtos.CrossResult cross(FinStmtDtos.CrossRequest req, String requestId) {
        if (req == null) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：请求体必填");
        }
        requireEntity(req.entityId());
        if (req.revenueAmount() == null || req.revenueAmount().signum() <= 0) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：revenueAmount 报表收入必填（>0）");
        }
        if (req.contractData() == null && req.logisticsData() == null && req.fundFlowData() == null && req.invoiceData() == null) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID,
                    "FST_1001 参数缺失：contractData/logisticsData/fundFlowData/invoiceData 至少提供一项");
        }
        if (req.tolerance() != null
                && (req.tolerance().signum() <= 0 || req.tolerance().compareTo(new BigDecimal("0.50")) > 0)) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID,
                    "FST_1001 参数非法：tolerance 须为 (0, 0.5] 的偏差阈值（默认 0.05）");
        }

        String hash = sha256(String.join("|", str(req.entityId()), String.valueOf(req.contractData()),
                String.valueOf(req.logisticsData()), String.valueOf(req.fundFlowData()), String.valueOf(req.invoiceData()),
                str(req.revenueAmount()), str(req.tolerance())));
        String prevHash = idempotency.get("cross-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(FinStmtErrorCodes.DUPLICATE_CONFLICT,
                        "FST_1005 重复提交/报表要素冲突，请使用原 requestId 查询首次验证结果");
            }
            return idempotency.get("cross", requestId);
        }

        FinStmtDtos.CrossResult result = computeCross(req);
        idempotency.put("cross-hash", requestId, hash);
        idempotency.put("cross", requestId, result);
        log.info("四流交叉验证完成: requestId={}, entityId={}, verification={}, deviations={}, crossNo={}",
                requestId, req.entityId(), result.verification(), result.deviationDetails().size(), result.crossNo());
        return result;
    }

    private FinStmtDtos.CrossResult computeCross(FinStmtDtos.CrossRequest req) {
        BigDecimal revenue = req.revenueAmount();
        BigDecimal tolerance = req.tolerance() == null ? CROSS_DEFAULT_TOLERANCE : req.tolerance();
        List<FinStmtDtos.DeviationDetail> details = new ArrayList<>();
        List<String> chain = new ArrayList<>();
        boolean abnormal = false;
        boolean deviation = false;

        // 主体一致性：合同/发票/收付款/物流当事人一致
        Set<String> parties = new LinkedHashSet<>();
        for (FinStmtDtos.FlowData f : new FinStmtDtos.FlowData[]{req.contractData(), req.logisticsData(),
                req.fundFlowData(), req.invoiceData()}) {
            if (f != null && !isBlank(f.counterparty())) {
                parties.add(f.counterparty());
            }
        }
        if (parties.size() > 1) {
            abnormal = true;
            chain.add("主体不一致：四流当事人" + parties + "，需核实业务真实性");
        } else if (parties.size() == 1) {
            chain.add("主体一致：" + parties.iterator().next());
        }

        // 金额偏差：|四流金额 − 报表收入| / 报表收入 ≤ 容差（默认 5%）；5%~15% 偏差；>15% 异常
        FinStmtDtos.FlowData[] flows = {req.contractData(), req.logisticsData(), req.fundFlowData(), req.invoiceData()};
        String[] flowNames = {"合同流", "物流", "资金流", "发票流"};
        for (int i = 0; i < flows.length; i++) {
            FinStmtDtos.FlowData f = flows[i];
            if (f == null || f.amount() == null) {
                continue;
            }
            BigDecimal dev = f.amount().subtract(revenue).abs().divide(revenue, 6, RoundingMode.HALF_UP);
            String conclusion = dev.compareTo(tolerance) <= 0 ? "CONSISTENT"
                    : dev.compareTo(CROSS_ABNORMAL_THRESHOLD) <= 0 ? "DEVIATION" : "ABNORMAL";
            if ("ABNORMAL".equals(conclusion)) {
                abnormal = true;
            } else if ("DEVIATION".equals(conclusion)) {
                deviation = true;
            }
            String pct = dev.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
            details.add(new FinStmtDtos.DeviationDetail(flowNames[i],
                    f.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                    revenue.setScale(MONEY_SCALE, RoundingMode.HALF_UP), pct, conclusion));
            chain.add(flowNames[i] + (isBlank(f.refNo()) ? "" : " " + f.refNo())
                    + " 金额 " + f.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                    + " 与报表收入偏差 " + pct + "（结论 " + conclusion + "）");
        }

        String verification = abnormal ? "ABNORMAL" : deviation ? "DEVIATION" : "CONSISTENT";
        String crossNo = String.format("CRS%s%04d", LocalDate.now().format(NO_DATE), noSeq.incrementAndGet());
        return new FinStmtDtos.CrossResult(verification, details, chain, crossNo);
    }

    // ------------------------------------------------------------ 共享校验/工具

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void requireEntity(String entityId) {
        if (isBlank(entityId)) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：entityId 必填");
        }
    }

    /** statements 报表数组校验：非空且 type 为 BALANCE/INCOME/CASHFLOW */
    private static void requireStatements(List<FinStmtDtos.Statement> statements) {
        if (statements == null || statements.isEmpty()) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：statements 报表数组必填");
        }
        for (FinStmtDtos.Statement s : statements) {
            if (s == null || s.type() == null || !STATEMENT_TYPES.contains(s.type())) {
                throw new BaiException(FinStmtErrorCodes.ELEMENT_ILLEGAL,
                        "FST_1007 报表类型非法：type 须为 BALANCE/INCOME/CASHFLOW，当前 " + (s == null ? null : s.type()));
            }
        }
    }

    /** 合并各报表科目（后表覆盖同名科目） */
    private static Map<String, Object> mergeItems(List<FinStmtDtos.Statement> statements) {
        Map<String, Object> items = new LinkedHashMap<>();
        for (FinStmtDtos.Statement s : statements) {
            if (s != null && s.items() != null) {
                items.putAll(s.items());
            }
        }
        return items;
    }

    /** 增长率：delta / |base|（base 为 0 返回 0） */
    private static BigDecimal rate(BigDecimal delta, BigDecimal base) {
        if (base == null || base.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return delta.divide(base.abs(), 4, RoundingMode.HALF_UP);
    }

    private static String optString(Map<String, Object> items, String key) {
        Object v = items.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private void validate(FinStmtDtos.AnalyzeRequest req) {
        if (req == null || req.entityId() == null || req.entityId().isBlank()) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：entityId 必填");
        }
        if (req.statements() == null || req.statements().isEmpty()) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：statements 报表数组必填");
        }
        for (FinStmtDtos.Statement s : req.statements()) {
            if (s == null || s.type() == null || !STATEMENT_TYPES.contains(s.type())) {
                throw new BaiException(FinStmtErrorCodes.ELEMENT_ILLEGAL,
                        "FST_1007 报表类型非法：type 须为 BALANCE/INCOME/CASHFLOW，当前 " + (s == null ? null : s.type()));
            }
        }
        if (req.period() == null || req.period().isBlank()) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：period 必填");
        }
        if (req.scenario() == null || !SCENARIOS.contains(req.scenario())) {
            throw new BaiException(FinStmtErrorCodes.SCENARIO_ILLEGAL,
                    "FST_4001 分析场景非法：scenario 须为 DUE_DILIGENCE/REVIEW/ANNUAL_CHECK/LOAN_AFTER，当前 " + req.scenario());
        }
    }

    /** 勾稽 + 指标 + 造假信号 + 四流验证 */
    private FinStmtDtos.AnalyzeResult compute(FinStmtDtos.AnalyzeRequest req) {
        // 合并各报表科目（BALANCE/INCOME/CASHFLOW），后表覆盖同名科目
        Map<String, Object> items = new LinkedHashMap<>();
        Set<String> types = new HashSet<>();
        for (FinStmtDtos.Statement s : req.statements()) {
            types.add(s.type());
            if (s.items() != null) {
                items.putAll(s.items());
            }
        }
        if (!types.contains("BALANCE")) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID,
                    "FST_1001 参数缺失：statements 须包含 BALANCE 资产负债表");
        }
        BigDecimal totalAssets = num(items, "totalAssets");
        BigDecimal totalLiab = num(items, "totalLiab");
        BigDecimal totalEquity = num(items, "totalEquity");

        // 勾稽校验：|总资产 −（负债+权益）| > 0.01 → FST_4101（返回差异）
        BigDecimal diff = totalAssets.subtract(totalLiab.add(totalEquity)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (diff.abs().compareTo(BALANCE_TOLERANCE) > 0) {
            throw new BaiException(FinStmtErrorCodes.BALANCE_UNBALANCED,
                    "FST_4101 勾稽不平：总资产 " + totalAssets + " ≠ 负债 " + totalLiab + " + 权益 " + totalEquity
                            + "（差异 " + diff + "），请核对报表数据");
        }

        // 缺失科目确定性仿真补齐（用于联调，输入哈希决定取值）
        long seed = simSeed(str(req.entityId()) + "|" + str(req.period()));
        BigDecimal revenue = optNum(items, "revenue");
        if (revenue == null) {
            revenue = BigDecimal.valueOf(10_000_000L + seed % 5_000_000L); // 仿真，用于联调
        }
        BigDecimal netProfit = optNum(items, "netProfit");
        if (netProfit == null) {
            netProfit = revenue.multiply(BigDecimal.valueOf(0.05 + (seed % 10) / 100.0)); // 仿真，用于联调
        }
        BigDecimal operatingCashFlow = optNum(items, "operatingCashFlow");
        if (operatingCashFlow == null) {
            operatingCashFlow = netProfit.multiply(BigDecimal.valueOf(0.30 + (seed % 70) / 100.0)); // 仿真，用于联调
        }
        BigDecimal currentAssets = optNum(items, "currentAssets");
        if (currentAssets == null) {
            currentAssets = totalAssets.multiply(BigDecimal.valueOf(0.35 + (seed % 30) / 100.0)); // 仿真，用于联调
        }
        BigDecimal currentLiab = optNum(items, "currentLiab");
        if (currentLiab == null) {
            currentLiab = totalLiab.multiply(BigDecimal.valueOf(0.45 + (seed % 25) / 100.0)); // 仿真，用于联调
        }
        BigDecimal grossProfit = optNum(items, "grossProfit");
        if (grossProfit == null) {
            grossProfit = revenue.multiply(BigDecimal.valueOf(0.15 + (seed % 20) / 100.0)); // 仿真，用于联调
        }
        BigDecimal receivables = orZero(optNum(items, "receivables"));
        BigDecimal inventory = orZero(optNum(items, "inventory"));
        BigDecimal monetaryFund = orZero(optNum(items, "monetaryFund"));

        List<FinStmtDtos.Metric> metrics = new ArrayList<>();
        BigDecimal debtRatio = ratio(totalLiab, totalAssets);
        BigDecimal ocfRatio = ratio(operatingCashFlow, netProfit.signum() > 0 ? netProfit : BigDecimal.ONE);
        addMetric(metrics, "CUR_RATIO", "流动比率", ratio(currentAssets, currentLiab),
                new BigDecimal("2.00"), false, seed);
        addMetric(metrics, "DEBT_RATIO", "资产负债率", debtRatio, new BigDecimal("0.60"), true, seed);
        addMetric(metrics, "GROSS_MARGIN", "毛利率", ratio(grossProfit, revenue), new BigDecimal("0.30"), false, seed);
        addMetric(metrics, "NET_MARGIN", "净利率", ratio(netProfit, revenue), new BigDecimal("0.10"), false, seed);
        addMetric(metrics, "ROE", "净资产收益率", ratio(netProfit, totalEquity), new BigDecimal("0.10"), false, seed);
        addMetric(metrics, "OCF_RATIO", "净现比",
                netProfit.signum() > 0 ? ocfRatio : BigDecimal.ZERO, new BigDecimal("1.00"), false, seed);

        // 造假信号（规则分累计，每条 25 分）
        List<FinStmtDtos.Alert> alerts = new ArrayList<>();
        BigDecimal ruleScore = BigDecimal.ZERO;
        if (netProfit.signum() > 0 && ocfRatio.compareTo(new BigDecimal("0.5")) < 0) {
            ruleScore = ruleScore.add(RULE_SCORE_PER_HIT);
            alerts.add(new FinStmtDtos.Alert("NET_CASH_RATIO", "HIGH",
                    "净现比 " + ocfRatio.setScale(2, RoundingMode.HALF_UP) + " 低于 0.5，经营现金流与净利润背离，关注收入真实性"));
        }
        if (revenue.signum() > 0 && ratio(receivables, revenue).compareTo(new BigDecimal("0.3")) > 0) {
            ruleScore = ruleScore.add(RULE_SCORE_PER_HIT);
            alerts.add(new FinStmtDtos.Alert("RECEIVABLE_SPIKE", "MEDIUM",
                    "应收账款/营业收入 " + ratio(receivables, revenue).setScale(2, RoundingMode.HALF_UP)
                            + " 超过 0.3，应收激增，核实赊销政策与回款"));
        }
        if (revenue.signum() > 0 && ratio(inventory, revenue).compareTo(new BigDecimal("0.4")) > 0) {
            ruleScore = ruleScore.add(RULE_SCORE_PER_HIT);
            alerts.add(new FinStmtDtos.Alert("INVENTORY_ANOMALY", "MEDIUM",
                    "存货/营业收入 " + ratio(inventory, revenue).setScale(2, RoundingMode.HALF_UP)
                            + " 超过 0.4，存货异常，核实存货跌价与周转"));
        }
        if (totalAssets.signum() > 0
                && ratio(monetaryFund, totalAssets).compareTo(new BigDecimal("0.3")) > 0
                && debtRatio.compareTo(new BigDecimal("0.6")) > 0) {
            ruleScore = ruleScore.add(RULE_SCORE_PER_HIT);
            alerts.add(new FinStmtDtos.Alert("CASH_DEBT_BOTH_HIGH", "MEDIUM",
                    "货币资金与负债双高（货币资金/总资产 "
                            + ratio(monetaryFund, totalAssets).setScale(2, RoundingMode.HALF_UP)
                            + "，资产负债率 " + debtRatio.setScale(2, RoundingMode.HALF_UP) + "），核实资金受限与有息负债结构"));
        }
        // fraudScore = min(100, 0.7×ruleScore + 0.3×aiScore)（aiScore 仿真 30）
        BigDecimal fraudScore = W_RULE.multiply(ruleScore).add(W_AI.multiply(AI_SCORE))
                .min(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
        String fraudLevel = fraudScore.compareTo(FRAUD_LOW) < 0 ? "LOW"
                : fraudScore.compareTo(FRAUD_HIGH) <= 0 ? "MEDIUM" : "HIGH";
        if ("HIGH".equals(fraudLevel)) {
            // 阻断：不返回自动结论，告警提示转人工
            throw new BaiException(FinStmtErrorCodes.FRAUD_HIGH,
                    "FST_4103 造假信号 HIGH（fraudScore=" + fraudScore + "），系统阻断自动结论：命中 " + alerts.size()
                            + " 条高风险规则，请转人工审核（" + alertRules(alerts) + "）");
        }

        // 四流验证：taxRevenue 与 revenue 偏差（bankFlow 预留，MVP 仅对税票收入）
        String verification;
        BigDecimal taxRevenue = req.externalEvidence() == null ? null : req.externalEvidence().taxRevenue();
        if (taxRevenue == null || revenue.signum() <= 0) {
            verification = "PENDING"; // 外部税务证据未提供，四流验证待补充
        } else {
            BigDecimal taxDev = taxRevenue.subtract(revenue).abs().divide(revenue, 6, RoundingMode.HALF_UP);
            if (taxDev.compareTo(new BigDecimal("0.20")) > 0) {
                verification = "ABNORMAL";
                alerts.add(new FinStmtDtos.Alert("TAX_REVENUE_CHECK", "HIGH",
                        "税务收入与报表收入偏差 " + taxDev.multiply(BigDecimal.valueOf(100))
                                .setScale(2, RoundingMode.HALF_UP) + "%（>±20%），四流验证异常，转人工核实"));
            } else if (taxDev.compareTo(new BigDecimal("0.05")) > 0) {
                verification = "DEVIATION";
                alerts.add(new FinStmtDtos.Alert("TAX_REVENUE_CHECK", "MEDIUM",
                        "税务收入与报表收入偏差 " + taxDev.multiply(BigDecimal.valueOf(100))
                                .setScale(2, RoundingMode.HALF_UP) + "%（>±5%），建议补充说明"));
            } else {
                verification = "CONSISTENT";
            }
        }

        String analysisNo = String.format("FST%s%04d", LocalDate.now().format(NO_DATE), analysisSeq.incrementAndGet());
        return new FinStmtDtos.AnalyzeResult(analysisNo,
                new FinStmtDtos.Summary(
                        totalAssets.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                        totalLiab.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                        revenue.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                        netProfit.setScale(MONEY_SCALE, RoundingMode.HALF_UP)),
                metrics, fraudScore, fraudLevel, verification, alerts);
    }

    private static String alertRules(List<FinStmtDtos.Alert> alerts) {
        StringBuilder sb = new StringBuilder();
        for (FinStmtDtos.Alert a : alerts) {
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(a.rule());
        }
        return sb.toString();
    }

    /** 指标组装：percentile 按 seed 确定性仿真（P25/P50/P75），status 按基准方向判定 */
    private static void addMetric(List<FinStmtDtos.Metric> metrics, String code, String name, BigDecimal value,
                                  BigDecimal benchmark, boolean lowerBetter, long seed) {
        String status = lowerBetter
                ? (value.compareTo(benchmark) <= 0 ? "正常" : "劣于基准")
                : (value.compareTo(benchmark) >= 0 ? "正常" : "劣于基准");
        String percentile = switch ((int) (seed % 3)) { // 分位数确定性仿真（用于联调）
            case 0 -> "P25";
            case 1 -> "P50";
            default -> "P75";
        };
        metrics.add(new FinStmtDtos.Metric(code, name, value.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                benchmark, percentile, status));
    }

    private static BigDecimal ratio(BigDecimal a, BigDecimal b) {
        if (b == null || b.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return a.divide(b, 4, RoundingMode.HALF_UP);
    }

    /** 必填数值科目（支持数值与可解析字符串），缺失抛 FST_1001 */
    private static BigDecimal num(Map<String, Object> items, String key) {
        BigDecimal v = optNum(items, key);
        if (v == null) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：items." + key + " 必填");
        }
        return v;
    }

    private static BigDecimal optNum(Map<String, Object> items, String key) {
        Object v = items.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID,
                    "FST_1001 参数非法：items." + key + " 须为数值");
        }
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 仿真种子：取输入 SHA-256 前 8 位十六进制，确定性 */
    private static long simSeed(String s) {
        return Long.parseLong(sha256(s).substring(0, 8), 16);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(s.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
