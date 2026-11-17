package com.bai.rag;

import java.util.List;

/**
 * 知识问答输出统一结构（提示词库 A-16：RagAnswerResult）
 * <p>答案≤800 字且不超 maxChars（超长截断+标记）；引用必须映射到上下文 entryId（映射失败置 warning）；
 * 依据判定：相关分≥0.70 ANSWERED、0.50—0.70 PARTIAL、&lt;0.50 REJECTED 引导话术（不编造率 100%）
 */
public final class RagAnswerDtos {

    private RagAnswerDtos() {}

    /** 溯源（docId+章节+原文引用） */
    public record SourceRef(String docId, String section, String quote) {}

    /** 知识上下文卡片（knowledge-retrieve-agent 检索输出，≤8 条） */
    public record ContextCard(String entryId, String title, String content, Double score, SourceRef source) {}

    /** 对话历史项 {role,text} */
    public record HistoryItem(String role, String text) {}

    /** answer 请求体 */
    public record AnswerRequest(String question, List<ContextCard> context, String sessionId,
                                List<HistoryItem> history, String mode, String style, Integer maxChars) {}

    /** 引用（[n] → 上下文卡片映射） */
    public record Citation(int index, String entryId, String title, SourceRef source) {}

    /** 质量评分（忠实度/相关性 0—1） */
    public record Quality(double faithfulness, double relevance) {}

    /** 问答结果 */
    public record AnswerResult(String answerId, String answerMode, String answer,
                               List<Citation> citations, Quality quality, long elapsedMs) {}

    /** batch 请求体（≤10000 条，异步） */
    public record BatchRequest(List<AnswerRequest> items) {}

    /** batch 受理结果 */
    public record BatchResult(String batchId, String status, int total) {}

    /** evaluate 请求体（忠实度/相关性抽检） */
    public record EvaluateRequest(String question, String answer, List<ContextCard> context) {}

    /** 评估结果 */
    public record EvaluateResult(String evaluateId, String verdict, Quality quality, long elapsedMs) {}

    /** 模型信息（GET /models） */
    public record ModelInfo(String modelId, String name, String version, String status, String deploy) {}

    /** 模型列表 */
    public record ModelsResult(String defaultModel, String fallback, List<ModelInfo> models) {}
}
