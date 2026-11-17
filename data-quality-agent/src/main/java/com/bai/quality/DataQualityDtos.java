package com.bai.quality;

import java.util.List;

/**
 * 数据质量治理输出统一结构（BIA-DTA-022：CheckResult）
 * <p>五维度规则集内置（SQL 表达式仅 SELECT 仿真，用于联调）；
 * 权重：完整性 0.25/准确性 0.30/一致性 0.20/及时性 0.10/唯一性 0.15；
 * qualityScore=加权维度分（0-100，2 位小数），&lt;80 触发降级标记 degraded=true；ERROR 违规生成工单号
 */
public final class DataQualityDtos {

    private DataQualityDtos() {}

    /** check 请求体（assetId 必填且须注册白名单；ruleIds 可选≤50；asOf 缺省当天） */
    public record CheckRequest(String assetId, List<String> ruleIds, String asOf) {}

    /** 五维度得分（0-100） */
    public record DimensionScores(double completeness, double accuracy, double consistency,
                                  double timeliness, double uniqueness) {}

    /** 单规则质检结果 */
    public record RuleResult(String ruleId, String ruleName, String dimension, String severity,
                             boolean passed, long affectedRows, String detail) {}

    /** 质检结果 */
    public record CheckResult(String checkId, String assetId, String asOf, double qualityScore,
                              DimensionScores dimensionScores, List<RuleResult> results,
                              boolean degraded, List<String> issues, long elapsedMs) {}

    /** batch 请求体（单资产 + 规则集≤200 条） */
    public record BatchRequest(String assetId, List<String> ruleIds) {}

    /** batch 结果 */
    public record BatchResult(String batchId, String assetId, int total, int passed, int failed,
                              boolean degraded, List<RuleResult> results) {}

    /** 规则定义（GET /rules，sql 仅 SELECT 仿真） */
    public record RuleDef(String ruleId, String ruleName, String dimension, String severity,
                          double threshold, String sql) {}

    /** 规则库（GET /rules） */
    public record RulesResult(String version, List<RuleDef> rules) {}

    /** 质量工单 */
    public record Issue(String issueId, String assetId, String ruleId, String asOf, String severity,
                        String status, String createdAt) {}

    /** 工单列表（GET /issues，内存模拟工单库） */
    public record IssuesResult(int total, List<Issue> issues) {}
}
