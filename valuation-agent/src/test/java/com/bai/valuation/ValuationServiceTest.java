package com.bai.valuation;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ValuationService 单元测试：市场/收益/成本三法估值、NPL、区间恒等、陈旧标记、查询、反馈、市场数据接入、参数校验、幂等。
 */
class ValuationServiceTest {

    private final ValuationService service = new ValuationService();

    private ValuationDtos.ValuationRequest req(String assetType, String method, String assetRef,
                                               Map<String, Object> params, String valueDate) {
        return new ValuationDtos.ValuationRequest(assetType, method, assetRef, params, valueDate, null);
    }

    // ------------------------------------------------------------ 估值计算

    @Test
    void 市场法房产_面积乘单价且区间含值() {
        ValuationDtos.ValuationResult r = service.value(req("REAL_ESTATE", "MARKET", "RE001",
                Map.of("area", 100, "unitPrice", 20000,
                        "caseLow", 19000, "caseHigh", 21000), "2026-11-10"), "v-1");
        assertEquals(0, new BigDecimal("2000000.00").compareTo(r.value()));
        assertEquals(0, new BigDecimal("1900000.00").compareTo(r.range().low()));
        assertEquals(0, new BigDecimal("2100000.00").compareTo(r.range().high()));
        assertEquals("VALUED", r.conclusion());
    }

    @Test
    void NPL按回收率估值_结论为人工() {
        ValuationDtos.ValuationResult r = service.value(req("NPL", "MARKET", "NPL001",
                Map.of("faceAmount", 1000000, "recoveryRate", 0.4), "2026-11-10"), "v-2");
        assertEquals(0, new BigDecimal("400000.00").compareTo(r.value()));
        assertEquals("MANUAL", r.conclusion());
    }

    @Test
    void 收益法_年金现值且区间恒等() {
        ValuationDtos.ValuationResult r = service.value(req("EQUIPMENT", "INCOME", "EQ001",
                Map.of("annualIncome", 100000, "discountRate", 0.05, "years", 10), "2026-11-10"), "v-3");
        // 年金现值系数 [1-(1.05)^-10]/0.05 ≈ 7.7217 → 772173.49
        assertTrue(r.value().compareTo(new BigDecimal("770000.00")) >= 0);
        assertTrue(r.value().compareTo(new BigDecimal("775000.00")) <= 0);
        assertTrue(r.range().low().compareTo(r.value()) <= 0);
        assertTrue(r.value().compareTo(r.range().high()) <= 0);
        assertEquals("VALUED", r.conclusion());
    }

    @Test
    void 成本法_重置成本乘成新率() {
        ValuationDtos.ValuationResult r = service.value(req("EQUIPMENT", "COST", "EQ002",
                Map.of("replacementCost", 1000000, "newnessRate", 0.6), "2026-11-10"), "v-4");
        assertEquals(0, new BigDecimal("600000.00").compareTo(r.value()));
    }

