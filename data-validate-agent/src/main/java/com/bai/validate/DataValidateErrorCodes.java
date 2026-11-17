package com.bai.validate;

/**
 * 数据校验原子错误码段（提示词库 BIA-DTA-021 条目，码表逐条保留）
 */
public final class DataValidateErrorCodes {

    private DataValidateErrorCodes() {}

    public static final String PARAM_INVALID = "DVA_1001";      // 参数缺失/非法（templateId 与 rules 二选一、data 必填、data 序列化超 512KB）；校验后重试
    public static final String TOKEN_INVALID = "DVA_1002";      // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "DVA_1003";      // 无调用权限；申请权限
    public static final String NOT_FOUND = "DVA_1004";          // templateId/rules 中规则 ID 不存在；校验参数
    public static final String OVER_LIMIT = "DVA_1005";         // 批量超 5000 条；分批提交
    public static final String RULE_INVALID = "DVA_1006";       // 规则定义非法（算子/阈值缺失）；修正规则
    public static final String RATE_LIMITED = "DVA_1007";       // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "DVA_2000";     // 校验引擎内部错误；记录 requestId 上报
    public static final String SERVICE_UNAVAILABLE = "DVA_2001"; // 校验服务不可用（降级中）；稍后重试
}
