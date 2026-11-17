package com.bai.carbon;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CarbonService 单元测试：排放核算（范围一二三公式）、因子库、目录分类、减排量、配额、碳资产估值、绿色统计、支持工具、洗绿识别、参数校验、幂等。
 */
class CarbonServiceTest {

    private final CarbonService service = new CarbonService();

    // ------------------------------------------------------------ 排放核算

    @Test
    void 排放核算_范围公式与质押额度正确() {
        CarbonDtos.AccountResult r = service.account(new CarbonDtos.AccountRequest("E001", "2026-10",
                Map.of("electricityKwh", 10000, "naturalGasM3", 1000, "coalTon", 10,
                        "purchasedHeatGj", 100, "supplyChainTons", 50), null, null), "a-1");
        // 范围二 = 10000/1000×0.5703 + 100×0.11 = 16.70；范围一 = 1000×0.0021622 + 10×2.66 = 28.76
        assertEquals(0, new BigDecimal("16.70").compareTo(r.scope2()));
        assertEquals(0, new BigDecimal("28.76").compareTo(r.scope1()));
        assertEquals(0, new BigDecimal("50.00").compareTo(r.scope3()));
        assertEquals(0, new BigDecimal("95.46").compareTo(r.totalCarbon()));
        assertEquals(0, new BigDecimal("95.46").compareTo(r.er()));
        // 质押额度 = 95.46 × 加权碳价 90.0 × 0.6 = 5154.84
        assertEquals(0, new BigDecimal("5154.84").compareTo(r.pledgeableAmount()));
        assertEquals("EF-2025-V2", r.factorVersion());
    }

    @Test
    void 范围三缺省_按范围一二之和乘两成() {
        CarbonDtos.AccountResult r = service.account(new CarbonDtos.AccountRequest("E002", "2026-10",
                Map.of("electricityKwh", 10000), null, null), "a-2");
        // 范围二 = 5.70，范围一 = 0 → 范围三 = 5.70×0.2 = 1.14
        assertEquals(0, new BigDecimal("5.70").compareTo(r.scope2()));
        assertEquals(0, new BigDecimal("1.14").compareTo(r.scope3()));
    }

