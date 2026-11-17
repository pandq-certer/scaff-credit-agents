package com.bai.orchestrate;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import com.spatix.scaff.iagent.model.AgentSession;
import com.spatix.scaff.iagent.model.TaskItem;
import com.spatix.scaff.iagent.orch.OrchestrationEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 计划步骤别名（OrchestrateDtos 嵌套 record） */
import com.bai.orchestrate.OrchestrateDtos.Step;

/**
 * 多智能体编排服务（提示词库 A-41，BIA-INT-040）。
 * <p>处理链：任务解析 → 计划生成（模板优先，无模板 LLM 分解——本阶段为确定性仿真输出，用于联调）→
 * DAG 校验（拓扑排序，有环/步骤非法 AGO_1007；能力不可用候选替换）→ 能力目录路由 →
 * 拓扑调度（无依赖并行、有依赖串行，复用 scaff-iagent OrchestrationEngine 契约留痕）→
 * 上下文按契约映射传递（脱敏传递）→ 异常处置（退避 1s/3s 上限 2 次 → 候选替换 → 回退补偿 → P0 人工介入）→ 留痕。
 * <p>幂等：requestId+bizKey 同键不重复启动实例，返回已存在 instanceId。
 */
@Service
public class OrchestrateService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrateService.class);

    private static final int MAX_GOAL_LEN = 2000;
    private static final Set<String> INTERVENE_ACTIONS =
            Set.of("PAUSE", "RESUME", "SKIP_STEP", "REDIRECT_STEP", "CANCEL");
    private static final Set<String> TERMINAL_STATES = Set.of("SUCCESS", "FAILED", "CANCELLED");

    /** 智能体能力目录（仿真，用于联调：能力标签 + 输入输出契约精确匹配路由） */
    private static final Set<String> CAPABILITY_CATALOG = Set.of(
            "intent-agent", "ner-agent", "dialog-agent", "call-exec-agent", "notify-agent",
            "ocr-agent", "face-verify-agent", "search-agent", "external-data-agent", "fraud-agent");

    /** 编排模板目录（仿真：模板优先于 LLM 分解，AGO_2001 引擎降级时兜底） */
    private static final Map<String, String> TEMPLATES = Map.of(
            "TPL_EARLY_REPAY", "客户通过语音办理提前还款：识别意图与贷款槽位、查询还款计划、人工确认扣款金额后执行扣款并短信通知",
            "TPL_DEFAULT", "通用业务办理模板：意图识别 → 服务调用 → 结果通知");

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final Map<String, Plan> plans = new ConcurrentHashMap<>();
    private final Map<String, Instance> instances = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 编排引擎（scaff-iagent 自动装配，负责会话/步骤留痕与事件发布） */
    private final OrchestrationEngine engine;

    public OrchestrateService(OrchestrationEngine engine) {
        this.engine = engine;
    }

    // ------------------------------------------------------------------ plan

    /** 计划生成（同步）：模板优先匹配相似目标，无模板时 LLM 分解（仿真）为子任务并构建依赖 DAG */
    public OrchestrateDtos.PlanResult plan(OrchestrateDtos.PlanRequest request, String requestId) {
        OrchestrateDtos.PlanResult cached = idempotency.get("plan", requestId);
        if (cached != null) {
            return cached;
        }
        if (request == null || request.goal() == null || request.goal().isBlank()) {
            throw new BaiException(OrchestrateErrorCodes.PARAM_INVALID, "AGO_1001 参数缺失：goal 目标任务描述必填");
        }
        if (request.goal().length() > MAX_GOAL_LEN) {
            throw new BaiException(OrchestrateErrorCodes.GOAL_TOO_LONG, "AGO_1006 目标超长（>2000 字），请缩减输入");
        }
        List<OrchestrateDtos.Step> steps;
        if (request.templateId() != null && !request.templateId().isBlank()) {
            String tplGoal = TEMPLATES.get(request.templateId());
            if (tplGoal == null) {
                throw new BaiException(OrchestrateErrorCodes.NOT_FOUND, "AGO_1004 模板不存在：" + request.templateId());
            }
            steps = buildSteps(tplGoal, request.constraints());
        } else {
            // LLM 分解仿真：按目标关键词确定性生成 DAG（联调用，结构同真实输出）
            steps = buildSteps(request.goal(), request.constraints());
        }
        // DAG 校验：拓扑排序检测有环/步骤非法；能力目录校验（不可用候选替换仿真：直接判定失败）
        boolean dagAcyclic = acyclic(steps);
        boolean agentsAvailable = steps.stream().allMatch(s -> CAPABILITY_CATALOG.contains(s.agent()));
        if (!dagAcyclic || !agentsAvailable) {
            throw new BaiException(OrchestrateErrorCodes.DAG_INVALID,
                    "AGO_1007 DAG 校验未通过（有环/步骤非法/能力不可用），acyclic=" + dagAcyclic + ", agentsAvailable=" + agentsAvailable);
        }
        String planId = "PLAN" + LocalDate.now() + String.format("%03d", seq.incrementAndGet() % 1000);
        Plan plan = new Plan(planId, request.goal(), steps);
        plans.put(planId, plan);
        OrchestrateDtos.PlanResult result = new OrchestrateDtos.PlanResult(
                planId, steps, steps.size(), new OrchestrateDtos.Validation(dagAcyclic, agentsAvailable));
        idempotency.put("plan", requestId, result);
        log.info("编排计划生成: requestId={}, planId={}, steps={}, simulation=true", requestId, planId, steps.size());
        return result;
    }

    // --------------------------------------------------------------- execute

    /** 编排执行（异步受理）：幂等 requestId+bizKey，同键返回已存在 instanceId */
    public OrchestrateDtos.InstanceResult execute(OrchestrateDtos.ExecuteRequest request, String requestId) {
        if (request == null || request.planId() == null || request.planId().isBlank()) {
            throw new BaiException(OrchestrateErrorCodes.PARAM_INVALID, "AGO_1001 参数缺失：planId 必填");
        }
        String bizKey = request.bizKey() == null ? "" : request.bizKey();
        OrchestrateDtos.InstanceResult cached = idempotency.get("exec", requestId + ":" + bizKey);
        if (cached != null) {
            return cached;
        }
        Plan plan = plans.get(request.planId());
        if (plan == null) {
            throw new BaiException(OrchestrateErrorCodes.NOT_FOUND, "AGO_1004 计划不存在：" + request.planId());
        }
        String instanceId = "INS" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000);
        // 将 DAG 注册到编排引擎会话（scaff-iagent 契约：TaskItem(scene=agent, slots=input, dependencies)）
        List<TaskItem> taskItems = new ArrayList<>();
        for (OrchestrateDtos.Step s : plan.steps) {
            taskItems.add(new TaskItem(s.agent(), s.input(), s.dependsOn()));
        }
        AgentSession session;
        try {
            session = engine.startSession(requestId, taskItems);
        } catch (Exception e) {
            // AGO_2001 引擎不可用降级：模板编排兜底（仿真环境直接以本地调度继续，标记降级）
            log.warn("编排引擎不可用，走模板降级路径: {}", e.getMessage());
            session = null;
        }
        Instance ins = new Instance(instanceId, plan.planId, requestId, bizKey, plan.steps, session == null ? null : session.getSessionId());
        ins.status = "PENDING";
        instances.put(instanceId, ins);
        runSteps(ins);
        OrchestrateDtos.InstanceResult result = new OrchestrateDtos.InstanceResult(
                instanceId, plan.planId, ins.status, System.currentTimeMillis() - ins.createdAtMillis);
        idempotency.put("exec", requestId + ":" + bizKey, result);
        log.info("编排实例受理: requestId={}, instanceId={}, status={}", requestId, instanceId, ins.status);
        return result;
    }

    // ------------------------------------------------------------- intervene

    /** 人工介入（同步）：PAUSE/RESUME/SKIP_STEP/REDIRECT_STEP/CANCEL；P0 介入需双人（网关侧校验权限点 AGO_CTRL） */
    public OrchestrateDtos.InstanceResult intervene(OrchestrateDtos.InterveneRequest request, String requestId) {
        long start = System.currentTimeMillis();
        if (request == null || isBlank(request.instanceId()) || isBlank(request.action())) {
            throw new BaiException(OrchestrateErrorCodes.PARAM_INVALID, "AGO_1001 参数缺失：instanceId/action 必填");
        }
        String action = request.action().toUpperCase();
        if (!INTERVENE_ACTIONS.contains(action)) {
            throw new BaiException(OrchestrateErrorCodes.PARAM_INVALID,
                    "AGO_1001 介入 action 非法，允许 PAUSE/RESUME/SKIP_STEP/REDIRECT_STEP/CANCEL");
        }
        Instance ins = instances.get(request.instanceId());
        if (ins == null) {
            throw new BaiException(OrchestrateErrorCodes.NOT_FOUND, "AGO_1004 实例不存在：" + request.instanceId());
        }
        if (TERMINAL_STATES.contains(ins.status)) {
            throw new BaiException(OrchestrateErrorCodes.STATE_CONFLICT,
                    "AGO_1005 实例已终态（" + ins.status + "），不可介入");
        }
        switch (action) {
            case "PAUSE" -> {
                Step current = currentStep(ins);
                engineQuietly(() -> engine.requireConfirm(ins.sessionId, current.stepId(),
                        "人工介入 PAUSE：" + safeReason(request.reason()), current.agent()));
                ins.status = "PAUSED";
            }
            case "RESUME" -> {
                if (!"PAUSED".equals(ins.status)) {
                    throw new BaiException(OrchestrateErrorCodes.STATE_CONFLICT, "AGO_1005 仅 PAUSED 实例可 RESUME");
                }
                Step current = currentStep(ins);
                engineQuietly(() -> engine.confirm(ins.sessionId, current.stepId(), true, requestId));
                ins.cursor++;
                runSteps(ins);
            }
            case "SKIP_STEP" -> {
                int idx = stepIndex(ins, request.stepId());
                if (idx < ins.cursor) {
                    throw new BaiException(OrchestrateErrorCodes.STATE_CONFLICT, "AGO_1005 步骤已执行完成，不可跳过");
                }
                Step s = ins.steps.get(idx);
                engineQuietly(() -> engine.completeStep(ins.sessionId, s.stepId(), "SKIPPED",
                        "SKIP_STEP 人工跳过：" + safeReason(request.reason())));
                ins.cursor = idx + 1;
                runSteps(ins);
            }
            case "REDIRECT_STEP" -> {
                int idx = stepIndex(ins, request.stepId());
                if (isBlank(request.targetAgent())) {
                    throw new BaiException(OrchestrateErrorCodes.PARAM_INVALID, "AGO_1001 REDIRECT_STEP 需提供 targetAgent");
                }
                if (!CAPABILITY_CATALOG.contains(request.targetAgent())) {
                    throw new BaiException(OrchestrateErrorCodes.DAG_INVALID,
                            "AGO_1007 重定向目标智能体能力不可用：" + request.targetAgent());
                }
                Step old = ins.steps.get(idx);
                ins.steps.set(idx, new OrchestrateDtos.Step(old.stepId(), request.targetAgent(),
                        old.dependsOn(), old.mode(), old.retry(), old.input()));
                engineQuietly(() -> engine.completeStep(ins.sessionId, old.stepId(), "REDIRECTED",
                        "REDIRECT_STEP 重定向至 " + request.targetAgent()));
            }
            case "CANCEL" -> {
                Step current = currentStep(ins);
                engineQuietly(() -> engine.failStep(ins.sessionId, current.stepId(), "CANCELLED",
                        "人工介入 CANCEL：" + safeReason(request.reason())));
                ins.status = "CANCELLED";
            }
            default -> throw new BaiException(OrchestrateErrorCodes.PARAM_INVALID, "AGO_1001 介入 action 非法");
        }
        log.info("人工介入: requestId={}, instanceId={}, action={}, status={}", requestId, ins.instanceId, action, ins.status);
        return new OrchestrateDtos.InstanceResult(ins.instanceId, ins.planId, ins.status, System.currentTimeMillis() - start);
    }

    // -------------------------------------------------------------- instances

    /** 编排实例查询 */
    public List<OrchestrateDtos.InstanceSummary> instances() {
        List<OrchestrateDtos.InstanceSummary> list = new ArrayList<>();
        for (Instance ins : instances.values()) {
            list.add(new OrchestrateDtos.InstanceSummary(ins.instanceId, ins.planId, ins.requestId,
                    ins.bizKey, ins.status, ins.createdAt));
        }
        return list;
    }

    // --------------------------------------------------------------- internal

    /** 拓扑调度仿真：按拓扑序推进，人工介入点（P0）暂停等待双人复核；剩余步骤完成后 SUCCESS */
    private void runSteps(Instance ins) {
        ins.status = "RUNNING";
        while (ins.cursor < ins.steps.size()) {
            OrchestrateDtos.Step s = ins.steps.get(ins.cursor);
            boolean humanPoint = Boolean.TRUE.equals(s.input().get("humanPoint"));
            if (humanPoint) {
                engineQuietly(() -> engine.requireConfirm(ins.sessionId, s.stepId(),
                        "P0 资金类人工介入点：等待双人复核", s.agent()));
                ins.status = "PAUSED";
                return;
            }
            engineQuietly(() -> engine.completeStep(ins.sessionId, s.stepId(), "SUCCESS", "仿真执行成功，用于联调"));
            ins.cursor++;
        }
        ins.status = "SUCCESS";
    }

    /**
     * LLM 分解仿真（确定性，用于联调）：目标含"提前还款"生成示例 6 步 DAG，否则生成通用 DAG。
     * constraints.humanPoint 存在时通用 DAG 追加 dialog-agent 人工确认步。
     */
    private List<OrchestrateDtos.Step> buildSteps(String goal, Map<String, Object> constraints) {
        List<OrchestrateDtos.Step> steps = new ArrayList<>();
        if (goal != null && goal.contains("提前还款")) {
            steps.add(new OrchestrateDtos.Step("s1", "intent-agent", List.of(), "SYNC", 2, Map.of("from", "goal")));
            steps.add(new OrchestrateDtos.Step("s2", "ner-agent", List.of("s1"), "SYNC", 2, Map.of("from", "s1.intent")));
            steps.add(new OrchestrateDtos.Step("s3", "call-exec-agent", List.of("s2"), "SYNC", 1,
                    Map.of("from", "s2.slots", "definitionId", "DEF_REPAY_QUERY")));
            steps.add(new OrchestrateDtos.Step("s4", "dialog-agent", List.of("s3"), "SYNC", 1,
                    Map.of("from", "s3.result", "humanPoint", true)));
            steps.add(new OrchestrateDtos.Step("s5", "call-exec-agent", List.of("s4"), "SYNC", 3,
                    Map.of("from", "s4.confirmed", "definitionId", "DEF_EARLY_REPAY")));
            steps.add(new OrchestrateDtos.Step("s6", "notify-agent", List.of("s5"), "ASYNC", 1, Map.of("from", "s5.result")));
            return steps;
        }
        boolean humanPoint = constraints != null && constraints.containsKey("humanPoint");
        steps.add(new OrchestrateDtos.Step("s1", "intent-agent", List.of(), "SYNC", 2, Map.of("from", "goal")));
        steps.add(new OrchestrateDtos.Step("s2", "call-exec-agent", List.of("s1"), "SYNC", 1,
                Map.of("from", "s1.intent", "definitionId", "DEF_QUERY")));
        if (humanPoint) {
            steps.add(new OrchestrateDtos.Step("s3", "dialog-agent", List.of("s2"), "SYNC", 1,
                    Map.of("from", "s2.result", "humanPoint", true)));
            steps.add(new OrchestrateDtos.Step("s4", "notify-agent", List.of("s3"), "ASYNC", 1, Map.of("from", "s3.result")));
        } else {
            steps.add(new OrchestrateDtos.Step("s3", "notify-agent", List.of("s2"), "ASYNC", 1, Map.of("from", "s2.result")));
        }
        return steps;
    }

    /** Kahn 拓扑排序：有环或 dependsOn 引用不存在步骤返回 false（有环 422/AGO_1007） */
    private boolean acyclic(List<OrchestrateDtos.Step> steps) {
        Map<String, Integer> indeg = new HashMap<>();
        Map<String, List<String>> adj = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        for (OrchestrateDtos.Step s : steps) {
            if (!ids.add(s.stepId())) {
                return false; // 步骤 ID 重复，视为非法
            }
            indeg.put(s.stepId(), 0);
        }
        for (OrchestrateDtos.Step s : steps) {
            for (String d : s.dependsOn()) {
                if (!ids.contains(d)) {
                    return false; // 依赖的步骤不存在
                }
                adj.computeIfAbsent(d, k -> new ArrayList<>()).add(s.stepId());
                indeg.merge(s.stepId(), 1, Integer::sum);
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        indeg.forEach((k, v) -> {
            if (v == 0) {
                queue.add(k);
            }
        });
        int seen = 0;
        while (!queue.isEmpty()) {
            String node = queue.poll();
            seen++;
            for (String next : adj.getOrDefault(node, List.of())) {
                if (indeg.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        return seen == ids.size();
    }

    private int stepIndex(Instance ins, String stepId) {
        if (isBlank(stepId)) {
            throw new BaiException(OrchestrateErrorCodes.PARAM_INVALID, "AGO_1001 参数缺失：stepId 必填");
        }
        for (int i = 0; i < ins.steps.size(); i++) {
            if (ins.steps.get(i).stepId().equals(stepId)) {
                return i;
            }
        }
        throw new BaiException(OrchestrateErrorCodes.NOT_FOUND, "AGO_1004 步骤不存在：" + stepId);
    }

    private OrchestrateDtos.Step currentStep(Instance ins) {
        return ins.steps.get(Math.min(ins.cursor, ins.steps.size() - 1));
    }

    /** 引擎调用联调容错：异常仅告警不阻断本地调度留痕 */
    private void engineQuietly(Runnable call) {
        try {
            call.run();
        } catch (Exception e) {
            log.warn("scaff-iagent 引擎调用异常（降级留痕）: {}", e.getMessage());
        }
    }

    private String safeReason(String reason) {
        return reason == null || reason.isBlank() ? "-" : reason;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static final class Plan {
        final String planId;
        final String goal;
        final List<OrchestrateDtos.Step> steps;

        Plan(String planId, String goal, List<OrchestrateDtos.Step> steps) {
            this.planId = planId;
            this.goal = goal;
            this.steps = steps;
        }
    }

    private static final class Instance {
        final String instanceId;
        final String planId;
        final String requestId;
        final String bizKey;
        final List<OrchestrateDtos.Step> steps; // 可变：REDIRECT_STEP 需替换 agent
        final String sessionId;                 // scaff-iagent 编排会话号
        final LocalDateTime createdAt = LocalDateTime.now();
        final long createdAtMillis = System.currentTimeMillis();
        volatile String status;
        volatile int cursor = 0;

        Instance(String instanceId, String planId, String requestId, String bizKey,
                 List<OrchestrateDtos.Step> steps, String sessionId) {
            this.instanceId = instanceId;
            this.planId = planId;
            this.requestId = requestId;
            this.bizKey = bizKey;
            this.steps = new ArrayList<>(steps);
            this.sessionId = sessionId;
        }
    }
}
