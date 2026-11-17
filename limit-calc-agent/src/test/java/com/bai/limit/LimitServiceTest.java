package com.bai.limit;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LimitService 单元测试：额度测算（SINGLE/GROUP 口径）、限额管理、试算查询、占用查询、参数校验、幂等。
 * <p>确定性仿真：同入参必得同结果，可直接断言数值。
 */
class LimitServiceTest {

    private final LimitService service = new LimitService();

    private LimitDtos.LimitCalcRequest calcReq(String customerId, String limitType, BigDecimal income,
                                               BigDecimal collateral, BigDecimal factor, BigDecimal available,
                                               BigDecimal requested) {
        return new LimitDtos.LimitCalcRequest(customerId, limitType, income, collateral, factor, available,
                requested, null);
    }

    // ------------------------------------------------------------ 额度测算

    @Test
    void 单一口径_限额取收入与押品较小值乘系数() {
        // min(1000万, 800万) × 0.8 = 640万；可用 200万 → 已用 440万、剩余 200万；申请 100万 ≤ 剩余 → PASS
        LimitDtos.LimitCalcResult r = service.calc(calcReq("C001", "SINGLE",
                new BigDecimal("10000000"), new BigDecimal("8000000"),
                new BigDecimal("0.8"), new BigDecimal("2000000"), new BigDecimal("1000000")), "lc-1");
        assertEquals(0, new BigDecimal("6400000.00").compareTo(r.limitAmount()));
        assertEquals(0, new BigDecimal("4400000.00").compareTo(r.usedAmount()));
        assertEquals(0, new BigDecimal("2000000.00").compareTo(r.remainAmount()));
        assertEquals("PASS", r.controlStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(r.overAmount()));
    }

    @Test
    void 集团口径_收入与押品加权_超限额返回超额() {
        // 1000万×0.6 + 500万×0.4 = 800万 ×0.8 = 640万；可用 100万 → 已用 540万；申请 1000万 → OVER_LIMIT
        LimitDtos.LimitCalcResult r = service.calc(calcReq("G001", "GROUP",
                new BigDecimal("10000000"), new BigDecimal("5000000"),
                new BigDecimal("0.8"), new BigDecimal("1000000"), new BigDecimal("10000000")), "lc-2");
        assertEquals(0, new BigDecimal("6400000.00").compareTo(r.limitAmount()));
        assertEquals("OVER_LIMIT", r.controlStatus());
        // overAmount = 已用 + 申请 − 限额 = 540 + 1000 − 640 = 900 万
        assertEquals(0, new BigDecimal("9000000.00").compareTo(r.overAmount()));
    }

    @Test
    void 核定额度为零_直接拒绝() {
        LimitDtos.LimitCalcResult r = service.calc(calcReq("C002", "SINGLE",
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.8"),
                BigDecimal.ZERO, new BigDecimal("500000")), "lc-3");
        assertEquals("REJECT", r.controlStatus());
        assertEquals(0, new BigDecimal("500000.00").compareTo(r.overAmount()));
    }

