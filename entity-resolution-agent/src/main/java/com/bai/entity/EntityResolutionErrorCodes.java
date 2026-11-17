package com.bai.entity;

/**
 * 实体归一原子错误码段（提示词库 BIA-DTA-025 条目，码表逐条保留）
 */
public final class EntityResolutionErrorCodes {

    private EntityResolutionErrorCodes() {}

    public static final String PARAM_INVALID = "ENT_1001";      // 参数缺失/非法（record/source 白名单/entityType/topK、record 序列化超 256KB）；校验后重试
    public static final String TOKEN_INVALID = "ENT_1002";      // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "ENT_1003";      // 无调用权限；申请权限
    public static final String GOLDEN_NOT_FOUND = "ENT_1004";   // goldenId 不存在；校验参数
    public static final String OVER_LIMIT = "ENT_1005";         // 批量超限；分批提交
    public static final String CONFLICT = "ENT_1006";           // 证件号与已命中记录冲突（同人不同证）；人工核实
    public static final String RATE_LIMITED = "ENT_1007";       // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "ENT_2000";     // 归一引擎内部错误；记录 requestId 上报
    public static final String SERVICE_UNAVAILABLE = "ENT_2001"; // 归一服务不可用（降级中）；稍后重试
}
