package com.bai.onsiteinspect;

/**
 * 现场尽调原子错误码段（设计文档 4.7 码表逐条保留；4005/4006 为限流与状态冲突补充码）
 */
public final class OnsiteInspectErrorCodes {

    private OnsiteInspectErrorCodes() {}

    public static final String PARAM_INVALID = "OIS_4001";          // 请求参数校验失败；按字段错误明细修正
    public static final String TASK_INVALID = "OIS_4002";           // 任务不存在或已终止；检查任务标识
    public static final String CUSTOMER_NOT_FOUND = "OIS_4003";     // 客户/贷款不存在；检查关联标识
    public static final String IDEMPOTENCY_CONFLICT = "OIS_4004";   // 幂等冲突（requestId 重复且参数不同）；使用原 requestId 查询
    public static final String RATE_LIMITED = "OIS_4005";           // 触发限流（补充码，设计 4.8 超限 429 退避）；按 Retry-After 退避
    public static final String STATE_CONFLICT = "OIS_4006";         // 状态流转非法/重复操作（补充码）；修正后重试
    public static final String DUAL_INSPECTORS_MISSING = "OIS_4101"; // 双人未成行（人员不足/回避冲突）；补齐双人后重试
    public static final String EVIDENCE_MISSING = "OIS_4102";       // 必采证据缺失；补采后提交
    public static final String RECORDING_AUTH_MISSING = "OIS_4103"; // 双录授权缺失；补齐授权后重试
    public static final String EVIDENCE_INCONSISTENT = "OIS_4104";  // 证据时间位置与任务不一致；核实后重新采集
    public static final String INTERNAL_ERROR = "OIS_5001";         // 内部计算错误；返回错误码并留痕
}
