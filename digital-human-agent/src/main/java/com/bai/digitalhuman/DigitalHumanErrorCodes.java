package com.bai.digitalhuman;

/**
 * 数字员工原子错误码段（原文档码表逐条保留，DHM_ 前缀）
 */
public final class DigitalHumanErrorCodes {

    private DigitalHumanErrorCodes() {}

    public static final String PARAM_INVALID = "DHM_1001";      // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "DHM_1002";      // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "DHM_1003";      // 无形象/展示权限；申请权限
    public static final String NOT_FOUND = "DHM_1004";          // 形象/渲染不存在；校验参数
    public static final String CONFLICT = "DHM_1005";           // 形象名冲突/渲染状态冲突；修正后重试
    public static final String CONTENT_TOO_LONG = "DHM_1006";   // 内容超长/资产过大；缩减输入
    public static final String CONTENT_INVALID = "DHM_1007";    // 内容合规未过/驱动类型非法；修正输入
    public static final String RATE_LIMITED = "DHM_1008";       // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "DHM_2000";     // 展示服务内部错误；记录 requestId 上报
    public static final String RENDER_UNAVAILABLE = "DHM_2001"; // 渲染算力不可用（降级中）；稍后重试
}
