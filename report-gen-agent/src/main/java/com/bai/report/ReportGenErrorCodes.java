package com.bai.report;

/**
 * 报表生成原子错误码段（提示词库 BIA-DTA-023 条目，码表逐条保留）
 */
public final class ReportGenErrorCodes {

    private ReportGenErrorCodes() {}

    public static final String PARAM_INVALID = "RPG_1001";      // 参数缺失/非法（templateId/params.orgId|date|granularity/format 枚举）；校验后重试
    public static final String TOKEN_INVALID = "RPG_1002";      // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "RPG_1003";      // 无调用权限；申请权限
    public static final String TEMPLATE_NOT_FOUND = "RPG_1004"; // 模板不存在或未发布；校验 templateId
    public static final String OVER_LIMIT = "RPG_1005";         // 批量超 200 张；分批提交
    public static final String RENDER_FAILED = "RPG_1006";      // 渲染/三校验失败（勾稽不平等）；修正数据
    public static final String RATE_LIMITED = "RPG_1007";       // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "RPG_2000";     // 渲染引擎内部错误；记录 requestId 上报
    public static final String SERVICE_UNAVAILABLE = "RPG_2001"; // 报表服务不可用（降级中）；稍后重试
}
