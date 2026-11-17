package com.bai.finstmt;

/**
 * 财务报表分析原子错误码段（BIA-CAL-065 条目）
 */
public final class FinStmtErrorCodes {

    private FinStmtErrorCodes() {}

    public static final String PARAM_INVALID = "FST_1001";       // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "FST_1002";       // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "FST_1003";       // 无分析权限；申请权限
    public static final String NOT_FOUND = "FST_1004";           // 主体/报表记录不存在；校验 entityId
    public static final String DUPLICATE_CONFLICT = "FST_1005";  // 重复提交/报表要素冲突；使用原 requestId 查询
    public static final String ELEMENT_TOO_LONG = "FST_1006";    // 报表要素超长；缩减输入
    public static final String ELEMENT_ILLEGAL = "FST_1007";     // 报表类型/科目非法；修正输入
    public static final String RATE_LIMITED = "FST_1008";        // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "FST_2000";      // 分析服务内部错误；记录 requestId 上报
    public static final String SCENARIO_ILLEGAL = "FST_4001";     // 分析场景非法（非尽调/复审/年检/贷后）；修正 scenario
    public static final String REPORT_DATA_INCOMPLETE = "FST_4002"; // 报表数据不完整/期次缺失（至少两期）；补齐报表
    public static final String ENTITY_NOT_FOUND = "FST_4003";       // 企业/报表不存在；检查主体标识
    public static final String BALANCE_UNBALANCED = "FST_4101";   // 勾稽不平：总资产 ≠ 负债 + 权益；核对报表数据
    public static final String PARSE_QUALITY_LOW = "FST_4102";    // 解析质量过低（accuracy<95%）；重新识别/人工补录
    public static final String FRAUD_HIGH = "FST_4103";           // 造假信号 HIGH 阻断，不返回自动结论；转人工审核
    public static final String BENCHMARK_NOT_FOUND = "FST_4104";  // 基准版本不存在；指定当前生效版本
}
