package com.bai.recommend;

/**
 * 智能推荐原子错误码段（提示词库 A-59 条目，原文档码表逐条保留）
 */
public final class RecommendErrorCodes {

    private RecommendErrorCodes() {}

    public static final String PARAM_INVALID = "RCM_1001";       // 参数缺失/非法（含 topN 超界）；校验后重试
    public static final String TOKEN_INVALID = "RCM_1002";       // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "RCM_1003";       // 无推荐权限；申请权限
    public static final String NOT_FOUND = "RCM_1004";           // 客户/产品/策略不存在；校验参数
    public static final String DUPLICATE_CONFLICT = "RCM_1005";  // 重复推荐/策略冲突；使用原 requestId 查询
    public static final String CONTEXT_TOO_LONG = "RCM_1006";    // 上下文超长；缩减输入
    public static final String ELEMENT_ILLEGAL = "RCM_1007";     // 场景/算法非法；修正输入
    public static final String RATE_LIMITED = "RCM_1008";        // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "RCM_2000";      // 推荐服务内部错误；记录 requestId 上报
    public static final String MODEL_UNAVAILABLE = "RCM_2001";   // 推荐模型/画像服务不可用（降级中）；稍后重试
}
