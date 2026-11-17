package com.bai.assetsupervise;

/**
 * 押品监管原子错误码段（提示词库 A-53，设计文档 4.8 错误码表逐条保留）
 */
public final class AssetSuperviseErrorCodes {

    private AssetSuperviseErrorCodes() {}

    public static final String PARAM_INVALID = "ASS_4001";      // 请求参数校验失败；按字段错误明细修正
    public static final String OBJECT_NOT_FOUND = "ASS_4002";   // 监管对象不存在或已终止；检查对象标识
    public static final String REF_NOT_FOUND = "ASS_4003";      // 押品/贷款不存在；检查关联标识
    public static final String IDEMPOTENT_CONFLICT = "ASS_4004";// 幂等冲突（requestId 重复且参数不同）；使用原 requestId 查询
    public static final String DATA_QUALITY_LOW = "ASS_4101";   // 监测数据质量过低（异常占比>20%）；重新采集/人工核实
    public static final String VALUATION_MISSING = "ASS_4102";  // 估值缺失或过期；先触发重估
    public static final String REGULATORY_LOSS = "ASS_4103";    // 监管失效（数据断连超期）；触发监管失效预警
    public static final String STRATEGY_NOT_FOUND = "ASS_4104"; // 处置策略不存在；指定当前生效版本
    public static final String INTERNAL_ERROR = "ASS_5001";     // 内部计算错误；返回错误码并留痕
    public static final String SERVICE_OVERLOAD = "ASS_5002";   // 计算服务过载；限流退避重试
}
