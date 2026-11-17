package com.bai.auth;

/**
 * 身份认证原子错误码段（提示词库 A-48 条目，原文档码表逐条保留）
 */
public final class AuthErrorCodes {

    private AuthErrorCodes() {}

    public static final String PARAM_INVALID = "AUT_1001";   // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "AUT_1002";   // Token 缺失/过期；重新登录
    public static final String LOCKED = "AUT_1003";          // 无认证权限/账户锁定；解锁后重试
    public static final String IDENTITY_NOT_FOUND = "AUT_1004"; // 身份/凭证不存在；校验参数
    public static final String STATE_CONFLICT = "AUT_1005";  // 重复提交/会话冲突；修正后重试
    public static final String INPUT_TOO_LONG = "AUT_1006";  // 凭证/上下文超长；缩减输入
    public static final String FACTOR_INVALID = "AUT_1007";  // 因子类型非法；修正输入
    public static final String RATE_LIMITED = "AUT_1008";    // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "AUT_2000";  // 认证服务内部错误；记录 requestId 上报
    public static final String FACTOR_DEGRADED = "AUT_2001"; // 凭证/生物服务不可用（降级中）；走备用因子，稍后重试
}
