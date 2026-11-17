package com.bai.fraud;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FraudService 单元测试：规则→图→模型级联融合决策、黑名单即时 REJECT、参数校验、幂等、确定性、批量、反馈冲突。
 * <p>确定性仿真：同入参必得同结果（实体指纹哈希驱动）。
 */
class FraudServiceTest {

    private final FraudService service = new FraudService();

    private Map<String, Object> entity(String entityType, String entityId) {
        return Map.of("entityType", entityType, "entityId", entityId);
    }

    @Test
    void 实时决策_三层融合输出与等级一致() {
        FraudDtos.CheckResult r = service.check(Map.of(
                "scene", "PRE_LOAN", "entity", entity("PERSON", "ENT-001")), "req-1");
        assertNotNull(r.caseId());
        assertEquals("PRE_LOAN", r.scene());
        assertEquals("ENT-001", r.entityId());
        assertTrue(r.fraudScore() >= 0 && r.fraudScore() <= 99);
        assertTrue(List.of("PASS", "ESCALATE", "MANUAL").contains(r.decision()));
        // 模型层命中恒存在
        assertTrue(r.hits().stream().anyMatch(h -> "MODEL".equals(h.source())));
        // 等级与评分口径一致
        String expectedLevel = r.fraudScore() >= 85 ? "CRITICAL" : r.fraudScore() >= 70 ? "HIGH"
                : r.fraudScore() >= 50 ? "MEDIUM" : "LOW";
        assertEquals(expectedLevel, r.riskLevel());
        assertFalse(r.reason().isBlank());
    }

    @Test
    void 黑名单命中_即时REJECT() {
        FraudDtos.CheckResult r = service.check(Map.of(
                "scene", "PRE_LOAN", "entity", entity("PERSON", "E-BLACK-0001")), "req-2");
        assertEquals("REJECT", r.decision());
        assertEquals(99, r.fraudScore());
        assertEquals("CRITICAL", r.riskLevel());
        assertEquals("RULE", r.hits().get(0).source());
        assertEquals("黑名单命中，即时拒绝", r.reason());
    }

    @Test
    void 场景缺失_抛FRD_1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.check(Map.of("entity", entity("PERSON", "E1")), "req-x"));
        assertEquals("FRD_1001", e.getCode());
    }

    @Test
    void 场景非法_抛FRD_1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.check(Map.of("scene", "XXX", "entity", entity("PERSON", "E1")), "req-y"));
        assertEquals("FRD_1007", e.getCode());
    }

    @Test
    void 交易监控缺txnRef_抛FRD_1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.check(Map.of("scene", "TRANS_MONITOR", "entity", entity("PERSON", "E1")), "req-z"));
        assertEquals("FRD_1001", e.getCode());
    }

    @Test
    void 同requestId_决策幂等复用首次结果() {
        String rid = "idem-1";
        Map<String, Object> body = Map.of("scene", "PRE_LOAN", "entity", entity("PERSON", "ENT-002"));
        FraudDtos.CheckResult a = service.check(body, rid);
        FraudDtos.CheckResult b = service.check(body, rid);
        assertEquals(a.caseId(), b.caseId());
        assertEquals(a.fraudScore(), b.fraudScore());
    }

    @Test
    void 同入参两次调用_决策完全一致() {
        FraudDtos.CheckResult a = service.check(Map.of(
                "scene", "PRE_LOAN", "entity", entity("PERSON", "ENT-003")), "det-a");
        FraudDtos.CheckResult b = service.check(Map.of(
                "scene", "PRE_LOAN", "entity", entity("PERSON", "ENT-003")), "det-b");
        assertEquals(a.decision(), b.decision());
        assertEquals(a.fraudScore(), b.fraudScore());
        assertEquals(a.hits().size(), b.hits().size());
    }

    @Test
    void 批量筛查_超100条抛FRD_1006() {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            items.add(Map.of("scene", "PRE_LOAN", "entity", entity("PERSON", "B" + i)));
        }
        BaiException e = assertThrows(BaiException.class,
                () -> service.batchCheck(Map.of("items", items), "batch-x"));
        assertEquals("FRD_1006", e.getCode());
    }

    @Test
    void 反馈重复标注_抛FRD_1005() {
        FraudDtos.CheckResult r = service.check(Map.of(
                "scene", "PRE_LOAN", "entity", entity("PERSON", "ENT-004")), "fb-0");
        FraudDtos.FeedbackResult f = service.feedback(Map.of("caseId", r.caseId(), "label", "FRAUD"));
        assertEquals("ACCEPTED", f.status());
        BaiException e = assertThrows(BaiException.class,
                () -> service.feedback(Map.of("caseId", r.caseId(), "label", "NORMAL")));
        assertEquals("FRD_1005", e.getCode());
    }
}
