package com.bai.codegen;

/**
 * 代码生成原子错误码段（设计文档表 4-3 码表逐条保留，CDG 前缀）
 */
public final class CodeGenErrorCodes {

    private CodeGenErrorCodes() {}

    public static final String PARAM_INVALID = "CDG_1001";        // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "CDG_1002";        // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "CDG_1003";        // 无生成/模板权限；申请权限
    public static final String NOT_FOUND = "CDG_1004";            // 模板/历史不存在；校验参数
    public static final String VERSION_CONFLICT = "CDG_1005";     // 模板名冲突/版本冲突；修正后重试
    public static final String INPUT_TOO_LONG = "CDG_1006";       // 需求/代码超长；缩减输入
    public static final String GENERATION_REJECT = "CDG_1007";    // 语法未过/安全扫描未过/类型非法；修正输入
    public static final String RATE_LIMITED = "CDG_1008";         // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "CDG_2000";       // 生成服务内部错误；记录 requestId 上报
    public static final String LLM_DEGRADED = "CDG_2001";         // LLM 不可用（降级中）；模板模式兜底
}
