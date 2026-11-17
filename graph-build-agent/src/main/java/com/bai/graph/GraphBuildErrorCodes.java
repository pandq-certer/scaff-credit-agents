package com.bai.graph;

/**
 * 图谱构建原子错误码段（提示词库 BIA-DTA-026 条目，码表逐条保留）
 */
public final class GraphBuildErrorCodes {

    private GraphBuildErrorCodes() {}

    public static final String PARAM_INVALID = "GRB_1001";      // 参数缺失/非法（mode 须为 FULL/INCREMENTAL、events 必填）；校验后重试
    public static final String TOKEN_INVALID = "GRB_1002";      // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "GRB_1003";      // 无调用权限；申请权限
    public static final String JOB_NOT_FOUND = "GRB_1004";      // jobId 不存在；校验参数
    public static final String OVER_LIMIT = "GRB_1005";         // events 超 10000 条；分批提交
    public static final String DATA_INVALID = "GRB_1006";       // dataVersion 缺失/格式非法（yyyyMMdd-HHmm）、relationTypes 未知；修正后重试
    public static final String RATE_LIMITED = "GRB_1007";       // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "GRB_2000";     // 图构建引擎内部错误；记录 requestId 上报
    public static final String SERVICE_UNAVAILABLE = "GRB_2001"; // 图服务不可用（降级中）；稍后重试
}
