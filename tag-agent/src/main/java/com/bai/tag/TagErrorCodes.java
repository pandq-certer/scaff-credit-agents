package com.bai.tag;

/**
 * 智能打标原子错误码段（提示词库 A-13 条目，原文档码表逐条保留）
 */
public final class TagErrorCodes {

    private TagErrorCodes() {}

    public static final String PARAM_INVALID = "TAG_1001";     // 参数缺失/text 非法；校验后重试
    public static final String TOKEN_INVALID = "TAG_1002";     // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "TAG_1003";     // 无调用权限；申请权限
    public static final String NOT_FOUND = "TAG_1004";         // taskId/体系版本不存在；校验参数
    public static final String TEXT_TOO_LONG = "TAG_1005";     // 文本超 5000 字/批量超 100000 条；截断或分批
    public static final String EMPTY_TEXT = "TAG_1006";        // 空文本/纯符号/非法组；修正输入
    public static final String RATE_LIMITED = "TAG_1007";      // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "TAG_2000";    // 服务内部错误；记录 requestId 上报
    public static final String MODEL_UNAVAILABLE = "TAG_2001"; // 模型服务不可用（规则引擎兜底中）；稍后重试
}
