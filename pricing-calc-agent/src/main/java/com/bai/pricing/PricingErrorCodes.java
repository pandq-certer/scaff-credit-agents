package com.bai.pricing;

/**
 * 定价计算原子错误码段（提示词库 A-55 条目，原文档码表逐条保留）
 */
public final class PricingErrorCodes {

    private PricingErrorCodes() {}

    public static final String PARAM_INVALID = "PRC_1001";       // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "PRC_1002";       // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "PRC_1003";       // 无试算权限或规则管理越权；申请权限
    public static final String NOT_FOUND = "PRC_1004";           // 产品/规则不存在；校验参数
    public static final String DUPLICATE_CONFLICT = "PRC_1005";  // 重复提交/规则冲突；使用原 requestId 查询
    public static final String ELEMENT_TOO_LONG = "PRC_1006";    // 定价要素超长；缩减输入
    public static final String ELEMENT_ILLEGAL = "PRC_1007";     // 产品/期限/担保非法；修正输入
    public static final String RATE_LIMITED = "PRC_1008";        // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "PRC_2000";      // 定价服务内部错误；记录 requestId 上报
    public static final String RULE_UNAVAILABLE = "PRC_2001";    // 产品/规则服务不可用（降级中）；稍后重试
}
