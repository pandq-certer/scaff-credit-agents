package com.bai.metric;

import java.util.List;
import java.util.Map;

/**
 * 指标计算输出统一结构（BIA-DTA-020：CalcResult）
 * <p>指标字典内置（确定性仿真，用于联调）；value 为基于口径版本+参数的确定性伪值（2 位小数，
 * 同参数复现一致）；fingerprint=口径版本+参数 SHA-256；lineage 保留上游表依赖
 */
public final class MetricCalcDtos {

    private MetricCalcDtos() {}

    /** calc 请求体（params.orgId/params.date 必填，version 缺省最新口径） */
    public record CalcRequest(String metricId, String version, Map<String, Object> params, String requestId) {}

    /**
     * 单指标计算结果（value：单值指标为 Double，复合指标为 Map&lt;String,Double&gt; 键值组）
     */
    public record CalcResult(String calcId, String metricId, String metricName, String version, String unit,
                             String granularity, Object value, String fingerprint, List<String> lineage, long elapsedMs) {}

    /** batch 请求体（≤2000 条） */
    public record BatchCalcRequest(List<CalcRequest> items, String requestId) {}

    /** batch 逐条结果（taskId + 逐条 CalcResult） */
    public record BatchCalcResult(String taskId, int total, List<CalcResult> results, long elapsedMs) {}

    /** 指标字典条目 */
    public record MetricDef(String metricId, String metricName, String unit, String granularity,
                            String formula, String version, List<String> lineage) {}

    /** 指标字典（GET /indicators） */
    public record IndicatorsResult(String version, List<MetricDef> indicators) {}

    /** 口径版本列表（GET /versions） */
    public record VersionsResult(String latest, List<String> versions) {}
}
