package com.bai.deriv;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DerivService 单元测试：四类合约定价（IRS/FORWARD/CDS/OPTION 真实公式）、盯市、对冲、曲线/曲面构建、敞口查询、套期有效性、参数校验、幂等。
 */
class DerivServiceTest {

    private final DerivService service = new DerivService();

    private DerivDtos.MarketData market(List<DerivDtos.CurvePoint> curve, BigDecimal spot, BigDecimal vol,
                                        String rating, String timestamp) {
        return new DerivDtos.MarketData(curve, spot, vol, rating, timestamp);
    }

    private DerivDtos.PriceRequest priceReq(String contractType, BigDecimal notional, Map<String, Object> params,
                                            DerivDtos.MarketData md) {
        return new DerivDtos.PriceRequest(contractType, notional, params, "2026-11-10", md, null);
    }

    // ------------------------------------------------------------ 定价核（真实公式）

    @Test
    void 远期定价_抛补利率平价() {
        // S=7.0，r_d=3%，r_f=0，T=1，K=7.1，BUY 100万：F=S·e^{rd·T}，NPV=(F−K)·N·e^{−rd·T} ≈ 10.99 万
        DerivDtos.PriceResult r = service.price(priceReq("FORWARD", new BigDecimal("1000000"),
                Map.of("tenor", 1, "direction", "BUY", "strike", 7.1),
                market(List.of(new DerivDtos.CurvePoint("1", new BigDecimal("0.03"))),
                        new BigDecimal("7.0"), null, "AA", "2026-11-10")), "p-1");
        assertTrue(r.npv().compareTo(new BigDecimal("100000.00")) > 0);
        assertTrue(r.npv().compareTo(new BigDecimal("120000.00")) < 0);
        assertFalse(r.stale());
    }

    @Test
    void 利率互换_固定腿与浮动腿相等_NPV为零() {
        // tenor 1，浮动利率取曲线 1Y=3%，固定腿 strike=3% → 逐期利差为 0 → NPV=0
        DerivDtos.PriceResult r = service.price(priceReq("IRS", new BigDecimal("1000000"),
                Map.of("tenor", 1, "direction", "PAY_FIXED", "strike", 0.03),
                market(List.of(new DerivDtos.CurvePoint("1", new BigDecimal("0.03"))),
                        null, null, "AAA", "2026-11-10")), "p-2");
        assertEquals(0, BigDecimal.ZERO.compareTo(r.npv()));
        assertTrue(r.cva().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void 期权定价_看涨期权NPV与希腊字母在合理区间() {
        // S=K=100，r=3%，σ=20%，T=1，N=1000：BS 价 ≈ 9.42 → NPV ≈ 9420
        DerivDtos.PriceResult r = service.price(priceReq("OPTION", new BigDecimal("1000"),
                Map.of("tenor", 1, "direction", "LONG", "optionType", "CALL", "strike", 100),
                market(List.of(new DerivDtos.CurvePoint("1", new BigDecimal("0.03"))),
                        new BigDecimal("100"), new BigDecimal("0.20"), "AA", "2026-11-10")), "p-3");
        assertTrue(r.npv().compareTo(new BigDecimal("8000.00")) > 0);
        assertTrue(r.npv().compareTo(new BigDecimal("11000.00")) < 0);
        assertTrue(r.greeks().delta().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(r.greeks().delta().compareTo(new BigDecimal("1000")) < 0);
    }

    @Test
    void 信用违约互换_NPV由利差腿减损失腿() {
        // AA pd=2%，回收率 40%，T=1：NPV = N×spread×DF − N×0.6×pd ≈ 7417
        DerivDtos.PriceResult r = service.price(priceReq("CDS", new BigDecimal("1000000"),
                Map.of("tenor", 1, "direction", "BUY_PROTECTION"),
                market(List.of(new DerivDtos.CurvePoint("1", new BigDecimal("0.03"))),
                        null, null, "AA", "2026-11-10")), "p-4");
        assertTrue(r.npv().compareTo(new BigDecimal("7000.00")) > 0);
        assertTrue(r.npv().compareTo(new BigDecimal("8000.00")) < 0);
    }

    @Test
    void 市场时间戳缺失_标记STALE() {
        DerivDtos.PriceResult r = service.price(priceReq("FORWARD", new BigDecimal("1000000"),
                Map.of("tenor", 1, "direction", "BUY", "strike", 7.1),
                market(List.of(new DerivDtos.CurvePoint("1", new BigDecimal("0.03"))),
                        new BigDecimal("7.0"), null, "AA", null)), "p-5");
        assertTrue(r.stale());
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    void 合约类型非法_抛DERIV1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.price(priceReq("SWAP",
                new BigDecimal("1000000"), Map.of("tenor", 1, "direction", "BUY"),
                market(List.of(new DerivDtos.CurvePoint("1", new BigDecimal("0.03"))),
                        new BigDecimal("7.0"), null, "AA", "2026-11-10")), "p-x"));
        assertEquals("DERIV_1007", e.getCode());
    }

    @Test
    void 交易方向非法_抛DERIV1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.price(priceReq("IRS",
                new BigDecimal("1000000"), Map.of("tenor", 1, "direction", "BUY", "strike", 0.03),
                market(List.of(new DerivDtos.CurvePoint("1", new BigDecimal("0.03"))),
                        null, null, "AAA", "2026-11-10")), "p-y"));
        assertEquals("DERIV_1007", e.getCode());
    }

