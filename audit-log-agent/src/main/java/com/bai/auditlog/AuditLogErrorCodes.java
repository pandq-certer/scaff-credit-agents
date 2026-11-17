package com.bai.auditlog;

/**
 * 审计留痕原子错误码段（提示词库 A-32 条目，原文档码表逐条保留）
 */
public final class AuditLogErrorCodes {

    private AuditLogErrorCodes() {}

    public static final String PARAM_INVALID = "ALG_1001";    // 参数缺失/事件格式非法；校验后重试
    public static final String TOKEN_INVALID = "ALG_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "ALG_1003";    // 无查询/导出权限（含跨机构）；申请权限
    public static final String NOT_FOUND = "ALG_1004";        // 日志/导出不存在；校验参数
    public static final String BATCH_LIMIT = "ALG_1005";      // 批量超限；分批提交
    public static final String EVENT_REQUIRED = "ALG_1006";   // 事件缺必填字段/策略拒绝；修正输入
    public static final String RATE_LIMITED = "ALG_1007";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "ALG_2000";   // 审计服务内部错误；记录 requestId 上报
    public static final String STORAGE_DEGRADED = "ALG_2001"; // 审计存储不可用（降级中）；稍后重试（MQ 暂存不丢失）
}
