package com.bai.structuregen;

/**
 * 结构化生成原子错误码段（提示词库 A-36 条目，原文档码表逐条保留）
 */
public final class StructureGenErrorCodes {

    private StructureGenErrorCodes() {}

    public static final String PARAM_INVALID = "STG_1001";    // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "STG_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "STG_1003";    // 无生成/Schema 权限；申请权限
    public static final String NOT_FOUND = "STG_1004";        // Schema/历史不存在；校验参数
    public static final String CONFLICT = "STG_1005";         // Schema 名冲突/版本冲突；修正后重试
    public static final String TOO_LONG = "STG_1006";         // 输入超长；缩减输入
    public static final String VALIDATION_FAILED = "STG_1007";// Schema 不符/枚举越界/校验未过；修正输入
    public static final String RATE_LIMITED = "STG_1008";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "STG_2000";   // 生成服务内部错误；记录 requestId 上报
    public static final String LLM_DEGRADED = "STG_2001";     // LLM 不可用（降级中）；规则抽取降级兜底
}
