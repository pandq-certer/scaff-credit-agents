package com.bai.deriv;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 衍生品定价输入/输出结构（BIA-CAL-060：POST /api/bai/deriv/price 字段表逐条对齐）
 */
public final class DerivDtos {

    private DerivDtos() {}

    /** 收益率曲线点：tenor 为年限数值字符串（如 "0.5"/"1"/"5"），rate 为年化小数（0.03 = 3%） */
    public record CurvePoint(String tenor, BigDecimal rate) {}

    /** 市场数据：yieldCurve/spotRate/volatility/counterpartyRating 必填，timestamp 可选（缺失标记 STALE） */
    public record MarketData(List<CurvePoint> yieldCurve, BigDecimal spotRate, BigDecimal volatility,
                             String counterpartyRating, String timestamp) {}

    /** 定价入参：contractType(IRS|FORWARD|CDS|OPTION)、notional>0、params{tenor,direction,strike,underlying,dayCount}、valuationDate、marketData */
    public record PriceRequest(String contractType, BigDecimal notional, Map<String, Object> params,
                               String valuationDate, MarketData marketData, String requestId) {}

    /** 希腊字母：delta/gamma/vega/dv01（vega 为每 1% 波动率、dv01 为每 1bp 利率的敏感性） */
    public record Greeks(BigDecimal delta, BigDecimal gamma, BigDecimal vega, BigDecimal dv01) {}

    /** 定价结果：NPV（2 位）、CVA（2 位）、对冲比例（默认 0.80）与模型版本 */
    public record PriceResult(String contractType, BigDecimal npv, Greeks greeks, BigDecimal cva,
                              BigDecimal hedgeRatio, String priceVersion, boolean stale, boolean jumpAlert) {}

    // ------------------------------------------------------------------ 4.3 每日盯市估值

    /** 盯市入参：mode=SINGLE/BATCH、valuationDate、positionIds[]（批量持仓清单）、forceRevalue（强制重估） */
    public record MarkRequest(String mode, String valuationDate, List<String> positionIds,
                              Boolean forceRevalue, String requestId) {}

    /** 盯市结果（每笔）：contractId/fairValue/prevFairValue/changeAmount/changeRatio/alertFlag/valuationMethod */
    public record MarkItem(String contractId, BigDecimal fairValue, BigDecimal prevFairValue,
                           BigDecimal changeAmount, BigDecimal changeRatio, boolean alertFlag,
                           String valuationMethod) {}

    /** 盯市结果集：估值结果 + 对账摘要（完成数/失败数/告警数）+ batchNo（批量任务幂等键） */
    public record MarkResult(String batchNo, String valuationDate, List<MarkItem> items,
                             int completed, int failed, int alerted) {}

    // ------------------------------------------------------------------ 4.4 对冲策略计算

    /** 对冲入参：exposureId、underlyingRisk(RATE|FX|COMMODITY)、riskFactor{amount,delta,dv01,duration}、hedgeTools[]、targetRatio(0~1) */
    public record HedgeRequest(String exposureId, String underlyingRisk, Map<String, Object> riskFactor,
                               List<String> hedgeTools, BigDecimal targetRatio, String requestId) {}

    /** 对冲建议（逐工具）：tool/notional/direction/hedgeRatio/expectedCost/effectiveness */
    public record HedgeSuggestion(String tool, BigDecimal notional, String direction, BigDecimal hedgeRatio,
                                  BigDecimal expectedCost, BigDecimal effectiveness) {}

    /** 对冲策略结果：建议清单 + 对冲后残余风险（residualRisk/residualDelta）+ 套保关系认定结论 */
    public record HedgeResult(String exposureId, BigDecimal delta, List<HedgeSuggestion> suggestions,
                              BigDecimal residualRisk, BigDecimal residualDelta, String complianceCheck) {}

    // ------------------------------------------------------------------ 4.5 利率曲线构建与发布

