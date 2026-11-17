package com.bai.docparse;

/**
 * 文档解析原子错误码段（提示词库 A-05 条目，原文档码表逐条保留）
 */
public final class DocParseErrorCodes {

    private DocParseErrorCodes() {}

    public static final String PARAM_INVALID = "DCP_1001";    // 参数缺失/文件非法；校验后重试
    public static final String TOKEN_INVALID = "DCP_1002";    // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "DCP_1003";    // 无调用权限；申请权限
    public static final String NOT_FOUND = "DCP_1004";        // taskId/Schema 不存在；校验参数
    public static final String TOO_LARGE = "DCP_1005";        // 文件超过 50MB/200 页；拆分提交
    public static final String FORMAT_INVALID = "DCP_1006";   // 格式不支持/PDF 加密；转换或解密
    public static final String UNPARSABLE = "DCP_1007";       // 文档不可解析（损坏/全黑）；重拍或转人工
    public static final String RATE_LIMITED = "DCP_1008";     // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "DCP_2000";   // 服务内部错误；记录 requestId 上报
    public static final String MODEL_DEGRADED = "DCP_2001";   // 模型服务不可用（模板规则兜底中）；稍后重试
}
