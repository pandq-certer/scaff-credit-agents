package com.bai.screening;

/**
 * 名单筛查原子错误码段（提示词库 A-50 条目，原文档码表逐条保留）
 */
public final class ScreeningErrorCodes {

    private ScreeningErrorCodes() {}

    public static final String PARAM_INVALID = "SCR_1001";    // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "SCR_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "SCR_1003";    // 无筛查权限/名单管理越权；申请权限
    public static final String NOT_FOUND = "SCR_1004";        // 名单/记录不存在；校验参数
    public static final String CONFLICT = "SCR_1005";         // 重复提交/名单冲突；修正后重试
    public static final String TOO_LONG = "SCR_1006";         // 主体信息超长；缩减输入
    public static final String SCENE_INVALID = "SCR_1007";    // 场景/名单类型非法；修正输入
    public static final String RATE_LIMITED = "SCR_1008";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "SCR_2000";   // 筛查服务内部错误；记录 requestId 上报
    public static final String LIST_DEGRADED = "SCR_2001";    // 名单服务不可用（降级中）；精确匹配兜底
}
