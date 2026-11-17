package com.bai.rule;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RuleEngineService 单元测试：规则引擎（真实可执行 DSL）——RETE 匹配、salience 冲突消解、
 * 结论聚合、事实校验错误码、幂等。
 */
class RuleEngineServiceTest {

    private final RuleEngineService service = new RuleEngineService();

    private Map<String, Object> facts(Map<String, Object> m) {
        return m;
    }

    @Test
    void 全部条件通过_结论APPROVE() {
        RuleEngineDtos.RuleResult r = service.execute(new RuleEngineDtos.ExecuteRequest(
                facts(Map.of("age", 35, "monthlyIncome", 20000, "loanAmount", 500000,
                        "term", 36, "creditScore", 700)),
                "ADMISSION_PERSONAL_LOAN", null, true), "rul-ok1");
        assertEquals("APPROVE", r.conclusion().decision());
        assertEquals("ADM-08", r.conclusion().code());
        assertEquals("R008", r.hits().get(0).ruleId());
        assertNotNull(r.execId());
    }

    @Test
    void 征信不足_REJECT且冲突消解取高salience() {
        RuleEngineDtos.RuleResult r = service.execute(new RuleEngineDtos.ExecuteRequest(
                facts(Map.of("age", 45, "monthlyIncome", 3000, "loanAmount", 500000,
                        "term", 24, "creditScore", 550)),
                "ADMISSION_PERSONAL_LOAN", null, true), "rul-rej1");
        assertEquals("REJECT", r.conclusion().decision());
        assertEquals("ADM-03", r.conclusion().code()); // R003 salience 95 > R004 90
        assertEquals(2, r.hits().size());
        assertEquals("R003", r.hits().get(0).ruleId());
    }

    @Test
    void 金额超限_结论REVIEW转人工复核() {
        RuleEngineDtos.RuleResult r = service.execute(new RuleEngineDtos.ExecuteRequest(
                facts(Map.of("age", 65, "monthlyIncome", 30000, "loanAmount", 2000000,
                        "term", 36, "creditScore", 750)),
                "ADMISSION_PERSONAL_LOAN", null, true), "rul-rv1");
        assertEquals("REVIEW", r.conclusion().decision());
        assertEquals("ADM-06", r.conclusion().code());
    }

    @Test
    void 年龄偏大_结论WARN() {
        RuleEngineDtos.RuleResult r = service.execute(new RuleEngineDtos.ExecuteRequest(
                facts(Map.of("age", 65, "monthlyIncome", 30000, "loanAmount", 500000,
                        "term", 36, "creditScore", 750)),
                "ADMISSION_PERSONAL_LOAN", null, true), "rul-w1");
        assertEquals("WARN", r.conclusion().decision());
        assertEquals("ADM-07", r.conclusion().code());
    }

    @Test
    void 预警规则集_逾期90天触发二级预警() {
        RuleEngineDtos.RuleResult r = service.execute(new RuleEngineDtos.ExecuteRequest(
                facts(Map.of("overdueDays", 95, "balance", 3000000, "fiveClass", "NORMAL")),
                "RISK_EARLY_WARNING", null, true), "rul-ew1");
        assertEquals("WARN", r.conclusion().decision());
        assertEquals("RSK-W02", r.conclusion().code());
    }

    @Test
    void 缺rulesetId_抛RUL1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.execute(new RuleEngineDtos.ExecuteRequest(
                        facts(Map.of("age", 30)), null, null, true), "rul-e1"));
        assertEquals("RUL_1001", e.getCode());
    }

    @Test
    void 规则集不存在_抛RUL1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.execute(new RuleEngineDtos.ExecuteRequest(
                        facts(Map.of("age", 30)), "NO_EXIST", null, true), "rul-e2"));
        assertEquals("RUL_1004", e.getCode());
    }

    @Test
    void 版本不匹配_抛RUL1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.execute(new RuleEngineDtos.ExecuteRequest(
                        facts(Map.of("age", 30, "monthlyIncome", 10000, "loanAmount", 100000,
                                "term", 12, "creditScore", 700)),
                        "ADMISSION_PERSONAL_LOAN", "2025.01.01", true), "rul-e3"));
        assertEquals("RUL_1004", e.getCode());
    }

    @Test
    void 事实缺必要字段_抛RUL1005() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.execute(new RuleEngineDtos.ExecuteRequest(
                        facts(Map.of("age", 30, "monthlyIncome", 10000, "loanAmount", 100000, "term", 12)),
                        "ADMISSION_PERSONAL_LOAN", null, true), "rul-e4"));
        assertEquals("RUL_1005", e.getCode());
    }

    @Test
    void 事实类型不符_抛RUL1005() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.execute(new RuleEngineDtos.ExecuteRequest(
                        facts(Map.of("age", "abc", "monthlyIncome", 10000, "loanAmount", 100000,
                                "term", 12, "creditScore", 700)),
                        "ADMISSION_PERSONAL_LOAN", null, true), "rul-e5"));
        assertEquals("RUL_1005", e.getCode());
    }

    @Test
    void 同requestId_返回首次结果() {
        String rid = "rul-idem";
        RuleEngineDtos.RuleResult a = service.execute(new RuleEngineDtos.ExecuteRequest(
                facts(Map.of("age", 35, "monthlyIncome", 20000, "loanAmount", 500000,
                        "term", 36, "creditScore", 700)),
                "ADMISSION_PERSONAL_LOAN", null, true), rid);
        RuleEngineDtos.RuleResult b = service.execute(new RuleEngineDtos.ExecuteRequest(
                facts(Map.of("age", 35, "monthlyIncome", 20000, "loanAmount", 500000,
                        "term", 36, "creditScore", 700)),
                "ADMISSION_PERSONAL_LOAN", null, true), rid);
        assertEquals(a.execId(), b.execId());
        assertEquals(a.conclusion().code(), b.conclusion().code());
    }

    @Test
    void 仿真test_通过判定() {
        RuleEngineDtos.TestResult r = service.test(new RuleEngineDtos.TestRequest(
                facts(Map.of("age", 35, "monthlyIncome", 20000, "loanAmount", 500000,
                        "term", 36, "creditScore", 700)),
                "ADMISSION_PERSONAL_LOAN", null), "rul-t1");
        assertTrue(r.pass());
        assertEquals("APPROVE", r.conclusion().decision());
    }

    @Test
    void 规则集列表_内置两套ACTIVE() {
        RuleEngineDtos.RulesetsResult r = service.rulesets();
        assertEquals("2026.09.01", r.version());
        assertEquals(2, r.rulesets().size());
        assertTrue(r.rulesets().stream().allMatch(s -> "ACTIVE".equals(s.status())));
    }
}
