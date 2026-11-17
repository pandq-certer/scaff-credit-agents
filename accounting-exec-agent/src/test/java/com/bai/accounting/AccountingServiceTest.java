package com.bai.accounting;

import com.bai.common.BaiException;
import com.spatix.scaff.iprov.ProviderResponse;
import com.spatix.scaff.iprov.provider.AccountingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * AccountingService 单元测试：记账（借贷平衡/凭证/过账）、计提、结息、查询、参数校验、幂等、总账降级。
 * <p>总账 Provider 以 Mockito mock（成功/异常两条路径）。
 */
class AccountingServiceTest {

    private AccountingProvider provider;
    private AccountingService service;

    @BeforeEach
    void setUp() {
        provider = Mockito.mock(AccountingProvider.class);
        when(provider.trans(any(), anyString())).thenReturn(ProviderResponse.success("txn-1",
                new AccountingProvider.TxnResult("txn-1", "SUCCESS", "CORE-V1",
                        "2026-11-10T10:00:00+08:00")));
        service = new AccountingService(provider);
    }

    private AccountingDtos.EntryRequest entryReq(String eventType, String contractId, BigDecimal amount,
                                                 List<AccountingDtos.EntryLine> entries) {
        return new AccountingDtos.EntryRequest(eventType, contractId, entries, amount, null, null, null);
    }

    // ------------------------------------------------------------ 记账执行

    @Test
    void 放款记账_借贷平衡并过账总账() {
        AccountingDtos.EntryResult r = service.entry(entryReq("LOAN", "LN001", new BigDecimal("1000000"),
                List.of(new AccountingDtos.EntryLine("1301", "DEBIT", new BigDecimal("1000000"), "贷款发放"),
                        new AccountingDtos.EntryLine("2011", "CREDIT", new BigDecimal("1000000"), "单位活期存款"))), "e-1");
        assertEquals("SUCCESS", r.code());
        assertEquals("PASS", r.balanceCheck());
        assertEquals("POSTED", r.glStatus());
        assertTrue(r.voucherNo().startsWith("V"));
        assertNotNull(r.entryId());
        assertEquals(2, r.entries().size());
    }

    @Test
    void 借贷不平衡_禁止过账() {
        BaiException e = assertThrows(BaiException.class, () -> service.entry(entryReq("LOAN", "LN002",
                new BigDecimal("1000000"),
                List.of(new AccountingDtos.EntryLine("1301", "DEBIT", new BigDecimal("900000"), "贷款发放"),
                        new AccountingDtos.EntryLine("2011", "CREDIT", new BigDecimal("1000000"), "单位活期存款"))), "e-x"));
        assertEquals("ACC_1001", e.getCode());
    }

