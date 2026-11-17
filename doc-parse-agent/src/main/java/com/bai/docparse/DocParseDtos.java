package com.bai.docparse;

import java.util.List;

/**
 * 文档解析输出统一结构（提示词库 A-05：ParseResult/Layout/Table/KvField/Seal/ReadingItem/Validation/TaskResult/ValidateResult）
 */
public final class DocParseDtos {

    private DocParseDtos() {}

    /** 版面区域（9 类：TITLE/PARAGRAPH/TABLE/IMAGE/SEAL/SIGNATURE/HEADER/FOOTER/PAGE_NUMBER） */
    public record Layout(int page, String type, double x, double y, double w, double h, double confidence) {}

    /** 表格结构 */
    public record Table(int page, int rows, int cols, List<String> header, List<Cell> cells, double confidence) {}

    /** 表格单元格 */
    public record Cell(int row, int col, String text, int mergeCols) {}

    /** KV 字段值（value/confidence/page/region） */
    public record KvField(String value, double confidence, int page, String region) {}

    /** 印章/签名 */
    public record Seal(int page, String type, double x, double y, double w, double h, double confidence) {}

    /** 阅读顺序 */
    public record ReadingItem(int seq, int page, String region, String text) {}

    /** 校验结果 */
    public record Validation(List<String> lowConfidenceFields, boolean needReview) {}

    /** 全流程解析结果 */
    public record ParseResult(String docId, String docType, double docTypeConfidence, String schemaVersion,
                              int pages, List<Layout> layout, List<Table> tables, Object kv, List<Seal> seals,
                              List<ReadingItem> readingOrder, Validation validation, int elapsedMs) {}

    /** 异步任务查询结果 */
    public record TaskResult(String taskId, String status, String docId, String docType, int pages,
                             String createdAt) {}

    /** 解析校验结果 */
    public record ValidateResult(String docId, int checkedCount, List<String> lowConfidenceFields,
                                 boolean needReview, int elapsedMs) {}

    /** Schema 概要 */
    public record SchemaInfo(String schemaId, String docTypes, String version, String status) {}
}
