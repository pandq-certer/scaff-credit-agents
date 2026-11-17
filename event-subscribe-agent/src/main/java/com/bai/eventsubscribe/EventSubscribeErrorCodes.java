package com.bai.eventsubscribe;

/**
 * 事件订阅原子错误码段（提示词库 A-33 条目，原文档码表逐条保留）
 */
public final class EventSubscribeErrorCodes {

    private EventSubscribeErrorCodes() {}

    public static final String PARAM_INVALID = "EVS_1001";    // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "EVS_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "EVS_1003";    // 无订阅/发布权限（含未授权类型）；申请权限
    public static final String NOT_FOUND = "EVS_1004";        // 事件类型/订阅不存在；校验参数
    public static final String CONFLICT = "EVS_1005";         // 类型冲突/重复订阅；修正后重试
    public static final String SCHEMA_INVALID = "EVS_1006";   // schema 不符/条件非法/端点白名单外；修正输入
    public static final String RATE_LIMITED = "EVS_1007";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "EVS_2000";   // 事件服务内部错误；记录 requestId 上报
    public static final String CHANNEL_DEGRADED = "EVS_2001"; // 消息通道不可用（降级中）；稍后重试
}
