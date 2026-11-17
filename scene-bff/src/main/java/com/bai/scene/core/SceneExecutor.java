package com.bai.scene.core;

import com.bai.scene.script.SceneScript;
import com.bai.scene.script.SceneStep;
import com.spatix.scaff.iagent.model.EventType;
import com.spatix.scaff.iagent.model.OrchestrationStep;
import com.spatix.scaff.iagent.model.StepState;
import com.spatix.scaff.iagent.model.TaskItem;
import com.spatix.scaff.iagent.orch.OrchestrationEngine;
import com.spatix.scaff.iagent.sse.SessionEventPublisher;
import com.spatix.scaff.iagent.store.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 场景编排执行器：把剧本步骤展开为 scaff-iagent OrchestrationStep（DAG），
 * 按拓扑序并行执行真实/占位原子调用，人工确认节点挂起等待自然语言确认后恢复。
 * 全部执行过程经 SessionEventPublisher 推送滚动式处理日志（SSE）。
 */
@Component
public class SceneExecutor {

    private static final Logger log = LoggerFactory.getLogger(SceneExecutor.class);

    private final OrchestrationEngine engine;
    private final SessionStore store;
    private final SessionEventPublisher publisher;
    private final AtomicInvoker invoker;
    private final SceneContextStore contextStore;

    public SceneExecutor(OrchestrationEngine engine, SessionStore store,
                         SessionEventPublisher publisher, AtomicInvoker invoker,
                         SceneContextStore contextStore) {
        this.engine = engine;
        this.store = store;
        this.publisher = publisher;
        this.invoker = invoker;
        this.contextStore = contextStore;
    }

    /** 启动场景：建会话 → 展开步骤 → 拓扑执行（同步返回，含确认挂起点） */
    public SceneContextStore.SceneContext start(SceneScript script, String userId, Map<String, Object> slots) {
        TaskItem task = new TaskItem(script.sceneId(), slots, List.of());
        var session = engine.startSession(userId, List.of(task));
        String sessionId = session.getSessionId();
        SceneContextStore.SceneContext ctx = contextStore.create(sessionId, script, userId, slots);

        for (SceneStep step : script.steps()) {
            OrchestrationStep os = new OrchestrationStep(step.stepId(), step.name(), step.agentId(),
                    step.dependsOn(), step.confirm());
            store.saveStep(sessionId, os);
        }
        publish(sessionId, "SYS", EventType.START, "已识别场景【" + script.title() + "】，开始执行处理链", null);
        runTopology(ctx);
        return ctx;
    }

    /** 确认恢复：approved=true 从挂起点继续拓扑；false 终止流程 */
    public SceneContextStore.SceneContext confirmResume(String sessionId, String pendingStepId, boolean approved) {
        SceneContextStore.SceneContext ctx = contextStore.find(sessionId);
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        engine.confirm(sessionId, pendingStepId, approved, traceId);
        if (approved) {
            engine.completeStep(sessionId, pendingStepId, "用户确认通过，继续执行后续处理链", traceId);
            ctx.pendingConfirmStepId = null;
            ctx.status = SceneContextStore.Status.RUNNING;
            runTopology(ctx);
        } else {
            ctx.status = SceneContextStore.Status.CANCELLED;
            ctx.blocks = List.of(Map.of("title", "已终止", "lines", List.of("您已选择暂不执行，本次流程终止。如需重新发起请直接说明诉求。")));
            publish(sessionId, pendingStepId, EventType.SUSPENDED, "用户选择不执行，流程终止", traceId);
        }
        return ctx;
    }

