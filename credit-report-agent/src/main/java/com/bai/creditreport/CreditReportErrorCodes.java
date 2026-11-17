package com.bai.creditreport;

/**
 * 征信管理原子错误码段（提示词库 A-52 条目，原文档码表逐条保留）
 */
public final class CreditReportErrorCodes {

    private CreditReportErrorCodes() {}

    public static final String PARAM_INVALID = "CRR_1001";   // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "CRR_1002";   // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "CRR_1003";   // 无查询权限/用途越权；申请权限
    public static final String NOT_FOUND = "CRR_1004";       // 授权/报告/异议不存在；校验参数
    public static final String STATE_CONFLICT = "CRR_1005";  // 重复提交/授权冲突；修正后重试
    public static final String PAYLOAD_TOO_LONG = "CRR_1006"; // 报文/批量超长；缩减输入
    public static final String ACTION_INVALID = "CRR_1007";  // action/用途非法；修正输入
    public static final String RATE_LIMITED = "CRR_1008";    // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "CRR_2000";  // 征信服务内部错误；记录 requestId 上报
    public static final String PBC_UNAVAILABLE = "CRR_2001"; // 人行接口不可用（降级中）；排队重试，稍后重试
}
