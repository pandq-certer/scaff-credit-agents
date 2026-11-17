package com.bai.sentiment;

/**
 * 情绪分析原子错误码段（提示词库 A-10 条目，原文档码表逐条保留）
 */
public final class SentimentErrorCodes {

    private SentimentErrorCodes() {}

    public static final String PARAM_INVALID = "SEN_1001";     // 参数缺失/文本非法；校验后重试
    public static final String TOKEN_INVALID = "SEN_1002";     // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "SEN_1003";     // 无调用权限；申请权限
    public static final String TASK_NOT_FOUND = "SEN_1004";    // taskId 不存在；校验参数
    public static final String TEXT_TOO_LONG = "SEN_1005";     // 文本超 5000 字/批量超 100000 条；截断或分批
    public static final String EMPTY_TEXT = "SEN_1006";        // 空文本/纯符号；修正输入
    public static final String RATE_LIMITED = "SEN_1007";      // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "SEN_2000";    // 服务内部错误；记录 requestId 上报
    public static final String MODEL_UNAVAILABLE = "SEN_2001"; // 模型服务不可用（信号规则兜底中）；稍后重试
}
