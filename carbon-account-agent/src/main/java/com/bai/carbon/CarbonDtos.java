package com.bai.carbon;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 碳账户核算输入/输出结构（BIA-CAL-061：POST /api/bai/carbon/account 字段表逐条对齐）
 */
public final class CarbonDtos {

    private CarbonDtos() {}

    /** 核算入参：entityId、period（yyyy 或 yyyy-MM）、activityData（electricityKwh/naturalGasM3/coalTon/purchasedHeatGj/supplyChainTons/purchasedOffsets/leakage）、factorVersion 可选 */
    public record AccountRequest(String entityId, String period, Map<String, Object> activityData,
                                 String factorVersion, String requestId) {}

    /** 碳价情景：base/neutral/pessimistic（元/tCO2e）与情景权重 */
    public record CarbonScenario(BigDecimal base, BigDecimal neutral, BigDecimal pessimistic, List<BigDecimal> weights) {}

    /** 核算结果：totalCarbon 恒等于 scope1+scope2+scope3；er 为净排放 ER=BE−PE−LE（purchasedOffsets/leakage 扣减） */
    public record AccountResult(String entityId, BigDecimal scope1, BigDecimal scope2, BigDecimal scope3,
                                BigDecimal totalCarbon, CarbonScenario carbonPriceScenario, BigDecimal pledgeableAmount,
                                String factorVersion, BigDecimal er) {}

    /** 排放因子库条目（GET /factors） */
    public record FactorItem(String code, String name, BigDecimal value, String unit) {}

    /** 目录分类判定入参（4.2，表 4-2 逐条对齐）：entityType/entityId 必填，PROJECT 时 projectInfo 必填 */
    public record ClassifyRequest(String entityType, String entityId, String industryCode, String industryDesc,
                                  Map<String, Object> projectInfo, String categoryVersion, String loanPurpose,
                                  String requestId) {}

    /** 目录分类判定结果（4.2，表 4-3 逐条对齐）：conclusion ∈ GREEN/NON_GREEN/SUSPECT，边界条目触发人工复核 */
    public record ClassifyResult(String conclusion, String categoryPath, BigDecimal matchScore,
                                 List<String> matchBasis, boolean reviewRequired, String reviewTaskId,
                                 String certificateNo) {}

    /** 项目减排量测算入参（4.4）：methodology 如 CM-001；boundary/baselineParams/operationData/emissionFactors/leakageParams 为参数映射 */
    public record ReductionRequest(String projectId, String methodology, String projectType,
                                   Map<String, Object> boundary, Map<String, Object> baselineParams,
                                   Map<String, Object> operationData, Map<String, Object> emissionFactors,
                                   Map<String, Object> leakageParams, String requestId) {}

    /** 项目减排量测算结果（4.4）：基准线情景法 ER = BE − PE − LE，reduction 为年减排量 tCO2e */
    public record ReductionResult(boolean applicable, List<String> applicabilityChecks, BigDecimal baselineEmission,
                                  BigDecimal projectEmission, BigDecimal leakage, BigDecimal reduction,
                                  List<String> calcProcess, String monitoringAdvice) {}

    /** 碳配额测算入参（4.5）：allocationMethod ∈ BASELINE/HISTORICAL */
    public record QuotaRequest(String entityId, BigDecimal emission, String allocationMethod,
                               BigDecimal benchmarkValue, BigDecimal outputAmount, BigDecimal reductionFactor,
                               BigDecimal freeRatio, String compliancePeriod, String ruleVersion, String requestId) {}

    /** 碳配额测算结果（4.5）：gapSurplus = 配额 − 实际排放，负为缺口；forecast 为预测区间 */
    public record QuotaResult(BigDecimal quotaAmount, BigDecimal gapSurplus,
                              Map<String, BigDecimal> forecast, String ruleVersion) {}

    /** 碳价情景（4.6）：name/base/upside/downside 价格与权重 */
    public record PriceScenario(String name, BigDecimal base, BigDecimal upside, BigDecimal downside, BigDecimal weight) {}

    /** 碳资产估值入参（4.6）：assetType ∈ ALLOWANCE/CCER；priceScenarios 缺省用 基准/上行(+50%)/下行(−30%)，权重 0.5/0.25/0.25 */
    public record ValueRequest(String assetId, String assetType, BigDecimal quantity, String maturity,
                               String ownership, List<PriceScenario> priceScenarios, BigDecimal discountRate,
                               BigDecimal pledgeRatio, String requestId) {}

    /** 情景估值结果（4.6）：情景/价格/现值 */
    public record ValuationScenario(String scenario, BigDecimal price, BigDecimal presentValue) {}

    /** 碳资产估值结果（4.6）：加权值/碳价与数量敏感性/质押价值建议/参数快照 */
    public record ValueResult(List<ValuationScenario> valuationByScenario, BigDecimal weightedValue,
                              Map<String, BigDecimal> sensitivity, BigDecimal pledgeValue,
                              Map<String, Object> paramsSnapshot) {}

    /** 贷款要素（4.7）：categoryPath 非空视为投向绿色目录项目 */
    public record LoanInfo(String loanNo, BigDecimal amount, String term, String purpose,
                           String industryCode, String categoryPath) {}

    /** 绿色贷款认定与统计测算入参（4.7）：loans[] + statPeriod + statVersion */
    public record GreenStatRequest(List<LoanInfo> loans, String statPeriod, String statVersion, String requestId) {}

    /** 单笔贷款绿色认定结果（4.7） */
    public record Recognition(String loanNo, String conclusion, String categoryPath, BigDecimal matchScore, String basis) {}

    /** 绿色贷款认定与统计测算结果（4.7）：绿色余额/占比/投向结构/与报送口径对账结论 */
    public record GreenStatResult(List<Recognition> recognitions, BigDecimal greenBalance, BigDecimal greenRatio,
                                  Map<String, BigDecimal> structureByIndustry, String reconResult) {}

    /** 碳减排支持工具测算入参（4.8）：projectType/reduction 必填，declarationItems 申报材料 */
    public record ToolCalcRequest(String projectType, BigDecimal reduction, BigDecimal loanRate, BigDecimal loanAmount,
                                  List<String> declarationItems, String toolRuleVersion, String requestId) {}

    /** 碳减排支持工具测算结果（4.8）：符合性结论/折算减排量/再贷款测算金额/申报材料清单 */
    public record ToolCalcResult(boolean compliance, String complianceDetail, BigDecimal reductionConverted,
                                 BigDecimal toolLoanAmount, List<String> materialChecklist) {}

    /** 洗绿识别入参（4.9）：evidenceDocs 证据文档（filing 备案/contract 合同/eia 环评/fundFlow 资金流水），riskRules 规则集版本 */
    public record GreenwashRequest(String entityId, String classifyConclusion, Map<String, Object> evidenceDocs,
                                   String riskRules, String requestId) {}

    /** 命中规则项（4.9）：规则/证据/评分 */
    public record HitRule(String ruleCode, String ruleName, String evidence, BigDecimal score) {}

    /** 洗绿识别结果（4.9）：风险等级 LOW/MEDIUM/HIGH 与处置建议 PASS/REVIEW/REJECT */
    public record GreenwashResult(String riskLevel, List<HitRule> hitRules, List<String> evidenceChain,
                                  String disposalAdvice) {}
}
