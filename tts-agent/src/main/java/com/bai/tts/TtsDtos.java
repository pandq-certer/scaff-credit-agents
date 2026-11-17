package com.bai.tts;

import java.util.List;

/**
 * 文字转语音输出统一结构（提示词库 A-03：TtsResult/Segment/TtsTask/Voice，对应设计文档 4.2.2/4.4）
 */
public final class TtsDtos {

    private TtsDtos() {}

    /** 文本分段（合成元信息） */
    public record Segment(int index, String text, long startMs, long endMs) {}

    /** 合成结果（全链统一结构） */
    public record TtsResult(String ttsId, String voiceId, String scene, String format, int sampleRate,
                            long durationMs, String audioUrl, List<Segment> segments, long elapsedMs) {}

    /** 异步任务提交回执（>5000 字或批量自动转异步，设计 4.2.3） */
    public record TtsTaskSubmit(String taskId, String status) {}

    /** 异步任务查询（status: PENDING/RUNNING/DONE） */
    public record TtsTask(String taskId, String status, TtsResult result) {}

    /** 音色注册表条目（表 7-2：voiceId/name/gender/style/scene/sampleRates） */
    public record Voice(String voiceId, String name, String gender, List<String> style,
                        List<String> scene, List<Integer> sampleRates) {}
}