    @Test
    void 同入参两次调用_结果完全一致() {
        LimitDtos.LimitCalcRequest req = calcReq("C003", "INDUSTRY",
                new BigDecimal("9000000"), new BigDecimal("7000000"),
                new BigDecimal("0.75"), new BigDecimal("3000000"), new BigDecimal("2000000"));
        LimitDtos.LimitCalcResult a = service.calc(req, "det-a");
        LimitDtos.LimitCalcResult b = service.calc(req, "det-b");
        assertEquals(0, a.limitAmount().compareTo(b.limitAmount()));
        assertEquals(0, a.remainAmount().compareTo(b.remainAmount()));
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    void 客户缺失_抛LMT1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.calc(calcReq(null, "SINGLE",
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0.8"),
                BigDecimal.ZERO, new BigDecimal("10")), "lc-x"));
        assertEquals("LMT_1004", e.getCode());
    }

    @Test
    void 限额口径非法_抛LMT1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.calc(calcReq("C004", "XXX",
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0.8"),
                BigDecimal.ZERO, new BigDecimal("10")), "lc-y"));
        assertEquals("LMT_1007", e.getCode());
    }

    @Test
    void 调节系数超界_抛LMT1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.calc(calcReq("C005", "SINGLE",
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("1.5"),
                BigDecimal.ZERO, new BigDecimal("10")), "lc-z"));
        assertEquals("LMT_1007", e.getCode());
    }

    @Test
    void 申请金额非正_抛LMT1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.calc(calcReq("C006", "SINGLE",
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0.8"),
                BigDecimal.ZERO, BigDecimal.ZERO), "lc-w"));
        assertEquals("LMT_1001", e.getCode());
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void 同requestId同要素_返回首次结果() {
        String rid = "idem-1";
        LimitDtos.LimitCalcRequest req = calcReq("C100", "SINGLE",
                new BigDecimal("1000000"), new BigDecimal("800000"),
                new BigDecimal("0.8"), new BigDecimal("100000"), new BigDecimal("50000"));
        LimitDtos.LimitCalcResult first = service.calc(req, rid);
        LimitDtos.LimitCalcResult second = service.calc(req, rid);
        assertEquals(first.limitAmount(), second.limitAmount());
        assertEquals(first.controlStatus(), second.controlStatus());
    }

    @Test
    void 同requestId不同要素_抛LMT1005() {
        String rid = "idem-2";
        service.calc(calcReq("C101", "SINGLE", new BigDecimal("1000000"), new BigDecimal("800000"),
                new BigDecimal("0.8"), new BigDecimal("100000"), new BigDecimal("50000")), rid);
        BaiException e = assertThrows(BaiException.class, () -> service.calc(calcReq("C102", "SINGLE",
                new BigDecimal("2000000"), new BigDecimal("800000"),
                new BigDecimal("0.8"), new BigDecimal("100000"), new BigDecimal("50000")), rid));
        assertEquals("LMT_1005", e.getCode());
    }

    // ------------------------------------------------------------ 限额管理（limit）

    @Test
    void 限额创建更新灰化停用_版本递增() {
        LimitDtos.LimitConfig cfg = new LimitDtos.LimitConfig("SINGLE", "C200",
                new BigDecimal("5000000"), "2026-01-01", null, "行内");
        LimitDtos.LimitResult created = service.limit(new LimitDtos.LimitRequest("CREATE", cfg, null), "lm-1");
        assertEquals("ACTIVE", created.status());
        assertEquals(1, created.version());
        LimitDtos.LimitResult updated = service.limit(new LimitDtos.LimitRequest("UPDATE", cfg, null), "lm-2");
        assertEquals(2, updated.version());
        LimitDtos.LimitResult gray = service.limit(new LimitDtos.LimitRequest("GRAY", cfg, null), "lm-3");
        assertEquals("GRAY", gray.status());
        assertEquals(3, gray.version());
        LimitDtos.LimitResult disabled = service.limit(new LimitDtos.LimitRequest("DISABLE", cfg, null), "lm-4");
        assertEquals("DISABLED", disabled.status());
        assertEquals(4, disabled.version());
    }

    @Test
    void 同口径同主体重复创建_抛LMT1005() {
        LimitDtos.LimitConfig cfg = new LimitDtos.LimitConfig("SINGLE", "C201",
                new BigDecimal("3000000"), "2026-01-01", null, "行内");
        service.limit(new LimitDtos.LimitRequest("CREATE", cfg, null), "lm-5");
        BaiException e = assertThrows(BaiException.class,
                () -> service.limit(new LimitDtos.LimitRequest("CREATE", cfg, null), "lm-6"));
        assertEquals("LMT_1005", e.getCode());
    }

    @Test
    void 限额动作非法_抛LMT1007() {
        LimitDtos.LimitConfig cfg = new LimitDtos.LimitConfig("SINGLE", "C202",
                new BigDecimal("3000000"), "2026-01-01", null, "行内");
        BaiException e = assertThrows(BaiException.class,
                () -> service.limit(new LimitDtos.LimitRequest("DELETE", cfg, null), "lm-7"));
        assertEquals("LMT_1007", e.getCode());
    }

    @Test
    void 更新不存在的限额_抛LMT1004() {
        LimitDtos.LimitConfig cfg = new LimitDtos.LimitConfig("GROUP", "C203",
                new BigDecimal("3000000"), "2026-01-01", null, "行内");
        BaiException e = assertThrows(BaiException.class,
                () -> service.limit(new LimitDtos.LimitRequest("UPDATE", cfg, null), "lm-8"));
        assertEquals("LMT_1004", e.getCode());
    }

    // ------------------------------------------------------------ 试算查询（query）

    @Test
    void 试算查询_按客户与calcId命中() {
        service.calc(calcReq("C300", "SINGLE", new BigDecimal("10000000"), new BigDecimal("8000000"),
                new BigDecimal("0.8"), new BigDecimal("2000000"), new BigDecimal("1000000")), "q-0");
        LimitDtos.CalcQueryResult byCustomer = service.query(
                new LimitDtos.CalcQueryRequest(null, "C300", null, null, 1, 20), "q-1");
        assertEquals(1, byCustomer.total());
        assertEquals("C300", byCustomer.items().get(0).customerId());
        String calcId = byCustomer.items().get(0).calcId();
        LimitDtos.CalcQueryResult byId = service.query(
                new LimitDtos.CalcQueryRequest(calcId, null, null, null, null, null), "q-2");
        assertEquals(1, byId.total());
    }

    @Test
    void 试算查询无记录_抛LMT1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.query(
                new LimitDtos.CalcQueryRequest(null, "NO-SUCH", null, null, 1, 20), "q-x"));
        assertEquals("LMT_1004", e.getCode());
    }

    // ------------------------------------------------------------ 占用查询（usage）

    @Test
    void 占用查询_比例在区间内且恒等() {
        LimitDtos.LimitConfig cfg = new LimitDtos.LimitConfig("SINGLE", "C400",
                new BigDecimal("10000000"), "2026-01-01", null, "行内");
        service.limit(new LimitDtos.LimitRequest("CREATE", cfg, null), "u-0");
        LimitDtos.UsageResult r = service.usage(new LimitDtos.UsageQueryRequest("C400", "SINGLE"), "u-1");
        assertEquals("SUCCESS", r.code());
        assertEquals(0, new BigDecimal("10000000.00").compareTo(r.limitAmount()));
        // 占用比例 20%~80%，已用+剩余=限额，两笔明细之和=已用
        assertTrue(r.usedAmount().compareTo(new BigDecimal("2000000.00")) >= 0);
        assertTrue(r.usedAmount().compareTo(new BigDecimal("8000000.00")) <= 0);
        assertEquals(0, r.usedAmount().add(r.remainAmount()).compareTo(r.limitAmount()));
        assertEquals(2, r.details().size());
        assertEquals(0, r.details().get(0).amount().add(r.details().get(1).amount())
                .compareTo(r.usedAmount()));
    }

    @Test
    void 占用查询未建限额_抛LMT1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.usage(
                new LimitDtos.UsageQueryRequest("NO-LIMIT", "SINGLE"), "u-x"));
        assertEquals("LMT_1004", e.getCode());
    }
}
