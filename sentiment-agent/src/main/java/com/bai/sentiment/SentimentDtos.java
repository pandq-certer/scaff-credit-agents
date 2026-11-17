package com.bai.sentiment;

import java.util.List;

/**
 * 情绪分析输出统一结构（提示词库 A-10：SentimentResult）
 * <p>五大类情绪 POSITIVE/NEUTRAL/NEGATIVE/COMPLAINT/HOSTILE；细粒度置信度 &lt;0.55 仅输出大类；
 * 信号命中优先于情绪类别强制输出并置强度 ≥4；轨迹 ≤50 点/会话，超窗聚合
 */
public final class SentimentDtos {

    private SentimentDtos() {}

    /** analyze 请求体（text 必填 ≤5000 字；sessionId/turnSeq 用于轨迹；enableSignal 默认 true） */
    public record AnalyzeRequest(String text, String scene, String sessionId, Integer turnSeq,
                                 Boolean enableSignal) {}

    /** 情绪（大类/细类/置信度/来源；细粒度置信度不足时 fineGrained 为 null） */
    public record Emotion(String category, String fineGrained, double confidence, String source) {}

    /** 强度 1—5 级与标签（1—2 LOW、3 MEDIUM、4 HIGH、5 CRITICAL） */
    public record Intensity(int level, String label) {}

    /** 投诉/异议信号（COMPLAINT_THREATEN/REFUSE_CONTACT/DEBT_DISPUTE/PAY_DIFFICULTY），evidence 为命中片段 */
    public record Signal(String type, double confidence, List<String> evidence) {}

    /** 轨迹点 */
    public record TrajectoryPoint(int seq, String category, int level) {}

    /** 轨迹极值点 */
    public record Peak(int seq, String category, int level) {}

    /** 会话情绪轨迹（trend=ESCALATING/DE-ESCALATING/STABLE） */
    public record Trajectory(List<TrajectoryPoint> turns, String trend, Peak peak) {}

    /** 分析结果 */
    public record SentimentResult(String sentimentId, String scene, String textHash, Emotion emotion,
                                  Intensity intensity, List<Signal> signals, Trajectory trajectory,
                                  long elapsedMs) {}

    /** batch 请求体（≤100000 条，异步） */
    public record BatchRequest(List<AnalyzeRequest> items) {}

    /** 批量受理结果（条目未定义批量任务查询接口，结果由调度器落库） */
    public record BatchResult(String batchId, String status, int total) {}

    /** 情绪体系条目 */
    public record SchemaCategory(String category, String name, List<String> fineGrained) {}
    public record SchemaSignal(String type, String name, double threshold) {}

    /** 情绪体系与信号体系（仿真内置快照） */
    public record SentimentSchemas(String version, List<SchemaCategory> categories,
                                   List<SchemaSignal> signals, List<String> trends) {}
}
