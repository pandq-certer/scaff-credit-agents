package com.bai.finstmt;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 财务报表分析输入/输出结构（BIA-CAL-065：POST /api/bai/finstmt/analyze 字段表逐条对齐）
 */
public final class FinStmtDtos {

    private FinStmtDtos() {}

    /** 报表：type(BALANCE|INCOME|CASHFLOW) + items 科目（totalAssets/totalLiab/totalEquity/revenue/netProfit/operatingCashFlow/inventory/receivables/monetaryFund/retainedEarnings 等） */
    public record Statement(String type, Map<String, Object> items) {}

    /** 分析入参：entityId/statements/period/scenario(DUE_DILIGENCE|REVIEW|ANNUAL_CHECK|LOAN_AFTER)/externalEvidence 可选 */
    public record AnalyzeRequest(String entityId, List<Statement> statements, String period, String scenario,
                                 ExternalEvidence externalEvidence, String requestId) {}

    /** 外部证据（四流验证）：taxRevenue 税票收入、bankFlow 银行流水（预留） */
    public record ExternalEvidence(BigDecimal taxRevenue, BigDecimal bankFlow) {}

    /** 报表摘要 */
    public record Summary(BigDecimal totalAssets, BigDecimal totalLiab, BigDecimal revenue, BigDecimal netProfit) {}

    /** 指标：code/name/value(2 位)/benchmark/percentile(P25|P50|P75 确定性仿真)/status(正常|劣于基准) */
    public record Metric(String code, String name, BigDecimal value, BigDecimal benchmark, String percentile, String status) {}

    /** 告警：规则/级别/处置建议 */
    public record Alert(String rule, String level, String advice) {}

    /** 分析结果：造假评分 = 0.7×ruleScore + 0.3×aiScore（aiScore 仿真 30），HIGH 阻断不返回 */
    public record AnalyzeResult(String analysisNo, Summary summary, List<Metric> metrics, BigDecimal fraudScore,
                                String fraudLevel, String verification, List<Alert> alerts) {}

    // -------------------------------------------------------------- 报表解析（4.2）

    /** 报表解析入参：statementType=BALANCE_SHEET/INCOME_STATEMENT/CASH_FLOW；standard=ASBE/SMALL_ASBE（默认 ASBE）；ocrResult 条件必填 */
    public record ParseRequest(String entityId, String statementType, String period, String standard,
                               Map<String, Object> ocrResult, List<String> docRefs, String requestId) {}

    /** 标准科目明细：code（BS-A100/IS-R100/CF-O100 等）/name/amount（统一转元）/period */
    public record ParseItem(String code, String name, BigDecimal amount, String period) {}

    /** 合计平衡校验：pass + difference（资产负债表 资产−负债−权益，容差 0.01 元） */
    public record BalanceCheck(boolean pass, BigDecimal difference) {}

    /** 解析质量：accuracy（<95% 抛 FST_4102）/unit（原始金额单位）/warnings */
    public record ParseQuality(BigDecimal accuracy, String unit, List<String> warnings) {}

    /** 报表解析结果：code=SUCCESS、statementId、items 科目明细、balanceCheck、quality、parseNo 流水号 */
    public record ParseResult(String code, String message, String statementId, List<ParseItem> items,
                              BalanceCheck balanceCheck, ParseQuality quality, String parseNo) {}

    // -------------------------------------------------------------- 勾稽校验（4.3）

    /** 勾稽校验入参：statements 三大报表（type+items 科目明细，与 analyze 一致）、periods 期次列表 */
    public record ReconcileRequest(String entityId, List<Statement> statements, List<String> periods, String requestId) {}

    /** 勾稽检查项：conclusion=PASS/WARN/FAIL、difference 差异额、reason 原因 */
    public record ReconcileCheck(String checkNo, String name, String conclusion, BigDecimal difference, String reason) {}

