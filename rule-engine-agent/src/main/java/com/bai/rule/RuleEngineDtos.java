package com.bai.rule;

import java.util.List;
import java.util.Map;

/**
 * 规则引擎输出统一结构（提示词库 A-17：RuleResult）
 * <p>结论域限定 PASS/REJECT/REVIEW/WARN/APPROVE；同事实同结果（确定性命中 100%）；
 * 命中规则≤100 条（超出截断并计数）；执行限时 1000ms 超时返回 REVIEW
 */
public final class RuleEngineDtos {

    private RuleEngineDtos() {}

    /** execute 请求体 */
    public record ExecuteRequest(Map<String, Object> facts, String rulesetId, String rulesetVersion, Boolean explain) {}

    /** 判定结论（decision/message/code） */
    public record Conclusion(String decision, String message, String code) {}

    /** 命中规则（含条件证据：fact.&lt;field&gt;/op/value） */
    public record MatchedHit(String ruleId, String name, int salience, Map<String, Object> matched) {}

    /** 执行结果 */
    public record RuleResult(String execId, String rulesetId, String rulesetVersion, Conclusion conclusion,
                             List<MatchedHit> hits, List<String> path, long elapsedMs) {}

    /** batch 请求体（≤100000 条，异步） */
    public record BatchRequest(List<ExecuteRequest> items) {}

    /** batch 受理结果 */
    public record BatchResult(String batchId, String status, int total) {}

    /** test 请求体（规则仿真） */
    public record TestRequest(Map<String, Object> facts, String rulesetId, String rulesetVersion) {}

    /** 仿真结果 */
    public record TestResult(String testId, String rulesetId, String rulesetVersion, boolean pass,
                             Conclusion conclusion, List<MatchedHit> hits, List<String> path, long elapsedMs) {}

    /** 规则摘要 */
    public record RuleSummary(String ruleId, String name, int salience, String decision, String message, String code) {}

    /** 规则集摘要 */
    public record RulesetSummary(String rulesetId, String name, String category, String version, String status,
                                 int ruleCount, List<RuleSummary> rules) {}

    /** 规则集列表（GET /rulesets） */
    public record RulesetsResult(String version, List<RulesetSummary> rulesets) {}
}
