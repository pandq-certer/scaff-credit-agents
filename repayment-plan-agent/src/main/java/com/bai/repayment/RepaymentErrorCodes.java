package com.bai.repayment;

/**
 * 还款计划原子错误码段（提示词库 A-60 条目，原文档码表逐条保留）
 */
public final class RepaymentErrorCodes {

    private RepaymentErrorCodes() {}

    public static final String PARAM_INVALID = "RPP_1001";       // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "RPP_1002";       // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "RPP_1003";       // 无计划权限；申请权限
    public static final String NOT_FOUND = "RPP_1004";           // 合同/计划不存在；校验参数
    public static final String DUPLICATE_CONFLICT = "RPP_1005";  // 重复生成/计划冲突；修正后重试
    public static final String TERMS_EXCEEDED = "RPP_1006";      // 期次数超限（>360）；缩减输入
    public static final String ELEMENT_ILLEGAL = "RPP_1007";     // 还款法/重算类型非法；修正输入
    public static final String RATE_LIMITED = "RPP_1008";        // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "RPP_2000";      // 计划服务内部错误；记录 requestId 上报
    public static final String PRICING_UNAVAILABLE = "RPP_2001"; // 利率/定价服务不可用（降级中）；稍后重试
}
