package com.bai.external;

import com.bai.common.BaiException;
import com.spatix.scaff.iprov.ProviderResponse;
import com.spatix.scaff.iprov.provider.BusinessInfoProvider;
import com.spatix.scaff.iprov.provider.CourtInfoProvider;
import com.spatix.scaff.iprov.provider.CreditReportProvider;
import com.spatix.scaff.iprov.provider.CustomerProvider;
import com.spatix.scaff.iprov.provider.RiskListProvider;
import com.spatix.scaff.iprov.provider.TaxInfoProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * ExternalDataService 单元测试：九类数据源 Provider 接入（Mockito mock P04~P09 数据源族）、
 * 标准化映射、参数校验、幂等（30s）、降级 DEGRADED、数据源管理。
 */
class ExternalDataServiceTest {

    private CreditReportProvider creditReportProvider;
    private BusinessInfoProvider businessInfoProvider;
    private CourtInfoProvider courtInfoProvider;
    private TaxInfoProvider taxInfoProvider;
    private RiskListProvider riskListProvider;
    private CustomerProvider customerProvider;
    private ExternalDataService service;

    @BeforeEach
    void setUp() {
        creditReportProvider = Mockito.mock(CreditReportProvider.class);
        businessInfoProvider = Mockito.mock(BusinessInfoProvider.class);
        courtInfoProvider = Mockito.mock(CourtInfoProvider.class);
        taxInfoProvider = Mockito.mock(TaxInfoProvider.class);
        riskListProvider = Mockito.mock(RiskListProvider.class);
        customerProvider = Mockito.mock(CustomerProvider.class);
        service = new ExternalDataService(creditReportProvider, businessInfoProvider, courtInfoProvider,
                taxInfoProvider, riskListProvider, customerProvider);
        // 默认成功响应
        when(creditReportProvider.query(anyString(), anyString(), anyString(), anyString())).thenReturn(
                ProviderResponse.success("req", new CreditReportProvider.CreditReport("CRP-TEST-001",
                        new CreditReportProvider.Overdue(0, 0, 1),   // (max30, max90, currentOverdue)
                        new CreditReportProvider.Liability(3_500_000L, 2, 3),
                        new CreditReportProvider.Queries(4), "SIGN")));
        when(businessInfoProvider.getBusiness(anyString(), anyString())).thenReturn(
                ProviderResponse.success("req", new BusinessInfoProvider.Business("91110108MA01ABCX5A",
                        "北京华信科技有限公司", "张三", "ACTIVE", 100_000_000L,
                        List.of(new BusinessInfoProvider.Shareholder("李四", 0.6)))));
        when(courtInfoProvider.getCourtInfo(anyString(), anyString())).thenReturn(
                ProviderResponse.success("req", new CourtInfoProvider.CourtInfo(
                        List.of(new CourtInfoProvider.Case("(2026)京01民初123号", "借款合同纠纷", "审理中")),
                        new CourtInfoProvider.Execution(2), true)));
        when(taxInfoProvider.getTaxInfo(anyString(), any(), anyString())).thenReturn(
                ProviderResponse.success("req", new TaxInfoProvider.TaxInfo("91310115MA1KDEFY2B", 2025,
                        5_000_000L, 120_000L, 0, "A")));
        when(riskListProvider.match(anyString(), anyString(), anyString())).thenReturn(
                ProviderResponse.success("req", new RiskListProvider.RiskMatch(true,
                        List.of(new RiskListProvider.Check("法院被执行人", 0.9)), List.of("疑似涉赌"))));
        when(customerProvider.getCustomer(anyString(), anyString(), anyString())).thenReturn(
                ProviderResponse.success("req", new CustomerProvider.Customer("C001", "张三", "ID",
                        "110101199001011234", "LOW", false)));
    }

