package com.bai.intent;

/**
 * 意图识别原子错误码段（提示词库 A-07 条目，原文档码表逐条保留）
 */
public final class IntentErrorCodes {

    private IntentErrorCodes() {}

    public static final String PARAM_INVALID = "INT_1001";    // 参数缺失/文本非法；校验后重试
    public static final String TOKEN_INVALID = "INT_1002";    // Token 缺失/过期；重新获取 Token（网关统一鉴权）
    public static final String NO_PERMISSION = "INT_1003";    // 无调用权限；申请权限（网关统一鉴权）
    public static final String NOT_FOUND = "INT_1004";        // taskId/意图不存在；校验参数（批量任务查询接口未在条目定义，保留）
    public static final String TEXT_TOO_LONG = "INT_1005";    // 文本超 500 字/批量超 10000 条；截断或分批
    public static final String EMPTY_TEXT = "INT_1006";       // 空文本/纯符号；修正输入
    public static final String RATE_LIMITED = "INT_1007";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "INT_2000";   // 服务内部错误；记录 requestId 上报
    public static final String MODEL_FALLBACK = "INT_2001";   // 模型服务不可用（关键词规则兜底中）；稍后重试或转人工
}
