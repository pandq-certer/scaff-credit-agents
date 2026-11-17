package com.bai.tts;

/**
 * 文字转语音原子错误码段（提示词库 A-03 条目，原文档码表逐条保留）
 */
public final class TtsErrorCodes {

    private TtsErrorCodes() {}

    public static final String PARAM_INVALID = "TTS_1001";         // 参数缺失/文本非法；校验后重试
    public static final String TOKEN_INVALID = "TTS_1002";         // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "TTS_1003";         // 无调用权限/音色无权限；申请权限
    public static final String NOT_FOUND = "TTS_1004";             // taskId/voiceId 不存在；校验参数
    public static final String TEXT_TOO_LONG = "TTS_1005";         // 文本超过 10000 字；分段提交
    public static final String FORMAT_UNSUPPORTED = "TTS_1006";    // 音频格式参数非法；修正 format
    public static final String TEXT_UNSYNTHESIZABLE = "TTS_1007";  // 文本不可合成（空/纯符号/SSML 非法）；修正文本
    public static final String RATE_LIMITED = "TTS_1008";          // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "TTS_2000";        // 服务内部错误；记录 requestId 上报
    public static final String MODEL_UNAVAILABLE = "TTS_2001";     // 模型服务不可用（降级中）；稍后重试或备用音色
}