    private Map<String, Object> query(String... kvs) {
        Map<String, Object> m = new java.util.HashMap<>();
        for (int i = 0; i + 1 < kvs.length; i += 2) {
            m.put(kvs[i], kvs[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------ 成功路径

    @Test
    void CREDIT数据源_标准化字段映射成功() {
        ExternalDtos.ExternalFetchResult r = service.fetch("CREDIT",
                query("name", "张三", "idNo", "110101199001011234"), "准入", "exd-1", "risk-svc");
        assertEquals("SUCCESS", r.status());
        assertTrue(r.masked());
        assertNotNull(r.callId());
        assertEquals("CRP-TEST-001", r.standardData().get("reportNo"));
        assertEquals(1, r.standardData().get("overdueAccounts"));
        assertEquals(3_500_000L, r.standardData().get("creditLimit"));
        assertEquals(4, r.standardData().get("queryCount3m"));
        assertEquals(2, r.standardData().get("creditCards"));
    }

    @Test
    void INDUSTRY与JUDICIAL_映射正确且脱敏() {
        ExternalDtos.ExternalFetchResult biz = service.fetch("INDUSTRY",
                query("creditCode", "91110108MA01ABCX5A"), "尽调", "exd-2", "risk-svc");
        assertEquals("SUCCESS", biz.status());
        assertEquals("北京华信科技有限公司", biz.standardData().get("enterpriseName"));
        assertEquals("张*", biz.standardData().get("legalPerson"));   // 姓名脱敏
        assertEquals(100_000_000L, biz.standardData().get("registeredCapital"));
        assertEquals(1, ((List<?>) biz.standardData().get("shareholders")).size());
        ExternalDtos.ExternalFetchResult court = service.fetch("JUDICIAL",
                query("name", "张三"), "贷后", "exd-3", "risk-svc");
        assertEquals(1, ((List<?>) court.standardData().get("litigation")).size());
        assertEquals(2, court.standardData().get("executionCount"));
        assertEquals(true, court.standardData().get("dishonest"));
    }

    @Test
    void CUSTOMS与BLACKLIST_适配输出() {
        ExternalDtos.ExternalFetchResult cust = service.fetch("CUSTOMS",
                query("customerId", "C001", "name", "张三", "idNo", "110101199001011234"), "贷后", "exd-4", "risk-svc");
        assertEquals("SUCCESS", cust.status());
        assertEquals("C001", cust.standardData().get("customerId"));
        assertEquals("张*", cust.standardData().get("name"));
        assertTrue(cust.mappingWarnings().stream().anyMatch(w -> w.contains("按客户信息族适配")));
        ExternalDtos.ExternalFetchResult risk = service.fetch("BLACKLIST",
                query("name", "张三", "idNo", "110101199001011234"), "准入", "exd-5", "risk-svc");
        assertEquals(true, risk.standardData().get("hit"));
        assertEquals(1, ((List<?>) risk.standardData().get("amlWarnings")).size());
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    void sourceType非法_抛EXD1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.fetch("BAD_SOURCE", query("name", "张三"), "准入", "exd-e1", "risk-svc"));
        assertEquals("EXD_1001", e.getCode());
    }

    @Test
    void query为空与scenario缺失_抛EXD1001() {
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.fetch("CREDIT", Map.of(), "准入", "exd-e2", "risk-svc"));
        assertEquals("EXD_1001", e1.getCode());
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.fetch("CREDIT", query("name", "张三"), null, "exd-e3", "risk-svc"));
        assertEquals("EXD_1001", e2.getCode());
    }

    @Test
    void 批量超100条_抛EXD1005() {
        List<Object> items = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            items.add(Map.of("name", "客户" + i));
        }
        BaiException e = assertThrows(BaiException.class,
                () -> service.fetch("CREDIT", Map.of("batch", items), "准入", "exd-e4", "risk-svc"));
        assertEquals("EXD_1005", e.getCode());
    }

    @Test
    void requestId超32位_抛EXD1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.fetch("CREDIT", query("name", "张三"), "准入",
                        "this-request-id-is-way-too-long-0000000000000001", "risk-svc"));
        assertEquals("EXD_1001", e.getCode());
    }

    // ------------------------------------------------------------ 降级与幂等

    @Test
    void provider失败_降级返回DEGRADED() {
        // 重试 3 次（1s/3s 退避）后降级：约 4s
        when(riskListProvider.match(anyString(), anyString(), anyString())).thenReturn(null);
        ExternalDtos.ExternalFetchResult r = service.fetch("BLACKLIST",
                query("name", "张三", "idNo", "110101199001011234"), "准入", "exd-d1", "risk-svc");
        assertEquals("DEGRADED", r.status());
        assertEquals(true, r.standardData().get("degraded"));
        assertTrue(r.mappingWarnings().stream().anyMatch(w -> w.contains("已降级返回空数据")));
    }

    @Test
    void 幂等_30s内同requestId同query返回首次callId() {
        String rid = "exd-idem";
        ExternalDtos.ExternalFetchResult a = service.fetch("CREDIT",
                query("name", "张三", "idNo", "110101199001011234"), "准入", rid, "risk-svc");
        ExternalDtos.ExternalFetchResult b = service.fetch("CREDIT",
                query("name", "张三", "idNo", "110101199001011234"), "准入", rid, "risk-svc");
        assertEquals(a.callId(), b.callId());
        // 留痕可查
        ExternalDtos.CallRecord rec = service.getRecord(null, rid);
        assertEquals(rid, rec.requestId());
        assertEquals("SUCCESS", rec.status());
    }

    // ------------------------------------------------------------ 数据源管理

    @Test
    void 数据源停用_抛EXD1006_令牌托管与记录查询() {
        service.manageSource("TAX", false, null, null, null);
        BaiException e = assertThrows(BaiException.class,
                () -> service.fetch("TAX", query("creditCode", "91310115MA1KDEFY2B"), "准入", "exd-m1", "risk-svc"));
        assertEquals("EXD_1006", e.getCode());
        // 令牌托管：仅存 KMS 引用 ID
        ExternalDtos.CredentialInfo cred = service.registerCredential("CREDIT", "kms-ref-001");
        assertTrue(cred.credentialId().startsWith("CRD"));
        assertEquals("ACTIVE", cred.status());
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.registerCredential("CREDIT", null));
        assertEquals("EXD_1001", e2.getCode());
        // 记录不存在
        BaiException e3 = assertThrows(BaiException.class, () -> service.getRecord("NO-CALL", null));
        assertEquals("EXD_1004", e3.getCode());
    }
}
