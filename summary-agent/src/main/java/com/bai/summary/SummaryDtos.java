package com.bai.summary;

import java.util.List;

/**
 * 文本摘要输出统一结构（提示词库 A-12：SummaryResult）
 * <p>overview 压缩率 10%/上限 2000 字；bullet 15%/上限 3000 字（每点 ≤100 字，句序即原文引用）；
 * terse ≤100 字一句话；关键信息缺失不阻断（keyInfo 为空）；摘要落库脱敏
 */
public final class SummaryDtos {

    private SummaryDtos() {}

    /** generate 请求体（text 必填 ≤20000 字；mode=overview/bullet/terse 默认 overview；maxChars 1—2000 优先于模式默认） */
    public record GenerateRequest(String text, String mode, String scene, Integer maxChars,
                                  Boolean extractKeyInfo) {}

    /** 关键信息（type=AMOUNT/DATE/PARTY/MATTER/GUARANTY/TERM） */
    public record KeyInfo(String type, String value, String normalized, String unit) {}

    /** 摘要统计：输入/输出长度与压缩率 */
    public record Stats(int inputChars, int outputChars, double ratio) {}

    /** 摘要结果 */
    public record SummaryResult(String summaryId, String scene, String mode, String summary,
                                List<KeyInfo> keyInfo, boolean chunked, Stats stats, long elapsedMs) {}

    /** evaluate 请求体（原文 + 摘要） */
    public record EvaluateRequest(String text, String summary) {}

    /** 质量评分（0—1；consistency<0.75 告警抽检） */
    public record QualityScore(double consistency, double faithfulness, double coverage, double overall) {}

    /** batch 请求体（≤5000 篇，异步） */
    public record BatchRequest(List<GenerateRequest> items) {}

    /** 批量受理结果（条目未定义批量任务查询接口，结果由调度器落库） */
    public record BatchResult(String batchId, String status, int total) {}

    /** 模型信息（仿真内置快照） */
    public record SummaryModels(String model, String type, String deploy, List<String> modes, String version) {}
}
