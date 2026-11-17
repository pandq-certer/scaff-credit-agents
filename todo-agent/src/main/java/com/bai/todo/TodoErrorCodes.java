package com.bai.todo;

/**
 * 任务待办原子错误码段（提示词库 A-31 条目，原文档码表逐条保留）
 */
public final class TodoErrorCodes {

    private TodoErrorCodes() {}

    public static final String PARAM_INVALID = "TDO_1001";    // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "TDO_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "TDO_1003";    // 非归属人/无操作权限；申请权限或转办
    public static final String NOT_FOUND = "TDO_1004";        // 待办/记录不存在；校验参数
    public static final String STATE_CONFLICT = "TDO_1005";   // 重复提交/状态冲突（重复领取等）；修正后重试
    public static final String RULE_VIOLATED = "TDO_1006";    // 业务规则不满足（source 未注册/终态操作/受理人缺失）；修正输入
    public static final String RATE_LIMITED = "TDO_1007";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "TDO_2000";   // 待办服务内部错误；记录 requestId 上报
    public static final String DEPEND_DEGRADED = "TDO_2001";  // 通知/待办下游不可用（降级中）；本地暂存
}
