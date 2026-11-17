package com.bai.sensitivemask;

/**
 * 敏感脱敏原子错误码段（提示词库 A-47 条目，原文档码表逐条保留）
 */
public final class SensitiveMaskErrorCodes {

    private SensitiveMaskErrorCodes() {}

    public static final String PARAM_INVALID = "SMK_1001";    // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "SMK_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "SMK_1003";    // 无脱敏权限/规则管理越权；申请权限
    public static final String NOT_FOUND = "SMK_1004";        // 记录/规则不存在；校验参数
    public static final String CONFLICT = "SMK_1005";         // 重复提交/规则冲突；修正后重试
    public static final String TOO_LONG = "SMK_1006";         // 内容超长；缩减输入
    public static final String TYPE_INVALID = "SMK_1007";     // 场景/类型非法；修正输入
    public static final String RATE_LIMITED = "SMK_1008";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "SMK_2000";   // 脱敏服务内部错误；记录 requestId 上报
    public static final String DETECT_DEGRADED = "SMK_2001";  // 识别/规则服务不可用（降级中）；字段规则兜底
}
