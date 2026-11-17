package com.bai.search;

/**
 * 多源搜索原子错误码段（提示词库 A-19 条目，原文档码表逐条保留）
 */
public final class SearchErrorCodes {

    private SearchErrorCodes() {}

    public static final String PARAM_INVALID = "SEA_1001";      // 参数缺失/query 非法；校验后重试
    public static final String TOKEN_INVALID = "SEA_1002";      // Token 缺失/过期；重新获取 Token（网关统一鉴权）
    public static final String NO_PERMISSION = "SEA_1003";      // 无调用权限/源未授权；申请权限（网关统一鉴权）
    public static final String NOT_FOUND = "SEA_1004";          // taskId/快照/源不存在；校验参数
    public static final String LIMIT_EXCEEDED = "SEA_1005";     // query 超 200 字/批量超 5000 条；截断或分批
    public static final String INVALID_QUERY = "SEA_1006";      // 空查询/非法源/黑名单命中；修正输入
    public static final String RATE_LIMITED = "SEA_1007";       // 触发限流/源频控；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "SEA_2000";     // 搜索服务内部错误；记录 requestId 上报
    public static final String SOURCE_UNAVAILABLE = "SEA_2001"; // 源不可用（降级中）；稍后重试
}
