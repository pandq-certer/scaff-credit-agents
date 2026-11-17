package com.bai.asr;

import java.util.List;

/**
 * 语音识别输出统一结构（提示词库 A-02：AsrResult/Segment/AsrTask/LangDetectResult）
 * <p>句级置信度&lt;0.45 标记 REJECT 并给出原因（NOISE/DIALECT/OVERLAP/LOW_CONF/EMPTY），不得静默输出；
 * 有效句 REJECT 占比&gt;30% 整体 PARTIAL、&gt;60% 整体 REJECT
 */
public final class AsrDtos {

    private AsrDtos() {}

    /** 句级结果（speaker 说话人分离标注 A/B/C，未分离时为 null） */
    public record Segment(int index, String speaker, long startMs, long endMs, String text,
                          double confidence, String status, String reason) {}

    /** 转写结果（全链统一结构） */
    public record AsrResult(String asrId, String scene, String lang, long durationMs, String status,
                            List<Segment> segments, String fullText, long elapsedMs) {}

    /** 异步任务（>20MB 自动转异步） */
    public record AsrTask(String taskId, String status, AsrResult result) {}

    /** 轻量语种探测结果 */
    public record LangDetectResult(String detectId, String lang, double confidence, long durationMs, long elapsedMs) {}
}
