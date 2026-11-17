package com.bai.dataquery;

/**
 * 统一数据查询原子错误码段（提示词库 A-18 条目，原文档码表逐条保留）
 */
public final class DataQueryErrorCodes {

    private DataQueryErrorCodes() {}

    public static final String PARAM_INVALID = "DTQ_1001";      // 参数缺失/非法（含 pageSize>1000）；校验后重试
    public static final String TOKEN_INVALID = "DTQ_1002";      // Token 缺失/过期；重新获取 Token
    public static final String FORBIDDEN = "DTQ_1003";          // 无权限/越权（非白名单表/列、注入命中）；拒绝并告警
    public static final String NOT_FOUND = "DTQ_1004";          // 数据源/表/指标不存在；校验参数
    public static final String OVER_LIMIT = "DTQ_1005";         // 超行数上限 10000/批量超限；缩小范围或分批
    public static final String NL_SQL_INVALID = "DTQ_1006";     // NL 生成 SQL 校验失败/非只读；修正问题
    public static final String RATE_LIMITED = "DTQ_1007";       // 触发限流；按 Retry-After 退避
    public static final String DATASOURCE_ERROR = "DTQ_2000";   // 数据源异常；记录 requestId 上报
    public static final String SERVICE_UNAVAILABLE = "DTQ_2001"; // 数据源/服务不可用（降级中）；切只读副本
}
