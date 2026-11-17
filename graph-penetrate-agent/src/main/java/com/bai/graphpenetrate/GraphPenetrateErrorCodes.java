package com.bai.graphpenetrate;

/**
 * 图谱穿透原子错误码段（提示词库 A-45 条目，原文档码表逐条保留）
 */
public final class GraphPenetrateErrorCodes {

    private GraphPenetrateErrorCodes() {}

    public static final String PARAM_INVALID = "GPT_1001";    // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "GPT_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "GPT_1003";    // 无穿透权限/批量管理越权；申请权限
    public static final String NOT_FOUND = "GPT_1004";        // 实体/记录不存在（未对齐）；校验参数
    public static final String CONFLICT = "GPT_1005";         // 重复提交/任务冲突；修正后重试
    public static final String TOO_LONG = "GPT_1006";         // 批量/路径超长；缩减输入
    public static final String DEPTH_INVALID = "GPT_1007";    // 深度/方向非法；修正输入
    public static final String RATE_LIMITED = "GPT_1008";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "GPT_2000";   // 图计算服务内部错误；记录 requestId 上报
    public static final String GRAPH_DEGRADED = "GPT_2001";   // 图谱服务不可用（降级中）；缓存图兜底
}