    @Test
    void 分录合计与事件金额不符_抛ACC1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.entry(entryReq("LOAN", "LN003",
                new BigDecimal("500000"),
                List.of(new AccountingDtos.EntryLine("1301", "DEBIT", new BigDecimal("1000000"), "贷款发放"),
                        new AccountingDtos.EntryLine("2011", "CREDIT", new BigDecimal("1000000"), "单位活期存款"))), "e-y"));
        assertEquals("ACC_1001", e.getCode());
    }

    @Test
    void 事件类型非法_抛ACC1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.entry(entryReq("GIFT", "LN004",
                new BigDecimal("100000"),
                List.of(new AccountingDtos.EntryLine("1301", "DEBIT", new BigDecimal("100000"), "x"),
                        new AccountingDtos.EntryLine("2011", "CREDIT", new BigDecimal("100000"), "x"))), "e-z"));
        assertEquals("ACC_1007", e.getCode());
    }

    @Test
    void 科目代码非法_抛ACC1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.entry(entryReq("LOAN", "LN005",
                new BigDecimal("100000"),
                List.of(new AccountingDtos.EntryLine("abc", "DEBIT", new BigDecimal("100000"), "x"),
                        new AccountingDtos.EntryLine("2011", "CREDIT", new BigDecimal("100000"), "x"))), "e-w"));
        assertEquals("ACC_1007", e.getCode());
    }

    @Test
    void 总账服务异常_抛ACC2001() {
        when(provider.trans(any(), anyString()))
                .thenThrow(new RuntimeException("provider down"));
        BaiException e = assertThrows(BaiException.class, () -> service.entry(entryReq("LOAN", "LN006",
                new BigDecimal("100000"),
                List.of(new AccountingDtos.EntryLine("1301", "DEBIT", new BigDecimal("100000"), "x"),
                        new AccountingDtos.EntryLine("2011", "CREDIT", new BigDecimal("100000"), "x"))), "e-v"));
        assertEquals("ACC_2001", e.getCode());
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void 同requestId同要素_返回首次结果() {
        String rid = "idem-1";
        AccountingDtos.EntryRequest rq = entryReq("REPAY", "LN100", new BigDecimal("200000"),
                List.of(new AccountingDtos.EntryLine("2011", "DEBIT", new BigDecimal("200000"), "还款"),
                        new AccountingDtos.EntryLine("1301", "CREDIT", new BigDecimal("200000"), "还款")));
        AccountingDtos.EntryResult first = service.entry(rq, rid);
        AccountingDtos.EntryResult second = service.entry(rq, rid);
        assertEquals(first.voucherNo(), second.voucherNo());
        assertEquals(first.glStatus(), second.glStatus());
    }

    @Test
    void 同requestId不同要素_抛ACC1005() {
        String rid = "idem-2";
        service.entry(entryReq("REPAY", "LN101", new BigDecimal("200000"),
                List.of(new AccountingDtos.EntryLine("2011", "DEBIT", new BigDecimal("200000"), "还款"),
                        new AccountingDtos.EntryLine("1301", "CREDIT", new BigDecimal("200000"), "还款"))), rid);
        BaiException e = assertThrows(BaiException.class, () -> service.entry(entryReq("REPAY", "LN102",
                new BigDecimal("300000"),
                List.of(new AccountingDtos.EntryLine("2011", "DEBIT", new BigDecimal("300000"), "还款"),
                        new AccountingDtos.EntryLine("1301", "CREDIT", new BigDecimal("300000"), "还款"))), rid));
        assertEquals("ACC_1005", e.getCode());
    }

    // ------------------------------------------------------------ 计提 / 结息

    @Test
    void 利息计提_科目映射与状态() {
        AccountingDtos.AccrualResult r = service.accrual(new AccountingDtos.AccrualRequest("INTEREST",
                new AccountingDtos.AccrualBusiness("LN200", "1122", "2026-10"),
                new AccountingDtos.AccrualParams(new BigDecimal("0.0345"), null),
                new BigDecimal("28750.00"), null), "a-1");
        assertEquals("ACCRUED", r.status());
        assertEquals(0, new BigDecimal("28750.00").compareTo(r.amount()));
        // 借 1122 应收利息 / 贷 6011 利息收入
        assertEquals("1122", r.entries().get(0).account());
        assertEquals("DEBIT", r.entries().get(0).direction());
        assertEquals("6011", r.entries().get(1).account());
        assertEquals("CREDIT", r.entries().get(1).direction());
    }

    @Test
    void 计提期间非法_抛ACC1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.accrual(new AccountingDtos.AccrualRequest(
                "INTEREST", new AccountingDtos.AccrualBusiness("LN201", "1122", "2026/10"),
                new AccountingDtos.AccrualParams(new BigDecimal("0.03"), null),
                new BigDecimal("1000"), null), "a-x"));
        assertEquals("ACC_1001", e.getCode());
    }

    @Test
    void 结息计算_本金乘利率乘天数除360() {
        AccountingDtos.SettleResult r = service.settle(new AccountingDtos.SettleRequest("PERIOD",
                new AccountingDtos.SettleBusiness("LN300", "1122", "2026-11"),
                new AccountingDtos.SettleParams(new BigDecimal("0.0345")),
                new BigDecimal("1000000"), "2026-11-10", null), "s-1");
        // 100万 × 3.45% × 30 天 / 360 = 2875.00
        assertEquals(0, new BigDecimal("2875.00").compareTo(r.interest()));
        assertEquals("SETTLED", r.status());
        assertTrue(r.voucherNo().startsWith("V"));
    }

    @Test
    void 结息利率缺失_抛ACC1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.settle(new AccountingDtos.SettleRequest(
                "PERIOD", new AccountingDtos.SettleBusiness("LN301", "1122", "2026-11"),
                new AccountingDtos.SettleParams(null),
                new BigDecimal("1000000"), "2026-11-10", null), "s-x"));
        assertEquals("ACC_1001", e.getCode());
    }

    // ------------------------------------------------------------ 查询

    @Test
    void 记账查询_按贷款号命中() {
        service.entry(entryReq("LOAN", "LN400", new BigDecimal("100000"),
                List.of(new AccountingDtos.EntryLine("1301", "DEBIT", new BigDecimal("100000"), "贷款发放"),
                        new AccountingDtos.EntryLine("2011", "CREDIT", new BigDecimal("100000"), "存款"))), "q-0");
        AccountingDtos.QueryResult r = service.query(new AccountingDtos.QueryRequest(null, "LN400", null,
                null, null, null, null, 1, 20), "q-1");
        assertEquals(1, r.total());
        assertEquals("ENTRY", r.items().get(0).type());
        assertEquals("POSTED", r.items().get(0).status());
    }

    @Test
    void 记账查询无记录_抛ACC1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.query(
                new AccountingDtos.QueryRequest(null, "NO-LOAN", null, null, null, null, null, 1, 20), "q-x"));
        assertEquals("ACC_1004", e.getCode());
    }

    @Test
    void 查询条件非法_抛ACC1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.query(
                new AccountingDtos.QueryRequest(null, null, null, null, null, null, null, 0, 20), "q-y"));
        assertEquals("ACC_1001", e.getCode());
    }
}
