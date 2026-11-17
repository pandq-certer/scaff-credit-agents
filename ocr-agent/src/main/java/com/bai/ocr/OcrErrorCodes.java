package com.bai.ocr;

/**
 * OCR 原子错误码段（提示词库 A-01 条目，原文档码表逐条保留）
 */
public final class OcrErrorCodes {

    private OcrErrorCodes() {}

    public static final String PARAM_INVALID = "OCR_1001";   // 参数缺失/格式非法；调用方校验参数后重试
    public static final String TOKEN_INVALID = "OCR_1002";   // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "OCR_1003";   // 无调用权限；申请权限
    public static final String TASK_NOT_FOUND = "OCR_1004";  // taskId 不存在；校验任务号
    public static final String FILE_TOO_LARGE = "OCR_1005";  // 图像超过 10MB；压缩或裁剪后重试
    public static final String FORMAT_UNSUPPORTED = "OCR_1006"; // 图像格式不支持；转格式后重试
    public static final String IMAGE_CORRUPTED = "OCR_1007"; // 图像不可解析（损坏/空）；重新上传
    public static final String RATE_LIMITED = "OCR_1008";    // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "OCR_2000";  // 服务内部错误；记录 requestId 上报
    public static final String MODEL_UNAVAILABLE = "OCR_2001"; // 模型服务不可用（降级中）；稍后重试或走人工兜底
}
