package com.bai.pricing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 定价计算输入/输出结构（提示词库 A-55：POST /api/bai/pricing/quote 字段表逐条对齐）
 */
public final class PricingDtos {

    private PricingDtos() {}

    /**
     * 试算入参：productId/amount/term(1~360)/rateType(FIXED|FLOATING)/collateral(信用|抵押|质押|保证)/customerRating/ruleVersion/requestId。
     * pricingType 缺省走利率试算；PENALTY=提前还款违约金试算（params.remainingPrincipal/params.penaltyRate）
     */
    public record QuoteRequest(String productId, BigDecimal amount, Integer term, String rateType,
                               String collateral, String customerRating, String ruleVersion, String requestId,
                               String pricingType, Map<String, Object> params) {}

    /** 费用明细：费用项/金额/收取方式 */
    public record FeeItem(String feeItem, BigDecimal amount, String chargeMode) {}

    /** 试算结果：finalRate = baseRate + riskPremium + costAddOn，可复算并与 ruleVersion 绑定 */
    public record QuoteResult(String code, String productId, BigDecimal baseRate, BigDecimal riskPremium,
                              BigDecimal costAddOn, BigDecimal finalRate, List<FeeItem> fees, String ruleVersion) {}

    /** 定价规则参数（rule）：ruleId 由 CREATE 生成，UPDATE/GRAY/DISABLE 需携带 */
    public record RuleParam(String ruleId, String name, String factor, String condition, BigDecimal premium,
                            String effectiveFrom, String effectiveTo) {}

    /** 定价规则管理入参：action=CREATE|UPDATE|GRAY|DISABLE（4.3） */
    public record RuleRequest(String action, RuleParam rule, String requestId) {}

    /** 定价规则管理结果：status=ACTIVE|GRAY|DRAFT|DISABLED */
    public record RuleResult(String ruleId, Integer version, String status) {}

    /** 试算查询入参（4.4：GET ?quoteId=&product=&customerId=&page=&size=） */
    public record QueryRequest(String quoteId, String product, String customerId, Integer page, Integer size) {}

    /** 试算记录条目 */
    public record QueryItem(String quoteId, String product, Integer term, String collateral,
                            BigDecimal finalRate, List<FeeItem> fees, String createdAt) {}

    /** 试算查询结果 */
    public record QueryResult(Integer total, List<QueryItem> items) {}

    /** 规则优化建议（4.5 ruleAdjust） */
    public record RuleAdjust(String ruleId, String suggestion) {}

    /** 定价反馈入参（4.5）：humanConclusion=ACCEPT|REJECT|ADJUST */
    public record FeedbackRequest(String quoteId, String humanConclusion, String reviewer, String comment,
                                  RuleAdjust ruleAdjust, String requestId) {}

    /** 定价反馈受理结果 */
    public record FeedbackResult(String feedbackId, String status, long elapsedMs) {}
}
