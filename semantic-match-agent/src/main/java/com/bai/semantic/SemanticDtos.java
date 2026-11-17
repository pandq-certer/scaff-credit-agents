package com.bai.semantic;

import java.util.List;

/**
 * 语义匹配输出统一结构（提示词库 A-11：MatchResult / SearchResult）
 * <p>相似度 0—1 四级分级（IDENTICAL/HIGHLY_SIMILAR/RELATED/DIFFERENT）；场景阈值默认 0.95/0.80/0.60 可覆盖；
 * hits source=RERANK|VECTOR；理由仅用于解释与抽检，不承担证据
 */
public final class SemanticDtos {

    private SemanticDtos() {}

    /** match 请求体（queryText/candidateText 必填各 ≤2000 字，超长截断；enableReason 默认 true） */
    public record MatchRequest(String queryText, String candidateText, String scene, Boolean enableReason) {}

    /** 当前生效阈值 */
    public record Threshold(double identical, double highlySimilar, double related) {}

    /** 匹配理由（token 级高相似片段） */
    public record MatchReason(String text, double weight) {}

    /** 匹配结果 */
    public record MatchResult(String matchId, String scene, double score, String level, Threshold threshold,
                              List<MatchReason> reasons, long elapsedMs) {}

    /** search 请求体（corpus=knowledge_faq/case_library/utterance_lib/clause_lib；topK 1—100 默认 20） */
    public record SearchRequest(String queryText, String corpus, Integer topK, Boolean rerank, String scene) {}

    /** 检索命中（source=RERANK|VECTOR） */
    public record Hit(String docId, String text, double score, int rank, String source) {}

    /** 检索结果 */
    public record SearchResult(String matchId, String scene, String corpus, List<Hit> hits, int total,
                               long elapsedMs) {}

    /** batch 请求体（≤100000 对，异步） */
    public record BatchRequest(List<MatchRequest> items) {}

    /** 批量受理结果（条目未定义批量任务查询接口，结果由调度器落库） */
    public record BatchResult(String batchId, String status, int total) {}

    /** 模型信息（仿真内置快照） */
    public record SemanticModels(String encoder, int dim, String reranker, String index, String version) {}
}
