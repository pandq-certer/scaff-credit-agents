package com.bai.kprocess;

/**
 * 知识加工原子错误码段（提示词库 A-14 条目，原文档码表逐条保留）
 */
public final class KnowledgeProcessErrorCodes {

    private KnowledgeProcessErrorCodes() {}

    public static final String PARAM_INVALID = "KNO_1001";    // 参数缺失/text 非法；校验后重试
    public static final String TOKEN_INVALID = "KNO_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "KNO_1003";    // 无调用权限；申请权限
    public static final String NOT_FOUND = "KNO_1004";        // taskId/docId 不存在；校验参数
    public static final String OVER_LIMIT = "KNO_1005";       // 文本超 50000 字/批量超 1000 篇；截断或分批
    public static final String INVALID_INPUT = "KNO_1006";    // 空文本/非法知识类型；修正输入
    public static final String RATE_LIMITED = "KNO_1007";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "KNO_2000";   // 服务内部错误；记录 requestId 上报
    public static final String MODEL_UNAVAILABLE = "KNO_2001"; // 模型服务不可用（降级中）；规则条目化兜底
}
