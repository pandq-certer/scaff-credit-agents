package com.bai.uiops;

/**
 * 界面操作原子错误码段（提示词库 A-42 条目，原文档码表逐条保留）
 */
public final class UiOpsErrorCodes {

    private UiOpsErrorCodes() {}

    public static final String PARAM_INVALID = "UIO_1001";     // 参数缺失/非法（action 非法）；校验后重试
    public static final String TOKEN_INVALID = "UIO_1002";     // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "UIO_1003";     // 无操作权限/沙箱外/P0 双人确认缺失；申请权限，回滚需 P0 双人授权
    public static final String NOT_FOUND = "UIO_1004";         // 元素/会话/记录不存在；校验参数
    public static final String CONFLICT = "UIO_1005";          // 交易状态冲突/重复提交；修正后重试（txnRef 幂等拦截）
    public static final String TOO_LONG = "UIO_1006";          // 表单/指令超长；缩减输入
    public static final String VALIDATION_DIFF = "UIO_1007";   // 表单校验未过/一致性差异；修正输入或人工介入
    public static final String RATE_LIMITED = "UIO_1008";      // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "UIO_2000";    // 操作服务内部错误；记录 requestId 上报
    public static final String PAGE_UNAVAILABLE = "UIO_2001";  // 页面/后台服务不可用（降级中）；转 API 通道执行
}
