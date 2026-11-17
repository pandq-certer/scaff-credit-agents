package com.bai.orchestrate;

/**
 * 多智能体编排原子错误码段（提示词库 A-41 条目，原文档码表逐条保留）
 */
public final class OrchestrateErrorCodes {

    private OrchestrateErrorCodes() {}

    public static final String PARAM_INVALID = "AGO_1001";   // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "AGO_1002";   // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "AGO_1003";   // 无编排/介入权限；申请权限，P0 介入需双人
    public static final String NOT_FOUND = "AGO_1004";       // 计划/实例/模板不存在；校验参数
    public static final String STATE_CONFLICT = "AGO_1005";  // 计划冲突/状态冲突；修正后重试
    public static final String GOAL_TOO_LONG = "AGO_1006";   // 目标/输入超长；缩减输入
    public static final String DAG_INVALID = "AGO_1007";     // DAG 有环/能力不可用/步骤非法；修正输入
    public static final String RATE_LIMITED = "AGO_1008";    // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "AGO_2000";  // 编排服务内部错误；记录 requestId 上报
    public static final String ENGINE_DEGRADED = "AGO_2001"; // 编排引擎不可用（降级中）；LLM 规划不可用时模板编排兜底
}
