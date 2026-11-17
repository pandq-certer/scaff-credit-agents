package com.bai.modelinfer;

/**
 * 模型推理原子错误码段（提示词库 A-54 条目，原文档码表逐条保留）
 */
public final class ModelInferErrorCodes {

    private ModelInferErrorCodes() {}

    public static final String PARAM_INVALID = "MDI_1001";    // 参数缺失/特征缺失；修正后重试
    public static final String TOKEN_INVALID = "MDI_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "MDI_1003";    // 无推理权限/模型管理越权；申请权限点
    public static final String MODEL_NOT_FOUND = "MDI_1004";  // 模型/特征不存在；校验 modelId 与特征字典
    public static final String CONFLICT = "MDI_1005";         // 重复提交/模型冲突；使用原 requestId 查询
    public static final String TOO_LONG = "MDI_1006";         // 特征集超长；缩减输入
    public static final String SCENE_INVALID = "MDI_1007";    // 场景/模型类型非法；修正 scene/modelType
    public static final String RATE_LIMITED = "MDI_1008";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "MDI_2000";   // 推理服务内部错误；记录 requestId 上报
    public static final String MODEL_DEGRADED = "MDI_2001";   // 模型/特征服务不可用（降级中）；稍后重试
}
