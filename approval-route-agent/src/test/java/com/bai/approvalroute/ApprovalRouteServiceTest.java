package com.bai.approvalroute;

import com.bai.common.BaiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApprovalRouteService 单元测试：路由规则匹配（金额/风险优先级命中即停）、会签/单人审批状态机、
 * 版本乐观锁、重复审批/终态冲突、非审批人拒绝。
 */
class ApprovalRouteServiceTest {

    private ApprovalRouteService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalRouteService();
    }

    private Map<String, Object> body(String bizType, Map<String, Object> context) {
        return Map.of("bizType", bizType, "context", context);
    }

    /** 路由并取首个任务 taskId（单任务场景） */
    private String routeTaskId(Map<String, Object> ctx) {
        ApprovalRouteDtos.RouteResult r = service.route(body("LOAN_APPROVAL", ctx), "apr-route");
        return service.queryTasks(Map.of("bizType", "LOAN_APPROVAL")).get(0).taskId();
    }

    // ------------------------------------------------------------ 路由

    @Test
    void 大额高风险_会签路由COUNTERSIGN() {
        ApprovalRouteDtos.RouteResult r = service.route(body("LOAN_APPROVAL",
                Map.of("amount", 60_000_000L, "riskLevel", "HIGH")), "apr-1");
        assertTrue(r.routeId().startsWith("RT"));
        assertEquals("支行信贷审批", r.nodeName());
        assertEquals(2, r.approvers().size());
        assertEquals("COUNTERSIGN", r.approvers().get(0).mode());   // 模式承载于审批人
        assertEquals(List.of("u2001", "u2002"), r.approvers().stream().map(a -> a.userId()).toList());
        assertEquals(48, r.timeLimit().hours());
        assertTrue(r.rulesHit().contains("rule-amount-5000w"));
        assertTrue(r.rulesHit().contains("rule-risk-high"));
    }

    @Test
    void 小额低风险_默认单人路由() {
        ApprovalRouteDtos.RouteResult r = service.route(body("LOAN_APPROVAL",
                Map.of("amount", 1_000_000L, "riskLevel", "LOW")), "apr-2");
        assertEquals("支行客户经理审批", r.nodeName());
        assertEquals(1, r.approvers().size());
        assertEquals("SINGLE", r.approvers().get(0).mode());
        assertEquals("u1001", r.approvers().get(0).userId());
        assertEquals(8, r.timeLimit().hours());
        assertEquals(List.of("rule-default"), r.rulesHit());
    }

    @Test
    void 路由参数缺失_抛APR1001() {
        BaiException e1 = assertThrows(BaiException.class, () -> service.route(Map.of(), "apr-e1"));
        assertEquals("APR_1001", e1.getCode());
        BaiException e2 = assertThrows(BaiException.class, () -> service.route(null, "apr-e2"));
        assertEquals("APR_1001", e2.getCode());
    }

    // ------------------------------------------------------------ 审批动作

    @Test
    void 会签_两票全部通过且版本递增() {
        String taskId = routeTaskId(Map.of("amount", 60_000_000L, "riskLevel", "HIGH"));
        ApprovalRouteDtos.ApproveResult v1 = service.approve(
                Map.of("taskId", taskId, "action", "APPROVE", "opinion", "同意"), "apr-a1", "u2001");
        assertEquals("PENDING", v1.status());   // 会签需全部同意
        assertEquals(2, v1.version());
        ApprovalRouteDtos.ApproveResult v2 = service.approve(
                Map.of("taskId", taskId, "action", "APPROVE"), "apr-a2", "u2002");
        assertEquals("APPROVED", v2.status());
        assertEquals(3, v2.version());
        ApprovalRouteDtos.ChainResult chain = service.chain(taskId, null);
        assertEquals("APPROVED", chain.status());
        assertEquals(2, chain.records().size());
    }

    @Test
    void 单人审批_一票即通过() {
        String taskId = routeTaskId(Map.of("amount", 1_000_000L, "riskLevel", "LOW"));
        ApprovalRouteDtos.ApproveResult r = service.approve(
                Map.of("taskId", taskId, "action", "APPROVE"), "apr-a3", "u1001");
        assertEquals("APPROVED", r.status());
        assertEquals(2, r.version());
    }

    @Test
    void 重复审批_抛APR1005() {
        String taskId = routeTaskId(Map.of("amount", 60_000_000L, "riskLevel", "HIGH"));
        service.approve(Map.of("taskId", taskId, "action", "APPROVE"), "apr-a4", "u2001");
        BaiException e = assertThrows(BaiException.class,
                () -> service.approve(Map.of("taskId", taskId, "action", "APPROVE"), "apr-a5", "u2001"));
        assertEquals("APR_1005", e.getCode());
    }

    @Test
    void 非审批人操作_抛APR1003() {
        String taskId = routeTaskId(Map.of("amount", 1_000_000L, "riskLevel", "LOW"));
        BaiException e = assertThrows(BaiException.class,
                () -> service.approve(Map.of("taskId", taskId, "action", "APPROVE"), "apr-a6", "u9999"));
        assertEquals("APR_1003", e.getCode());
    }

    @Test
    void 驳回_直接终态REJECTED且终态不可逆() {
        String taskId = routeTaskId(Map.of("amount", 1_000_000L, "riskLevel", "LOW"));
        ApprovalRouteDtos.ApproveResult r = service.approve(
                Map.of("taskId", taskId, "action", "REJECT"), "apr-a7", "u1001");
        assertEquals("REJECTED", r.status());
        BaiException e = assertThrows(BaiException.class,
                () -> service.approve(Map.of("taskId", taskId, "action", "APPROVE"), "apr-a8", "u1001"));
        assertEquals("APR_1005", e.getCode());   // 终态不可逆
    }

    @Test
    void 版本冲突_抛APR1005() {
        String taskId = routeTaskId(Map.of("amount", 1_000_000L, "riskLevel", "LOW"));
        BaiException e = assertThrows(BaiException.class,
                () -> service.approve(Map.of("taskId", taskId, "action", "APPROVE", "expectedVersion", 99),
                        "apr-a9", "u1001"));
        assertEquals("APR_1005", e.getCode());
    }

    @Test
    void 审批任务不存在与意见超长_抛错() {
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.approve(Map.of("taskId", "NO-TASK", "action", "APPROVE"), "apr-e3", "u1001"));
        assertEquals("APR_1004", e1.getCode());
        String taskId = routeTaskId(Map.of("amount", 1_000_000L, "riskLevel", "LOW"));
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.approve(Map.of("taskId", taskId, "action", "APPROVE",
                        "opinion", "x".repeat(501)), "apr-e4", "u1001"));
        assertEquals("APR_1001", e2.getCode());
    }
}
