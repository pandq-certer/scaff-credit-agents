package com.bai.permission;

import com.bai.common.BaiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PermissionCheckService 单元测试（设计文档 46-H 表 11-1 节选）：
 * 角色授权 / Deny-Override / 角色继承 / 默认拒绝 / 幂等 / 错误码 / 数据范围（机构+列级）/ 授权申请级别与幂等。
 */
class PermissionCheckServiceTest {

    private PermissionCheckService service;

    @BeforeEach
    void setUp() {
        service = new PermissionCheckService();
    }

    private PermissionCheckDtos.Identity identity(String userId) {
        return new PermissionCheckDtos.Identity(userId, null, null, null);
    }

    private PermissionCheckDtos.OperationCheckRequest checkReq(String userId, String operation) {
        return new PermissionCheckDtos.OperationCheckRequest(identity(userId), operation, null, null);
    }

    // ----------------------------------------------------------- 操作权限校验

    @Test
    void 角色授权_命中角色策略返回ALLOW() {
        PermissionCheckDtos.CheckResult r = service.check(checkReq("u001", "CUST_VIEW"), "c1");
        assertEquals("ALLOW", r.decision());
        assertEquals("DENY_OVERRIDE", r.evaluateChain());
        assertFalse(r.matchedPolicies().isEmpty());
        assertEquals("ROLE", r.matchedPolicies().get(0).source());
        assertEquals("ALLOW", r.matchedPolicies().get(0).effect());
        assertNotNull(r.checkId());
        assertTrue(r.checkId().startsWith("PMC"));
    }

    @Test
    void Deny覆盖_直接拒绝优先于角色授权抛PMC_1003() {
        // u002：角色 ROLE_DEPT_HEAD 授权 LOAN_APPROVE（ALLOW）+ 直接授权 DG_002_01 DENY → Deny-Override
        BaiException e = assertThrows(BaiException.class,
                () -> service.check(checkReq("u002", "LOAN_APPROVE"), "c2"));
        assertEquals("PMC_1003", e.getCode());
        assertTrue(e.getMessage().contains("Deny-Override"));
    }

    @Test
    void 角色继承_父角色权限以INHERIT源生效() {
        // u002 无 CUST_VIEW 直配，经父角色 ROLE_CUSTOMER_MANAGER 继承获得
        PermissionCheckDtos.CheckResult r = service.check(checkReq("u002", "CUST_VIEW"), "c3");
        assertEquals("ALLOW", r.decision());
        PermissionCheckDtos.MatchedPolicy p = r.matchedPolicies().get(0);
        assertEquals("INHERIT", p.source());
        assertEquals("ALLOW", p.effect());
    }

    @Test
    void 默认拒绝_无匹配策略返回DENY抛PMC_1003() {
        // u001 无 FRD_CHECK 相关策略 → 默认拒绝（安全优先）
        BaiException e = assertThrows(BaiException.class,
                () -> service.check(checkReq("u001", "FRD_CHECK"), "c4"));
        assertEquals("PMC_1003", e.getCode());
        assertTrue(e.getMessage().contains("默认拒绝"));
    }

    @Test
    void 幂等_同requestId同操作返回首次结论() {
        PermissionCheckDtos.CheckResult first = service.check(checkReq("u001", "LOAN_QUERY"), "idem-1");
        PermissionCheckDtos.CheckResult second = service.check(checkReq("u001", "LOAN_QUERY"), "idem-1");
        assertEquals(first.checkId(), second.checkId());
        // 同 requestId 不同操作 → 幂等键含 operation，另行校验
        PermissionCheckDtos.CheckResult other = service.check(checkReq("u001", "CUST_VIEW"), "idem-1");
        assertNotEquals(first.checkId(), other.checkId());
    }

