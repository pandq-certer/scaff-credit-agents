package com.bai.fraud;

import java.util.List;

/**
 * 反欺诈输出统一结构（提示词库 A-43：CheckResult/Hit/BatchResult/ExplainResult/FeedbackResult）
 * <p>规则→图→模型级联融合：黑名单即时 REJECT，团伙高危或模型高分 MANUAL，综合中风险 ESCALATE
 */
public final class FraudDtos {

    private FraudDtos() {}

    /** 命中明细（source: RULE/GRAPH/MODEL） */
    public record Hit(String source, String name, int weight) {}

    /** 实时欺诈决策结果 */
    public record CheckResult(String caseId, String scene, String entityId, String decision, int fraudScore,
                              String riskLevel, List<Hit> hits, String reason, int elapsedMs) {}

    /** 批量筛查结果（异步，MVP 同步计算并登记批次） */
    public record BatchResult(String batchId, int total, List<CheckResult> results) {}

    /** 决策解释（规则命中明细 + 特征贡献 + 规则/模型版本快照） */
    public record ExplainResult(String caseId, String decision, int fraudScore, String riskLevel,
                                List<Hit> hits, String reason, String ruleVersion, String modelVersion,
                                String decidedAt) {}

    /** 人工标注样本回流结果 */
    public record FeedbackResult(String caseId, String label, String status, String feedbackAt) {}
}
