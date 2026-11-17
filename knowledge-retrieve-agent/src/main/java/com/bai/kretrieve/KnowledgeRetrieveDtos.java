package com.bai.kretrieve;

import java.util.List;
import java.util.Map;

/**
 * 知识检索输出统一结构（提示词库 A-15：KnowledgeRetrieveResult）
 * <p>融合后 topK（默认 10，1—20）；卡片内容≤2000 字且溯源必带；全部通道无命中返回 0 条并给出建议；
 * 最高分&lt;0.50 标记低置信（日志警示）
 */
public final class KnowledgeRetrieveDtos {

    private KnowledgeRetrieveDtos() {}

    /** 溯源（docId+章节+原文引用） */
    public record SourceRef(String docId, String section, String quote) {}

    /** 知识卡片 */
    public record KnowledgeHit(String entryId, String library, String type, String title, String content,
                               SourceRef source, double score, String channel, List<String> tags) {}

    /** retrieve 请求体 */
    public record RetrieveRequest(String query, Integer topK, List<String> libraries,
                                  Map<String, Object> filters, List<String> channels, Boolean rerank) {}

    /** graph 请求体（图谱一跳检索，maxHop=1） */
    public record GraphRequest(String query, List<String> seedEntities, Integer maxHop, Integer topK) {}

    /** 检索结果 */
    public record RetrieveResult(String retrieveId, String query, List<KnowledgeHit> hits, int total, long elapsedMs) {}

    /** batch 请求体（≤10000 条，异步） */
    public record BatchRequest(List<RetrieveRequest> items) {}

    /** batch 受理结果 */
    public record BatchResult(String batchId, String status, int total) {}

    /** 通道配置（RFF 权重） */
    public record ChannelConfig(String channel, double weight, String description) {}

    /** 检索配置（GET /config） */
    public record RetrieveConfig(String version, int defaultTopK, int maxTopK, int maxPerChannel,
                                 int hotCacheTtlMinutes, List<ChannelConfig> channels) {}
}
