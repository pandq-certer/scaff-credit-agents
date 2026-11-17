package com.bai.limit;

/**
 * 额度计算原子错误码段（提示词库 A-56 条目，原文档码表逐条保留）
 */
public final class LimitErrorCodes {

    private LimitErrorCodes() {}

    public static final String PARAM_INVALID = "LMT_1001";       // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "LMT_1002";       // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "LMT_1003";       // 无试算权限或限额管理越权；申请权限
    public static final String NOT_FOUND = "LMT_1004";           // 客户/限额不存在；校验参数
    public static final String DUPLICATE_CONFLICT = "LMT_1005";  // 重复提交/限额冲突；使用原 requestId 查询
    public static final String ELEMENT_TOO_LONG = "LMT_1006";    // 额度要素超长；缩减输入
    public static final String ELEMENT_ILLEGAL = "LMT_1007";     // 限额口径/金额非法；修正输入
    public static final String RATE_LIMITED = "LMT_1008";        // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "LMT_2000";      // 额度服务内部错误；记录 requestId 上报
    public static final String SERVICE_UNAVAILABLE = "LMT_2001"; // 限额/占用服务不可用（降级中）；稍后重试
}
