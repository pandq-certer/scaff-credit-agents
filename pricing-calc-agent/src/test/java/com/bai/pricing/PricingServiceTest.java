package com.bai.pricing;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PricingService 单元测试：利率试算（LPR 基准+风险溢价+成本加成）、违约金试算、幂等/冲突、
 * 规则管理、试算查询、反馈受理。
 * <p>口径：≤36 月取 1 年期 LPR 3.45，&gt;36 月取 5 年期以上 3.95；费用=金额×0.05%。
 */
class PricingServiceTest {

    private final PricingService service = new PricingService();

    private PricingDtos.QuoteRequest req(String productId, BigDecimal amount, Integer term, String rateType,
                                         String collateral, String rating, String pricingType, Map<String, Object> params) {
        return new PricingDtos.QuoteRequest(productId, amount, term, rateType, collateral, rating,
                null, null, pricingType, params);
    }

    @Test
    void 浮动利率短期_最终利率与手续费正确() {
        PricingDtos.QuoteResult r = service.quote(req("P001", new BigDecimal("1000000"), 12,
                "FLOATING", "抵押", "AAA", null, null), "q1");
        assertEquals("SUCCESS", r.code());
        assertEquals(0, new BigDecimal("3.45").compareTo(r.baseRate()));
        assertEquals(0, new BigDecimal("0.40").compareTo(r.riskPremium()));   // AAA 0.40 + 抵押 0.00
        assertEquals(0, new BigDecimal("0.85").compareTo(r.costAddOn()));     // 资金 0.60 + 运营 0.25
        assertEquals(0, new BigDecimal("4.70").compareTo(r.finalRate()));
        assertEquals(1, r.fees().size());
        assertEquals(0, new BigDecimal("500.00").compareTo(r.fees().get(0).amount()));
        assertEquals("PRC-RULE-2026-Q3", r.ruleVersion());
    }

    @Test
    void 固定利率长期_取5年期以上LPR并叠加锁定溢价() {
        PricingDtos.QuoteResult r = service.quote(req("P002", new BigDecimal("500000"), 60,
                "FIXED", "信用", "B", null, null), "q2");
        assertEquals(0, new BigDecimal("3.95").compareTo(r.baseRate()));
        assertEquals(0, new BigDecimal("3.00").compareTo(r.riskPremium()));   // B 2.50 + 信用 0.50
        assertEquals(0, new BigDecimal("0.95").compareTo(r.costAddOn()));     // + 固定锁定 0.10
        assertEquals(0, new BigDecimal("7.90").compareTo(r.finalRate()));
    }

    @Test
    void 期限超限_抛PRC_1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.quote(req("P003", new BigDecimal("100000"), 361,
                        "FLOATING", "抵押", "AAA", null, null), "e1"));
        assertEquals("PRC_1007", e.getCode());
    }

    @Test
    void amount非正_抛PRC_1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.quote(req("P004", BigDecimal.ZERO, 12,
                        "FLOATING", "抵押", "AAA", null, null), "e2"));
        assertEquals("PRC_1001", e.getCode());
    }

    @Test
    void 同requestId幂等_不同要素抛PRC_1005() {
        String rid = "idem-1";
        PricingDtos.QuoteResult first = service.quote(req("P005", new BigDecimal("100000"), 12,
                "FLOATING", "抵押", "AAA", null, null), rid);
        PricingDtos.QuoteResult second = service.quote(req("P005", new BigDecimal("100000"), 12,
                "FLOATING", "抵押", "AAA", null, null), rid);
        assertEquals(0, first.finalRate().compareTo(second.finalRate()));
        // 同 requestId 不同要素 → 定价要素冲突
        BaiException conflict = assertThrows(BaiException.class,
                () -> service.quote(req("P005", new BigDecimal("200000"), 12,
                        "FLOATING", "抵押", "AAA", null, null), rid));
        assertEquals("PRC_1005", conflict.getCode());
    }

    @Test
    void 违约金试算_金额等于本金乘费率() {
        PricingDtos.QuoteResult r = service.quote(req("P006", null, null, null, null, null, "PENALTY",
                Map.of("remainingPrincipal", 1000000, "penaltyRate", 0.02)), "q3");
        assertEquals(1, r.fees().size());
        assertEquals("提前还款违约金", r.fees().get(0).feeItem());
        assertEquals(0, new BigDecimal("20000.00").compareTo(r.fees().get(0).amount()));
        assertNull(r.finalRate());
    }

    @Test
    void 规则管理_CREATE升版与灰度() {
        PricingDtos.RuleParam p1 = new PricingDtos.RuleParam(null, "小微客群规则", "客群",
                "客户群=小微", new BigDecimal("15"), "2026-11-01", null);
        PricingDtos.RuleResult created = service.rule(new PricingDtos.RuleRequest("CREATE", p1, null), "r1");
        assertNotNull(created.ruleId());
        assertEquals(1, created.version());
        assertEquals("ACTIVE", created.status());
        PricingDtos.RuleParam p2 = new PricingDtos.RuleParam(created.ruleId(), "小微客群规则", "客群",
                "客户群=小微", new BigDecimal("20"), "2026-11-01", null);
        PricingDtos.RuleResult updated = service.rule(new PricingDtos.RuleRequest("UPDATE", p2, null), "r2");
        assertEquals(2, updated.version());
        PricingDtos.RuleResult gray = service.rule(
                new PricingDtos.RuleRequest("GRAY", new PricingDtos.RuleParam(created.ruleId(), null, null,
                        null, null, null, null), null), "r3");
        assertEquals("GRAY", gray.status());
    }

    @Test
    void 试算查询_按产品过滤与quoteId精确() {
        service.quote(req("P007", new BigDecimal("300000"), 24, "FLOATING", "抵押", "AA", null, null), "q4");
        PricingDtos.QueryResult byProduct = service.query(new PricingDtos.QueryRequest(null, "P007", null, null, null), "qr1");
        assertEquals(1, byProduct.total());
        String quoteId = byProduct.items().get(0).quoteId();
        PricingDtos.QueryResult byId = service.query(new PricingDtos.QueryRequest(quoteId, null, null, null, null), "qr2");
        assertEquals(1, byId.total());
        assertEquals(new Integer(24), byId.items().get(0).term());
    }

    @Test
    void 反馈重复受理_抛PRC_1005() {
        service.quote(req("P008", new BigDecimal("300000"), 24, "FLOATING", "抵押", "AA", null, null), "q5");
        PricingDtos.QueryResult q = service.query(new PricingDtos.QueryRequest(null, "P008", null, null, null), "qr3");
        String quoteId = q.items().get(0).quoteId();
        PricingDtos.FeedbackResult f = service.feedback(
                new PricingDtos.FeedbackRequest(quoteId, "ACCEPT", "reviewer1", "定价合理", null, "fb1"), "fb1");
        assertEquals("ACCEPTED", f.status());
        BaiException dup = assertThrows(BaiException.class,
                () -> service.feedback(new PricingDtos.FeedbackRequest(quoteId, "REJECT", "reviewer2", null, null, "fb2"), "fb2"));
        assertEquals("PRC_1005", dup.getCode());
    }
}
