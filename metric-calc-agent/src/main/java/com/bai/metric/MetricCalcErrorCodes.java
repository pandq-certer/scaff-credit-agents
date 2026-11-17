package com.bai.metric;

/**
 * 指标计算原子错误码段（提示词库 BIA-DTA-020 条目，码表逐条保留）
 */
public final class MetricCalcErrorCodes {

    private MetricCalcErrorCodes() {}

    public static final String PARAM_INVALID = "MTC_1001";      // 参数缺失/非法（metricId/params.orgId/params.date 格式）；校验后重试
    public static final String TOKEN_INVALID = "MTC_1002";      // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "MTC_1003";      // 无调用权限；申请权限
    public static final String METRIC_NOT_FOUND = "MTC_1004";   // 指标/口径版本未注册；校验 metricId 与 version
    public static final String OVER_LIMIT = "MTC_1005";         // 批量超 2000 条；分批提交
    public static final String CALC_FAILED = "MTC_1006";        // 计算校验失败（分母为零等）；修正入参
    public static final String RATE_LIMITED = "MTC_1007";       // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "MTC_2000";     // 计算引擎内部错误；记录 requestId 上报
    public static final String SERVICE_UNAVAILABLE = "MTC_2001"; // 计算服务不可用（降级中）；稍后重试
}
