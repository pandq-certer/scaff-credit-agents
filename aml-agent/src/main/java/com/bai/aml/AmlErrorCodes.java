package com.bai.aml;

/**
 * 反洗钱原子错误码段（提示词库 A-51 条目，原文档码表逐条保留）
 */
public final class AmlErrorCodes {

    private AmlErrorCodes() {}

    public static final String PARAM_INVALID = "AML_1001";    // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "AML_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "AML_1003";    // 无监测权限/报告越权；申请权限
    public static final String NOT_FOUND = "AML_1004";        // 记录/报告不存在；校验参数
    public static final String CONFLICT = "AML_1005";         // 重复提交/报告冲突；修正后重试
    public static final String TOO_LONG = "AML_1006";         // 交易清单超长；缩减输入
    public static final String WINDOW_INVALID = "AML_1007";   // 窗口/场景非法；修正输入
    public static final String RATE_LIMITED = "AML_1008";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "AML_2000";   // 监测服务内部错误；记录 requestId 上报
    public static final String MODEL_DEGRADED = "AML_2001";   // 模型/数据服务不可用（降级中）；规则监测兜底
}
