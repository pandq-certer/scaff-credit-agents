package com.bai.dataquery;

import java.util.List;
import java.util.Map;

/**
 * 统一数据查询输出统一结构（提示词库 A-18：QueryResult）
 * <p>单次最大 10000 行；全部数据源只读（禁止 DDL/DML）；结果集按列脱敏；
 * 参数绑定条件生成 SQL 审计留痕；NL2SQL 查询另返 generatedSql 与 validated 标记
 */
public final class DataQueryDtos {

    private DataQueryDtos() {}

    /** 查询条件（field/op/value，参数绑定） */
    public record Condition(String field, String op, Object value) {}

    /** 排序（field/direction） */
    public record OrderBy(String field, String direction) {}

    /** 分页（pageNo/pageSize≤1000） */
    public record Page(Integer pageNo, Integer pageSize) {}

    /** query 请求体（requestId 幂等请求号，body 优先、header 兜底） */
    public record QueryRequest(String requestId, String datasource, String table, List<String> fields,
                               List<Condition> conditions, List<String> groupBy,
                               OrderBy orderBy, Page page) {}

    /** 查询结果 */
    public record QueryResult(String queryId, String datasource, List<String> columns,
                              List<List<Object>> rows, int rowCount, boolean masked, String sql, long elapsedMs) {}

    /** nl 请求体（自然语言查询，requestId 幂等请求号，body 优先、header 兜底） */
    public record NlRequest(String requestId, String question, String datasource, Integer topRows) {}

    /** NL2SQL 结果（另返 generatedSql 与 validated） */
    public record NlResult(String queryId, String datasource, String question, String generatedSql, boolean validated,
                           List<String> columns, List<List<Object>> rows, int rowCount, boolean masked,
                           String sql, long elapsedMs) {}

    /** batch 请求体（≤1000 条，异步，requestId 幂等请求号，body 优先、header 兜底） */
    public record BatchRequest(String requestId, List<QueryRequest> items) {}

    /** batch 受理结果 */
    public record BatchResult(String batchId, String status, int total) {}

    /** 表白名单 */
    public record TableInfo(String table, String description, List<String> columns) {}

    /** 数据源信息（GET /datasources） */
    public record DatasourceInfo(String datasource, String description, boolean readOnly, List<TableInfo> tables) {}

    /** 数据源列表 */
    public record DatasourcesResult(String version, List<DatasourceInfo> datasources) {}
}
