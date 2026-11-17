package com.bai.classify;

/**
 * 文本分类原子错误码段（提示词库 A-09 条目，原文档码表逐条保留）
 */
public final class ClassifyErrorCodes {

    private ClassifyErrorCodes() {}

    public static final String PARAM_INVALID = "TXC_1001";     // 参数缺失/文本非法；校验后重试
    public static final String TOKEN_INVALID = "TXC_1002";     // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "TXC_1003";     // 无调用权限；申请权限
    public static final String TASK_NOT_FOUND = "TXC_1004";    // taskId/任务不存在；校验参数
    public static final String TEXT_TOO_LONG = "TXC_1005";     // 文本超 20000 字/批量超 100000 条；截断或分批
    public static final String EMPTY_TEXT = "TXC_1006";        // 空文本/纯符号；修正输入
    public static final String RATE_LIMITED = "TXC_1007";      // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "TXC_2000";    // 服务内部错误；记录 requestId 上报
    public static final String MODEL_UNAVAILABLE = "TXC_2001"; // 模型服务不可用（关键词规则兜底中）；稍后重试
}
