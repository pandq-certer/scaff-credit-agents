package com.bai.bill;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BillService 单元测试：贴现（利息/实付/状态核验）、验真、承兑、兑付、票据池、背书审查、质押、利率定价、参数校验、幂等。
 */
class BillServiceTest {

    private final BillService service = new BillService();

    private String plusDays(long days) {
        return LocalDate.now().plusDays(days).toString();
    }

    private String minusDays(long days) {
        return LocalDate.now().minusDays(days).toString();
    }

    /** 以验真登记票据（amount/期限可指定），返回登记票据信息 */
    private BillDtos.BillInfo register(String billNo, String billType, BigDecimal amount, String issueDate, String dueDate) {
        service.verify(new BillDtos.VerifyRequest(billNo, billType, true, amount,
                issueDate, dueDate, "出票人A", "承兑人B", "收款人C", List.of(), List.of(), null), "reg-" + billNo);
        return service.bill(billNo);
    }

    // ------------------------------------------------------------ 贴现

    @Test
    void 贴现_利息与实付按剩余天数计算() {
        BillDtos.DiscountResult r = service.discount(new BillDtos.DiscountRequest("BILL001", "BANK_ACCEPTANCE",
                new BigDecimal("1000000"), minusDays(10), plusDays(30), "出票人A", "承兑人B",
                new BigDecimal("3.6"), null), "d-1");
        // 利息 = 100万×3.6%×30/360 = 3000.00，实付 = 997000.00
        assertEquals(0, new BigDecimal("3000.00").compareTo(r.interestAmount()));
        assertEquals(0, new BigDecimal("997000.00").compareTo(r.payableAmount()));
        assertEquals(30, r.remainingDays());
        assertEquals("存续", r.billStatus());
        assertTrue(Set.of("PASS", "SUSPECT").contains(r.verifyResult()));
    }

    @Test
    void 票据已到期_抛BILL4101() {
        BaiException e = assertThrows(BaiException.class, () -> service.discount(
                new BillDtos.DiscountRequest("BILL002", "BANK_ACCEPTANCE", new BigDecimal("1000000"),
                        minusDays(20), minusDays(1), "出票人A", "承兑人B", new BigDecimal("3.6"), null), "d-x"));
        assertEquals("BILL_4101", e.getCode());
    }

    @Test
    void 冻结票据_贴现阻断() {
        BaiException e = assertThrows(BaiException.class, () -> service.discount(
                new BillDtos.DiscountRequest("FROZEN001", "BANK_ACCEPTANCE", new BigDecimal("1000000"),
                        minusDays(10), plusDays(30), "出票人A", "承兑人B", new BigDecimal("3.6"), null), "d-y"));
        assertEquals("BILL_4101", e.getCode());
    }

    @Test
    void 贴现率过高_实付低于票面九成_抛BILL4103() {
        BaiException e = assertThrows(BaiException.class, () -> service.discount(
                new BillDtos.DiscountRequest("BILL003", "BANK_ACCEPTANCE", new BigDecimal("1000000"),
                        minusDays(10), plusDays(360), "出票人A", "承兑人B", new BigDecimal("50"), null), "d-z"));
        assertEquals("BILL_4103", e.getCode());
    }