    @Test
    void 周期非法_抛CARB1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.account(
                new CarbonDtos.AccountRequest("E003", "2026-13", Map.of("electricityKwh", 100), null, null), "a-x"));
        assertEquals("CARB_1007", e.getCode());
    }

    @Test
    void 因子版本不存在_抛CARB1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.account(
                new CarbonDtos.AccountRequest("E004", "2026-10", Map.of("electricityKwh", 100),
                        "EF-2026", null), "a-y"));
        assertEquals("CARB_1004", e.getCode());
    }

    @Test
    void 活动数据为负_抛CARB1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.account(
                new CarbonDtos.AccountRequest("E005", "2026-10", Map.of("electricityKwh", -100), null, null), "a-z"));
        assertEquals("CARB_1001", e.getCode());
    }

    @Test
    void 同requestId不同要素_抛CARB1005() {
        String rid = "idem-1";
        service.account(new CarbonDtos.AccountRequest("E100", "2026-10", Map.of("electricityKwh", 100), null, null), rid);
        BaiException e = assertThrows(BaiException.class, () -> service.account(
                new CarbonDtos.AccountRequest("E101", "2026-10", Map.of("electricityKwh", 200), null, null), rid));
        assertEquals("CARB_1005", e.getCode());
    }

    @Test
    void 因子库_含电网排放因子() {
        List<CarbonDtos.FactorItem> factors = service.factors();
        assertEquals(6, factors.size());
        CarbonDtos.FactorItem grid = factors.stream().filter(f -> f.code().equals("EF-GRID")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("0.5703").compareTo(grid.value()));
    }

    // ------------------------------------------------------------ 目录分类（4.2）

    @Test
    void 目录分类_结论与评分在区间内() {
        CarbonDtos.ClassifyResult r = service.classify(new CarbonDtos.ClassifyRequest("ENTERPRISE", "E200",
                "4412", "太阳能发电", Map.of(), null, "绿色信贷", null), "c-1");
        assertTrue(Set.of("GREEN", "NON_GREEN", "SUSPECT").contains(r.conclusion()));
        assertTrue(r.matchScore().compareTo(new BigDecimal("0.55")) >= 0);
        assertTrue(r.matchScore().compareTo(new BigDecimal("0.95")) <= 0);
        assertFalse(r.matchBasis().isEmpty());
    }

    @Test
    void 项目分类缺项目信息_抛CARB4001() {
        BaiException e = assertThrows(BaiException.class, () -> service.classify(
                new CarbonDtos.ClassifyRequest("PROJECT", "E201", null, null, Map.of(), null, null, null), "c-x"));
        assertEquals("CARB_4001", e.getCode());
    }

    @Test
    void 目录版本不存在_抛CARB4003() {
        BaiException e = assertThrows(BaiException.class, () -> service.classify(
                new CarbonDtos.ClassifyRequest("ENTERPRISE", "E202", "4412", "太阳能",
                        Map.of(), "CAT-2026", null, null), "c-y"));
        assertEquals("CARB_4003", e.getCode());
    }

    // ------------------------------------------------------------ 减排量（4.4）

    @Test
    void 减排量测算_适用则返回净减排() {
        CarbonDtos.ReductionResult result = null;
        for (int i = 1; i <= 50 && result == null; i++) {
            try {
                result = service.reduction(new CarbonDtos.ReductionRequest("PRJ-" + i, "CM-001", "光伏",
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null), "rd-" + i);
            } catch (BaiException ex) {
                if (!"CARB_4101".equals(ex.getCode())) {
                    throw ex;
                }
            }
        }
        assertNotNull(result);
        assertTrue(result.applicable());
        assertEquals(0, result.baselineEmission().subtract(result.projectEmission()).subtract(result.leakage())
                .compareTo(result.reduction()));
    }

    @Test
    void 无净减排_抛CARB4101() {
        BaiException e = assertThrows(BaiException.class, () -> service.reduction(
                new CarbonDtos.ReductionRequest("PRJ-0", "CM-001", "光伏",
                        Map.of(), Map.of("emission", 100), Map.of("emission", 200), Map.of(), Map.of(), null), "rd-x"));
        assertEquals("CARB_4101", e.getCode());
    }

    @Test
    void 方法学不存在_抛CARB4003() {
        BaiException e = assertThrows(BaiException.class, () -> service.reduction(
                new CarbonDtos.ReductionRequest("PRJ-1", "CM-099", "光伏",
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null), "rd-y"));
        assertEquals("CARB_4003", e.getCode());
    }

    // ------------------------------------------------------------ 配额（4.5）

    @Test
    void 基准线法配额_基准乘产量乘免费比例() {
        CarbonDtos.QuotaResult r = service.quota(new CarbonDtos.QuotaRequest("E300",
                new BigDecimal("6000"), "BASELINE", new BigDecimal("0.8"),
                new BigDecimal("10000"), null, new BigDecimal("0.9"), "2026", null, null), "q-1");
        // Q = 0.8×10000×0.9 = 7200.00；G = Q − 排放 = 1200.00
        assertEquals(0, new BigDecimal("7200.00").compareTo(r.quotaAmount()));
        assertEquals(0, new BigDecimal("1200.00").compareTo(r.gapSurplus()));
        assertNotNull(r.forecast());
    }

    @Test
    void 基准线法缺参数_抛CARB4001() {
        BaiException e = assertThrows(BaiException.class, () -> service.quota(
                new CarbonDtos.QuotaRequest("E301", new BigDecimal("6000"), "BASELINE",
                        null, null, null, new BigDecimal("0.9"), "2026", null, null), "q-x"));
        assertEquals("CARB_4001", e.getCode());
    }

    @Test
    void 配额规则版本不存在_抛CARB4003() {
        BaiException e = assertThrows(BaiException.class, () -> service.quota(
                new CarbonDtos.QuotaRequest("E302", new BigDecimal("6000"), "HISTORICAL",
                        null, null, new BigDecimal("0.9"), new BigDecimal("0.9"), "2026", "QUOTA-2026", null), "q-y"));
        assertEquals("CARB_4003", e.getCode());
    }

    // ------------------------------------------------------------ 碳资产估值（4.6）

    @Test
    void 碳资产估值_缺省情景加权与质押值() {
        CarbonDtos.ValueResult r = service.value(new CarbonDtos.ValueRequest("A001", "ALLOWANCE",
                new BigDecimal("1000"), "2026-12", "自有", null, null, null, null), "v-1");
        assertEquals(3, r.valuationByScenario().size());
        // 加权值 = 75000×0.5 + 112500×0.25 + 52500×0.25 = 78750.00；质押 = ×0.6 = 47250.00
        assertEquals(0, new BigDecimal("78750.00").compareTo(r.weightedValue()));
        assertEquals(0, new BigDecimal("47250.00").compareTo(r.pledgeValue()));
    }

    @Test
    void 资产类型非法_抛CARB4001() {
        BaiException e = assertThrows(BaiException.class, () -> service.value(
                new CarbonDtos.ValueRequest("A002", "GOLD", new BigDecimal("100"), null, null, null, null, null, null), "v-x"));
        assertEquals("CARB_4001", e.getCode());
    }

    // ------------------------------------------------------------ 绿色统计（4.7）

    @Test
    void 绿色统计_绿色余额与占比() {
        CarbonDtos.GreenStatResult r = service.greenStat(new CarbonDtos.GreenStatRequest(
                List.of(new CarbonDtos.LoanInfo("LN001", new BigDecimal("1000000"), "1Y", "设备",
                        "4412", "清洁能源产业-太阳能-分布式光伏发电"),
                        new CarbonDtos.LoanInfo("LN002", new BigDecimal("1000000"), "1Y", "周转",
                        "4710", null)),
                "2026-Q3", null, null), "gs-1");
        assertEquals(2, r.recognitions().size());
        assertEquals(0, new BigDecimal("1000000.00").compareTo(r.greenBalance()));
        assertEquals(0, new BigDecimal("0.5000").compareTo(r.greenRatio()));
        assertEquals("GREEN", r.recognitions().get(0).conclusion());
        assertEquals("NON_GREEN", r.recognitions().get(1).conclusion());
    }

    @Test
    void 贷款金额为负_抛CARB4001() {
        BaiException e = assertThrows(BaiException.class, () -> service.greenStat(
                new CarbonDtos.GreenStatRequest(
                        List.of(new CarbonDtos.LoanInfo("LN003", new BigDecimal("-100"), "1Y", "x", "4412", null)),
                        "2026-Q3", null, null), "gs-x"));
        assertEquals("CARB_4001", e.getCode());
    }

    // ------------------------------------------------------------ 支持工具（4.8）

    @Test
    void 支持工具_达门槛符合并测算再贷款() {
        CarbonDtos.ToolCalcResult r = service.toolCalc(new CarbonDtos.ToolCalcRequest("光伏",
                new BigDecimal("150"), null, new BigDecimal("500000"), List.of(), null, null), "t-1");
        assertTrue(r.compliance());
        // 再贷款 = 500000 × 0.6 = 300000.00
        assertEquals(0, new BigDecimal("300000.00").compareTo(r.toolLoanAmount()));
        assertFalse(r.materialChecklist().isEmpty());
    }

    @Test
    void 支持工具_未达门槛不符合() {
        CarbonDtos.ToolCalcResult r = service.toolCalc(new CarbonDtos.ToolCalcRequest("风电",
                new BigDecimal("50"), null, null, List.of(), null, null), "t-2");
        assertFalse(r.compliance());
        // 无 loanAmount → 按减排量×100 兜底
        assertEquals(0, new BigDecimal("5000.00").compareTo(r.toolLoanAmount()));
    }

    // ------------------------------------------------------------ 洗绿识别（4.9）

    @Test
    void 洗绿识别_证据缺失命中规则() {
        CarbonDtos.GreenwashResult r = service.greenwash(new CarbonDtos.GreenwashRequest("E400", "GREEN",
                Map.of(), null, null), "g-1");
        // 证据缺失必命中 RULE-05 → 风险至少 MEDIUM，处置 REVIEW/REJECT
        assertTrue(r.hitRules().stream().anyMatch(h -> h.ruleCode().equals("RULE-05")));
        assertTrue(Set.of("MEDIUM", "HIGH").contains(r.riskLevel()));
        assertTrue(Set.of("REVIEW", "REJECT").contains(r.disposalAdvice()));
        assertFalse(r.evidenceChain().isEmpty());
    }

    @Test
    void 洗绿识别_结论非法_抛CARB4001() {
        BaiException e = assertThrows(BaiException.class, () -> service.greenwash(
                new CarbonDtos.GreenwashRequest("E401", "BLUE", Map.of(), null, null), "g-x"));
        assertEquals("CARB_4001", e.getCode());
    }

    @Test
    void 洗绿规则集版本不存在_抛CARB4003() {
        BaiException e = assertThrows(BaiException.class, () -> service.greenwash(
                new CarbonDtos.GreenwashRequest("E402", "GREEN", Map.of(), "GR-2026", null), "g-y"));
        assertEquals("CARB_4003", e.getCode());
    }
}