    /** 曲线报价点：tenor 年限数值字符串（如 "0.5"）、quote 年化小数、quoteType（OIS/LPR/SHIBOR/SWAP/BOND） */
    public record CurveQuote(String tenor, BigDecimal quote, String quoteType) {}

    /** 曲线构建入参：currency、curveType(DISCOUNT|FORWARD|SPOT)、method(BOOTSTRAP|NELSON_SIEGEL|SPLINE)、quotes[]、effectiveTime、params */
    public record CurveRequest(String currency, String curveType, String method, List<CurveQuote> quotes,
                               String effectiveTime, Map<String, Object> params, String requestId) {}

    /** 曲线节点：tenor/discountFactor/zeroRate/forwardRate */
    public record CurvePointResult(String tenor, BigDecimal discountFactor, BigDecimal zeroRate,
                                   BigDecimal forwardRate) {}

    /** 曲线质量报告：单调性/远期非负/跳变检查 */
    public record CurveQuality(boolean monotonic, boolean forwardNonNegative, boolean jumpFree, String detail) {}

    /** 曲线发布结果：curveId + 期限结构 + 版本 + 质量报告 + 状态（PUBLISHED/REJECTED） */
    public record CurveResult(String curveId, String currency, String curveType, String method,
                              List<CurvePointResult> points, String version, CurveQuality qualityReport,
                              String effectiveTime, String status) {}

    // ------------------------------------------------------------------ 4.6 波动率曲面构建与发布

    /** 波动率报价点：tenor/strike/quote（隐含波动率小数）/quoteType */
    public record VolQuote(String tenor, String strike, BigDecimal quote, String quoteType) {}

    /** 曲面构建入参：underlyingType(EQUITY|RATE|FX)、method(SVI|SPLINE)、quotes[]、params、effectiveTime */
    public record VolRequest(String underlyingType, String method, List<VolQuote> quotes,
                             Map<String, Object> params, String effectiveTime, String requestId) {}

    /** 曲面网格点：tenor/strike/delta/impliedVol */
    public record VolGridPoint(String tenor, String strike, BigDecimal delta, BigDecimal impliedVol) {}

    /** 无套利检查报告（日历价差/蝶式价差非负，通过方可发布） */
    public record ArbitrageReport(boolean passed, String detail) {}

    /** 曲面发布结果：volSurfaceId + 网格 + 参数 + 无套利报告 + 版本 + 状态 */
    public record VolResult(String volSurfaceId, String underlyingType, String method, List<VolGridPoint> grid,
                            Map<String, Object> params, ArbitrageReport arbitrageReport, String version,
                            String effectiveTime, String status) {}

    // ------------------------------------------------------------------ 4.7 敞口查询

    /** 潜在未来敞口 PFE 分位数点：tenor/quantile */
    public record PfePoint(String tenor, BigDecimal quantile) {}

    /** 敞口汇总：mtmExposure（当前敞口）/delta/VaR/pfeByTenor/cva/dva/limitUsage/overLimitFlag */
    public record ExposureResult(String portfolioId, String counterpartyId, String currency, String asOfDate,
                                 BigDecimal mtmExposure, BigDecimal delta, BigDecimal var, List<PfePoint> pfeByTenor,
                                 BigDecimal cva, BigDecimal dva, BigDecimal limitUsage, boolean overLimitFlag,
                                 String confidenceLevel) {}

    // ------------------------------------------------------------------ 4.8 套期有效性评估

    /** 套期有效性入参：hedgeRelationId、method(RETROSPECTIVE|PROSPECTIVE)、window（回溯窗口）、scenarioId（前瞻情景） */
    public record EffectivenessRequest(String hedgeRelationId, String method, String window,
                                       String scenarioId, String requestId) {}

    /** 套期有效性结果：assessmentResult(EFFECTIVE|INEFFECTIVE)、ratio（80%~125% 区间）、detail、reportUrl */
    public record EffectivenessResult(String hedgeRelationId, String method, String assessmentResult,
                                      BigDecimal ratio, String detail, String reportUrl) {}
}