    /** 勾稽校验结果：overall=PASS/WARN/FAIL、reconcileNo 流水号 */
    public record ReconcileResult(List<ReconcileCheck> checks, String overall, String reconcileNo) {}

    // -------------------------------------------------------------- 指标测算（4.4）

    /** 指标测算入参：industryCode 行业分类、benchmarkVersion 基准版本（须 V1.0） */
    public record MetricRequest(String entityId, List<Statement> statements, String industryCode,
                                String benchmarkVersion, String requestId) {}

    /** 异常指标：低于基准标记 */
    public record Abnormal(String metricCode, String reason) {}

    /** 指标测算结果：metrics 四维指标（偿债/盈利/营运/成长）、abnormals、metricNo 流水号 */
    public record MetricResult(List<Metric> metrics, List<Abnormal> abnormals, String metricNo) {}

    // -------------------------------------------------------------- 趋势分析（4.5）

    /** 趋势分析入参：metricCodes 指标清单、periods 期次（至少两期） */
    public record TrendRequest(String entityId, List<String> metricCodes, List<String> periods,
                               String industryCode, String requestId) {}

    /** 各期指标值 */
    public record TrendPoint(String period, BigDecimal value) {}

    /** 指标趋势：series 各期值、qoq 环比、yoy 同比（4 位小数）、status=上升/下降/平稳 */
    public record Trend(String metricCode, String metricName, List<TrendPoint> series,
                        BigDecimal qoq, BigDecimal yoy, String status) {}

    /** 趋势分析结果：abnormalTrends 波动超 ±30% 的指标、trendNo 流水号 */
    public record TrendResult(List<Trend> trends, List<String> abnormalTrends, String trendNo) {}

    // -------------------------------------------------------------- 造假信号识别（4.6）

    /** 造假识别入参：metrics 预计算指标可选（仿真阶段以报表科目重算为准）、externalEvidence 外部佐证、signalRuleVersion=V1.0 */
    public record FraudRequest(String entityId, List<Statement> statements, List<Metric> metrics,
                               ExternalEvidence externalEvidence, String signalRuleVersion, String requestId) {}

    /** 造假信号：strength=强/中/弱、evidence 命中证据、advice 处置建议 */
    public record FraudSignal(String signalNo, String name, String strength, String evidence, String advice) {}

    /** 造假识别结果：fraudScore 0~100（0.7×规则分+0.3×AI 分，AI 仿真 30）、fraudLevel=LOW/MEDIUM/HIGH、fraudNo 流水号 */
    public record FraudResult(List<FraudSignal> signals, BigDecimal fraudScore, String fraudLevel, String fraudNo) {}

    // -------------------------------------------------------------- 四流合一交叉验证（4.7）

    /** 四流数据：amount 金额、refNo 单号、period 期次、counterparty 对方主体 */
    public record FlowData(BigDecimal amount, String refNo, String period, String counterparty) {}

    /** 交叉验证入参：contractData 合同流/logisticsData 物流/fundFlowData 资金流/invoiceData 发票流、
     * revenueAmount 报表收入、tolerance 偏差阈值默认 5% */
    public record CrossRequest(String entityId, FlowData contractData, FlowData logisticsData, FlowData fundFlowData,
                               FlowData invoiceData, BigDecimal revenueAmount, BigDecimal tolerance, String requestId) {}

    /** 偏差明细：deviationRate 偏差百分比文本、conclusion=CONSISTENT/DEVIATION/ABNORMAL */
    public record DeviationDetail(String item, BigDecimal flowAmount, BigDecimal reportAmount,
                                  String deviationRate, String conclusion) {}

    /** 交叉验证结果：verification=CONSISTENT/DEVIATION/ABNORMAL、deviationDetails、evidenceChain 证据链、crossNo 流水号 */
    public record CrossResult(String verification, List<DeviationDetail> deviationDetails,
                              List<String> evidenceChain, String crossNo) {}
}
