package com.bai.compliance;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ComplianceService 单元测试：红线/必办/提示三级规则命中、结论判定、参数校验、幂等、规则注册冲突。
 * <p>判定口径：任一 RED_LINE 命中→NON_COMPLIANT；REQUIRED 未满足→MANUAL；SUGGEST 未满足→COMPLIANT 提示。
 */
class ComplianceServiceTest {

    private final ComplianceService service = new ComplianceService();

    private Map<String, Object> business(Object annualRate, String usage, Object abnormal,
                                         List<String> materials, String guarantee, int valuationAge) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("annualRate", annualRate);
        b.put("loanUsage", usage);
        b.put("abnormal", abnormal);
        b.put("materials", materials);
        b.put("guarantee", guarantee);
        b.put("valuationAgeMonths", valuationAge);
        return b;
    }

    @Test
    void 年化利率超监管上限_命中红线NON_COMPLIANT() {
        ComplianceDtos.CheckResult r = service.check(Map.of(
                "scene", "ADMISSION",
                "business", business(25.0, "经营周转", "否",
                        List.of("身份证明", "征信授权书", "财务报表"), "抵押", 3)), "c1");
        assertEquals("NON_COMPLIANT", r.conclusion());
        assertEquals("HIGH", r.riskLevel());
        assertTrue(r.escalated());
        assertTrue(r.hits().stream().anyMatch(h -> "RL_RED_014".equals(h.ruleId())
                && "RED_LINE".equals(h.level())));
    }

    @Test
    void 贷款用途涉股本投资_命中红线NON_COMPLIANT() {
        ComplianceDtos.CheckResult r = service.check(Map.of(
                "scene", "ADMISSION",
                "business", business(10.0, "股权投资", "否",
                        List.of("身份证明", "征信授权书", "财务报表"), "抵押", 3)), "c2");
        assertEquals("NON_COMPLIANT", r.conclusion());
        assertTrue(r.hits().stream().anyMatch(h -> "RL_RED_015".equals(h.ruleId())));
    }

    @Test
    void 准入材料缺失_必办未满足转MANUAL() {
        ComplianceDtos.CheckResult r = service.check(Map.of(
                "scene", "ADMISSION",
                "business", business(10.0, "经营周转", "否",
                        List.of("身份证明"), "抵押", 3)), "c3");
        assertEquals("MANUAL", r.conclusion());
        assertEquals("MEDIUM", r.riskLevel());
        assertTrue(r.escalated());
        assertTrue(r.hits().stream().anyMatch(h -> "RL_REQ_021".equals(h.ruleId())
                && "REQUIRED".equals(h.level())));
    }

    @Test
    void 全部合规_COMPLIANT() {
        ComplianceDtos.CheckResult r = service.check(Map.of(
                "scene", "ADMISSION",
                "business", business(10.0, "经营周转", "否",
                        List.of("身份证明", "征信授权书", "财务报表"), "抵押", 3)), "c4");
        assertEquals("COMPLIANT", r.conclusion());
        assertEquals("LOW", r.riskLevel());
        assertFalse(r.escalated());
        assertEquals("审查全部通过", r.reason());
    }

    @Test
    void 评估价超6个月_含SUGGEST提示仍COMPLIANT() {
        ComplianceDtos.CheckResult r = service.check(Map.of(
                "scene", "CONTRACT",
                "business", business(10.0, "经营周转", "否",
                        List.of("身份证明", "征信授权书", "财务报表"), "抵押", 10)), "c5");
        assertEquals("COMPLIANT", r.conclusion());
        assertFalse(r.escalated());
        assertTrue(r.hits().stream().anyMatch(h -> "RL_SUG_031".equals(h.ruleId())
                && "SUGGEST".equals(h.level())));
        assertTrue(r.reason().contains("RL_SUG_031"));
    }

    @Test
    void 场景缺失或非法或版本非法_错误码正确() {
        // scene 缺失 → CMP_1001
        BaiException missing = assertThrows(BaiException.class,
                () -> service.check(Map.of("business", business(10.0, "经营周转", "否",
                        List.of("身份证明", "征信授权书", "财务报表"), "抵押", 3)), "e1"));
        assertEquals("CMP_1001", missing.getCode());
        // scene 非法 → CMP_1007
        BaiException scene = assertThrows(BaiException.class,
                () -> service.check(Map.of("scene", "XXX",
                        "business", business(10.0, "经营周转", "否",
                                List.of("身份证明", "征信授权书", "财务报表"), "抵押", 3)), "e2"));
        assertEquals("CMP_1007", scene.getCode());
        // 规则版本非法 → CMP_1007
        Map<String, Object> badVersion = new LinkedHashMap<>();
        badVersion.put("scene", "ADMISSION");
        badVersion.put("business", business(10.0, "经营周转", "否",
                List.of("身份证明", "征信授权书", "财务报表"), "抵押", 3));
        badVersion.put("ruleVersion", "V2025.01");
        BaiException version = assertThrows(BaiException.class,
                () -> service.check(badVersion, "e3"));
        assertEquals("CMP_1007", version.getCode());
    }

    @Test
    void 业务要素超长_抛CMP_1006() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20100; i++) {
            sb.append('a');
        }
        Map<String, Object> big = new LinkedHashMap<>();
        big.put("content", sb.toString());
        BaiException e = assertThrows(BaiException.class,
                () -> service.check(Map.of("scene", "ADMISSION", "business", big), "e4"));
        assertEquals("CMP_1006", e.getCode());
    }

    @Test
    void 同requestId_审查幂等复用首次结论() {
        String rid = "idem-1";
        Map<String, Object> body = Map.of("scene", "ADMISSION",
                "business", business(10.0, "经营周转", "否",
                        List.of("身份证明", "征信授权书", "财务报表"), "抵押", 3));
        ComplianceDtos.CheckResult a = service.check(body, rid);
        ComplianceDtos.CheckResult b = service.check(body, rid);
        assertEquals(a.checkId(), b.checkId());
        assertEquals(a.conclusion(), b.conclusion());
    }

    @Test
    void 重复注册规则_抛CMP_1005() {
        Map<String, Object> rule = Map.of("ruleId", "RL_TEST_001", "scene", "ADMISSION",
                "level", "REQUIRED", "clause", "测试规则");
        ComplianceDtos.RuleInfo info = service.registerRule(rule, "r1");
        assertEquals("ACTIVE", info.status());
        BaiException e = assertThrows(BaiException.class, () -> service.registerRule(rule, "r2"));
        assertEquals("CMP_1005", e.getCode());
    }
}
