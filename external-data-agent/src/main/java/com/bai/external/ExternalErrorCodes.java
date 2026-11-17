package com.bai.external;

/**
 * 外部数据接入原子错误码段（提示词库 A-27 条目，原文档码表逐条保留）
 */
public final class ExternalErrorCodes {

    private ExternalErrorCodes() {}

    public static final String PARAM_INVALID = "EXD_1001";    // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "EXD_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "EXD_1003";    // 无调用权限/数据源未授权；申请权限
    public static final String SOURCE_NOT_FOUND = "EXD_1004"; // 数据源/记录不存在；校验参数
    public static final String BATCH_OVER_LIMIT = "EXD_1005"; // 批量超限；分批提交
    public static final String SOURCE_DISABLED = "EXD_1006";  // 数据源停用/映射缺失；修正输入
    public static final String RATE_LIMITED = "EXD_1007";     // 触发频控/配额不足；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "EXD_2000";   // 接入服务内部错误；记录 requestId 上报
    public static final String SOURCE_UNAVAILABLE = "EXD_2001"; // 外部数据源不可用（熔断/降级中）；稍后重试或降级
}
