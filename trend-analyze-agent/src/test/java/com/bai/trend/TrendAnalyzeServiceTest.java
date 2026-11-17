package com.bai.trend;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TrendAnalyzeService 单元测试：趋势/环比/同比/分解/异常检测为真实计算（确定性可精确断言），
 * forecast 为确定性仿真；注册序列 S_LOAN_BAL 由固定种子生成。
 */
class TrendAnalyzeServiceTest {

    private final TrendAnalyzeService service = new TrendAnalyzeService();

    private List<TrendAnalyzeDtos.Point> points(Double... values) {
        String[] dates = {"2026-01-01", "2026-02-01", "2026-03-01", "2026-04-01",
                "2026-05-01", "2026-06-01", "2026-07-01", "2026-08-01",
                "2026-09-01", "2026-10-01", "2026-11-01", "2026-12-01",
                "2027-01-01", "2027-02-01", "2027-03-01", "2027-04-01"};
        java.util.ArrayList<TrendAnalyzeDtos.Point> pts = new java.util.ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            pts.add(new TrendAnalyzeDtos.Point(dates[i], values[i]));
        }
        return pts;
    }

    // ------------------------------------------------------------ 分析

    @Test
    void 注册序列分析_24点且确定性一致() {
        TrendAnalyzeDtos.AnalyzeResult a = service.analyze(
                new TrendAnalyzeDtos.AnalyzeRequest("S_LOAN_BAL", null, null, null), "tan-1");
        assertEquals(24, a.pointCount());
        assertFalse(a.imputed());
        assertTrue(a.analyzeId().startsWith("TAN"));
        assertNotNull(a.trend());
        assertNotNull(a.mom());
        assertNotNull(a.yoy());
        // 确定性：不同 requestId 同入参结果完全一致
        TrendAnalyzeDtos.AnalyzeResult b = service.analyze(
                new TrendAnalyzeDtos.AnalyzeRequest("S_LOAN_BAL", null, null, null), "tan-1b");
        assertEquals(a.trend().direction(), b.trend().direction());
        assertEquals(a.trend().slope(), b.trend().slope(), 0.0);
        assertEquals(a.mom().rate(), b.mom().rate());
    }

    @Test
    void 自定义序列_趋势方向斜率环比同比精确断言() {
        // 4 点 [100,110,100,110]：S=2（含连结修正 varS=6.667，z=0.387）、cv=0.048<0.10 → FLAT/WEAK
        TrendAnalyzeDtos.AnalyzeResult r = service.analyze(new TrendAnalyzeDtos.AnalyzeRequest(null,
                points(100.0, 110.0, 100.0, 110.0), "M12",
                List.of("trend", "mom", "yoy", "decompose")), "tan-2");
        assertEquals("FLAT", r.trend().direction());
        assertEquals("WEAK", r.trend().strength());
        assertEquals(2.0, r.trend().slope(), 1e-9);          // OLS 斜率
        assertEquals(0.30, r.trend().confidence(), 0.01);    // 2*normCdf(0.387)-1≈0.30
        assertEquals(0.1, r.mom().rate(), 1e-9);             // (110-100)/100
        assertEquals("UP", r.mom().direction());
        assertNull(r.yoy().rate());                          // 点数不足 13 → null
        assertNotNull(r.decompose());
        assertEquals(4, r.decompose().trend().size());
        assertEquals(4, r.decompose().residual().size());
    }

    @Test
    void 序列未注册_抛TDA1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.analyze(new TrendAnalyzeDtos.AnalyzeRequest("S_NO_EXIST", null, null, null), "tan-3"));
        assertEquals("TDA_1004", e.getCode());
    }

    @Test
    void seriesId与points均缺_抛TDA1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.analyze(new TrendAnalyzeDtos.AnalyzeRequest(null, null, null, null), "tan-4"));
        assertEquals("TDA_1001", e.getCode());
    }

    @Test
    void window与methods非法_抛TDA1001() {
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.analyze(new TrendAnalyzeDtos.AnalyzeRequest(null, points(1.0, 2.0, 3.0), "X12", null), "tan-5"));
        assertEquals("TDA_1001", e1.getCode());
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.analyze(new TrendAnalyzeDtos.AnalyzeRequest(null, points(1.0, 2.0, 3.0), null,
                        List.of("bad-method")), "tan-6"));
        assertEquals("TDA_1001", e2.getCode());
    }

    @Test
    void 全部值缺失_抛TDA1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.analyze(new TrendAnalyzeDtos.AnalyzeRequest(null, points(null, null, null), null, null), "tan-7"));
        assertEquals("TDA_1006", e.getCode());
    }

    @Test
    void 缺失超两成_标记imputed() {
        // 6 点中 2 点缺失（33%>20%）：线性插补后仍可分析
        TrendAnalyzeDtos.AnalyzeResult r = service.analyze(new TrendAnalyzeDtos.AnalyzeRequest(null,
                List.of(new TrendAnalyzeDtos.Point("2026-01-01", 10.0),
                        new TrendAnalyzeDtos.Point("2026-02-01", null),
                        new TrendAnalyzeDtos.Point("2026-03-01", 20.0),
                        new TrendAnalyzeDtos.Point("2026-04-01", null),
                        new TrendAnalyzeDtos.Point("2026-05-01", 30.0),
                        new TrendAnalyzeDtos.Point("2026-06-01", 40.0)), null, null), "tan-8");
        assertTrue(r.imputed());
        assertEquals(6, r.pointCount());
        assertNotNull(r.trend());
    }

    // ------------------------------------------------------------ 预测

    @Test
    void forecast_horizon校验() {
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.forecast(new TrendAnalyzeDtos.ForecastRequest("S_LOAN_BAL", null, 13, null), "tfc-1"));
        assertEquals("TDA_1006", e1.getCode());   // horizon 超 12
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.forecast(new TrendAnalyzeDtos.ForecastRequest("S_LOAN_BAL", null, 0, null), "tfc-2"));
        assertEquals("TDA_1001", e2.getCode());   // horizon<1
        BaiException e3 = assertThrows(BaiException.class,
                () -> service.forecast(new TrendAnalyzeDtos.ForecastRequest("S_LOAN_BAL", null, null, null), "tfc-3"));
        assertEquals("TDA_1001", e3.getCode());
    }

    @Test
    void forecast_确定性仿真结果() {
        TrendAnalyzeDtos.ForecastResult a = service.forecast(
                new TrendAnalyzeDtos.ForecastRequest("S_LOAN_BAL", null, 3, "LINEAR_EXTRAP"), "tfc-4");
        assertEquals("LINEAR_EXTRAP", a.model());
        assertEquals(3, a.horizon());
        assertEquals(3, a.values().size());
        assertEquals(3, a.lower().size());
        assertEquals(3, a.upper().size());
        assertTrue(a.mape() >= 2.0 && a.mape() <= 4.9);   // 仿真 MAPE 2.0%~4.9%
        // LINEAR_EXTRAP 无噪声：同入参两次调用值一致
        TrendAnalyzeDtos.ForecastResult b = service.forecast(
                new TrendAnalyzeDtos.ForecastRequest("S_LOAN_BAL", null, 3, "LINEAR_EXTRAP"), "tfc-5");
        assertEquals(a.values(), b.values());
        // 置信区间 = value ± 1.96σ（σ>0）
        assertTrue(a.upper().get(0) > a.values().get(0));
        assertTrue(a.lower().get(0) < a.values().get(0));
    }

    // ------------------------------------------------------------ 异常检测

    @Test
    void anomaly_3σ双通道命中ERROR且确定性() {
        // 16 点中 100 为离群值：3σ 与 IQR 双通道命中 → ERROR
        TrendAnalyzeDtos.AnomalyResult a = service.anomaly(new TrendAnalyzeDtos.AnomalyRequest(null,
                points(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 100.0)), "tam-1");
        assertEquals(16, a.pointCount());
        assertEquals(1, a.count());
        TrendAnalyzeDtos.AnomalyPoint p = a.anomalies().get(0);
        assertEquals(15, p.index());
        assertEquals("ERROR", p.level());
        assertTrue(p.channels().contains("THREE_SIGMA"));
        assertTrue(p.channels().contains("IQR"));
        // 确定性
        TrendAnalyzeDtos.AnomalyResult b = service.anomaly(new TrendAnalyzeDtos.AnomalyRequest(null,
                points(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 100.0)), "tam-2");
        assertEquals(a.count(), b.count());
        assertEquals(a.anomalies(), b.anomalies());
    }

    @Test
    void series_返回5条注册序列() {
        TrendAnalyzeDtos.SeriesResult r = service.series();
        assertEquals("2026.09.01", r.version());
        assertEquals(5, r.series().size());
    }
}
