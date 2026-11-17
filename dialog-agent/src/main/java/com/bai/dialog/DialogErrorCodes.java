package com.bai.dialog;

/**
 * 对话管理原子错误码段（提示词库 A-39 条目，原文档码表逐条保留）
 */
public final class DialogErrorCodes {

    private DialogErrorCodes() {}

    public static final String PARAM_INVALID = "DLG_1001";     // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "DLG_1002";     // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "DLG_1003";     // 无会话/无场景权限（越权访问他人会话）；申请权限
    public static final String SESSION_NOT_FOUND = "DLG_1004"; // 会话/历史不存在（或非 ACTIVE）；校验参数
    public static final String SESSION_CONFLICT = "DLG_1005";  // 会话冲突（重复创建）；恢复会话
    public static final String MESSAGE_TOO_LONG = "DLG_1006";  // 消息超长（>2000 字）；缩减输入
    public static final String SENSITIVE_BLOCKED = "DLG_1007"; // 敏感拦截/场景非法；修正输入（BLOCK/REDIRECT）
    public static final String RATE_LIMITED = "DLG_1008";      // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "DLG_2000";    // 对话服务内部错误；记录 requestId 上报
    public static final String LLM_UNAVAILABLE = "DLG_2001";   // LLM 不可用（降级中）；模板回复降级并转人工
}
