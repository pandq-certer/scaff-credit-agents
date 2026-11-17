package com.bai.recommend;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RecommendService 单元测试：推荐排序（RULE/CF/MAB/MODEL）、适当性过滤、模型降级、推荐执行、查询、反馈、策略管理、参数校验、幂等。
 */
class RecommendServiceTest {

    private final RecommendService service = new RecommendService();

    private RecommendDtos.RecommendRequest itemsReq(String customerId, String scene, String algo, Integer topN,
                                                    Map<String, Object> context) {
        return new RecommendDtos.RecommendRequest(customerId, scene, algo, topN, context, null);
    }

    // ------------------------------------------------------------ 推荐排序（items）

    @Test
    void 规则推荐_按topN截取且每项含理由() {
        RecommendDtos.RecommendResult r = service.items(itemsReq("C001", "PRODUCT", "RULE", 3,
                Map.of("riskPreference", "平衡", "activity90d", 0.8)), "r-1");
        assertEquals("SUCCESS", r.code());
        assertEquals("RULE", r.algoUsed());
        assertTrue(r.items().size() <= 3);
        for (RecommendDtos.RecommendItem item : r.items()) {
            assertNotNull(item.reason());
            assertFalse(item.reason().isBlank());
        }
    }

    @Test
    void 低风险偏好_不推荐高风险产品() {
        RecommendDtos.RecommendResult r = service.items(itemsReq("C002", "PRODUCT", "RULE", 10,
                Map.of("riskPreference", "稳健")), "r-2");
        // 稳健 → LOW 承受等级，PROD-EQUIP-LEASE（HIGH 风险）必须被过滤
        assertFalse(r.items().stream().anyMatch(i -> i.itemId().equals("PROD-EQUIP-LEASE")));
    }

    @Test
    void 模型降级_切换规则并标记() {
        RecommendDtos.RecommendResult r = service.items(itemsReq("C003", "PRODUCT", "MODEL", 5,
                Map.of("modelDown", true, "riskPreference", "平衡")), "r-3");
        assertEquals("RULE", r.algoUsed());
        assertTrue(r.strategyVersion().endsWith("-DEGRADED"));
    }

