package com.bai.semantic;

/**
 * 语义匹配原子错误码段（提示词库 A-11 条目，原文档码表逐条保留）
 */
public final class SemanticErrorCodes {

    private SemanticErrorCodes() {}

    public static final String PARAM_INVALID = "SEM_1001";     // 参数缺失/文本非法；校验后重试
    public static final String TOKEN_INVALID = "SEM_1002";     // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "SEM_1003";     // 无调用权限；申请权限
    public static final String NOT_FOUND = "SEM_1004";         // taskId/语料库不存在；校验参数
    public static final String TEXT_TOO_LONG = "SEM_1005";     // 文本超长/批量超 100000 对；截断或分批
    public static final String EMPTY_TEXT = "SEM_1006";        // 空文本/纯符号；修正输入
    public static final String RATE_LIMITED = "SEM_1007";      // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "SEM_2000";    // 服务内部错误；记录 requestId 上报
    public static final String MODEL_UNAVAILABLE = "SEM_2001"; // 模型/索引服务不可用（降级中）；稍后重试
}
