package com.bai.tasksched;

import com.bai.common.BaiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskScheduleService 单元测试：cron 6 段秒级解析、调度注册/更新、幂等、立即触发、
 * 实例状态机 PAUSE/RESUME/CANCEL/RETRY 与非法迁移冲突。
 */
class TaskScheduleServiceTest {

    private TaskScheduleService service;

    @BeforeEach
    void setUp() {
        service = new TaskScheduleService();
    }

    private Map<String, Object> target(String agent, String action) {
        return Map.of("agent", agent, "action", action, "params", Map.of("orgId", "ORG001"));
    }

    // ------------------------------------------------------------ 注册

    @Test
    void 调度注册_ACTIVE且nextFireTime有效() {
        TaskScheduleDtos.ScheduleResult r = service.schedule("nightly-close",
                "0 0 9 * * *", target("repayment-plan-agent", "generate"), Map.of("retry", 3), "tsk-1");
        assertTrue(r.scheduleId().startsWith("SCH"));
        assertEquals("nightly-close", r.name());
        assertEquals("ACTIVE", r.status());
        assertNotNull(r.nextFireTime());
        assertTrue(r.nextFireTime().contains("T"));   // ISO 偏移时间
    }

    @Test
    void 同requestId同name_幂等返回首次scheduleId() {
        String rid = "tsk-idem";
        TaskScheduleDtos.ScheduleResult a = service.schedule("daily-task", "0 0 8 * * *",
                target("report-gen-agent", "generate"), null, rid);
        TaskScheduleDtos.ScheduleResult b = service.schedule("daily-task", "0 0 8 * * *",
                target("report-gen-agent", "generate"), null, rid);
        assertEquals(a.scheduleId(), b.scheduleId());
    }

    @Test
    void 同名单重注册_覆盖cron且保持ACTIVE() {
        TaskScheduleDtos.ScheduleResult a = service.schedule("daily-task", "0 0 8 * * *",
                target("report-gen-agent", "generate"), null, "tsk-r1");
        TaskScheduleDtos.ScheduleResult b = service.schedule("daily-task", "0 0 12 * * *",
                target("report-gen-agent", "generate"), null, "tsk-r2");
        assertEquals(a.scheduleId(), b.scheduleId());   // 更新语义复用 scheduleId
        assertEquals("0 0 12 * * *", b.cron());
        assertEquals("ACTIVE", b.status());
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    void name与cron缺失_抛TSK1001() {
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.schedule(null, "0 0 9 * * *", target("a", "b"), null, "tsk-e1"));
        assertEquals("TSK_1001", e1.getCode());
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.schedule("t", null, target("a", "b"), null, "tsk-e2"));
        assertEquals("TSK_1001", e2.getCode());
    }

    @Test
    void cron非法_抛TSK1001() {
        // 5 段（非 6 段秒级）
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.schedule("t", "0 0 9 * *", target("a", "b"), null, "tsk-e3"));
        assertEquals("TSK_1001", e1.getCode());
        // 秒域取值越界（60）
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.schedule("t", "60 0 9 * * *", target("a", "b"), null, "tsk-e4"));
        assertEquals("TSK_1001", e2.getCode());
    }

    @Test
    void target缺失或agent缺失_抛TSK1001() {
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.schedule("t", "0 0 9 * * *", null, null, "tsk-e5"));
        assertEquals("TSK_1001", e1.getCode());
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.schedule("t", "0 0 9 * * *", Map.of("action", "run"), null, "tsk-e6"));
        assertEquals("TSK_1001", e2.getCode());
    }

    // ------------------------------------------------------------ 触发与状态机

    @Test
    void 触发_立即生成RUNNING实例() {
        TaskScheduleDtos.ScheduleResult s = service.schedule("on-demand", "0 0 9 * * *",
                target("todo-agent", "create"), null, "tsk-t1");
        TaskScheduleDtos.TriggerResult t = service.trigger(s.scheduleId());
        assertTrue(t.instanceId().startsWith("INS"));
        assertEquals("RUNNING", t.status());
        TaskScheduleDtos.InstanceList list = service.instances(s.scheduleId());
        assertEquals(1, list.total());
        assertEquals("RUNNING", list.items().get(0).status());
    }

    @Test
    void 状态机_PAUSE_RESUME_CANCEL_RETRY流转() {
        TaskScheduleDtos.ScheduleResult s = service.schedule("stm", "0 0 9 * * *",
                target("a", "b"), null, "tsk-s1");
        String instanceId = service.trigger(s.scheduleId()).instanceId();
        assertEquals("PAUSED", service.instanceAction(instanceId, "PAUSE").status());
        assertEquals("RUNNING", service.instanceAction(instanceId, "RESUME").status());
        assertEquals("CANCELLED", service.instanceAction(instanceId, "CANCEL").status());
        assertEquals("RUNNING", service.instanceAction(instanceId, "RETRY").status());
        // RUNNING 状态下 RESUME 非法迁移 → TSK_1005
        BaiException e = assertThrows(BaiException.class,
                () -> service.instanceAction(instanceId, "RESUME"));
        assertEquals("TSK_1005", e.getCode());
    }

    @Test
    void 实例不存在_抛TSK1004_调度不存在_抛TSK1004() {
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.instanceAction("NO-INS", "PAUSE"));
        assertEquals("TSK_1004", e1.getCode());
        BaiException e2 = assertThrows(BaiException.class, () -> service.trigger("NO-SCH"));
        assertEquals("TSK_1004", e2.getCode());
        BaiException e3 = assertThrows(BaiException.class,
                () -> service.instanceAction(null, "PAUSE"));
        assertEquals("TSK_1001", e3.getCode());
    }
}
