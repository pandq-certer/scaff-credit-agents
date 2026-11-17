package com.bai.tts;

/**
 * TTS 引擎提供者抽象：隔离「引擎内核」与「服务编排」。
 * <p>mock = {@link MockTtsProvider} 确定性仿真（联调/回归专用，行为与仿真版完全一致）；
 * real = {@link RealTtsProvider} 统一 AI 推理网关（/v1/tts/synthesize，GPU 声学模型+声码器内核）。
 * <p>切换开关 {@code bai.tts.provider=mock|real}，默认 mock。
 */
public interface TtsProvider {

    /**
     * 合成：服务层完成 SSML 剥离/文本校验（空/纯符号/超长）/场景-音色-语气-语速-格式解析/文本规整后，
     * 传入规整文本与已解析参数，实现只负责内核合成（时长/分段/音频产物元信息）。
     */
    TtsDtos.TtsResult synthesize(String normText, String voiceId, String scene, String style,
                                 double rate, String format, int sampleRate, String requestId);
}
