package com.bai.image;

/**
 * 图像处理原子错误码段（提示词库 A-04 条目，原文档码表逐条保留）
 */
public final class ImageErrorCodes {

    private ImageErrorCodes() {}

    public static final String PARAM_INVALID = "IMG_1001";    // 参数缺失/文件非法；校验后重试
    public static final String TOKEN_INVALID = "IMG_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "IMG_1003";    // 无调用权限；申请权限
    public static final String NOT_FOUND = "IMG_1004";        // taskId 不存在；校验任务号
    public static final String FILE_TOO_LARGE = "IMG_1005";   // 文件超过 20MB；压缩后提交
    public static final String FORMAT_INVALID = "IMG_1006";   // 格式不支持/解码失败；转换格式
    public static final String UNPROCESSABLE = "IMG_1007";    // 图像不可处理（损坏/全黑/坐标越界）；重拍或修正参数
    public static final String RATE_LIMITED = "IMG_1008";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "IMG_2000";   // 服务内部错误；记录 requestId 上报
    public static final String MODEL_DEGRADED = "IMG_2001";   // 模型服务不可用（传统算法兜底中）；稍后重试
}
