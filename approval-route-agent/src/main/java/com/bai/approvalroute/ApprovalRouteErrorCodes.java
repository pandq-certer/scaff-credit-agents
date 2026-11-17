package com.bai.approvalroute;

/**
 * 审批流转原子错误码段（提示词库 A-29 条目，原文档码表逐条保留）
 */
public final class ApprovalRouteErrorCodes {

    private ApprovalRouteErrorCodes() {}

    public static final String PARAM_INVALID = "APR_1001";    // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "APR_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NOT_APPROVER = "APR_1003";     // 无审批权限/非审批人；申请权限
    public static final String NOT_FOUND = "APR_1004";        // 任务/审批链不存在；校验参数
    public static final String STATE_CONFLICT = "APR_1005";   // 状态冲突（重复审批/终态操作）；刷新后重试
    public static final String RULE_UNMATCHED = "APR_1006";   // 路由规则未匹配/委托冲突；修正输入
    public static final String RATE_LIMITED = "APR_1007";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "APR_2000";   // 审批服务内部错误；记录 requestId 上报
    public static final String DEPENDENCY_DEGRADED = "APR_2001"; // 审批依赖不可用（降级中）；稍后重试
}
