package com.bai.compliance;

import java.util.List;

/**
 * 合规审查输出统一结构（提示词库 A-49：CheckResult/RuleHit/RuleInfo/FeedbackResult）
 */
public final class ComplianceDtos {

    private ComplianceDtos() {}

    /** 命中条款 */
    public record RuleHit(String ruleId, String clause, String source, String level, String detail) {}

    /** 合规审查结论 */
    public record CheckResult(String checkId, String scene, String conclusion, String riskLevel,
                              List<RuleHit> hits, boolean escalated, String reason, int elapsedMs) {}

    /** 规则概要 */
    public record RuleInfo(String ruleId, String scene, String level, String clause,
                           String source, String version, String status) {}

    /** 人工反馈回流结果 */
    public record FeedbackResult(String checkId, boolean accepted, String message) {}
}
