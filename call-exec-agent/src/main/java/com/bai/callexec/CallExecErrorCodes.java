package com.bai.callexec;

/**
 * 调用执行原子错误码段（提示词库 A-38 条目，原文档码表逐条保留）
 */
public final class CallExecErrorCodes {

    private CallExecErrorCodes() {}

    public static final String PARAM_INVALID = "CEX_1001";     // 参数缺失/非法；调用方校验参数后重试
    public static final String TOKEN_INVALID = "CEX_1002";     // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "CEX_1003";     // 无调用权限/白名单外；申请权限，P0 资金类需双重授权
    public static final String NOT_FOUND = "CEX_1004";         // 接口定义/历史不存在；校验参数
    public static final String CONFLICT = "CEX_1005";          // 定义名冲突/版本冲突；修正后重试
    public static final String TOO_LONG = "CEX_1006";          // 参数超长；缩减输入
    public static final String CONTRACT_INVALID = "CEX_1007";  // 参数契约不符/目标服务不可达；修正输入
    public static final String RATE_LIMITED = "CEX_1008";      // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "CEX_2000";    // 执行服务内部错误；记录 requestId 上报
    public static final String TARGET_DEGRADED = "CEX_2001";   // 目标服务不可用（降级中）；稍后重试，P2 走降级结果
}
