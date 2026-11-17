package com.bai.face;

/**
 * 人脸核验原子错误码段（提示词库 A-06 条目，原文档码表逐条保留）
 */
public final class FaceVerifyErrorCodes {

    private FaceVerifyErrorCodes() {}

    public static final String PARAM_INVALID = "FAC_1001";      // 参数缺失/文件非法；校验后重试
    public static final String TOKEN_INVALID = "FAC_1002";      // Token 缺失/过期；重新获取 Token（网关统一鉴权）
    public static final String NO_PERMISSION = "FAC_1003";      // 无调用权限；申请权限（网关统一鉴权）
    public static final String NOT_FOUND = "FAC_1004";          // taskId/底片不存在；校验参数
    public static final String FILE_TOO_LARGE = "FAC_1005";     // 文件超过 50MB；压缩后提交
    public static final String FORMAT_UNSUPPORTED = "FAC_1006"; // 格式不支持/解码失败；转换格式
    public static final String NO_FACE = "FAC_1007";            // 图像无人脸/质量过差；重新采集
    public static final String RATE_LIMITED = "FAC_1008";       // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "FAC_2000";     // 服务内部错误；记录 requestId 上报
    public static final String MODEL_UNAVAILABLE = "FAC_2001";  // 模型服务不可用（降级中）；稍后重试或转柜面核身
}
