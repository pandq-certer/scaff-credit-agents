package com.bai.asr;

/**
 * 语音识别原子错误码段（提示词库 A-02 条目，原文档码表逐条保留）
 */
public final class AsrErrorCodes {

    private AsrErrorCodes() {}

    public static final String PARAM_INVALID = "ASR_1001";      // 参数缺失/格式非法；校验参数后重试
    public static final String TOKEN_INVALID = "ASR_1002";      // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "ASR_1003";      // 无调用权限；申请权限
    public static final String TASK_NOT_FOUND = "ASR_1004";     // taskId 不存在；校验任务号
    public static final String AUDIO_TOO_LARGE = "ASR_1005";    // 音频超过 200MB/时长超 4h；切片后重试
    public static final String FORMAT_UNSUPPORTED = "ASR_1006"; // 音频格式不支持/采样率过低；转格式后重试
    public static final String AUDIO_CORRUPTED = "ASR_1007";    // 音频不可解析（静音/损坏）；重新上传
    public static final String RATE_LIMITED = "ASR_1008";       // 触发限流/并发超限；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "ASR_2000";     // 服务内部错误；记录 requestId 上报
    public static final String MODEL_UNAVAILABLE = "ASR_2001";  // 模型服务不可用（降级中）；稍后重试或人工听录兜底
}
