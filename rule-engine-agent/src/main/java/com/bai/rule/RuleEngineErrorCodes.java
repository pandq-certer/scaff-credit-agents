package com.bai.rule;

/**
 * 规则引擎原子错误码段（提示词库 A-17 条目，原文档码表逐条保留）
 */
public final class RuleEngineErrorCodes {

    private RuleEngineErrorCodes() {}

    public static final String PARAM_INVALID = "RUL_1001";      // 参数缺失/facts 非法；校验后重试
    public static final String TOKEN_INVALID = "RUL_1002";      // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "RUL_1003";      // 无调用权限；申请权限
    public static final String RULESET_NOT_FOUND = "RUL_1004";  // rulesetId/版本/taskId 不存在；校验参数
    public static final String FACT_INVALID = "RUL_1005";       // 事实字段缺失/类型不符/批量超限；修正输入
    public static final String RATE_LIMITED = "RUL_1006";       // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "RUL_2000";     // 规则编译/执行内部错误；记录 requestId 上报
    public static final String SERVICE_UNAVAILABLE = "RUL_2001"; // 规则服务不可用（降级中）；短期缓存结论兜底
}
