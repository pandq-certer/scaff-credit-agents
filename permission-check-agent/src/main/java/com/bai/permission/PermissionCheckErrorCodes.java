package com.bai.permission;

/**
 * 权限校验原子错误码段（设计文档 46-H 表 4-3 码表逐条保留，前缀 PMC_）
 */
public final class PermissionCheckErrorCodes {

    private PermissionCheckErrorCodes() {}

    public static final String PARAM_INVALID = "PMC_1001";       // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "PMC_1002";       // Token 缺失/过期；重新认证
    public static final String PERMISSION_DENIED = "PMC_1003";   // 校验结论 DENY（无权限）；申请授权
    public static final String NOT_FOUND = "PMC_1004";           // 策略/角色/用户不存在；校验参数
    public static final String STATE_CONFLICT = "PMC_1005";      // 授权冲突/重复申请；修正后重试
    public static final String INPUT_TOO_LONG = "PMC_1006";      // 请求超长；缩减输入
    public static final String SCOPE_INVALID = "PMC_1007";       // 权限点非法/数据范围非法；修正输入
    public static final String RATE_LIMITED = "PMC_1008";        // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "PMC_2000";      // 校验服务内部错误；记录 requestId 上报
    public static final String POLICY_DEGRADED = "PMC_2001";     // 策略服务不可用（降级中）；稍后重试
}
