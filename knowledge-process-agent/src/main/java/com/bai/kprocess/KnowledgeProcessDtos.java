package com.bai.kprocess;

import java.util.List;
import java.util.Map;

/**
 * 知识加工输出统一结构（提示词库 A-14：KnowledgeProcessResult）
 * <p>条目≤2000 字/条且必带来源引用；FAQ 答案≤500 字；质量评分&lt;0.80 置 REJECTED 退回重加工；
 * 加工记录只存文本哈希不落明文，PASSED 条目经 knowledge.processed 事件供 kge 入库
 */
public final class KnowledgeProcessDtos {

    private KnowledgeProcessDtos() {}

    /** 溯源（docId+章节） */
    public record SourceRef(String docId, String section) {}

    /** 质量校验（完整性/一致性，通过线 0.80） */
    public record Quality(double completeness, double consistency, boolean pass) {}

    /** 知识条目（CLAUSE/RULE/CASE/FAQ/TRIPLE） */
    public record KnowledgeEntry(String entryId, String type, String title, String content,
                                 SourceRef source, List<String> tags, Quality quality) {}

    /** 产出统计 */
    public record Stats(int total, Map<String, Integer> byType) {}

    /** 加工结果 */
    public record ProcessResult(String processId, String docId, String pipelineVersion,
                                List<KnowledgeEntry> entries, Stats stats, long elapsedMs) {}

    /** process 请求体 */
    public record ProcessRequest(String text, String docId, List<String> knowledgeTypes,
                                 String source, String pipelineVersion) {}

    /** batch 请求体（≤1000 篇，异步） */
    public record BatchRequest(List<ProcessRequest> items) {}

    /** batch 受理结果 */
    public record BatchResult(String batchId, String status, int total) {}

    /** 流水线阶段 */
    public record PipelineStage(String stage, String name, String description) {}

    /** 流水线配置（GET /pipeline） */
    public record PipelineInfo(String pipelineVersion, String status, List<PipelineStage> stages, String updatedAt) {}

    /** validate 请求体（待校验条目） */
    public record ValidateRequest(String docId, List<KnowledgeEntry> entries) {}

    /** 单条校验结论 */
    public record EntryVerdict(String entryId, double score, boolean pass, List<String> reasons) {}

    /** 校验结果 */
    public record ValidateResult(String docId, int total, int passed, int rejected,
                                 List<EntryVerdict> verdicts, long elapsedMs) {}
}
