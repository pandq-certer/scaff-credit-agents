package com.bai.orchestrate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 多智能体编排输出统一结构（提示词库 A-41：PlanResult / InstanceResult）
 * <p>steps 必须无环且每个 agent 能力可用；实例状态按 PENDING→RUNNING→SUCCESS/FAILED 流转，
 * PAUSED/CANCELLED 可介入（PAUSE/RESUME/SKIP_STEP/REDIRECT_STEP/CANCEL）。
 */
public final class OrchestrateDtos {

    private OrchestrateDtos() {}

    /** 计划生成入参：goal≤2000 字；constraints（时限/级别/人工点）；templateId 复用编排模板 */
    public record PlanRequest(String goal, Map<String, Object> constraints, String templateId) {}

    /** 编排执行入参：planId/templateId 加 bizKey 启动异步实例 */
    public record ExecuteRequest(String planId, String templateId, String bizKey) {}

    /** 人工介入入参：action=PAUSE/RESUME/SKIP_STEP/REDIRECT_STEP/CANCEL */
    public record InterveneRequest(String instanceId, String action, String stepId, String targetAgent, String reason) {}

    /** DAG 步骤：stepId/agent/dependsOn/mode/retry/input */
    public record Step(String stepId, String agent, List<String> dependsOn, String mode, int retry,
                       Map<String, Object> input) {}

    /** DAG 校验结果 */
    public record Validation(boolean acyclic, boolean agentsAvailable) {}

    /** 计划生成结果 */
    public record PlanResult(String planId, List<Step> steps, int estimatedSteps, Validation validation) {}

    /** 实例状态：PENDING/RUNNING/SUCCESS/FAILED/PAUSED/CANCELLED */
    public record InstanceResult(String instanceId, String planId, String status, long elapsedMs) {}

    /** 实例查询条目 */
    public record InstanceSummary(String instanceId, String planId, String requestId, String bizKey,
                                  String status, LocalDateTime createdAt) {}
}