    @Test
    void 贴现校验_票据类型非法_抛BILL1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.discount(
                new BillDtos.DiscountRequest("BILL004", "CASH", new BigDecimal("1000000"),
                        minusDays(10), plusDays(30), "出票人A", "承兑人B", new BigDecimal("3.6"), null), "d-w"));
        assertEquals("BILL_1007", e.getCode());
    }

    @Test
    void 贴现幂等_同requestId不同要素_抛BILL1005() {
        String rid = "idem-1";
        service.discount(new BillDtos.DiscountRequest("BILL100", "BANK_ACCEPTANCE", new BigDecimal("1000000"),
                minusDays(10), plusDays(30), "出票人A", "承兑人B", new BigDecimal("3.6"), null), rid);
        BaiException e = assertThrows(BaiException.class, () -> service.discount(
                new BillDtos.DiscountRequest("BILL100", "BANK_ACCEPTANCE", new BigDecimal("2000000"),
                        minusDays(10), plusDays(30), "出票人A", "承兑人B", new BigDecimal("3.6"), null), rid));
        assertEquals("BILL_1005", e.getCode());
    }

    // ------------------------------------------------------------ 验真（4.2）

    @Test
    void 验真_冻结票FAIL_纸质无影像SUSPECT() {
        BillDtos.VerifyResult frozen = service.verify(new BillDtos.VerifyRequest("FROZEN002",
                "BANK_ACCEPTANCE", true, new BigDecimal("1000000"), minusDays(10), plusDays(30),
                "出票人A", "承兑人B", "收款人C", List.of(), List.of(), null), "v-1");
        assertEquals("FAIL", frozen.verifyResult());
        assertEquals("冻结", frozen.billStatus());
        // 纸质票无影像：防伪通道 SUSPECT → 整体 SUSPECT
        BillDtos.VerifyResult paper = service.verify(new BillDtos.VerifyRequest("PAPER001",
                "COMMERCIAL_ACCEPTANCE", false, new BigDecimal("500000"), minusDays(10), plusDays(60),
                "出票人A", "承兑人B", "收款人C", List.of(), List.of(), null), "v-2");
        assertEquals("SUSPECT", paper.verifyResult());
        assertTrue(paper.verifyNo().startsWith("VRF"));
    }

    @Test
    void 验真校验_票面日期不一致_抛BILL4002() {
        BaiException e = assertThrows(BaiException.class, () -> service.verify(
                new BillDtos.VerifyRequest("V003", "BANK_ACCEPTANCE", true, new BigDecimal("1000000"),
                        plusDays(30), minusDays(10), "出票人A", "承兑人B", "收款人C", List.of(), List.of(), null), "v-x"));
        assertEquals("BILL_4002", e.getCode());
    }

    // ------------------------------------------------------------ 承兑（4.3）

    @Test
    void 承兑_保证金按评级查表且敞口正确() {
        register("ACC001", "BANK_ACCEPTANCE", new BigDecimal("1000000"), minusDays(10), plusDays(100));
        BillDtos.AcceptResult r = service.accept(new BillDtos.AcceptRequest("ACC001", "出票人A",
                null, "AA", null), "ac-1");
        // AA 保证金比例 20%：保证金 20 万、敞口 80 万
        assertEquals(0, new BigDecimal("200000.00").compareTo(r.marginAmount()));
        assertEquals(0, new BigDecimal("800000.00").compareTo(r.exposure()));
        assertEquals("ACCEPTED", r.status());
        assertEquals(4, r.issueCheck().size());
    }

    @Test
    void 承兑敞口超限_抛BILL4103() {
        register("ACC002", "BANK_ACCEPTANCE", new BigDecimal("1000000"), minusDays(10), plusDays(100));
        BaiException e = assertThrows(BaiException.class, () -> service.accept(
                new BillDtos.AcceptRequest("ACC002", "出票人A",
                        new BillDtos.AcceptanceApply(null, new BigDecimal("500000"), null), "AA", null), "ac-x"));
        assertEquals("BILL_4103", e.getCode());
    }

    @Test
    void 承兑校验_评级非法_抛BILL4001() {
        register("ACC003", "BANK_ACCEPTANCE", new BigDecimal("1000000"), minusDays(10), plusDays(100));
        BaiException e = assertThrows(BaiException.class, () -> service.accept(
                new BillDtos.AcceptRequest("ACC003", "出票人A", null, "CCC", null), "ac-y"));
        assertEquals("BILL_4001", e.getCode());
    }

    // ------------------------------------------------------------ 兑付（4.5）

    @Test
    void 兑付_已兑付票据直接返回PAID() {
        service.bill("PAID001");
        BillDtos.PayResult r = service.pay(new BillDtos.PayRequest("PAID001", null, null), "p-1");
        assertEquals("PAID", r.payResult());
        assertNotNull(r.payAmount());
    }

    @Test
    void 兑付_冻结票据阻断() {
        service.bill("FROZEN010");
        BaiException e = assertThrows(BaiException.class, () -> service.pay(
                new BillDtos.PayRequest("FROZEN010", null, null), "p-x"));
        assertEquals("BILL_4101", e.getCode());
    }

    @Test
    void 兑付_余额不足转入垫款或足额兑付() {
        service.bill("PAY001");
        BillDtos.PayResult r = service.pay(new BillDtos.PayRequest("PAY001", null, null), "p-2");
        assertTrue(Set.of("PAID", "ADVANCE", "OVERDUE").contains(r.payResult()));
        assertTrue(r.payAmount().compareTo(BigDecimal.ZERO) >= 0);
    }

    // ------------------------------------------------------------ 票据池（4.6）

    @Test
    void 入池_质押额度按银承八成() {
        register("POOL-A01", "BANK_ACCEPTANCE", new BigDecimal("1000000"), minusDays(10), plusDays(100));
        BillDtos.PoolResult r = service.pool(new BillDtos.PoolRequest("IN_POOL", List.of("POOL-A01"),
                "CUST001", null, null, null), "po-1");
        assertEquals("IN_POOL", r.status());
        assertEquals(0, new BigDecimal("800000.00").compareTo(r.pledgeAmount()));
    }

    @Test
    void 换票_入池价值不足_抛BILL4103() {
        register("POOL-B01", "BANK_ACCEPTANCE", new BigDecimal("1000000"), minusDays(10), plusDays(100));
        register("POOL-C01", "BANK_ACCEPTANCE", new BigDecimal("1000000"), minusDays(10), plusDays(100));
        BaiException e = assertThrows(BaiException.class, () -> service.pool(
                new BillDtos.PoolRequest("SWAP", List.of(), "CUST001", null,
                        new BillDtos.SwapDetail(List.of("POOL-B01"), List.of("POOL-B01", "POOL-C01")), null), "po-x"));
        assertEquals("BILL_4103", e.getCode());
    }

    @Test
    void 池操作非法_抛BILL4001() {
        BaiException e = assertThrows(BaiException.class, () -> service.pool(
                new BillDtos.PoolRequest("SELL", List.of("POOL-A01"), "CUST001", null, null, null), "po-y"));
        assertEquals("BILL_4001", e.getCode());
    }

    // ------------------------------------------------------------ 背书审查（4.7）

    @Test
    void 背书链连续_审查通过() {
        BillDtos.EndorseCheckResult r = service.endorseCheck(new BillDtos.EndorseCheckRequest("E001",
                List.of(new BillDtos.Endorsement(1, "甲", "乙", minusDays(10), null),
                        new BillDtos.Endorsement(2, "乙", "丙", minusDays(5), null)),
                "丙", null, null), "en-1");
        assertTrue(r.continuous());
        assertEquals("PASS", r.checkResult());
    }

    @Test
    void 背书链断裂_审查拒绝() {
        BillDtos.EndorseCheckResult r = service.endorseCheck(new BillDtos.EndorseCheckRequest("E002",
                List.of(new BillDtos.Endorsement(1, "甲", "乙", minusDays(10), null),
                        new BillDtos.Endorsement(2, "丁", "丙", minusDays(5), null)),
                "丙", null, null), "en-2");
        assertFalse(r.continuous());
        assertEquals("REJECT", r.checkResult());
    }

    @Test
    void 限制性背书后继续背书_审查拒绝() {
        BillDtos.EndorseCheckResult r = service.endorseCheck(new BillDtos.EndorseCheckRequest("E003",
                List.of(new BillDtos.Endorsement(1, "甲", "乙", minusDays(10), "不得转让"),
                        new BillDtos.Endorsement(2, "乙", "丙", minusDays(5), null)),
                "丙", null, null), "en-3");
        assertEquals("REJECT", r.checkResult());
    }

    // ------------------------------------------------------------ 质押（4.8）

    @Test
    void 质押_银承九成上限内可融() {
        register("PLG-A01", "BANK_ACCEPTANCE", new BigDecimal("1000000"), minusDays(10), plusDays(100));
        BillDtos.PledgeResult r = service.pledge(new BillDtos.PledgeRequest(List.of("PLG-A01"), "CUST002",
                new BigDecimal("500000"), null, null, null), "pl-1");
        // 银承默认质押率 0.8（≤0.9 上限）→ 质押额 80 万，可用 = 80−50 = 30 万
        assertEquals(0, new BigDecimal("300000.00").compareTo(r.availableAmount()));
        assertTrue(r.rateCheck().passed());
    }

    @Test
    void 质押率超上限_抛BILL4103() {
        register("PLG-B01", "BANK_ACCEPTANCE", new BigDecimal("1000000"), minusDays(10), plusDays(100));
        BaiException e = assertThrows(BaiException.class, () -> service.pledge(
                new BillDtos.PledgeRequest(List.of("PLG-B01"), "CUST002", new BigDecimal("100000"),
                        new BigDecimal("0.95"), null, null), "pl-x"));
        assertEquals("BILL_4103", e.getCode());
    }

    @Test
    void 融资金额超出可融额度_抛BILL4103() {
        register("PLG-C01", "BANK_ACCEPTANCE", new BigDecimal("1000000"), minusDays(10), plusDays(100));
        BaiException e = assertThrows(BaiException.class, () -> service.pledge(
                new BillDtos.PledgeRequest(List.of("PLG-C01"), "CUST002", new BigDecimal("900000"),
                        null, null, null), "pl-y"));
        assertEquals("BILL_4103", e.getCode());
    }

    // ------------------------------------------------------------ 利率定价（4.9）

    @Test
    void 利率定价_基准加三类溢价() {
        BillDtos.RateResult r = service.rate(new BillDtos.RateRequest("BANK_ACCEPTANCE", "BANK",
                90, "AAA", new BigDecimal("2.0"), null, null), "r-1");
        // 2.0 + 0.3 + 0.2 + 0.1 = 2.6
        assertEquals(0, new BigDecimal("2.6000").compareTo(r.discountRate()));
        assertTrue(r.rateCheck().passed());
        assertNotNull(r.rateVersion());
    }

    @Test
    void 利率超上限_标记不通过() {
        BillDtos.RateResult r = service.rate(new BillDtos.RateRequest("BANK_ACCEPTANCE", "BANK",
                90, "AAA", new BigDecimal("40.0"), null, null), "r-2");
        assertFalse(r.rateCheck().passed());
    }

    @Test
    void 利率定价校验_客户评级非法_抛BILL4001() {
        BaiException e = assertThrows(BaiException.class, () -> service.rate(
                new BillDtos.RateRequest("BANK_ACCEPTANCE", "BANK", 90, "CCC",
                        new BigDecimal("2.0"), null, null), "r-x"));
        assertEquals("BILL_4001", e.getCode());
    }
}
