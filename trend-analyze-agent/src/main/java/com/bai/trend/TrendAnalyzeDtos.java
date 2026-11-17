package com.bai.trend;

import java.util.List;

/**
 * 趋势分析输出统一结构（BIA-DTA-024：AnalyzeResult）
 * <p>趋势为真实计算（Mann-Kendall 简化 S 统计 + OLS 斜率，非仿真，同输入复现一致）；
 * forecast 置信区间与 mape 为确定性仿真值（用于联调）；anomaly 为 3σ+IQR 双通道真实计算；
 * 缺失值比例&gt;20% 时线性插补并标记 imputed=true
 */
public final class TrendAnalyzeDtos {

    private TrendAnalyzeDtos() {}

    /** 数据点（value=null 视为缺失值，参与线性插补） */
    public record Point(String date, Double value) {}

    /** analyze 请求体（seriesId 与 points 二选一；window 缺省 M12；methods 缺省 [trend,mom,yoy]） */
    public record AnalyzeRequest(String seriesId, List<Point> points, String window, List<String> methods) {}

    /** 趋势（direction: UP/DOWN/FLAT(变异系数<0.10)/VOLATILE；strength: STRONG/MEDIUM/WEAK） */
    public record Trend(String direction, String strength, double slope, double confidence) {}

    /** 环比（点数不足时 rate 为 null） */
    public record Mom(Double rate, String direction, String period) {}

    /** 同比（点数不足 13 时 rate 为 null） */
    public record Yoy(Double rate, String direction, String period) {}

    /** 简化 STL 分解（11 点居中移动平均趋势 + 月度季节指数 + 残差） */
    public record Decompose(List<Double> trend, List<Double> seasonal, List<Double> residual) {}

    /** 分析结果 */
    public record AnalyzeResult(String analyzeId, String seriesId, int pointCount, boolean imputed, Trend trend,
                                Mom mom, Yoy yoy, Decompose decompose, String snapshotId, long elapsedMs) {}

    /** forecast 请求体（horizon≤12；model 缺省 AUTO） */
    public record ForecastRequest(String seriesId, List<Point> points, Integer horizon, String model) {}

    /** 预测结果（lower/upper 为 1.96σ 置信区间；mape 为确定性仿真值） */
    public record ForecastResult(String forecastId, String seriesId, String model, int horizon,
                                 List<Double> values, List<Double> lower, List<Double> upper, double mape, long elapsedMs) {}

    /** anomaly 请求体（seriesId 与 points 二选一） */
    public record AnomalyRequest(String seriesId, List<Point> points) {}

    /** 异常点（level: ERROR=3σ 命中/WARN=仅 IQR 命中；channels: THREE_SIGMA/IQR） */
    public record AnomalyPoint(int index, String date, Double value, String level, List<String> channels) {}

    /** 异常检测结果 */
    public record AnomalyResult(String anomalyId, int pointCount, int count, List<AnomalyPoint> anomalies, long elapsedMs) {}

    /** 序列元信息（GET /series，注册序列仿真） */
    public record SeriesInfo(String seriesId, String name, String unit, String granularity,
                             String frequency, String startPeriod, String endPeriod, int pointCount) {}

    /** 序列列表（GET /series） */
    public record SeriesResult(String version, List<SeriesInfo> series) {}
}
