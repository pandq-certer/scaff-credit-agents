package com.bai.orchestrate;

import com.bai.common.BaiException;
import com.spatix.scaff.iagent.model.AgentSession;
import com.spatix.scaff.iagent.orch.OrchestrationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * OrchestrateService 单元测试：计划生成（模板/LLM 分解仿真 + DAG 校验）、编排执行（拓扑调度）、
 * 人工介入、幂等。
 * <p>OrchestrationEngine 用 Mockito mock（scaff-iagent 编排契约，异常仅告警不阻断本地调度）。
 */
class OrchestrateServiceTest {

    private OrchestrationEngine engine;
    private OrchestrateService service;

    @BeforeEach
    void setUp() {
        engine = Mockito.mock(OrchestrationEngine.class);
        service = new OrchestrateService(engine);
        AgentSession session = new AgentSession();
        session.setSessionId("SES-ENG-001");
        when(engine.startSession(anyString(), anyList())).thenReturn(session);
    }

    // ------------------------------------------------------------ 计划生成

    @Test
    void 提前还款目标_生成6步DAG且校验通过() {
        OrchestrateDtos.PlanResult r = service.plan(
                new OrchestrateDtos.PlanRequest("客户通过语音办理提前还款业务", null, null), "req-p1");
        assertEquals(6, r.steps().size());
        assertTrue(r.validation().acyclic());
        assertTrue(r.validation().agentsAvailable());
        // 第 4 步为人工确认点（dialog-agent）
        assertEquals("dialog-agent", r.steps().get(3).agent());
        assertTrue(Boolean.TRUE.equals(r.steps().get(3).input().get("humanPoint")));
    }

    @Test
    void 通用目标_生成3步DAG() {
        OrchestrateDtos.PlanResult r = service.plan(
                new OrchestrateDtos.PlanRequest("查询客户授信信息", null, null), "req-p2");
        assertEquals(3, r.steps().size());
        assertEquals("notify-agent", r.steps().get(2).agent());
    }

