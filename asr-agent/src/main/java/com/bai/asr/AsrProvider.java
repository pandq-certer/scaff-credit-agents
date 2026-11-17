package com.bai.asr;

/**
 * ASR 引擎提供者抽象：隔离「引擎内核」与「服务编排」。
 * <p>mock = {@link MockAsrProvider} 确定性仿真（联调/回归专用，行为与仿真版完全一致）；
 * real = {@link RealAsrProvider} 统一 AI 推理网关（GPU 声学模型内核，HTTP 调用，数据不出行）。
 * <p>切换开关 {@code bai.asr.provider=mock|real}，默认 mock。
 */
public interface AsrProvider {

    /**
     * 音频字节 → 结构化转写（句级时间戳/说话人/置信度）。前置的大小/格式校验由服务层完成，
     * 实现只负责引擎转写；scene/lang 缺省与非法校验由实现决定（mock=内置校验 ASR_1001）。
     */
    AsrDtos.AsrResult recognize(byte[] content, String filename, String scene, String lang,
                                boolean speakerDiarization, boolean maskSensitive, String requestId);
}
