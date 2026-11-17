package com.bai.quality;

/**
 * 数据质量治理原子错误码段（提示词库 BIA-DTA-022 条目，码表逐条保留）
 */
public final class DataQualityErrorCodes {

    private DataQualityErrorCodes() {}

    public static final String PARAM_INVALID = "DQL_1001";      // 参数缺失/非法（assetId/asOf 格式 yyyy-MM-dd/ruleIds）；校验后重试
    public static final String TOKEN_INVALID = "DQL_1002";      // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "DQL_1003";      // 无调用权限；申请权限
    public static final String NOT_FOUND = "DQL_1004";          // ruleId 不存在；校验参数
    public static final String OVER_LIMIT = "DQL_1005";         // ruleIds 超 50/批量超 200 规则；拆分提交
    public static final String ASSET_NOT_REGISTERED = "DQL_1006"; // 资产未注册质量白名单；先登记资产
    public static final String RATE_LIMITED = "DQL_1007";       // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "DQL_2000";     // 质检引擎内部错误；记录 requestId 上报
    public static final String SERVICE_UNAVAILABLE = "DQL_2001"; // 质检服务不可用（降级中）；稍后重试
}
