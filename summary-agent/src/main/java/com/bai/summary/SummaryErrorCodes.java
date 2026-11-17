package com.bai.summary;

/**
 * 文本摘要原子错误码段（提示词库 A-12 条目，原文档码表逐条保留）
 */
public final class SummaryErrorCodes {

    private SummaryErrorCodes() {}

    public static final String PARAM_INVALID = "SUM_1001";     // 参数缺失/text 非法或 mode 非法；校验后重试
    public static final String TOKEN_INVALID = "SUM_1002";     // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "SUM_1003";     // 无调用权限；申请权限
    public static final String TASK_NOT_FOUND = "SUM_1004";    // taskId 不存在；校验参数
    public static final String TEXT_TOO_LONG = "SUM_1005";     // 文本超 20000 字/批量超 5000 篇；截断或分批
    public static final String EMPTY_TEXT = "SUM_1006";        // 空文本/纯符号；修正输入
    public static final String RATE_LIMITED = "SUM_1007";      // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "SUM_2000";    // 服务内部错误；记录 requestId 上报
    public static final String MODEL_UNAVAILABLE = "SUM_2001"; // 模型服务不可用（降级中）；稍后重试或业务侧转抽取式摘要
}
