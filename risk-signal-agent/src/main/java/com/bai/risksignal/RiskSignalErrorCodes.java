package com.bai.risksignal;

/**
 * 风险信号识别原子错误码段（提示词库 A-44 条目，原文档码表逐条保留）
 */
public final class RiskSignalErrorCodes {

    private RiskSignalErrorCodes() {}

    public static final String PARAM_INVALID = "RSG_1001";    // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "RSG_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "RSG_1003";    // 无信号权限；申请权限
    public static final String NOT_FOUND = "RSG_1004";        // 记录/信号条目不存在；校验参数
    public static final String CONFLICT = "RSG_1005";         // 重复提交/规则冲突；修正后重试
    public static final String TOO_LONG = "RSG_1006";         // 事件/批量超长；缩减输入
    public static final String SOURCE_INVALID = "RSG_1007";   // 信号源非法；修正输入
    public static final String RATE_LIMITED = "RSG_1008";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "RSG_2000";   // 检测服务内部错误；记录 requestId 上报
    public static final String SOURCE_DEGRADED = "RSG_2001";  // 信号/数据服务不可用（降级中）；缓存信号兜底
}
