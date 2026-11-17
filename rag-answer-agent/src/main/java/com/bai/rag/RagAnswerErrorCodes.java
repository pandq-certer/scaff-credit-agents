package com.bai.rag;

/**
 * 知识问答原子错误码段（提示词库 A-16 条目，原文档码表逐条保留）
 */
public final class RagAnswerErrorCodes {

    private RagAnswerErrorCodes() {}

    public static final String PARAM_INVALID = "RAG_1001";    // 参数缺失/question 非法；校验后重试
    public static final String TOKEN_INVALID = "RAG_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "RAG_1003";    // 无调用权限；申请权限
    public static final String NOT_FOUND = "RAG_1004";        // taskId/session 不存在；校验参数
    public static final String OVER_LIMIT = "RAG_1005";       // question 超 500 字/批量超 10000 条；截断或分批
    public static final String INVALID_INPUT = "RAG_1006";    // 空问题/非法风格/上下文超 8 条；修正输入
    public static final String RATE_LIMITED = "RAG_1007";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "RAG_2000";   // 服务内部错误；记录 requestId 上报
    public static final String MODEL_UNAVAILABLE = "RAG_2001"; // 模型服务不可用（降级中）；业务侧检索结果直出 top1 卡片
}
