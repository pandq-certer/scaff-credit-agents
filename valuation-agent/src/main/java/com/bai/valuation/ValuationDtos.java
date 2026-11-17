package com.bai.valuation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 资产估值输入/输出结构（提示词库 A-57：POST /api/bai/valuation/value、GET /query、
 * POST /feedback、POST /market 字段表逐条对齐）
 */
public final class ValuationDtos {

    private ValuationDtos() {}

    /** 估值入参：assetType(REAL_ESTATE|EQUIPMENT|INVENTORY|NPL)、method(MARKET|INCOME|COST)、params（面积/成新率/收益预期/成本单价等） */
    public record ValuationRequest(String assetType, String method, String assetRef, Map<String, Object> params,
                                   String valueDate, String requestId) {}

    /** 估值区间：low ≤ value ≤ high 恒等 */
    public record ValueRange(BigDecimal low, BigDecimal high) {}

    /** 估值结果：结论 VALUED（自动估值）/ MANUAL（需人工估值，原因并入 basisRef） */
    public record ValuationResult(String code, String assetType, String method, BigDecimal value, ValueRange range,
                                  String conclusion, String valueDate, String basisRef) {}

    /** 分方法结果（估值留痕 methodResults 条目） */
    public record MethodResult(String method, BigDecimal value, BigDecimal weight, String detail) {}

    /** 估值留痕记录（val_case 行结构，query 返回条目：valueId/assetId/assetType/value/range/methodResults/conclusion/createdAt） */
    public record ValuationRecord(String valueId, String assetId, String assetType, BigDecimal value, ValueRange range,
                                  List<MethodResult> methodResults, String conclusion, String createdAt) {}

    /** 估值查询结果（分页：total/items） */
    public record ValuationQueryResult(int total, int page, int pageSize, List<ValuationRecord> items) {}

    /** 方法优化建议（反馈 methodAdjust：method + suggestion） */
    public record MethodAdjust(String method, String suggestion) {}

    /** 估值反馈入参：humanConclusion=ACCEPT|ADJUST|REJECT，reviewer 必填，ADJUST 需 adjustValue */
    public record FeedbackRequest(String valueId, String humanConclusion, String reviewer, String comment,
                                  BigDecimal adjustValue, MethodAdjust methodAdjust, String requestId) {}

    /** 估值反馈受理结果：feedbackId + status（RECEIVED） */
    public record FeedbackResult(String feedbackId, String status, long elapsedMs) {}

    /** 市场数据点：行情日期/成交均价（price）/价格指数（priceIndex）/来源 */
    public record MarketPoint(String date, BigDecimal price, BigDecimal priceIndex, String source) {}

    /** 市场数据入参：action=REFRESH|QUERY|UPDATE（默认 REFRESH），data 含价格指数/成交均价/行情日期 */
    public record MarketRequest(String action, String assetType, String region, String industry,
                                List<MarketPoint> data, String requestId) {}

    /** 市场数据接入结果：dataId + status + 数据版本 + 受理/入索引条数 */
    public record MarketResult(String dataId, String status, int version, int accepted, int indexed, long elapsedMs) {}
}