    @Test
    void 权限点非法_抛PMC_1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.check(checkReq("u001", "HACK_OP"), "c5"));
        assertEquals("PMC_1007", e.getCode());
    }

    @Test
    void 用户不存在_抛PMC_1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.check(checkReq("u999", "CUST_VIEW"), "c6"));
        assertEquals("PMC_1004", e.getCode());
    }

    // ----------------------------------------------------------- 数据权限校验

    @Test
    void 数据权限_机构范围本级加下级() {
        PermissionCheckDtos.DataCheckResult r = service.dataCheck(
                new PermissionCheckDtos.DataCheckRequest(identity("u002"), "CUSTOMER", null, null, null), "d1");
        assertTrue(r.allowed());
        List<String> orgs = r.scopeExpression().orgs();
        assertTrue(orgs.contains("ORG_CREDIT_001"));
        // 机构范围含本级+下级，且不含越权机构 ORG_CREDIT_002
        assertFalse(orgs.contains("ORG_CREDIT_002"));
        assertTrue(r.scopeExpression().customers().contains("CUST_001"));
        // ORG 范围列级：CONTACT_LIST/INCOME 受限，证件/手机可见
        assertTrue(r.deniedColumns().contains("CONTACT_LIST"));
        assertFalse(r.deniedColumns().contains("CUST_ID"));
    }

    @Test
    void 数据权限_列级过滤受限列最小授权() {
        // u001 SELF 范围：INCOME 为敏感受限列
        PermissionCheckDtos.DataCheckResult r = service.dataCheck(
                new PermissionCheckDtos.DataCheckRequest(identity("u001"), "CUSTOMER", null,
                        List.of("CUST_ID", "INCOME", "CUST_NAME"), null), "d2");
        assertEquals(List.of("CUST_ID", "CUST_NAME"), r.columns());
        assertEquals(List.of("INCOME"), r.deniedColumns());
    }

    @Test
    void 数据权限_数据类型非法_抛PMC_1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.dataCheck(
                        new PermissionCheckDtos.DataCheckRequest(identity("u001"), "ASSET", null, null, null), "d3"));
        assertEquals("PMC_1007", e.getCode());
    }

    // ----------------------------------------------------------- 权限查询

    @Test
    void 权限查询_按用户过滤并分页() {
        PermissionCheckDtos.QueryResult r = service.query("u001", null, null, 1, 2);
        assertEquals(3, r.total()); // ROLE_CUSTOMER_MANAGER 3 个权限点
        assertEquals(2, r.items().size());
        assertTrue(r.items().stream().allMatch(i -> i.userId().equals("u001")));
        assertTrue(r.items().stream().allMatch(i -> "ROLE".equals(i.source())));
        // 分页第二页取剩余 1 条
        PermissionCheckDtos.QueryResult page2 = service.query("u001", null, null, 2, 2);
        assertEquals(1, page2.items().size());
    }

    @Test
    void 权限查询_角色不存在_抛PMC_1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.query(null, "ROLE_HACK", null, 1, 20));
        assertEquals("PMC_1004", e.getCode());
    }

    // ----------------------------------------------------------- 授权申请

    @Test
    void 授权申请_L1自助审批通过_L4双人审批挂起() {
        PermissionCheckDtos.GrantResult l1 = service.grant(
                new PermissionCheckDtos.GrantRequest(null, "u001", "u002", "CUST_VIEW", "CUST", "SELF",
                        "查看客户基础信息", null, "2026-11-10", "2026-12-31"), "g1");
        assertEquals("APPROVED", l1.status());
        assertEquals(1, l1.approvalChain().size());
        assertEquals("L1", l1.approvalChain().get(0).level());
        assertEquals("APPROVED", l1.approvalChain().get(0).status());

        PermissionCheckDtos.GrantResult l4 = service.grant(
                new PermissionCheckDtos.GrantRequest(null, "u004", "u003", "PMC_ADMIN", "PMC", null,
                        "全局权限申请", "BRANCH_APPROVER", "2026-11-10", "2026-12-31"), "g2");
        assertEquals("PENDING", l4.status());
        assertEquals(2, l4.approvalChain().size()); // 双人+分管
        assertTrue(l4.approvalChain().stream().allMatch(s -> "L4".equals(s.level()) && "PENDING".equals(s.status())));
    }

    @Test
    void 授权申请_重复申请抛PMC_1005() {
        PermissionCheckDtos.GrantRequest req = new PermissionCheckDtos.GrantRequest(
                null, "u003", "u002", "AML_CHECK", "AML", null, "敏感操作权限", null, "2026-11-10", "2026-12-31");
        PermissionCheckDtos.GrantResult first = service.grant(req, "g3");
        assertEquals("PENDING", first.status()); // L3 部门负责人审批
        BaiException e = assertThrows(BaiException.class, () -> service.grant(req, "g4"));
        assertEquals("PMC_1005", e.getCode());
        assertTrue(e.getMessage().contains("重复申请"));
    }

    @Test
    void 授权申请_按applyId幂等() {
        PermissionCheckDtos.GrantRequest req = new PermissionCheckDtos.GrantRequest(
                "GRA-TEST-001", "u001", "u002", "CUST_VIEW", "CUST", "SELF", "幂等验证", null, "2026-11-10", "2026-12-31");
        PermissionCheckDtos.GrantResult first = service.grant(req, "g5");
        PermissionCheckDtos.GrantResult second = service.grant(req, "g6");
        assertEquals(first.applyId(), second.applyId());
        assertEquals(first.status(), second.status());
        assertEquals(first.approvalChain(), second.approvalChain());
    }
}
