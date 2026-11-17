package com.bai.notify;

/**
 * 消息通知原子错误码段（提示词库 A-30 条目，原文档码表逐条保留）
 */
public final class NotifyErrorCodes {

    private NotifyErrorCodes() {}

    public static final String PARAM_INVALID = "NTF_1001";   // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "NTF_1002";   // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "NTF_1003";   // 无发送权限（含跨机构群发）；申请权限
    public static final String TEMPLATE_NOT_FOUND = "NTF_1004"; // 模板/渠道/记录不存在；校验参数
    public static final String RECEIPT_CONFLICT = "NTF_1005"; // 回执幂等冲突；刷新后重试
    public static final String OPTOUT_OR_RENDER_FAIL = "NTF_1006"; // 退订拦截/渠道停用/渲染失败；修正输入
    public static final String RATE_LIMITED = "NTF_1007";    // 触发频控；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "NTF_2000";  // 通知服务内部错误；记录 requestId 上报
    public static final String CHANNEL_UNAVAILABLE = "NTF_2001"; // 渠道不可用（降级中）；稍后重试或降级次优渠道
}
