package com.bai.limit;

import java.math.BigDecimal;
import java.util.List;

/**
 * 额度计算输入/输出结构（提示词库 A-56：POST /api/bai/limit/calc 及 4.3~4.5 接口字段表逐条对齐）
 */
public final class LimitDtos {

    private LimitDtos() {}

    /** 额度测算入参：limitType(SINGLE|GROUP|INDUSTRY)，ratingFactor∈[0,1]，availableLimit≥0，requestedAmount>0 */
    public record LimitCalcRequest(String customerId, String limitType, BigDecimal incomeBased,
                                   BigDecimal collateralBased, BigDecimal ratingFactor, BigDecimal availableLimit,
                                   BigDecimal requestedAmount, String requestId) {}

    /** 测算结果：remainAmount = limitAmount - usedAmount 恒等；OVER_LIMIT 时 overAmount>0 */
    public record LimitCalcResult(String code, String customerId, BigDecimal limitAmount, BigDecimal usedAmount,
                                  BigDecimal remainAmount, BigDecimal requestedAmount, String controlStatus,
                                  BigDecimal overAmount) {}

    /** 限额管理入参（4.3）：action(CREATE|UPDATE|GRAY|DISABLE) + 限额配置要素 */
    public record LimitRequest(String action, LimitConfig limit, String requestId) {}

    /** 限额配置要素（lmt_rule）：limitType(SINGLE|GROUP|INDUSTRY)、scope(客户/集团/行业标识)、amount>0、source(监管|行内) */
    public record LimitConfig(String limitType, String scope, BigDecimal amount, String effectiveFrom,
                              String effectiveTo, String source) {}

    /** 限额管理结果（4.3）：limitId + 当前版本 + 状态(ACTIVE|GRAY|DISABLED) */
    public record LimitResult(String code, String limitId, int version, String status) {}

    /** 试算查询入参（4.4）：calcId 或 customerId（可加 limitType/status 过滤，page/size 分页） */
    public record CalcQueryRequest(String calcId, String customerId, String limitType, String status,
                                   Integer page, Integer size) {}

    /** 试算查询单条记录（4.4）：control 为控制结论，status 为 PASS|OVER_LIMIT|REJECT */
    public record CalcQueryItem(String calcId, String customerId, BigDecimal availableLimit, CalcControl control,
                                String status, String createdAt) {}

    /** 控制结论：限额/已用/剩余/申请后/结论 */
    public record CalcControl(String limitType, BigDecimal limitAmount, BigDecimal usedAmount,
                              BigDecimal remainAmount, BigDecimal requestedAmount, String controlStatus) {}

    /** 试算查询结果（4.4）：total + 分页 items */
    public record CalcQueryResult(int total, List<CalcQueryItem> items) {}

    /** 占用查询入参（4.5）：customerId 必填，limitType 可过滤（缺省 SINGLE） */
    public record UsageQueryRequest(String customerId, String limitType) {}

    /** 占用明细行（4.5）：合同号/占用金额/状态(ACTIVE|RELEASED) */
    public record UsageDetail(String contract, BigDecimal amount, String status) {}

    /** 占用查询结果（4.5）：总额/已用/可用/占用比例 + 明细 */
    public record UsageResult(String code, String limitType, BigDecimal limitAmount, BigDecimal usedAmount,
                              BigDecimal remainAmount, String ratio, List<UsageDetail> details) {}
}
