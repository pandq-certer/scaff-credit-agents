package com.bai.metric;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MetricCalcService 单元测试：指标计算（确定性仿真）、参数校验、幂等、批量。
 */
class MetricCalcServiceTest {

    private final MetricCalcService service = new MetricCalcService();

    private MetricCalcDtos.CalcRequest req(String metricId, Map<String, Object> params) {
        return new MetricCalcDtos.CalcRequest(metricId, null, params, null);
    }

    @Test
    void 已知指标_返回确定性数值() {
        MetricCalcDtos.CalcResult r = service.calc(req("BAD_LOAN_RATE", Map.of("orgId", "1001", "date", "2026-10-01")), "m1");
        assertNotNull(r.calcId());
        assertEquals("BAD_LOAN_RATE", r.metricId());
        assertNotNull(r.metricName());
        // 数值在基线浮动区间内（base 2.35 ± span 1.20）
        double v = ((Number) r.value()).doubleValue();
        assertTrue(v >= 2.35 && v <= 3.55, "BAD_LOAN_RATE 应在 [2.35, 3.55] 内，实际 " + v);
    }

    @Test
    void 同入参两次调用_结果一致() {
        MetricCalcDtos.CalcRequest r1 = req("NIM", Map.of("orgId", "1002", "date", "2026-10-01"));
        MetricCalcDtos.CalcResult a = service.calc(r1, "det-a");
        MetricCalcDtos.CalcResult b = service.calc(r1, "det-b");
        assertEquals(a.value(), b.value());
        assertEquals(a.metricName(), b.metricName());
    }

    @Test
    void 未知指标_抛MTC1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.calc(req("UNKNOWN_METRIC", Map.of("orgId", "1")), "m-x"));
        assertEquals("MTC_1004", e.getCode());
    }

    @Test
    void 缺指标ID_抛MTC1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.calc(new MetricCalcDtos.CalcRequest(null, null, Map.of(), null), "m-y"));
        assertEquals("MTC_1001", e.getCode());
    }

    @Test
    void 同requestId同参数_返回首次结果() {
        String rid = "idem-1";
        MetricCalcDtos.CalcRequest rq = req("BAD_LOAN_RATE", Map.of("orgId", "1", "date", "2026-10-01"));
        MetricCalcDtos.CalcResult first = service.calc(rq, rid);
        MetricCalcDtos.CalcResult second = service.calc(rq, rid);
        assertEquals(first.value(), second.value());
    }

    @Test
    void 批量计算_逐条返回() {
        MetricCalcDtos.BatchCalcRequest batch = new MetricCalcDtos.BatchCalcRequest(
                java.util.List.of(
                        req("BAD_LOAN_RATE", Map.of("orgId", "1", "date", "2026-10-01")),
                        req("NIM", Map.of("orgId", "2", "date", "2026-10-01"))), null);
        MetricCalcDtos.BatchCalcResult r = service.batch(batch, "bt-1");
        assertEquals(2, r.results().size());
        assertNotNull(r.taskId());
    }

    @Test
    void 批量空列表_抛MTC1001() {
        MetricCalcDtos.BatchCalcRequest empty = new MetricCalcDtos.BatchCalcRequest(java.util.List.of(), null);
        assertThrows(BaiException.class, () -> service.batch(empty, "bt-x"));
    }

    @Test
    void 指标字典_非空且含最新版本() {
        MetricCalcDtos.IndicatorsResult ind = service.indicators();
        assertFalse(ind.indicators().isEmpty());
        assertEquals("2026-10-01", ind.version());
    }
}
