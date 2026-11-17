package com.bai.bill;

/**
 * 票据业务原子错误码段（BIA-CAL-062 条目）
 */
public final class BillErrorCodes {

    private BillErrorCodes() {}

    public static final String PARAM_INVALID = "BILL_1001";       // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "BILL_1002";       // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "BILL_1003";       // 无贴现权限；申请权限
    public static final String NOT_FOUND = "BILL_1004";           // 票据不存在；校验 billNo
    public static final String DUPLICATE_CONFLICT = "BILL_1005";  // 重复提交/贴现要素冲突；使用原 requestId 查询
    public static final String ELEMENT_TOO_LONG = "BILL_1006";    // 票据要素超长；缩减输入
    public static final String ELEMENT_ILLEGAL = "BILL_1007";     // 票据类型/利率非法；修正输入
    public static final String RATE_LIMITED = "BILL_1008";        // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "BILL_2000";      // 票据服务内部错误；记录 requestId 上报
    public static final String BILL_STATUS_ABNORMAL = "BILL_4101"; // 票据状态异常（已到期/冻结/挂失止付），贴现阻断；核实票据状态
    public static final String DISCOUNT_FLOOR_BREACH = "BILL_4103"; // 贴现实付低于票面 90% 下限（贴现率过高），阻断；调整贴现率

    // ---- 4.10 业务码段（设计文档表 4-4 补齐）----
    public static final String PARAM_CHECK_FAILED = "BILL_4001";   // 请求参数校验失败；按字段错误明细修正
    public static final String ELEMENT_INCONSISTENT = "BILL_4002"; // 票面要素不完整/不一致；补齐票面要素（ocr 重识别）
    public static final String BILL_NOT_REGISTERED = "BILL_4003";  // 票据不存在/未登记；检查票据号码
    public static final String IDEMPOTENT_CONFLICT = "BILL_4004";  // 幂等冲突（requestId 重复且参数不同）；使用原 requestId 查询
    public static final String ENDORSE_ABNORMAL = "BILL_4102";     // 背书不连续/限制性背书；阻断贴现/质押
    public static final String LIMIT_BREACH = "BILL_4103";         // 敞口/质押率超限；阻断并提示调整
    public static final String PBC_UNAVAILABLE = "BILL_4104";      // 票交所不可用（降级）；本地验真+人工复核标记
    public static final String CALC_INTERNAL_ERROR = "BILL_5001";  // 内部计算错误；返回错误码并留痕
    public static final String SERVICE_OVERLOAD = "BILL_5002";     // 计算服务过载；限流退避重试
}
