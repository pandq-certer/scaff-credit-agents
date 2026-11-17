package com.bai.finstmt;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FinStmtService 单元测试：报表分析（勾稽/指标/造假/四流）、报表解析、勾稽校验、指标测算、趋势分析、造假信号、四流交叉验证、参数校验、幂等。
 */
class FinStmtServiceTest {

    private final FinStmtService service = new FinStmtService();

    private FinStmtDtos.Statement stmt(String type, Map<String, Object> items) {
        return new FinStmtDtos.Statement(type, items);
    }

    // ------------------------------------------------------------ 报表分析（analyze）

    @Test
    void 正常报表_勾稽通过且低造假评分() {
        FinStmtDtos.AnalyzeResult r = service.analyze(new FinStmtDtos.AnalyzeRequest("E001", List.of(
                stmt("BALANCE", Map.of(
                        "totalAssets", 10000000, "totalLiab", 4000000, "totalEquity", 6000000,
                        "receivables", 1000000, "inventory", 1000000, "monetaryFund", 1000000)),
                stmt("INCOME", Map.of("revenue", 50000000, "netProfit", 5000000)),
                stmt("CASHFLOW", Map.of("operatingCashFlow", 6000000))),
                "2026-10", "REVIEW", null, null), "a-1");
        assertEquals("LOW", r.fraudLevel());
        assertEquals(0, new BigDecimal("9.0").compareTo(r.fraudScore()));
        assertEquals("PENDING", r.verification());
        assertEquals(6, r.metrics().size());
        assertNotNull(r.analysisNo());
    }

    @Test
    void 勾稽不平_抛FST4101() {
        BaiException e = assertThrows(BaiException.class, () -> service.analyze(
                new FinStmtDtos.AnalyzeRequest("E002", List.of(
                        stmt("BALANCE", Map.of("totalAssets", 10000000, "totalLiab", 4000000, "totalEquity", 5000000)),
                        stmt("INCOME", Map.of("revenue", 50000000, "netProfit", 5000000))),
                        "2026-10", "REVIEW", null, null), "a-x"));
        assertEquals("FST_4101", e.getCode());
    }

    @Test
    void 造假评分HIGH_系统阻断转人工() {
        BaiException e = assertThrows(BaiException.class, () -> service.analyze(
                new FinStmtDtos.AnalyzeRequest("E003", List.of(
                        stmt("BALANCE", Map.of(
                                "totalAssets", 10000000, "totalLiab", 7000000, "totalEquity", 3000000,
                                "receivables", 20000000, "inventory", 30000000, "monetaryFund", 4000000)),
                        stmt("INCOME", Map.of("revenue", 50000000, "netProfit", 5000000)),
                        stmt("CASHFLOW", Map.of("operatingCashFlow", 500000))),
                        "2026-10", "DUE_DILIGENCE", null, null), "a-y"));
        assertEquals("FST_4103", e.getCode());
    }

    @Test
    void 场景非法_抛FST4001() {
        BaiException e = assertThrows(BaiException.class, () -> service.analyze(
                new FinStmtDtos.AnalyzeRequest("E004", List.of(
                        stmt("BALANCE", Map.of("totalAssets", 10000000, "totalLiab", 4000000, "totalEquity", 6000000))),
                        "2026-10", "SPOT", null, null), "a-z"));
        assertEquals("FST_4001", e.getCode());
    }

