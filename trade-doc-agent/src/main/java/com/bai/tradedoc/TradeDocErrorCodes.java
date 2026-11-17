package com.bai.tradedoc;

/**
 * 贸易单证原子错误码段（BIA-CAL-063 条目）
 */
public final class TradeDocErrorCodes {

    private TradeDocErrorCodes() {}

    public static final String PARAM_INVALID = "TRD_1001";       // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "TRD_1002";       // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "TRD_1003";       // 无审单权限；申请权限
    public static final String NOT_FOUND = "TRD_1004";           // 信用证/单据不存在；校验 lcNo
    public static final String DUPLICATE_CONFLICT = "TRD_1005";  // 重复提交/审单要素冲突；使用原 requestId 查询
    public static final String ELEMENT_TOO_LONG = "TRD_1006";    // 单证要素超长；缩减输入
    public static final String ELEMENT_ILLEGAL = "TRD_1007";     // 单据类型/业务类型非法；修正输入
    public static final String RATE_LIMITED = "TRD_1008";        // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "TRD_2000";      // 审单服务内部错误；记录 requestId 上报
    public static final String RULE_NOT_FOUND = "TRD_4101";       // 审单规则版本不存在；校验 ruleVersion
    public static final String CHECK_OVERDUE = "TRD_4102";        // 审单超期（单据日期距今超 5 日），阻断；说明原因后重审
    public static final String ELEMENTS_INCONSISTENT = "TRD_4002"; // 单据要素不完整/不一致；补齐单据要素（ocr 重识别）
    public static final String FRAUD_RISK = "TRD_4103";           // 单据欺诈风险 HIGH；阻断并转反欺诈调查
    public static final String SANCTION_HIT = "TRD_4104";         // 制裁名单命中，阻断；转合规人工处置
}
