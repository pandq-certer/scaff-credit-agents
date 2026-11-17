package com.bai.eventsubscribe;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EventSubscribeService 单元测试：事件发布（schema/授权/订阅匹配/条件过滤）、订阅管理、类型注册、幂等。
 * <p>确定性仿真：哈希驱动端点投递、指纹去重，可直接断言受理状态与匹配计数。
 */
class EventSubscribeServiceTest {

    private final EventSubscribeService service = new EventSubscribeService();

    private Map<String, Object> publishBody(String eventType, String bizKey, Map<String, Object> payload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", eventType);
        body.put("bizKey", bizKey);
        body.put("payload", payload);
        return body;
    }

    private Map<String, Object> subscribeBody(String eventType, String subscriberId, String url,
                                              Map<String, Object> condition) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "CREATE");
        body.put("eventType", eventType);
        body.put("subscriberId", subscriberId);
        body.put("endpoint", Map.of("url", url));
        if (condition != null) {
            body.put("condition", condition);
        }
        return body;
    }

    // ------------------------------------------------------------ 主能力

    @Test
    void 发布_预置类型受理ACCEPTED() {
        EventSubscribeDtos.PublishResult r = service.publish(
                publishBody("loan.approved", "BK-001", Map.of("loanNo", "LN001")), "req-p1", "sys");
        assertEquals("ACCEPTED", r.status());
        assertNotNull(r.eventId());
        assertTrue(r.eventId().startsWith("EVT"));
        // 无订阅时 matched=0
        assertEquals(0, r.matchedSubscriptions());
    }

    @Test
    void 订阅匹配_条件命中则分发() {
        service.subscribe(subscribeBody("loan.approved", "sub-1", "https://cb.bai.internal/hook",
                Map.of("field", "loanNo", "op", "EQ", "value", "LN001")), "req-s1", "op-1");
        EventSubscribeDtos.PublishResult r = service.publish(
                publishBody("loan.approved", "BK-002", Map.of("loanNo", "LN001")), "req-p2", "sys");
        assertEquals(1, r.matchedSubscriptions());
        assertEquals(1, r.dispatched());
        assertEquals("ACCEPTED", r.status());
    }

    @Test
    void 条件不命中_状态FILTERED_OUT() {
        service.subscribe(subscribeBody("loan.approved", "sub-2", "https://cb.bai.internal/hook",
                Map.of("field", "loanNo", "op", "EQ", "value", "LN001")), "req-s2", "op-1");
        EventSubscribeDtos.PublishResult r = service.publish(
                publishBody("loan.approved", "BK-003", Map.of("loanNo", "LN999")), "req-p3", "sys");
        assertEquals(1, r.matchedSubscriptions());
        assertEquals(0, r.dispatched());
        assertEquals("FILTERED_OUT", r.status());
    }

    @Test
    void 订阅创建_活跃订阅可查询() {
        EventSubscribeDtos.SubscribeResult r = service.subscribe(
                subscribeBody("loan.approved", "sub-3", "https://cb.bai.internal/hook", null), "req-s3", "op-1");
        assertEquals("ACTIVE", r.status());
        assertEquals("CREATE", r.action());
        EventSubscribeDtos.SubscriptionQuery q = service.querySubscriptions(Map.of("eventType", "loan.approved"));
        assertEquals(1, q.total());
        assertEquals("ACTIVE", q.subscriptions().get(0).status());
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    void 发布_缺payload抛EVS1001() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", "loan.approved");
        body.put("bizKey", "BK-1");
        BaiException e = assertThrows(BaiException.class,
                () -> service.publish(body, "req-b1", "sys"));
        assertEquals("EVS_1001", e.getCode());
    }

    @Test
    void 发布_无caller抛EVS1003() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.publish(publishBody("loan.approved", "BK-1", Map.of("loanNo", "LN1")), "req-b2", null));
        assertEquals("EVS_1003", e.getCode());
    }

    @Test
    void 发布_未注册类型抛EVS1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.publish(publishBody("loan.foo", "BK-1", Map.of("a", 1)), "req-b3", "sys"));
        assertEquals("EVS_1004", e.getCode());
    }

    @Test
    void 发布_schema缺必填字段抛EVS1006() {
        // loan.disbursed 必填 loanNo+amount，缺 amount
        BaiException e = assertThrows(BaiException.class,
                () -> service.publish(publishBody("loan.disbursed", "BK-1", Map.of("loanNo", "LN1")), "req-b4", "sys"));
        assertEquals("EVS_1006", e.getCode());
    }

    @Test
    void 订阅_端点白名单外抛EVS1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.subscribe(subscribeBody("loan.approved", "sub-x", "https://evil.com/cb", null),
                        "req-b5", "op-1"));
        assertEquals("EVS_1006", e.getCode());
    }

    @Test
    void 订阅_条件运算符非白名单抛EVS1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.subscribe(subscribeBody("loan.approved", "sub-y", "https://cb.bai.internal/hook",
                        Map.of("field", "loanNo", "op", "EXEC", "value", "1")), "req-b6", "op-1"));
        assertEquals("EVS_1006", e.getCode());
    }

    // ------------------------------------------------------------ 冲突/去重

    @Test
    void 重复订阅_抛EVS1005() {
        Map<String, Object> body = subscribeBody("loan.approved", "sub-d", "https://cb.bai.internal/hook", null);
        service.subscribe(body, "req-c1", "op-1");
        BaiException e = assertThrows(BaiException.class,
                () -> service.subscribe(body, "req-c2", "op-1"));
        assertEquals("EVS_1005", e.getCode());
    }

    @Test
    void 取消不存在的订阅_抛EVS1004() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", "CANCEL");
        body.put("eventType", "loan.approved");
        body.put("subscriberId", "ghost");
        BaiException e = assertThrows(BaiException.class,
                () -> service.subscribe(body, "req-c3", "op-1"));
        assertEquals("EVS_1004", e.getCode());
    }

    @Test
    void 类型重复注册_抛EVS1005() {
        service.registerType(Map.of("name", "custom.event", "schema", Map.of("required", List.of("a"))));
        BaiException e = assertThrows(BaiException.class,
                () -> service.registerType(Map.of("name", "custom.event")));
        assertEquals("EVS_1005", e.getCode());
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void 同requestId同bizKey同类型_返回首次eventId() {
        String rid = "idem-p1";
        EventSubscribeDtos.PublishResult a = service.publish(
                publishBody("loan.approved", "BK-IDEM", Map.of("loanNo", "LN001")), rid, "sys");
        EventSubscribeDtos.PublishResult b = service.publish(
                publishBody("loan.approved", "BK-IDEM", Map.of("loanNo", "LN001")), rid, "sys");
        assertEquals(a.eventId(), b.eventId());
    }

    @Test
    void 事件指纹相同_7天内不重复发布() {
        EventSubscribeDtos.PublishResult a = service.publish(
                publishBody("loan.approved", "BK-FP", Map.of("loanNo", "LN001")), "req-f1", "sys");
        EventSubscribeDtos.PublishResult b = service.publish(
                publishBody("loan.approved", "BK-FP", Map.of("loanNo", "LN001")), "req-f2", "sys");
        assertEquals(a.eventId(), b.eventId());
    }
}
