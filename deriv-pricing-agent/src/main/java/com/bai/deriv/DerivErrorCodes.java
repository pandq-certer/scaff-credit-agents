package com.bai.deriv;

/**
 * 衍生品定价原子错误码段（BIA-CAL-060 条目）
 */
public final class DerivErrorCodes {

    private DerivErrorCodes() {}

    public static final String PARAM_INVALID = "DERIV_1001";       // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "DERIV_1002";       // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "DERIV_1003";       // 无定价权限；申请权限
    public static final String NOT_FOUND = "DERIV_1004";           // 合约/记录不存在；校验参数
    public static final String DUPLICATE_CONFLICT = "DERIV_1005";  // 重复提交/合约要素冲突；使用原 requestId 查询
    public static final String ELEMENT_TOO_LONG = "DERIV_1006";    // 合约要素超长；缩减输入
    public static final String ELEMENT_ILLEGAL = "DERIV_1007";     // 合约类型/方向/期限非法；修正输入
    public static final String RATE_LIMITED = "DERIV_1008";        // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "DERIV_2000";      // 定价服务内部错误；记录 requestId 上报
    public static final String MARKET_STALE = "DERIV_2001";        // 市场数据陈旧/不可用（降级标记 STALE）；确认数据源
    // 以下按设计文档 4.9 补充（前缀沿用 DERIV_，语义与 4.9 错误码表逐条对齐）
    public static final String CURVE_NOT_FOUND = "DERIV_4003";     // 曲线/波动率版本不存在；指定当前生效版本
    public static final String MODEL_DIVERGED = "DERIV_4102";      // 定价模型收敛失败；自动切换备用模型/参数
    public static final String ARBITRAGE_VIOLATION = "DERIV_4103"; // 无套利检查不通过；阻止曲线/曲面发布
    public static final String ENGINE_OVERLOAD = "DERIV_5002";     // 计算引擎过载；限流退避重试
}