    @Test
    void 市场数据陈旧超180天_标记人工复核() {
        String staleDate = LocalDate.now().minusDays(200).toString();
        ValuationDtos.ValuationResult r = service.value(req("REAL_ESTATE", "MARKET", "RE002",
                Map.of("area", 100, "unitPrice", 20000, "marketDate", staleDate), "2026-11-10"), "v-5");
        assertEquals("MANUAL", r.conclusion());
        assertTrue(r.basisRef().contains("STALE"));
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    void 资产类型非法_抛VAL1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.value(req("CAR", "MARKET", "X001",
                Map.of("area", 100, "unitPrice", 1000), "2026-11-10"), "v-x"));
        assertEquals("VAL_1007", e.getCode());
    }

    @Test
    void 参数缺失_抛VAL1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.value(req("REAL_ESTATE", "MARKET", "X002",
                Map.of(), "2026-11-10"), "v-y"));
        assertEquals("VAL_1001", e.getCode());
    }

    @Test
    void 可比案例区间冲突_抛VAL1005() {
        BaiException e = assertThrows(BaiException.class, () -> service.value(req("REAL_ESTATE", "MARKET", "X003",
                Map.of("area", 100, "unitPrice", 20000, "caseLow", 25000, "caseHigh", 15000), "2026-11-10"), "v-z"));
        assertEquals("VAL_1005", e.getCode());
    }

    @Test
    void 折现率超界_抛VAL1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.value(req("EQUIPMENT", "INCOME", "X004",
                Map.of("annualIncome", 100000, "discountRate", 1.2, "years", 5), "2026-11-10"), "v-w"));
        assertEquals("VAL_1001", e.getCode());
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void 同requestId同要素_返回首次结果() {
        String rid = "idem-1";
        ValuationDtos.ValuationRequest rq = req("REAL_ESTATE", "MARKET", "RE100",
                Map.of("area", 80, "unitPrice", 15000), "2026-11-10");
        ValuationDtos.ValuationResult first = service.value(rq, rid);
        ValuationDtos.ValuationResult second = service.value(rq, rid);
        assertEquals(first.value(), second.value());
        assertEquals(first.conclusion(), second.conclusion());
    }

    @Test
    void 同requestId不同要素_抛VAL1005() {
        String rid = "idem-2";
        service.value(req("REAL_ESTATE", "MARKET", "RE101",
                Map.of("area", 80, "unitPrice", 15000), "2026-11-10"), rid);
        BaiException e = assertThrows(BaiException.class, () -> service.value(req("REAL_ESTATE", "MARKET", "RE102",
                Map.of("area", 90, "unitPrice", 15000), "2026-11-10"), rid));
        assertEquals("VAL_1005", e.getCode());
    }

    // ------------------------------------------------------------ 查询 / 反馈 / 市场数据

    @Test
    void 估值查询_按valueId与资产维度命中() {
        service.value(req("REAL_ESTATE", "MARKET", "RE200",
                Map.of("area", 100, "unitPrice", 10000), "2026-11-10"), "q-0");
        ValuationDtos.ValuationQueryResult byAsset = service.query(null, "RE200", null, 1, 20, "q-1");
        assertEquals(1, byAsset.total());
        String valueId = byAsset.items().get(0).valueId();
        ValuationDtos.ValuationQueryResult byId = service.query(valueId, null, null, 1, 20, "q-2");
        assertEquals(1, byId.total());
    }

    @Test
    void 估值查询无记录_抛VAL1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.query(null, "NO-ASSET", null, 1, 20, "q-x"));
        assertEquals("VAL_1004", e.getCode());
    }

    @Test
    void 反馈受理_调整需附调整值() {
        service.value(req("REAL_ESTATE", "MARKET", "RE300",
                Map.of("area", 100, "unitPrice", 10000), "2026-11-10"), "f-0");
        ValuationDtos.ValuationQueryResult q = service.query(null, "RE300", null, 1, 20, "f-q");
        String valueId = q.items().get(0).valueId();
        ValuationDtos.FeedbackResult r = service.feedback(
                new ValuationDtos.FeedbackRequest(valueId, "ACCEPT", "审核员A", null, null, null, null), "f-1");
        assertEquals("RECEIVED", r.status());
        assertNotNull(r.feedbackId());
        // ADJUST 缺 adjustValue → VAL_1001
        BaiException e = assertThrows(BaiException.class, () -> service.feedback(
                new ValuationDtos.FeedbackRequest(valueId, "ADJUST", "审核员A", null, null, null, null), "f-2"));
        assertEquals("VAL_1001", e.getCode());
    }

    @Test
    void 重复反馈_抛VAL1005() {
        service.value(req("REAL_ESTATE", "MARKET", "RE301",
                Map.of("area", 100, "unitPrice", 10000), "2026-11-10"), "f-10");
        ValuationDtos.ValuationQueryResult q = service.query(null, "RE301", null, 1, 20, "f-q2");
        String valueId = q.items().get(0).valueId();
        service.feedback(new ValuationDtos.FeedbackRequest(valueId, "REJECT", "审核员B", null, null, null, null), "f-11");
        BaiException e = assertThrows(BaiException.class, () -> service.feedback(
                new ValuationDtos.FeedbackRequest(valueId, "REJECT", "审核员C", null, null, null, null), "f-12"));
        assertEquals("VAL_1005", e.getCode());
    }

    @Test
    void 市场数据接入_刷新更新查询版本递增() {
        service.market(new ValuationDtos.MarketRequest("REFRESH", "REAL_ESTATE", "上海", "房地产",
                java.util.List.of(new ValuationDtos.MarketPoint("2026-11-01", new BigDecimal("30000"), null, "链家")), null), "m-1");
        ValuationDtos.MarketResult updated = service.market(new ValuationDtos.MarketRequest("UPDATE", "REAL_ESTATE", "上海", "房地产",
                java.util.List.of(new ValuationDtos.MarketPoint("2026-11-02", new BigDecimal("30500"), null, "链家")), null), "m-2");
        assertEquals("UPDATED", updated.status());
        assertEquals(2, updated.version());
        ValuationDtos.MarketResult queried = service.market(new ValuationDtos.MarketRequest("QUERY", "REAL_ESTATE", "上海", "房地产",
                java.util.List.of(), null), "m-3");
        assertEquals("QUERIED", queried.status());
        assertEquals(2, queried.indexed());
    }

    @Test
    void 市场数据查询无数据_抛VAL1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.market(
                new ValuationDtos.MarketRequest("QUERY", "REAL_ESTATE", "北京", "房地产",
                        java.util.List.of(), null), "m-x"));
        assertEquals("VAL_1004", e.getCode());
    }
}