    @Test
    void 目标为空_抛AGO1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.plan(new OrchestrateDtos.PlanRequest("", null, null), "req-e1"));
        assertEquals("AGO_1001", e.getCode());
    }

    @Test
    void 目标超2000字_抛AGO1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.plan(new OrchestrateDtos.PlanRequest("a".repeat(2001), null, null), "req-e2"));
        assertEquals("AGO_1006", e.getCode());
    }

    @Test
    void 模板不存在_抛AGO1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.plan(new OrchestrateDtos.PlanRequest("任意目标", null, "TPL_NOPE"), "req-e3"));
        assertEquals("AGO_1004", e.getCode());
    }

    // ------------------------------------------------------------ 执行

    @Test
    void 执行通用计划_状态SUCCESS() {
        OrchestrateDtos.PlanResult plan = service.plan(
                new OrchestrateDtos.PlanRequest("查询客户授信信息", null, null), "req-x1");
        OrchestrateDtos.InstanceResult r = service.execute(
                new OrchestrateDtos.ExecuteRequest(plan.planId(), null, "biz-1"), "req-x2");
        assertEquals("SUCCESS", r.status());
        assertEquals(plan.planId(), r.planId());
        assertTrue(r.instanceId().startsWith("INS"));
        assertEquals(1, service.instances().size());
    }

    @Test
    void 执行提前还款计划_在人工点PAUSED() {
        OrchestrateDtos.PlanResult plan = service.plan(
                new OrchestrateDtos.PlanRequest("客户提前还款申请", null, null), "req-y1");
        OrchestrateDtos.InstanceResult r = service.execute(
                new OrchestrateDtos.ExecuteRequest(plan.planId(), null, "biz-2"), "req-y2");
        assertEquals("PAUSED", r.status());   // P0 人工介入点暂停等待双人复核
    }

    @Test
    void 执行_planId缺失抛AGO1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.execute(new OrchestrateDtos.ExecuteRequest("", null, "biz"), "req-e4"));
        assertEquals("AGO_1001", e.getCode());
    }

    @Test
    void 执行_计划不存在抛AGO1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.execute(new OrchestrateDtos.ExecuteRequest("PLAN-GHOST", null, "biz"), "req-e5"));
        assertEquals("AGO_1004", e.getCode());
    }

    @Test
    void 同requestId同bizKey_返回首次instanceId() {
        OrchestrateDtos.PlanResult plan = service.plan(
                new OrchestrateDtos.PlanRequest("查询客户授信信息", null, null), "req-i1");
        String rid = "idem-o1";
        OrchestrateDtos.InstanceResult a = service.execute(
                new OrchestrateDtos.ExecuteRequest(plan.planId(), null, "biz-idem"), rid);
        OrchestrateDtos.InstanceResult b = service.execute(
                new OrchestrateDtos.ExecuteRequest(plan.planId(), null, "biz-idem"), rid);
        assertEquals(a.instanceId(), b.instanceId());
    }

    // ------------------------------------------------------------ 人工介入

    @Test
    void 暂停后恢复_执行至SUCCESS() {
        OrchestrateDtos.PlanResult plan = service.plan(
                new OrchestrateDtos.PlanRequest("客户提前还款申请", null, null), "req-z1");
        OrchestrateDtos.InstanceResult paused = service.execute(
                new OrchestrateDtos.ExecuteRequest(plan.planId(), null, "biz-3"), "req-z2");
        assertEquals("PAUSED", paused.status());
        OrchestrateDtos.InstanceResult resumed = service.intervene(
                new OrchestrateDtos.InterveneRequest(paused.instanceId(), "RESUME", null, null, null), "req-z3");
        assertEquals("SUCCESS", resumed.status());
    }

    @Test
    void 介入action非法_抛AGO1001() {
        OrchestrateDtos.PlanResult plan = service.plan(
                new OrchestrateDtos.PlanRequest("查询客户授信信息", null, null), "req-w1");
        OrchestrateDtos.InstanceResult r = service.execute(
                new OrchestrateDtos.ExecuteRequest(plan.planId(), null, "biz-4"), "req-w2");
        BaiException e = assertThrows(BaiException.class,
                () -> service.intervene(new OrchestrateDtos.InterveneRequest(r.instanceId(), "EXPLODE", null, null, null), "req-w3"));
        assertEquals("AGO_1001", e.getCode());
    }

    @Test
    void 介入_实例不存在抛AGO1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.intervene(new OrchestrateDtos.InterveneRequest("INS-GHOST", "CANCEL", null, null, null), "req-w4"));
        assertEquals("AGO_1004", e.getCode());
    }

    @Test
    void 终态实例不可介入_抛AGO1005() {
        OrchestrateDtos.PlanResult plan = service.plan(
                new OrchestrateDtos.PlanRequest("查询客户授信信息", null, null), "req-v1");
        OrchestrateDtos.InstanceResult r = service.execute(
                new OrchestrateDtos.ExecuteRequest(plan.planId(), null, "biz-5"), "req-v2");
        assertEquals("SUCCESS", r.status());
        BaiException e = assertThrows(BaiException.class,
                () -> service.intervene(new OrchestrateDtos.InterveneRequest(r.instanceId(), "CANCEL", null, null, null), "req-v3"));
        assertEquals("AGO_1005", e.getCode());
    }

    @Test
    void 重定向能力不可用_抛AGO1007() {
        OrchestrateDtos.PlanResult plan = service.plan(
                new OrchestrateDtos.PlanRequest("客户提前还款申请", null, null), "req-u1");
        OrchestrateDtos.InstanceResult r = service.execute(
                new OrchestrateDtos.ExecuteRequest(plan.planId(), null, "biz-6"), "req-u2");
        assertEquals("PAUSED", r.status());
        BaiException e = assertThrows(BaiException.class,
                () -> service.intervene(new OrchestrateDtos.InterveneRequest(r.instanceId(), "REDIRECT_STEP",
                        "s4", "hack-agent", "换"), "req-u3"));
        assertEquals("AGO_1007", e.getCode());
    }
}