    @Test
    void 报表缺资产负债表_抛FST1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.analyze(
                new FinStmtDtos.AnalyzeRequest("E005", List.of(
                        stmt("INCOME", Map.of("revenue", 50000000, "netProfit", 5000000))),
                        "2026-10", "REVIEW", null, null), "a-w"));
        assertEquals("FST_1001", e.getCode());
    }

    // ------------------------------------------------------------ 报表解析（4.2）

    @Test
    void 报表解析_标准科目与合计平衡() {
        FinStmtDtos.ParseResult r = service.parse(new FinStmtDtos.ParseRequest("E100", "BALANCE_SHEET",
                "2026-10", "ASBE", null, List.of(), null), "p-1");
        assertEquals("SUCCESS", r.code());
        assertFalse(r.items().isEmpty());
        assertTrue(r.balanceCheck().pass());
        assertTrue(r.quality().accuracy().compareTo(new BigDecimal("0.95")) >= 0);
        assertTrue(r.parseNo().startsWith("PRS"));
    }

    @Test
    void 解析质量过低_抛FST4102() {
        BaiException e = assertThrows(BaiException.class, () -> service.parse(
                new FinStmtDtos.ParseRequest("E101", "BALANCE_SHEET", "2026-10", "ASBE",
                        Map.of("accuracy", 0.90), List.of(), null), "p-x"));
        assertEquals("FST_4102", e.getCode());
    }

    @Test
    void 报表类型非法_抛FST1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.parse(
                new FinStmtDtos.ParseRequest("E102", "LEDGER", "2026-10", "ASBE", null, List.of(), null), "p-y"));
        assertEquals("FST_1007", e.getCode());
    }

    // ------------------------------------------------------------ 勾稽校验（4.3）

    @Test
    void 勾稽校验_四类检查全部通过() {
        FinStmtDtos.Statement balance = stmt("BALANCE", Map.ofEntries(
                Map.entry("totalAssets", 10000000), Map.entry("totalLiab", 4000000),
                Map.entry("totalEquity", 6000000), Map.entry("retainedEarnings", 2000000),
                Map.entry("netProfit", 1000000), Map.entry("prevRetainedEarnings", 1500000),
                Map.entry("dividends", 500000), Map.entry("cashBegin", 1000000),
                Map.entry("cashEnd", 1300000), Map.entry("cashNetIncrease", 300000),
                Map.entry("monetaryFund", 1300000)));
        FinStmtDtos.ReconcileResult r = service.reconcile(
                new FinStmtDtos.ReconcileRequest("E200", List.of(balance),
                        List.of("2026-09", "2026-10"), null), "r-1");
        assertEquals("PASS", r.overall());
        assertEquals(4, r.checks().size());
        assertTrue(r.reconcileNo().startsWith("RCL"));
    }

    @Test
    void 勾稽校验_期次缺失_抛FST1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.reconcile(
                new FinStmtDtos.ReconcileRequest("E201", List.of(
                        stmt("BALANCE", Map.of("totalAssets", 10000000, "totalLiab", 4000000, "totalEquity", 6000000))),
                        List.of(), null), "r-x"));
        assertEquals("FST_1001", e.getCode());
    }

    // ------------------------------------------------------------ 指标测算（4.4）

    @Test
    void 指标测算_二十项四维指标() {
        FinStmtDtos.MetricResult r = service.metric(new FinStmtDtos.MetricRequest("E300", List.of(
                stmt("BALANCE", Map.of("totalAssets", 10000000, "totalLiab", 4000000, "totalEquity", 6000000,
                        "currentAssets", 5000000, "currentLiab", 2000000, "inventory", 1000000,
                        "receivables", 1500000, "monetaryFund", 1000000, "payables", 800000)),
                stmt("INCOME", Map.of("revenue", 50000000, "netProfit", 5000000, "grossProfit", 12000000,
                        "costOfSales", 38000000, "interestExpense", 500000))),
                "4412", null, null), "m-1");
        assertEquals(19, r.metrics().size());
        assertTrue(r.metrics().stream().anyMatch(x -> x.code().equals("CUR_RATIO")));
        assertNotNull(r.metricNo());
    }

    @Test
    void 基准版本不存在_抛FST4104() {
        BaiException e = assertThrows(BaiException.class, () -> service.metric(
                new FinStmtDtos.MetricRequest("E301", List.of(
                        stmt("BALANCE", Map.of("totalAssets", 10000000, "totalLiab", 4000000, "totalEquity", 6000000))),
                        "4412", "V9.9", null), "m-x"));
        assertEquals("FST_4104", e.getCode());
    }

    // ------------------------------------------------------------ 趋势分析（4.5）

    @Test
    void 趋势分析_两期环比同比() {
        FinStmtDtos.TrendResult r = service.trend(new FinStmtDtos.TrendRequest("E400",
                List.of("CUR_RATIO", "DEBT_RATIO"), List.of("2026-09", "2026-10"), "4412", null), "t-1");
        assertEquals(2, r.trends().size());
        for (FinStmtDtos.Trend tr : r.trends()) {
            assertEquals(2, tr.series().size());
            assertNotNull(tr.qoq());
            assertNotNull(tr.yoy());
        }
    }

    @Test
    void 趋势分析_单期_抛FST4002() {
        BaiException e = assertThrows(BaiException.class, () -> service.trend(
                new FinStmtDtos.TrendRequest("E401", List.of("CUR_RATIO"), List.of("2026-10"), null, null), "t-x"));
        assertEquals("FST_4002", e.getCode());
    }

    @Test
    void 指标编码非法_抛FST1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.trend(
                new FinStmtDtos.TrendRequest("E402", List.of("NOPE"), List.of("2026-09", "2026-10"), null, null), "t-y"));
        assertEquals("FST_1007", e.getCode());
    }

    // ------------------------------------------------------------ 造假信号（4.6）

    @Test
    void 造假信号_高风险数据返回HIGH() {
        FinStmtDtos.FraudResult r = service.fraud(new FinStmtDtos.FraudRequest("E500", List.of(
                stmt("BALANCE", Map.of(
                        "totalAssets", 10000000, "totalLiab", 7000000, "totalEquity", 3000000,
                        "receivables", 20000000, "inventory", 30000000, "constructionInProgress", 4000000)),
                stmt("INCOME", Map.of("revenue", 50000000, "netProfit", 5000000, "grossProfit", 500000)),
                stmt("CASHFLOW", Map.of("operatingCashFlow", 500000))),
                List.of(), null, null, null), "f-1");
        assertFalse(r.signals().isEmpty());
        assertEquals("HIGH", r.fraudLevel());
        assertTrue(r.fraudScore().compareTo(new BigDecimal("70.0")) > 0);
        assertTrue(r.fraudNo().startsWith("FRD"));
    }

    @Test
    void 信号规则版本非法_抛FST1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.fraud(
                new FinStmtDtos.FraudRequest("E501", List.of(
                        stmt("BALANCE", Map.of("totalAssets", 10000000, "totalLiab", 4000000, "totalEquity", 6000000))),
                        List.of(), null, "V9.9", null), "f-x"));
        assertEquals("FST_1007", e.getCode());
    }

    // ------------------------------------------------------------ 四流交叉验证（4.7）

    @Test
    void 四流验证_金额一致返回CONSISTENT() {
        FinStmtDtos.CrossResult r = service.cross(new FinStmtDtos.CrossRequest("E600",
                new FinStmtDtos.FlowData(new BigDecimal("10000000"), "CT-1", "2026-10", "X公司"),
                null, null, null, new BigDecimal("10000000"), null, null), "c-1");
        assertEquals("CONSISTENT", r.verification());
        assertFalse(r.deviationDetails().isEmpty());
    }

    @Test
    void 四流验证_偏差超一成五返回ABNORMAL() {
        FinStmtDtos.CrossResult r = service.cross(new FinStmtDtos.CrossRequest("E601",
                null, null,
                new FinStmtDtos.FlowData(new BigDecimal("15000000"), "FLOW-1", "2026-10", "X公司"),
                null, new BigDecimal("10000000"), null, null), "c-2");
        assertEquals("ABNORMAL", r.verification());
    }

    @Test
    void 四流验证_报表收入缺失_抛FST1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.cross(
                new FinStmtDtos.CrossRequest("E602", null, null, null, null, null, null, null), "c-x"));
        assertEquals("FST_1001", e.getCode());
    }

    @Test
    void 四流验证_容差超界_抛FST1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.cross(
                new FinStmtDtos.CrossRequest("E603",
                        new FinStmtDtos.FlowData(new BigDecimal("10000000"), "CT-1", "2026-10", "X公司"),
                        null, null, null, new BigDecimal("10000000"), new BigDecimal("0.8"), null), "c-y"));
        assertEquals("FST_1001", e.getCode());
    }
}
