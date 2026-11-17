package com.bai.compliance;

/**
 * 合规审查原子错误码段（提示词库 A-49 条目，原文档码表逐条保留）
 */
public final class ComplianceErrorCodes {

    private ComplianceErrorCodes() {}

    public static final String PARAM_INVALID = "CMP_1001";    // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "CMP_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "CMP_1003";    // 无审查权限/规则管理越权；申请权限
    public static final String NOT_FOUND = "CMP_1004";        // 规则/结论不存在；校验参数
    public static final String CONFLICT = "CMP_1005";         // 重复提交/规则冲突；修正后重试
    public static final String TOO_LONG = "CMP_1006";         // 业务要素超长；缩减输入
    public static final String SCENE_INVALID = "CMP_1007";    // 场景非法/规则版本非法；修正输入
    public static final String RATE_LIMITED = "CMP_1008";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "CMP_2000";   // 审查服务内部错误；记录 requestId 上报
    public static final String RULE_DEGRADED = "CMP_2001";    // 规则/知识服务不可用（降级中）；MANUAL 兜底
}
