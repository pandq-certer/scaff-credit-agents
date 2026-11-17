package com.bai.textgen;

/**
 * 文本生成原子错误码段（提示词库 A-35 条目，原文档码表逐条保留）
 */
public final class TextGenErrorCodes {

    private TextGenErrorCodes() {}

    public static final String PARAM_INVALID = "TXG_1001";        // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "TXG_1002";        // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "TXG_1003";        // 无生成/模板权限；申请权限
    public static final String TEMPLATE_NOT_FOUND = "TXG_1004";   // 模板/历史不存在；校验参数
    public static final String VERSION_CONFLICT = "TXG_1005";     // 模板名冲突/版本冲突；修正后重试
    public static final String INPUT_TOO_LONG = "TXG_1006";       // 要点/文本超长（points 超 20 条）；缩减输入
    public static final String COMPLIANCE_REJECT = "TXG_1007";    // 变量缺失/合规未过/风格非法；修正输入
    public static final String RATE_LIMITED = "TXG_1008";         // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "TXG_2000";       // 生成服务内部错误；记录 requestId 上报
    public static final String LLM_DEGRADED = "TXG_2001";         // LLM 不可用（降级中）；模板模式兜底
}
