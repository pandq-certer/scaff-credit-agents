package com.bai.risksignal;

import java.util.List;

/**
 * 风险信号识别输出统一结构（提示词库 A-44：Signal/Component/Aggregated/DetectResult/BatchDetectResult/FeedbackResult）
 */
public final class RiskSignalDtos {

    private RiskSignalDtos() {}

    /** 单条信号明细 */
    public record Signal(String signalType, String level, double strength, List<String> evidence) {}

    /** 聚合分量 */
    public record Component(String signalType, double weight, int score) {}

    /** 加权聚合结果 */
    public record Aggregated(String aggLevel, double aggScore, List<Component> components) {}

    /** 单主体检测结果 */
    public record DetectResult(String signalId, List<Signal> signals, Aggregated aggregated,
                               boolean alertTriggered, int elapsedMs) {}

    /** 批量检测结果 */
    public record BatchDetectResult(int total, List<DetectResult> results) {}

    /** 人工反馈回流结果 */
    public record FeedbackResult(String signalId, boolean accepted, String message) {}
}
