package com.bai.carbon;

/**
 * 碳账户核算原子错误码段（BIA-CAL-061 条目）
 */
public final class CarbonErrorCodes {

    private CarbonErrorCodes() {}

    public static final String PARAM_INVALID = "CARB_1001";       // 参数缺失/非法；校验后重试
    public static final String TOKEN_INVALID = "CARB_1002";       // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "CARB_1003";       // 无核算权限；申请权限
    public static final String NOT_FOUND = "CARB_1004";           // 排放因子版本不存在；校验 factorVersion
    public static final String DUPLICATE_CONFLICT = "CARB_1005";  // 重复提交/核算要素冲突；使用原 requestId 查询
    public static final String ELEMENT_TOO_LONG = "CARB_1006";    // 核算要素超长；缩减输入
    public static final String ELEMENT_ILLEGAL = "CARB_1007";     // 核算周期/活动数据非法；修正输入
    public static final String RATE_LIMITED = "CARB_1008";        // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "CARB_2000";      // 碳核算服务内部错误；记录 requestId 上报

    // 设计文档 4.10 错误码表（表 4-4，classification/reduction/quota/value/green-stat/tool-calc/greenwash 共用码段）
    public static final String PARAM_CHECK_FAILED = "CARB_4001";      // 请求参数校验失败；按字段错误明细修正
    public static final String ACTIVITY_DATA_INVALID = "CARB_4002";   // 活动数据不完整或量纲错误；补齐活动数据
    public static final String VERSION_NOT_FOUND = "CARB_4003";       // 因子/方法学/目录版本不存在；指定当前生效版本
    public static final String ENTITY_NOT_FOUND = "CARB_4004";        // 主体/项目/资产不存在；检查标识
    public static final String IDEMPOTENT_CONFLICT = "CARB_4005";     // 幂等冲突（requestId 重复且参数不同）；使用原 requestId 查询
    public static final String METHOD_NOT_APPLICABLE = "CARB_4101";   // 方法学不适用（适用性检查未过）；更换方法学或补充边界
    public static final String DATA_QUALITY_LOW = "CARB_4102";        // 排放数据质量过低（C 级且影响结论）；补充实测数据
    public static final String GREENWASH_HIGH = "CARB_4103";          // 洗绿风险 HIGH 阻断认定；进入复核流程
    public static final String INTERNAL_CALC_ERROR = "CARB_5001";     // 内部计算错误；返回错误码并留痕
    public static final String SERVICE_OVERLOADED = "CARB_5002";      // 计算服务过载；限流退避重试
}