    @Test
    void 场景非法_抛RCM1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.items(
                itemsReq("C004", "XXX", "RULE", 3, Map.of()), "r-x"));
        assertEquals("RCM_1007", e.getCode());
    }

    @Test
    void topN超界_抛RCM1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.items(
                itemsReq("C005", "PRODUCT", "RULE", 51, Map.of("riskPreference", "平衡")), "r-y"));
        assertEquals("RCM_1001", e.getCode());
    }

    @Test
    void 客户缺失_抛RCM1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.items(
                itemsReq(null, "PRODUCT", "RULE", 3, Map.of("riskPreference", "平衡")), "r-z"));
        assertEquals("RCM_1004", e.getCode());
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void 同requestId同要素_返回首次结果() {
        String rid = "idem-1";
        RecommendDtos.RecommendRequest rq = itemsReq("C100", "SPEECH", "CF", 5,
                Map.of("riskPreference", "平衡"));
        RecommendDtos.RecommendResult first = service.items(rq, rid);
        RecommendDtos.RecommendResult second = service.items(rq, rid);
        assertEquals(first.items().size(), second.items().size());
        assertEquals(first.algoUsed(), second.algoUsed());
    }

    @Test
    void 同requestId不同要素_抛RCM1005() {
        String rid = "idem-2";
        service.items(itemsReq("C101", "SPEECH", "CF", 5, Map.of("riskPreference", "平衡")), rid);
        BaiException e = assertThrows(BaiException.class, () -> service.items(
                itemsReq("C102", "SPEECH", "CF", 5, Map.of("riskPreference", "平衡")), rid));
        assertEquals("RCM_1005", e.getCode());
    }

    // ------------------------------------------------------------ 推荐执行（4.2）

    @Test
    void 推荐执行_默认策略融合排序() {
        RecommendDtos.RecommendExecuteResult r = service.recommend(
                new RecommendDtos.RecommendExecuteRequest("PRODUCT", Map.of("customerId", "C200", "riskPreference", "平衡"),
                        5, null, null), "re-1");
        assertNotNull(r.recommendId());
        assertEquals("HYBRID", r.algoUsed());
        assertEquals(5, r.items().size());
        // rank 自 1 起递增
        for (int i = 0; i < r.items().size(); i++) {
            assertEquals(i + 1, r.items().get(i).rank());
        }
    }

    @Test
    void 推荐执行_指定不存在策略_抛RCM1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.recommend(
                new RecommendDtos.RecommendExecuteRequest("PRODUCT", Map.of("customerId", "C201"),
                        3, "STR-NONE", null), "re-x"));
        assertEquals("RCM_1004", e.getCode());
    }

    @Test
    void 推荐执行_上下文缺失_抛RCM1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.recommend(
                new RecommendDtos.RecommendExecuteRequest("PRODUCT", Map.of(), 3, null, null), "re-y"));
        assertEquals("RCM_1001", e.getCode());
    }

    // ------------------------------------------------------------ 查询 / 反馈

    @Test
    void 推荐查询_按recommendId命中且可反馈() {
        RecommendDtos.RecommendExecuteResult ex = service.recommend(
                new RecommendDtos.RecommendExecuteRequest("DISPOSAL", Map.of("customerId", "C300"),
                        3, null, null), "fq-0");
        RecommendDtos.RecommendQueryResult q = service.query(
                new RecommendDtos.RecommendQueryRequest(ex.recommendId(), null, null, null, null, null), "fq-1");
        assertEquals(1, q.total());
        // 反馈受理
        String itemId = ex.items().get(0).itemId();
        RecommendDtos.FeedbackResult fb = service.feedback(
                new RecommendDtos.FeedbackRequest(ex.recommendId(), itemId, "CLICK", Map.of("pos", 1), null), "fq-2");
        assertEquals("RECEIVED", fb.status());
        assertNotNull(fb.feedbackId());
    }

    @Test
    void 反馈动作非法_抛RCM1007() {
        RecommendDtos.RecommendExecuteResult ex = service.recommend(
                new RecommendDtos.RecommendExecuteRequest("DISPOSAL", Map.of("customerId", "C301"),
                        3, null, null), "fb-0");
        BaiException e = assertThrows(BaiException.class, () -> service.feedback(
                new RecommendDtos.FeedbackRequest(ex.recommendId(), ex.items().get(0).itemId(), "LIKE", null, null), "fb-x"));
        assertEquals("RCM_1007", e.getCode());
    }

    @Test
    void 查询无记录_抛RCM1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.query(
                new RecommendDtos.RecommendQueryRequest("RCM-NONE", null, null, null, null, null), "q-x"));
        assertEquals("RCM_1004", e.getCode());
    }

    // ------------------------------------------------------------ 策略管理（4.5）

    @Test
    void 策略创建更新生效回滚_状态流转() {
        RecommendDtos.StrategyConfig cfg = new RecommendDtos.StrategyConfig(null, "PRODUCT",
                List.of("CF", "RULE"), Map.of("CF", 0.6, "RULE", 0.4), Map.of(), 10);
        RecommendDtos.StrategyResult created = service.strategy(
                new RecommendDtos.StrategyRequest("CREATE", cfg, null), "st-1");
        assertEquals("DRAFT", created.status());
        assertEquals(1, created.version());
        String strategyId = created.strategyId();
        RecommendDtos.StrategyConfig withId = new RecommendDtos.StrategyConfig(strategyId, "PRODUCT",
                List.of("CF", "RULE"), Map.of("CF", 0.6, "RULE", 0.4), Map.of(), 10);
        RecommendDtos.StrategyResult updated = service.strategy(
                new RecommendDtos.StrategyRequest("UPDATE", withId, null), "st-2");
        assertEquals(2, updated.version());
        RecommendDtos.StrategyResult activated = service.strategy(
                new RecommendDtos.StrategyRequest("ACTIVATE", withId, null), "st-3");
        assertEquals("ACTIVE", activated.status());
        RecommendDtos.StrategyResult rolled = service.strategy(
                new RecommendDtos.StrategyRequest("ROLLBACK", withId, null), "st-4");
        assertEquals("DISABLED", rolled.status());
    }

    @Test
    void 策略动作非法_抛RCM1007() {
        RecommendDtos.StrategyConfig cfg = new RecommendDtos.StrategyConfig(null, "PRODUCT",
                List.of("CF"), Map.of("CF", 1.0), Map.of(), 5);
        BaiException e = assertThrows(BaiException.class, () -> service.strategy(
                new RecommendDtos.StrategyRequest("DELETE", cfg, null), "st-x"));
        assertEquals("RCM_1007", e.getCode());
    }

    @Test
    void 更新不存在策略_抛RCM1004() {
        RecommendDtos.StrategyConfig cfg = new RecommendDtos.StrategyConfig("STR-NONE", "PRODUCT",
                List.of("CF"), Map.of("CF", 1.0), Map.of(), 5);
        BaiException e = assertThrows(BaiException.class, () -> service.strategy(
                new RecommendDtos.StrategyRequest("UPDATE", cfg, null), "st-y"));
        assertEquals("RCM_1004", e.getCode());
    }
}