    /**
     * 拓扑执行：循环取「依赖全部 DONE」的 PENDING 步骤并行执行；
     * 遇确认节点挂起返回；真实调用失败按降级策略继续（对应各场景 E6xxx 降级条款）。
     */
    private void runTopology(SceneContextStore.SceneContext ctx) {
        String sessionId = ctx.sessionId;
        SceneScript script = ctx.script;
        while (true) {
            List<OrchestrationStep> steps = store.findSteps(sessionId);
            List<OrchestrationStep> ready = steps.stream()
                    .filter(s -> s.getState() == StepState.PENDING)
                    .filter(s -> s.getDependsOn() == null || s.getDependsOn().stream().allMatch(dep ->
                            steps.stream().filter(x -> x.getStepId().equals(dep)).findFirst()
                                    .map(x -> x.getState() == StepState.DONE).orElse(true)))
                    .toList();
            if (ready.isEmpty()) {
                boolean allDone = steps.stream().allMatch(s -> s.getState() == StepState.DONE);
                boolean anyFailed = steps.stream().anyMatch(s -> s.getState() == StepState.FAILED);
                if (allDone) {
                    ctx.status = SceneContextStore.Status.COMPLETED;
                    ctx.blocks = script.blocksFn().apply(ctx.slots, ctx.summaries, ctx.stepData);
                    publish(sessionId, "SYS", EventType.DONE, "场景处理链执行完毕，结果已分块呈现", null);
                } else if (anyFailed) {
                    ctx.status = SceneContextStore.Status.CANCELLED;
                    publish(sessionId, "SYS", EventType.FAILED, "流程已终止", null);
                }
                return;
            }
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (OrchestrationStep step : ready) {
                SceneStep def = script.steps().stream()
                        .filter(d -> d.stepId().equals(step.getStepId())).findFirst().orElseThrow();
                if (def.confirm()) {
                    // 人工确认节点：挂起
                    step.setState(StepState.SUSPENDED);
                    step.setDetail(script.confirmPrompt());
                    store.saveStep(sessionId, step);
                    ctx.pendingConfirmStepId = step.getStepId();
                    ctx.status = SceneContextStore.Status.AWAITING_CONFIRM;
                    publisher.publish(new com.spatix.scaff.iagent.model.ProcessLogEvent(
                            sessionId, step.getStepId(), 0, EventType.CONFIRM_REQUIRED,
                            step.getName() + " → 等待人工确认（" + script.confirmPrompt() + "）", null));
                } else {
                    futures.add(CompletableFuture.runAsync(() -> executeStep(ctx, def)));
                }
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            if (ctx.pendingConfirmStepId != null) {
                return; // 挂起等待确认
            }
        }
    }

    private void executeStep(SceneContextStore.SceneContext ctx, SceneStep def) {
        String sessionId = ctx.sessionId;
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        OrchestrationStep step = store.findSteps(sessionId).stream()
                .filter(s -> s.getStepId().equals(def.stepId())).findFirst().orElseThrow();
        step.setState(StepState.RUNNING);
        store.saveStep(sessionId, step);
        publisher.publish(new com.spatix.scaff.iagent.model.ProcessLogEvent(
                sessionId, def.stepId(), 0, EventType.START, def.name() + " …", traceId));

        String detail;
        if (def.placeholder()) {
            detail = "→ 完成（演示结果；" + def.agentId() + " 属后批开发原子，当前为占位实现，上线后自动替换）";
        } else {
            Object payload = def.payloadFn() == null ? null
                    : def.payloadFn().apply(ctx.slots, ctx.summaries, ctx.stepData);
            AtomicInvoker.InvokeResult r = invoker.post(def.url(), payload);
            if (r.ok()) {
                String summary = def.summaryFn() != null
                        ? def.summaryFn().apply(ctx.slots, r.data())
                        : "返回 data=" + shortData(r.data());
                detail = "→ 完成，" + summary;
            } else {
                // 降级：不阻断场景链（对应 E6003/E6015/E6031/E6036 等降级条款）
                detail = "→ 降级完成（调用失败：" + r.error() + "；按场景降级条款以演示数据继续，真接口联调阶段修复）";
            }
            if (r.ok() && r.data() != null) {
                // 降级/无返回体时不写入 stepData（ConcurrentHashMap 不接受 null 值）
                ctx.stepData.put(def.stepId(), r.data());
            }
        }
        ctx.summaries.put(def.stepId(), detail);
        engine.completeStep(sessionId, def.stepId(), def.name() + " " + detail, traceId);
    }

    private String shortData(com.fasterxml.jackson.databind.JsonNode data) {
        String t = data == null || data.isMissingNode() || data.isNull() ? "{}" : data.toString();
        return t.length() <= 80 ? t : t.substring(0, 80) + "…";
    }

    private void publish(String sessionId, String stepId, EventType type, String message, String traceId) {
        publisher.publish(new com.spatix.scaff.iagent.model.ProcessLogEvent(sessionId, stepId, 0, type, message, traceId));
    }
}
