package com.bai.fraud;

/**
 * 反欺诈原子错误码段（提示词库 A-43 条目，原文档码表逐条保留）
 */
public final class FraudErrorCodes {

    private FraudErrorCodes() {}

    public static final String PARAM_INVALID = "FRD_1001";    // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "FRD_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "FRD_1003";    // 无决策权限/名单管理越权；申请权限
    public static final String NOT_FOUND = "FRD_1004";        // 案例/样本/名单条目不存在；校验参数
    public static final String CONFLICT = "FRD_1005";         // 重复提交/名单冲突；修正后重试
    public static final String TOO_LONG = "FRD_1006";         // 特征/批量超长；缩减输入
    public static final String SCENE_INVALID = "FRD_1007";    // 场景不支持/特征非法；修正输入
    public static final String RATE_LIMITED = "FRD_1008";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "FRD_2000";   // 决策服务内部错误；记录 requestId 上报
    public static final String MODEL_DEGRADED = "FRD_2001";   // 模型/图服务不可用（降级中）；规则加图决策兜底
}
