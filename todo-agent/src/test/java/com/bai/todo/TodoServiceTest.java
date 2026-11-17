package com.bai.todo;

import com.bai.common.BaiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TodoService 单元测试：待办创建（默认优先级 P2）、状态机 CLAIM→START→COMPLETE、CANCEL/TRANSFER、
 * 归属校验、终态冲突、幂等（requestId+source+bizId）、MINE/ORG 列表与统计。
 */
class TodoServiceTest {

    private TodoService service;

    @BeforeEach
    void setUp() {
        service = new TodoService();
    }

    private Map<String, Object> createBody(String source, String bizId, String title, Map<String, Object> assignee) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("source", source);
        m.put("bizId", bizId);
        m.put("title", title);
        m.put("assignee", assignee);
        return m;
    }

    private Map<String, Object> actionBody(String todoId, String action, String operator) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("todoId", todoId);
        m.put("action", action);
        if (operator != null) {
            m.put("operator", operator);
        }
        return m;
    }

    // ------------------------------------------------------------ 创建

    @Test
    void 创建待办_默认P2且状态PENDING() {
        TodoDtos.TodoView v = service.create(createBody("APPROVAL", "BIZ-001", "审批待办",
                Map.of("userId", "u1")), "tdo-1");
        assertTrue(v.todoId().startsWith("TDO"));
        assertEquals("PENDING", v.status());
        assertEquals("P2", v.priority());   // 缺省优先级 P2
        assertEquals("USER", v.assignee().type());
        assertEquals("u1", v.assignee().id());
    }

    @Test
    void 状态机_认领开始完成() {
        TodoDtos.TodoView v = service.create(createBody("APPROVAL", "BIZ-002", "审批待办",
                Map.of("userId", "u1")), "tdo-2");
        String id = v.todoId();
        assertEquals("CLAIMED", service.action(actionBody(id, "CLAIM", null), "tdo-2a").status());
        assertEquals("IN_PROGRESS", service.action(actionBody(id, "START", "u1"), "tdo-2b").status());
        assertEquals("DONE", service.action(actionBody(id, "COMPLETE", "u1"), "tdo-2c").status());
        TodoDtos.TodoView done = service.list("MINE", "u1", null).get(0);
        assertEquals("DONE", done.status());
    }

    @Test
    void 重复领取_抛TDO1005() {
        TodoDtos.TodoView v = service.create(createBody("APPROVAL", "BIZ-003", "审批待办",
                Map.of("userId", "u1")), "tdo-3");
        service.action(actionBody(v.todoId(), "CLAIM", null), "tdo-3a");
        BaiException e = assertThrows(BaiException.class,
                () -> service.action(actionBody(v.todoId(), "CLAIM", null), "tdo-3b"));
        assertEquals("TDO_1005", e.getCode());
    }

    @Test
    void 非归属人操作_抛TDO1003() {
        TodoDtos.TodoView v = service.create(createBody("APPROVAL", "BIZ-004", "审批待办",
                Map.of("userId", "u1")), "tdo-4");
        BaiException e = assertThrows(BaiException.class,
                () -> service.action(actionBody(v.todoId(), "COMPLETE", "u2"), "tdo-4a"));
        assertEquals("TDO_1003", e.getCode());
    }

    @Test
    void 终态操作_抛TDO1006() {
        TodoDtos.TodoView v = service.create(createBody("APPROVAL", "BIZ-005", "审批待办",
                Map.of("userId", "u1")), "tdo-5");
        service.action(actionBody(v.todoId(), "CLAIM", null), "tdo-5a");
        service.action(actionBody(v.todoId(), "COMPLETE", "u1"), "tdo-5b");
        BaiException e = assertThrows(BaiException.class,
                () -> service.action(actionBody(v.todoId(), "CANCEL", "u1"), "tdo-5c"));
        assertEquals("TDO_1006", e.getCode());
    }

    @Test
    void 转办_改派受理人后可由新受理人完成() {
        TodoDtos.TodoView v = service.create(createBody("APPROVAL", "BIZ-006", "审批待办",
                Map.of("userId", "u1")), "tdo-6");
        TodoDtos.ActionResult t = service.action(transferBody(v.todoId(), "u2"), "tdo-6a");
        assertEquals("PENDING", t.status());   // TRANSFER 不改状态，仅改派
        service.action(actionBody(v.todoId(), "CLAIM", null), "tdo-6b");   // 新受理人认领
        assertEquals("DONE", service.action(actionBody(v.todoId(), "COMPLETE", "u2"), "tdo-6c").status());
    }

    private Map<String, Object> transferBody(String todoId, String to) {
        Map<String, Object> m = actionBody(todoId, "TRANSFER", "u1");
        m.put("transferTo", Map.of("userId", to));
        return m;
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    void 参数校验_多错误码() {
        // source 缺失 → TDO_1001
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.create(createBody(null, "BIZ-010", "待办", Map.of("userId", "u1")), "tdo-e1"));
        assertEquals("TDO_1001", e1.getCode());
        // source 未注册 → TDO_1006
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.create(createBody("XXX", "BIZ-010", "待办", Map.of("userId", "u1")), "tdo-e2"));
        assertEquals("TDO_1006", e2.getCode());
        // title 超 200 → TDO_1006
        BaiException e3 = assertThrows(BaiException.class,
                () -> service.create(createBody("APPROVAL", "BIZ-010", "x".repeat(201), Map.of("userId", "u1")), "tdo-e3"));
        assertEquals("TDO_1006", e3.getCode());
        // 受理人缺失 → TDO_1006
        BaiException e4 = assertThrows(BaiException.class,
                () -> service.create(createBody("APPROVAL", "BIZ-010", "待办", Map.of()), "tdo-e4"));
        assertEquals("TDO_1006", e4.getCode());
        // priority 非法 → TDO_1001
        Map<String, Object> bad = createBody("APPROVAL", "BIZ-010", "待办", Map.of("userId", "u1"));
        bad.put("priority", "P9");
        BaiException e5 = assertThrows(BaiException.class, () -> service.create(bad, "tdo-e5"));
        assertEquals("TDO_1001", e5.getCode());
        // dueTime 非法 → TDO_1001
        Map<String, Object> badTime = createBody("APPROVAL", "BIZ-010", "待办", Map.of("userId", "u1"));
        badTime.put("dueTime", "2026-11-30");
        BaiException e6 = assertThrows(BaiException.class, () -> service.create(badTime, "tdo-e6"));
        assertEquals("TDO_1001", e6.getCode());
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void 幂等_同requestId同source同bizId_返回首次todoId() {
        String rid = "tdo-idem";
        TodoDtos.TodoView a = service.create(createBody("APPROVAL", "BIZ-100", "待办", Map.of("userId", "u1")), rid);
        TodoDtos.TodoView b = service.create(createBody("APPROVAL", "BIZ-100", "待办", Map.of("userId", "u1")), rid);
        assertEquals(a.todoId(), b.todoId());
    }

    // ------------------------------------------------------------ 列表与统计

    @Test
    void list与stats_MINE_ORG过滤及统计() {
        service.create(createBody("APPROVAL", "BIZ-200", "审批待办", Map.of("userId", "u1")), "tdo-l1");
        service.create(createBody("DISBURSE", "BIZ-201", "放款待办", Map.of("orgId", "o1")), "tdo-l2");
        assertEquals(1, service.list("MINE", "u1", null).size());
        assertEquals(1, service.list("ORG", null, "o1").size());
        TodoDtos.Stats stats = service.stats();
        assertEquals(2, stats.total());
        assertEquals(2L, stats.byStatus().get("PENDING"));
        assertEquals(1L, stats.bySource().get("APPROVAL"));
        assertEquals(1L, stats.bySource().get("DISBURSE"));
        // scope 缺失
        BaiException e = assertThrows(BaiException.class, () -> service.list(null, "u1", null));
        assertEquals("TDO_1001", e.getCode());
    }
}
