package com.bai.accounting;

/**
 * 账务执行原子错误码段（提示词库 A-58 条目，原文档码表逐条保留）
 */
public final class AccountingErrorCodes {

    private AccountingErrorCodes() {}

    public static final String PARAM_INVALID = "ACC_1001";       // 参数缺失/非法（含借贷不平衡）；校验后重试
    public static final String TOKEN_INVALID = "ACC_1002";       // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "ACC_1003";       // 无记账权限或科目越权；申请权限
    public static final String NOT_FOUND = "ACC_1004";           // 合同/账户不存在；校验参数
    public static final String DUPLICATE_CONFLICT = "ACC_1005";  // 重复记账/凭证冲突；使用原 requestId 查询
    public static final String DATA_TOO_LONG = "ACC_1006";       // 业务数据超长；缩减输入
    public static final String ELEMENT_ILLEGAL = "ACC_1007";     // 事件/科目非法；修正输入
    public static final String RATE_LIMITED = "ACC_1008";        // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "ACC_2000";      // 记账服务内部错误；记录 requestId 上报
    public static final String GL_UNAVAILABLE = "ACC_2001";      // 总账/科目服务不可用（降级中）；稍后重试
}
