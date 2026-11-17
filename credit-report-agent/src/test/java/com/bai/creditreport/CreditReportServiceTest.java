package com.bai.creditreport;

import com.bai.common.BaiException;
import com.spatix.scaff.iprov.ProviderResponse;
import com.spatix.scaff.iprov.provider.CreditReportProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * CreditReportService 单元测试（H 类风控原子）：授权三重校验、查询授权前置、Provider 调用、异议时限。
 * Provider 用 Mockito mock（对应 P04 征信 Provider 契约）。
 */
class CreditReportServiceTest {

    private CreditReportProvider provider;
    private CreditReportService service;

    @BeforeEach
    void setUp() {
        provider = Mockito.mock(CreditReportProvider.class);
        service = new CreditReportService(provider);
        // 默认 Provider 返回成功征信报告
        when(provider.query(anyString(), anyString(), anyString(), anyString())).thenReturn(
                ProviderResponse.success("req",
                        new CreditReportProvider.CreditReport("CRP-TEST-001",
                                new CreditReportProvider.Overdue(1, 0, 0),
                                new CreditReportProvider.Liability(3_500_000L, 2, 3),
                                new CreditReportProvider.Queries(4), "SIGN")));
    }

    private CreditReportDtos.AuthorizeRequest authReq(String action, String certNo, String name, String purpose) {
        return new CreditReportDtos.AuthorizeRequest(action, null,
                new CreditReportDtos.Customer(certNo, name),
                new CreditReportDtos.AuthDoc("DOC-001", certNo, name, "2026-11-01"),
                purpose, 365, null);
    }

    @Test
    void 授权创建_要素一致返回ACTIVE() {
        CreditReportDtos.AuthorizeResult r = service.authorize(
                authReq("CREATE", "110101199001011234", "张三", "信贷审批"), "cr-a1");
        assertEquals("ACTIVE", r.authStatus());
        assertTrue(r.purposeValid());
        assertEquals(365, r.validDays());
    }

    @Test
    void 授权创建_要素不一致返回INVALID() {
        CreditReportDtos.AuthorizeResult r = service.authorize(
                new CreditReportDtos.AuthorizeRequest("CREATE", null,
                        new CreditReportDtos.Customer("110101199001011234", "张三"),
                        new CreditReportDtos.AuthDoc("DOC-002", "110101199001011234", "李四", "2026-11-01"),
                        "信贷审批", 365, null), "cr-a2");
        assertEquals("INVALID", r.authStatus());
        assertNotNull(r.warning());
    }

    @Test
    void 用途不在白名单_抛CRR1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.authorize(authReq("CREATE", "110101199001011234", "张三", "个人消费"), "cr-a3"));
        assertEquals("CRR_1007", e.getCode());
    }

    @Test
    void 非法action_抛CRR1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.authorize(authReq("DELETE", "110101199001011234", "张三", "信贷审批"), "cr-a4"));
        assertEquals("CRR_1007", e.getCode());
    }

    @Test
    void 查询_凭有效授权返回报告() {
        CreditReportDtos.AuthorizeResult auth = service.authorize(
                authReq("CREATE", "110101199001011234", "张三", "信贷审批"), "cr-q0");
        CreditReportDtos.ReportResult report = service.query(
                new CreditReportDtos.ReportQueryRequest(auth.authId(), "PERSON", null), "cr-q1");
        assertEquals("CRP-TEST-001", report.reportNo());
        assertNotNull(report.liability());
    }

    @Test
    void 查询_授权不存在抛CRR1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.query(new CreditReportDtos.ReportQueryRequest("NO-AUTH", "PERSON", null), "cr-q2"));
        assertEquals("CRR_1004", e.getCode());
    }

    @Test
    void 异议受理_20日时限() {
        CreditReportDtos.DisputeResult r = service.dispute(
                new CreditReportDtos.DisputeRequest(null,
                        new CreditReportDtos.Customer("110101199001011234", "张三"),
                        "征信记录有误", null, null), "cr-d1");
        assertEquals("ACCEPTED", r.status());
        assertEquals(20, r.limitDays());
        assertNotNull(r.feedbackDueDate());
    }

    @Test
    void 同requestId_授权幂等() {
        String rid = "cr-idem";
        CreditReportDtos.AuthorizeResult a = service.authorize(
                authReq("CREATE", "110101199001011234", "张三", "信贷审批"), rid);
        CreditReportDtos.AuthorizeResult b = service.authorize(
                authReq("CREATE", "110101199001011234", "张三", "信贷审批"), rid);
        assertEquals(a.authId(), b.authId());
    }
}
