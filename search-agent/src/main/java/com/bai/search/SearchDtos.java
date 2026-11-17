package com.bai.search;

import java.util.List;

/**
 * 多源搜索输出统一结构（提示词库 A-19：SearchResult）
 * <p>源权重：internet 0.30 / internal 0.35 / external 0.20 / knowledge 0.15；
 * 可信度：GOV 0.95 / OFFICIAL 0.85 / MEDIA 0.70 / OTHER 0.50，credibility&lt;0.50 置 warning
 */
public final class SearchDtos {

    private SearchDtos() {}

    /** 过滤条件（dateFrom/dateTo/siteType/blacklist） */
    public record Filters(String dateFrom, String dateTo, String siteType, List<String> blacklist) {}

    /** search 请求体 */
    public record SearchRequest(String query, List<String> sources, Integer topK, Filters filters,
                                Boolean enableSnapshot, Boolean rerank) {}

    /** 归一化结果（快照仅存公开内容，涉个人隐私结果脱敏存储） */
    public record ResultItem(String resultId, String source, String title, String url, String snippet,
                             String date, String siteType, double credibility, String snapshotId, boolean warning) {}

    /** 搜索结果 */
    public record SearchOutput(String searchId, String query, List<ResultItem> results, int total, long elapsedMs) {}

    /** 批量请求（≤5000 条，异步） */
    public record BatchRequest(List<String> queries) {}

    /** 批量受理结果 */
    public record BatchResult(String batchId, String status, int total) {}

    /** 原文快照（HTML 转文本，保留≥1 年，仅用于尽调留证不对外分发） */
    public record Snapshot(String snapshotId, String url, String source, String capturedAt, String content) {}

    /** 搜索源信息 */
    public record SourceInfo(String source, double weight, int qpsLimit, boolean enabled) {}
}
