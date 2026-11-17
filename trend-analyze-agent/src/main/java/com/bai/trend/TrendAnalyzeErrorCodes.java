package com.bai.trend;

/**
 * 趋势分析原子错误码段（提示词库 BIA-DTA-024 条目，码表逐条保留）
 */
public final class TrendAnalyzeErrorCodes {

    private TrendAnalyzeErrorCodes() {}

    public static final String PARAM_INVALID = "TDA_1001";      // 参数缺失/非法（seriesId 与 points 二选一、window 格式、model 枚举）；校验后重试
    public static final String TOKEN_INVALID = "TDA_1002";      // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "TDA_1003";      // 无调用权限；申请权限
    public static final String SERIES_NOT_FOUND = "TDA_1004";   // 序列未注册；校验 seriesId
    public static final String OVER_LIMIT = "TDA_1005";         // points 超 10000 点；分批提交
    public static final String DATA_INSUFFICIENT = "TDA_1006";  // 样本不足（< window 点数）/horizon 超 12；调整参数
    public static final String RATE_LIMITED = "TDA_1007";       // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "TDA_2000";     // 算法引擎内部错误；记录 requestId 上报
    public static final String SERVICE_UNAVAILABLE = "TDA_2001"; // 分析服务不可用（降级中）；稍后重试
}
