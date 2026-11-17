package com.bai.risksignal;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RiskSignalService 单元测试：事件关键词规则分级、五源加权聚合、预警判定、参数校验、幂等、查询。
 * <p>确定性仿真：聚合分=Σ(权重/Σ权重)×信号分，同入参必得同结果。
 */
class RiskSignalServiceTest {

    private final RiskSignalService service = new RiskSignalService();

    private Map<String, Object> body(List<String> sources, List<Map<String, String>> events) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("entity", Map.of("entityType", "PERSON", "entityId", "P-001"));
        m.put("sources", sources);
        m.put("events", events);
        return m;
    }

    @Test
    void 司法被执行_CRITICAL预警且聚合分92() {
        RiskSignalDtos.DetectResult r = service.detect(body(List.of("JUDICIAL"),
                List.of(Map.of("description", "2026-11-01 被执行人 王某，执行标的 500 万元"))), "r1");
        assertEquals(1, r.signals().size());
        RiskSignalDtos.Signal s = r.signals().get(0);
        assertEquals("JUDICIAL", s.signalType());
        assertEquals("CRITICAL", s.level());
        assertEquals(0.92, s.strength());
        assertEquals("CRITICAL", r.aggregated().aggLevel());
        assertEquals(92.0, r.aggregated().aggScore());
        assertTrue(r.alertTriggered());
    }

    @Test
    void 多源加权聚合_司法加流水87分HIGH() {
        RiskSignalDtos.DetectResult r = service.detect(body(List.of("JUDICIAL", "FLOW"),
                List.of(Map.of("description", "被执行人 李某，执行标的 100 万元"),
                        Map.of("description", "账户资金大幅流出"))), "r2");
        // 0.35×92 + 0.25×80 = 52.2，权重和 0.60 → 87.0
        assertEquals(2, r.signals().size());
        assertEquals(87.0, r.aggregated().aggScore());
        assertEquals("HIGH", r.aggregated().aggLevel());
        assertTrue(r.alertTriggered());
        assertEquals(2, r.aggregated().components().size());
    }

    @Test
    void 无信号命中_聚合分0LOW且不预警() {
        RiskSignalDtos.DetectResult r = service.detect(body(List.of("PUBLIC"),
                List.of(Map.of("description", "正常经营，无异常"))), "r3");
        assertEquals(0, r.signals().size());
        assertEquals(0.0, r.aggregated().aggScore());
        assertEquals("LOW", r.aggregated().aggLevel());
        assertFalse(r.alertTriggered());
    }

    @Test
    void entity缺失_抛RSG_1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.detect(Map.of("sources", List.of("JUDICIAL"),
                        "events", List.of(Map.of("description", "被执行"))), "e1"));
        assertEquals("RSG_1001", e.getCode());
    }

    @Test
    void 信号源非法_抛RSG_1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.detect(body(List.of("XXX"),
                        List.of(Map.of("description", "被执行"))), "e2"));
        assertEquals("RSG_1007", e.getCode());
    }

    @Test
    void 事件缺失_抛RSG_1001() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("entity", Map.of("entityType", "PERSON", "entityId", "P-001"));
        m.put("sources", List.of("JUDICIAL"));
        BaiException e = assertThrows(BaiException.class, () -> service.detect(m, "e3"));
        assertEquals("RSG_1001", e.getCode());
    }

    @Test
    void 事件超100条_抛RSG_1006() {
        List<Map<String, String>> events = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            events.add(Map.of("description", "被执行" + i));
        }
        BaiException e = assertThrows(BaiException.class,
                () -> service.detect(body(List.of("JUDICIAL"), events), "e4"));
        assertEquals("RSG_1006", e.getCode());
    }

    @Test
    void 同requestId_检测幂等复用首次结论() {
        String rid = "idem-1";
        Map<String, Object> b = body(List.of("JUDICIAL"),
                List.of(Map.of("description", "被执行人 张某")));
        RiskSignalDtos.DetectResult a = service.detect(b, rid);
        RiskSignalDtos.DetectResult c = service.detect(b, rid);
        assertEquals(a.signalId(), c.signalId());
        assertEquals(a.aggregated().aggScore(), c.aggregated().aggScore());
    }

    @Test
    void 查询不存在_抛RSG_1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.query("NO-SIG"));
        assertEquals("RSG_1004", e.getCode());
    }
}