    @Test
    void 期限非整数年_抛DERIV1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.price(priceReq("FORWARD",
                new BigDecimal("1000000"), Map.of("tenor", 1.5, "direction", "BUY", "strike", 7.1),
                market(List.of(new DerivDtos.CurvePoint("1", new BigDecimal("0.03"))),
                        new BigDecimal("7.0"), null, "AA", "2026-11-10")), "p-z"));
        assertEquals("DERIV_1007", e.getCode());
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void 同requestId同要素_返回首次结果() {
        String rid = "idem-1";
        DerivDtos.PriceRequest rq = priceReq("FORWARD", new BigDecimal("1000000"),
                Map.of("tenor", 1, "direction", "BUY", "strike", 7.0),
                market(List.of(new DerivDtos.CurvePoint("1", new BigDecimal("0.03"))),
                        new BigDecimal("7.0"), null, "AA", "2026-11-10"));
        DerivDtos.PriceResult first = service.price(rq, rid);
        DerivDtos.PriceResult second = service.price(rq, rid);
        assertEquals(first.npv(), second.npv());
    }

    @Test
    void 同requestId不同要素_抛DERIV1005() {
        String rid = "idem-2";
        service.price(priceReq("FORWARD", new BigDecimal("1000000"),
                Map.of("tenor", 1, "direction", "BUY", "strike", 7.0),
                market(List.of(new DerivDtos.CurvePoint("1", new BigDecimal("0.03"))),
                        new BigDecimal("7.0"), null, "AA", "2026-11-10")), rid);
        BaiException e = assertThrows(BaiException.class, () -> service.price(priceReq("FORWARD",
                new BigDecimal("2000000"), Map.of("tenor", 1, "direction", "BUY", "strike", 7.0),
                market(List.of(new DerivDtos.CurvePoint("1", new BigDecimal("0.03"))),
                        new BigDecimal("7.0"), null, "AA", "2026-11-10")), rid));
        assertEquals("DERIV_1005", e.getCode());
    }

    // ------------------------------------------------------------ 盯市 / 敞口

    @Test
    void 每日盯市_批量持仓完成且敞口留痕() {
        DerivDtos.MarkResult r = service.mark(new DerivDtos.MarkRequest("BATCH", "2026-11-10",
                List.of("POS001", "POS002"), true, null), "mk-1");
        assertEquals(2, r.completed());
        assertEquals(0, r.failed());
        assertEquals("MTM20261110", r.batchNo().substring(0, 11));
        for (DerivDtos.MarkItem item : r.items()) {
            assertTrue(item.fairValue().compareTo(new BigDecimal("500000")) >= 0);
        }
        // 敞口查询命中
        DerivDtos.ExposureResult ex = service.exposure("POS001", null, null, "2026-11-10",
                "97.5", true, "mk-2");
        assertEquals("POS001", ex.portfolioId());
        assertTrue(ex.mtmExposure().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void 盯市持仓清单为空_抛DERIV1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.mark(
                new DerivDtos.MarkRequest("SINGLE", "2026-11-10", List.of(), false, null), "mk-x"));
        assertEquals("DERIV_1001", e.getCode());
    }

    @Test
    void 敞口查询无记录_抛DERIV1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.exposure("POS-NONE", null, null,
                "2026-11-10", "97.5", true, "mk-y"));
        assertEquals("DERIV_1004", e.getCode());
    }

    // ------------------------------------------------------------ 对冲策略

    @Test
    void 对冲策略_残余风险等于敞口乘未对冲比例() {
        DerivDtos.HedgeResult r = service.hedge(new DerivDtos.HedgeRequest("EXP001", "RATE",
                Map.of("amount", 10000000), List.of("IRS"), new BigDecimal("0.8"), null), "h-1");
        assertFalse(r.suggestions().isEmpty());
        // 残余风险 = 1000万 × (1−0.8) = 200万
        assertEquals(0, new BigDecimal("2000000.00").compareTo(r.residualRisk()));
        assertTrue(r.complianceCheck().contains("符合"));
        for (DerivDtos.HedgeSuggestion s : r.suggestions()) {
            assertTrue(s.effectiveness().compareTo(new BigDecimal("0.80")) >= 0);
        }
    }

    @Test
    void 对冲入参缺失_抛DERIV1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.hedge(
                new DerivDtos.HedgeRequest(null, "RATE", Map.of("amount", 1000000), null, null, null), "h-x"));
        assertEquals("DERIV_1001", e.getCode());
    }

    @Test
    void 目标对冲比例超界_抛DERIV1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.hedge(
                new DerivDtos.HedgeRequest("EXP002", "RATE", Map.of("amount", 1000000), null,
                        new BigDecimal("1.5"), null), "h-y"));
        assertEquals("DERIV_1001", e.getCode());
    }

    // ------------------------------------------------------------ 曲线 / 曲面构建

    @Test
    void 利率曲线_单调递增发布成功() {
        DerivDtos.CurveResult r = service.curve(new DerivDtos.CurveRequest("CNY", "DISCOUNT", "BOOTSTRAP",
                List.of(new DerivDtos.CurveQuote("1", new BigDecimal("0.03"), "OIS"),
                        new DerivDtos.CurveQuote("2", new BigDecimal("0.035"), "OIS"),
                        new DerivDtos.CurveQuote("3", new BigDecimal("0.04"), "OIS")),
                "2026-11-10T09:00:00", Map.of(), null), "c-1");
        assertEquals("PUBLISHED", r.status());
        assertEquals(3, r.points().size());
        assertTrue(r.qualityReport().monotonic());
    }

    @Test
    void 利率曲线非单调_抛DERIV4103() {
        BaiException e = assertThrows(BaiException.class, () -> service.curve(
                new DerivDtos.CurveRequest("CNY", "DISCOUNT", "BOOTSTRAP",
                        List.of(new DerivDtos.CurveQuote("1", new BigDecimal("0.04"), "OIS"),
                                new DerivDtos.CurveQuote("2", new BigDecimal("0.03"), "OIS")),
                        "2026-11-10T09:00:00", Map.of(), null), "c-x"));
        assertEquals("DERIV_4103", e.getCode());
    }

    @Test
    void 波动率曲面_无套利通过发布() {
        DerivDtos.VolResult r = service.vol(new DerivDtos.VolRequest("EQUITY", "SPLINE",
                List.of(new DerivDtos.VolQuote("1", "100", new BigDecimal("0.20"), "IV"),
                        new DerivDtos.VolQuote("1", "110", new BigDecimal("0.22"), "IV"),
                        new DerivDtos.VolQuote("1", "120", new BigDecimal("0.24"), "IV"),
                        new DerivDtos.VolQuote("2", "100", new BigDecimal("0.25"), "IV"),
                        new DerivDtos.VolQuote("2", "110", new BigDecimal("0.27"), "IV"),
                        new DerivDtos.VolQuote("2", "120", new BigDecimal("0.29"), "IV")),
                Map.of(), "2026-11-10T09:00:00", null), "v-1");
        assertEquals("PUBLISHED", r.status());
        assertEquals(6, r.grid().size());
    }

    @Test
    void 波动率曲面日历价差为负_抛DERIV4103() {
        BaiException e = assertThrows(BaiException.class, () -> service.vol(
                new DerivDtos.VolRequest("FX", "SVI",
                        List.of(new DerivDtos.VolQuote("1", "100", new BigDecimal("0.20"), "IV"),
                                new DerivDtos.VolQuote("2", "100", new BigDecimal("0.10"), "IV")),
                        Map.of(), "2026-11-10T09:00:00", null), "v-x"));
        assertEquals("DERIV_4103", e.getCode());
    }

    // ------------------------------------------------------------ 套期有效性

    @Test
    void 回溯有效性_比率落于区间() {
        DerivDtos.EffectivenessResult r = service.effectiveness(
                new DerivDtos.EffectivenessRequest("HR001", "RETROSPECTIVE", "12M", null, null), "ef-1");
        assertEquals("HR001", r.hedgeRelationId());
        assertTrue(r.ratio().compareTo(new BigDecimal("0.60")) >= 0);
        assertTrue(r.ratio().compareTo(new BigDecimal("1.35")) <= 0);
        assertTrue(Set.of("EFFECTIVE", "INEFFECTIVE").contains(r.assessmentResult()));
    }

    @Test
    void 前瞻评估缺情景_抛DERIV1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.effectiveness(
                new DerivDtos.EffectivenessRequest("HR002", "PROSPECTIVE", null, null, null), "ef-x"));
        assertEquals("DERIV_1001", e.getCode());
    }
}
