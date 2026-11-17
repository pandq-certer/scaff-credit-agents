package com.bai.kretrieve;

/**
 * 知识检索原子错误码段（提示词库 A-15 条目，原文档码表逐条保留）
 */
public final class KnowledgeRetrieveErrorCodes {

    private KnowledgeRetrieveErrorCodes() {}

    public static final String PARAM_INVALID = "KRE_1001";    // 参数缺失/query 非法；校验后重试
    public static final String TOKEN_INVALID = "KRE_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "KRE_1003";    // 无调用权限；申请权限
    public static final String NOT_FOUND = "KRE_1004";        // taskId/库不存在；校验参数
    public static final String OVER_LIMIT = "KRE_1005";       // query 超 500 字/批量超 10000 条；截断或分批
    public static final String INVALID_INPUT = "KRE_1006";    // 空查询/非法库/非法通道；修正输入
    public static final String RATE_LIMITED = "KRE_1007";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "KRE_2000";   // 服务内部错误；记录 requestId 上报
    public static final String INDEX_UNAVAILABLE = "KRE_2001"; // 索引/图谱服务不可用（降级中）；降级另一路
}
