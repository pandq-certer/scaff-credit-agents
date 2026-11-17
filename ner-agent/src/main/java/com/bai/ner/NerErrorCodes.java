package com.bai.ner;

/**
 * 实体抽取原子错误码段（提示词库 A-08 条目，原文档码表逐条保留）
 */
public final class NerErrorCodes {

    private NerErrorCodes() {}

    public static final String PARAM_INVALID = "NER_1001";     // 参数缺失/文本非法；校验后重试
    public static final String TOKEN_INVALID = "NER_1002";     // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "NER_1003";     // 无调用权限；申请权限
    public static final String NOT_FOUND = "NER_1004";         // taskId/类型不存在；校验参数
    public static final String TEXT_TOO_LONG = "NER_1005";     // 文本超 20000 字/批量超 100000 条；截断或分批
    public static final String EMPTY_TEXT = "NER_1006";        // 空文本/纯符号；修正输入
    public static final String RATE_LIMITED = "NER_1007";      // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "NER_2000";    // 服务内部错误；记录 requestId 上报
    public static final String MODEL_UNAVAILABLE = "NER_2001"; // 模型服务不可用（词典+规则兜底中）；稍后重试
}
