package com.bai.valuation;

/**
 * 资产估值原子错误码段（提示词库 A-57 条目，原文档码表逐条保留）
 */
public final class ValuationErrorCodes {

    private ValuationErrorCodes() {}

    public static final String PARAM_INVALID = "VAL_1001";       // 参数缺失/非法（含估值结果为负）；校验后重试
    public static final String TOKEN_INVALID = "VAL_1002";       // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "VAL_1003";       // 无估值权限或反馈越权；申请权限
    public static final String NOT_FOUND = "VAL_1004";           // 标的/记录不存在；校验参数
    public static final String DATA_CONFLICT = "VAL_1005";       // 重复提交/市场数据冲突；使用原 requestId 查询
    public static final String ELEMENT_TOO_LONG = "VAL_1006";    // 标的要素超长；缩减输入
    public static final String ELEMENT_ILLEGAL = "VAL_1007";     // 资产类型/方法非法；修正输入
    public static final String RATE_LIMITED = "VAL_1008";        // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "VAL_2000";      // 估值服务内部错误；记录 requestId 上报
    public static final String MARKET_UNAVAILABLE = "VAL_2001";  // 市场数据/估值服务不可用（降级中）；稍后重试
}
