package com.bai.classify;

import java.util.List;

/**
 * 文本分类输出统一结构（提示词库 A-09：ClassifyResult）
 * <p>类别置信度默认 0.70（合规任务 0.80、文档类别 0.65）；最高置信度 &lt;0.60 且体系含 OTHER 时输出 OTHER；
 * 合规仅作标记与证据输出，判罚归 compliance-check-agent；理由仅用于可解释与抽检
 */
public final class ClassifyDtos {

    private ClassifyDtos() {}

    /** classify 请求体（text/task 必填；topK 1—5 默认 3；enableRule/enableReason 默认 true） */
    public record ClassifyRequest(String text, String task, Integer topK,
                                  Boolean enableRule, Boolean enableReason) {}

    /** 命中类别（两级，source=MODEL/RULE） */
    public record Label(String level1, String level2, double confidence, String source) {}

    /** TopK 候选（label 形如 level1/level2） */
    public record Candidate(String label, double confidence) {}

    /** 分类理由（RULE_KEYWORD 命中词 / MODEL_SEGMENT 高权重片段） */
    public record Reason(String type, String text, double weight) {}

    /** 合规标记（仅标记与证据，判罚归 compliance-check-agent） */
    public record Compliance(boolean flagged, List<String> categories, List<String> evidence) {}

    /** 分类结果 */
    public record ClassifyResult(String classifyId, String task, String textHash, List<Label> labels,
                                 List<Candidate> candidates, List<Reason> reasons,
                                 Compliance compliance, long elapsedMs) {}

    /** batch 请求体（≤100000 条，异步） */
    public record BatchRequest(List<ClassifyRequest> items) {}

    /** 异步批量任务（MVP 仿真为同步完成，落任务存储供 tasks 查询） */
    public record ClassifyTask(String taskId, String status, int total, List<ClassifyResult> results) {}

    /** 分类体系条目 */
    public record SchemaLevel2(String code, String name) {}
    public record SchemaLevel1(String code, String name, List<SchemaLevel2> level2) {}
    public record SchemaTask(String task, String name, double threshold, String aggregate, List<SchemaLevel1> level1) {}

    /** 分类体系（六类任务，仿真内置快照） */
    public record ClassifySchemas(String version, List<SchemaTask> tasks) {}
}
