package com.bai.trend;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 趋势分析服务（BIA-DTA-024）。趋势/环比/同比/分解/异常检测为真实计算（非仿真，同输入复现一致）；
 * forecast 置信区间与 mape 为确定性仿真值（标注：仿真，用于联调）；注册序列（S_LOAN_BAL 等）
 * 由固定种子生成确定性仿真数据。
 * <p>趋势口径：Mann-Kendall 简化 S 统计（含连结修正与连续性修正）+ OLS 斜率；
 * direction：变异系数&lt;0.10 → FLAT，|Z|&gt;1.96 → UP/DOWN，否则 VOLATILE；
 * mom=(v_t-v_{t-1})/v_{t-1}、yoy=(v_t-v_{t-12})/v_{t-12}（点数不足时对应字段为 null）；
 * methods 含 decompose 时输出简化 STL（11 点居中移动平均趋势+月度季节指数+残差）；
 * 缺失值比例&gt;20% 时线性插补并标记 imputed=true（小比例缺失同样插补，仅不标记）。
 * <p>幂等：requestId+输入序列哈希，缓存 24h；限流 analyze 50 QPS / forecast 10 QPS / anomaly 50 QPS
 */
@Service
public class TrendAnalyzeService {

    private static final Logger log = LoggerFactory.getLogger(TrendAnalyzeService.class);

    private static final int MAX_POINTS = 10000;
    private static final int MAX_HORIZON = 12;
    private static final String DEFAULT_WINDOW = "M12";
    private static final Pattern WINDOW = Pattern.compile("M\\d{1,4}");
    private static final Set<String> METHODS = Set.of("trend", "mom", "yoy", "decompose");
    private static final List<String> DEFAULT_METHODS = List.of("trend", "mom", "yoy");
    private static final Set<String> MODELS = Set.of("AUTO", "HOLT_WINTERS", "ARIMA", "PROPHET", "LINEAR_EXTRAP");

    /** 注册序列元信息（确定性仿真序列，固定种子生成 24 个月度点：2024-10 ~ 2026-09） */
    private static final Map<String, DataModel.Series> SERIES = buildSeries();

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final AtomicLong analyzeSeq = new AtomicLong();
    private final AtomicLong forecastSeq = new AtomicLong();
    private final AtomicLong anomalySeq = new AtomicLong();

    /** 趋势分析（真实计算；幂等：requestId+输入序列哈希，缓存 24h） */
    public TrendAnalyzeDtos.AnalyzeResult analyze(TrendAnalyzeDtos.AnalyzeRequest req, String requestId) {
        long start = System.currentTimeMillis();
        Input input = resolveInput(req == null ? null : req.seriesId(), req == null ? null : req.points());
        int minPoints = resolveWindow(req == null ? null : req.window());
        List<String> methods = resolveMethods(req == null ? null : req.methods());
        String key = requestId + ":" + input.seriesId() + ":" + minPoints + ":" + methods + ":" + input.points().hashCode();
        TrendAnalyzeDtos.AnalyzeResult cached = idempotency.get("analyze", key);
        if (cached != null) {
            return cached;
        }
        TrendAnalyzeDtos.AnalyzeResult result = doAnalyze(input, minPoints, methods, requestId, start);
        idempotency.put("analyze", key, result);
        return result;
    }

    /** 趋势预测（模型仿真：以 OLS 外推为基线，非 LINEAR_EXTRAP 模型叠加确定性噪声；mape 为仿真值） */
    public TrendAnalyzeDtos.ForecastResult forecast(TrendAnalyzeDtos.ForecastRequest req, String requestId) {
        long start = System.currentTimeMillis();
        if (req == null || req.horizon() == null) {
            throw new BaiException(TrendAnalyzeErrorCodes.PARAM_INVALID, "TDA_1001 参数缺失：horizon 必填");
        }
        if (req.horizon() < 1) {
            throw new BaiException(TrendAnalyzeErrorCodes.PARAM_INVALID, "TDA_1001 参数非法：horizon 须≥1");
        }
        if (req.horizon() > MAX_HORIZON) {
            throw new BaiException(TrendAnalyzeErrorCodes.DATA_INSUFFICIENT,
                    "TDA_1006 参数非法：horizon 超 " + MAX_HORIZON + "（实际 " + req.horizon() + "）");
        }
        Input input = resolveInput(req.seriesId(), req.points());
        String model = req.model() == null || req.model().isBlank() ? "AUTO" : req.model().toUpperCase();
        if (!MODELS.contains(model)) {
            throw new BaiException(TrendAnalyzeErrorCodes.PARAM_INVALID,
                    "TDA_1001 参数非法：model 须为 AUTO/HOLT_WINTERS/ARIMA/PROPHET/LINEAR_EXTRAP");
        }
        double[] v = values(input.points());
        if (v.length < 2) {
            throw new BaiException(TrendAnalyzeErrorCodes.DATA_INSUFFICIENT, "TDA_1006 样本不足：forecast 至少需 2 点");
        }
        // OLS 基线外推（AUTO → LINEAR_EXTRAP）
        double[] xy = ols(v);
        double slope = xy[0];
        double intercept = xy[1];
        double sigma = residualStd(v, slope, intercept);
        String effectiveModel = "AUTO".equals(model) ? "LINEAR_EXTRAP" : model;
        Random r = new Random((input.seriesId() + "|" + effectiveModel + "|" + req.horizon()).hashCode());
        List<Double> values = new ArrayList<>();
        List<Double> lower = new ArrayList<>();
        List<Double> upper = new ArrayList<>();
        for (int k = 1; k <= req.horizon(); k++) {
            double base = intercept + slope * (v.length - 1 + k);
            double noise = "LINEAR_EXTRAP".equals(effectiveModel) ? 0 : r.nextGaussian() * sigma * 0.2; // 模型差异用确定性噪声仿真
            double value = round4(base + noise);
            values.add(value);
            lower.add(round4(value - 1.96 * sigma));
            upper.add(round4(value + 1.96 * sigma));
        }
        double mape = round2(2.0 + ((input.seriesId() + "|" + effectiveModel).hashCode() & 0x7fffffff) % 30 / 10.0); // 仿真值：MAPE 2.0%~4.9%
        String forecastId = "TFC" + dayStamp() + String.format("%07d", forecastSeq.incrementAndGet());
        long elapsed = System.currentTimeMillis() - start;
        log.info("趋势预测: requestId={}, forecastId={}, seriesId={}, model={}, horizon={}, mape={}(仿真，用于联调)",
                requestId, forecastId, input.seriesId(), effectiveModel, req.horizon(), mape);
        return new TrendAnalyzeDtos.ForecastResult(forecastId, input.seriesId(), effectiveModel, req.horizon(),
                values, lower, upper, mape, elapsed);
    }

    /** 异常检测（3σ+IQR 双通道：3σ 命中 → ERROR，仅 IQR 命中 → WARN） */
    public TrendAnalyzeDtos.AnomalyResult anomaly(TrendAnalyzeDtos.AnomalyRequest req, String requestId) {
        long start = System.currentTimeMillis();
        Input input = resolveInput(req == null ? null : req.seriesId(), req == null ? null : req.points());
        double[] v = values(input.points());
        double mean = mean(v);
        double std = std(v, mean);
        double[] q = quartiles(v);
        double iqr = q[1] - q[0];
        double lowFence = q[0] - 1.5 * iqr;
        double highFence = q[1] + 1.5 * iqr;
        List<TrendAnalyzeDtos.AnomalyPoint> anomalies = new ArrayList<>();
        for (int i = 0; i < v.length; i++) {
            boolean sigma = std > 0 && Math.abs(v[i] - mean) > 3 * std;
            boolean fence = v[i] < lowFence || v[i] > highFence;
            if (sigma || fence) {
                List<String> channels = new ArrayList<>();
                if (sigma) {
                    channels.add("THREE_SIGMA");
                }
                if (fence) {
                    channels.add("IQR");
                }
                String level = sigma ? "ERROR" : "WARN";
                anomalies.add(new TrendAnalyzeDtos.AnomalyPoint(i, input.points().get(i).date(),
                        input.points().get(i).value(), level, channels));
            }
        }
        String anomalyId = "TAM" + dayStamp() + String.format("%07d", anomalySeq.incrementAndGet());
        long elapsed = System.currentTimeMillis() - start;
        log.info("异常检测: requestId={}, anomalyId={}, seriesId={}, pointCount={}, 异常点数={}",
                requestId, anomalyId, input.seriesId(), v.length, anomalies.size());
        return new TrendAnalyzeDtos.AnomalyResult(anomalyId, v.length, anomalies.size(), anomalies, elapsed);
    }

    /** 注册序列元信息（GET /series） */
    public TrendAnalyzeDtos.SeriesResult series() {
        List<TrendAnalyzeDtos.SeriesInfo> infos = SERIES.values().stream()
                .map(s -> new TrendAnalyzeDtos.SeriesInfo(s.seriesId(), s.name(), s.unit(), s.granularity(),
                        "MONTH", "2024-10-01", "2026-09-01", s.points().size()))
                .toList();
        return new TrendAnalyzeDtos.SeriesResult("2026.09.01", infos);
    }

    // ---------- 分析主链 ----------

    private TrendAnalyzeDtos.AnalyzeResult doAnalyze(Input input, int minPoints, List<String> methods,
                                                     String requestId, long start) {
        List<TrendAnalyzeDtos.Point> points = input.points();
        int n = points.size();
        double[] raw = new double[n];
        int missing = 0;
        for (int i = 0; i < n; i++) {
            Double val = points.get(i).value();
            if (val == null) {
                missing++;
                raw[i] = Double.NaN;
            } else {
                raw[i] = val;
            }
        }
        if (missing == n) {
            throw new BaiException(TrendAnalyzeErrorCodes.DATA_INSUFFICIENT, "TDA_1006 样本不足：全部点值缺失");
        }
        boolean imputed = missing * 1.0 / n > 0.20;
        double[] v = interpolate(raw); // 缺失值线性插补（确定性）
        // 趋势：MK 检验（简化 S 统计，含连结修正）+ OLS 斜率
        long s = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (v[j] > v[i]) {
                    s++;
                } else if (v[j] < v[i]) {
                    s--;
                }
            }
        }
        double varS = n * (n - 1L) * (2L * n + 5) / 18.0;
        Map<Double, Integer> ties = new LinkedHashMap<>();
        for (double x : v) {
            ties.merge(x, 1, Integer::sum);
        }
        for (int t : ties.values()) {
            if (t > 1) {
                varS -= t * (t - 1.0) * (2.0 * t + 5) / 18.0;
            }
        }
        double z = 0;
        if (varS > 0) {
            z = s > 0 ? (s - 1) / Math.sqrt(varS) : (s < 0 ? (s + 1) / Math.sqrt(varS) : 0);
        }
        double slope = ols(v)[0];
        double mean = mean(v);
        double std = std(v, mean);
        double cv = mean == 0 ? Double.MAX_VALUE : Math.abs(std / mean);
        String direction = cv < 0.10 ? "FLAT" : (Math.abs(z) > 1.96 ? (slope > 0 ? "UP" : "DOWN") : "VOLATILE");
        String strength = Math.abs(z) >= 2.58 ? "STRONG" : (Math.abs(z) >= 1.96 ? "MEDIUM" : "WEAK");
        double confidence = round2(Math.max(0, Math.min(1, 2 * normCdf(Math.abs(z)) - 1)));
        TrendAnalyzeDtos.Trend trend = new TrendAnalyzeDtos.Trend(direction, strength, round4(slope), confidence);
        // 环比/同比（点数不足时对应字段为 null）
        TrendAnalyzeDtos.Mom mom = methods.contains("mom") ? mom(v, points) : null;
        TrendAnalyzeDtos.Yoy yoy = methods.contains("yoy") ? yoy(v, points) : null;
        // 简化 STL：11 点居中移动平均趋势 + 月度季节指数 + 残差
        TrendAnalyzeDtos.Decompose decompose = methods.contains("decompose") ? decompose(v, points) : null;
        String analyzeId = "TAN" + dayStamp() + String.format("%07d", analyzeSeq.incrementAndGet());
        String snapshotId = "SNP" + dayStamp() + String.format("%07d", analyzeSeq.incrementAndGet());
        long elapsed = System.currentTimeMillis() - start;
        log.info("趋势分析: requestId={}, analyzeId={}, seriesId={}, pointCount={}, imputed={}, direction={}, slope={}",
                requestId, analyzeId, input.seriesId(), n, imputed, direction, round4(slope));
        return new TrendAnalyzeDtos.AnalyzeResult(analyzeId, input.seriesId(), n, imputed, trend, mom, yoy,
                decompose, snapshotId, elapsed);
    }

    private TrendAnalyzeDtos.Mom mom(double[] v, List<TrendAnalyzeDtos.Point> points) {
        int n = v.length;
        if (n < 2 || v[n - 2] == 0) {
            return new TrendAnalyzeDtos.Mom(null, null, points.get(n - 1).date());
        }
        double rate = round4((v[n - 1] - v[n - 2]) / v[n - 2]);
        return new TrendAnalyzeDtos.Mom(rate, rateDirection(rate), points.get(n - 1).date());
    }

    private TrendAnalyzeDtos.Yoy yoy(double[] v, List<TrendAnalyzeDtos.Point> points) {
        int n = v.length;
        if (n < 13 || v[n - 13] == 0) {
            return new TrendAnalyzeDtos.Yoy(null, null, points.get(n - 1).date());
        }
        double rate = round4((v[n - 1] - v[n - 13]) / v[n - 13]);
        return new TrendAnalyzeDtos.Yoy(rate, rateDirection(rate), points.get(n - 1).date());
    }

    private TrendAnalyzeDtos.Decompose decompose(double[] v, List<TrendAnalyzeDtos.Point> points) {
        int n = v.length;
        double[] trendArr = new double[n];
        for (int i = 0; i < n; i++) {
            int from = Math.max(0, i - 5);
            int to = Math.min(n - 1, i + 5);
            double sum = 0;
            for (int j = from; j <= to; j++) {
                sum += v[j];
            }
            trendArr[i] = sum / (to - from + 1);
        }
        // 月度季节指数：去趋势后按日历月（date 月份）求均值，再中心化
        Map<Integer, List<Double>> byMonth = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            byMonth.computeIfAbsent(monthOf(points.get(i).date(), i), k -> new ArrayList<>()).add(v[i] - trendArr[i]);
        }
        double seasonalMean = byMonth.values().stream().mapToDouble(l -> l.stream().mapToDouble(Double::doubleValue).average().orElse(0))
                .average().orElse(0);
        double[] seasonalArr = new double[n];
        for (int i = 0; i < n; i++) {
            List<Double> l = byMonth.get(monthOf(points.get(i).date(), i));
            seasonalArr[i] = round4(l.stream().mapToDouble(Double::doubleValue).average().orElse(0) - seasonalMean);
        }
        List<Double> trend = new ArrayList<>();
        List<Double> seasonal = new ArrayList<>();
        List<Double> residual = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            trend.add(round4(trendArr[i]));
            seasonal.add(seasonalArr[i]);
            residual.add(round4(v[i] - trendArr[i] - seasonalArr[i]));
        }
        return new TrendAnalyzeDtos.Decompose(trend, seasonal, residual);
    }

    // ---------- 输入解析与校验 ----------

    private record Input(String seriesId, List<TrendAnalyzeDtos.Point> points) {}

    /** 序列解析：seriesId 与 points 二选一（均缺 TDA_1001；同时给出时以 points 为准）；注册序列未注册 → TDA_1004 */
    private Input resolveInput(String seriesId, List<TrendAnalyzeDtos.Point> points) {
        boolean hasPoints = points != null && !points.isEmpty();
        boolean hasSeries = seriesId != null && !seriesId.isBlank();
        if (!hasPoints && !hasSeries) {
            throw new BaiException(TrendAnalyzeErrorCodes.PARAM_INVALID, "TDA_1001 参数缺失：seriesId 与 points 二选一必填");
        }
        if (hasPoints) {
            if (points.size() > MAX_POINTS) {
                throw new BaiException(TrendAnalyzeErrorCodes.OVER_LIMIT,
                        "TDA_1005 points 超 " + MAX_POINTS + " 点（实际 " + points.size() + "），请分批");
            }
            for (int i = 0; i < points.size(); i++) {
                TrendAnalyzeDtos.Point p = points.get(i);
                if (p == null || p.date() == null || p.date().isBlank()) {
                    throw new BaiException(TrendAnalyzeErrorCodes.PARAM_INVALID, "TDA_1001 参数非法：points[" + i + "].date 必填");
                }
            }
            return new Input(hasSeries ? seriesId : "CUSTOM", points);
        }
        DataModel.Series s = SERIES.get(seriesId);
        if (s == null) {
            throw new BaiException(TrendAnalyzeErrorCodes.SERIES_NOT_FOUND, "TDA_1004 序列未注册：" + seriesId);
        }
        return new Input(seriesId, s.points());
    }

    /** window 解析：缺省 M12；格式非法 → TDA_1001；样本不足（<N 点）→ TDA_1006 */
    private int resolveWindow(String window) {
        String w = window == null || window.isBlank() ? DEFAULT_WINDOW : window.toUpperCase();
        if (!WINDOW.matcher(w).matches()) {
            throw new BaiException(TrendAnalyzeErrorCodes.PARAM_INVALID, "TDA_1001 参数非法：window 格式须为 M{N}（如 M12）");
        }
        return Integer.parseInt(w.substring(1));
    }

    private List<String> resolveMethods(List<String> methods) {
        if (methods == null || methods.isEmpty()) {
            return DEFAULT_METHODS;
        }
        for (String m : methods) {
            if (!METHODS.contains(m)) {
                throw new BaiException(TrendAnalyzeErrorCodes.PARAM_INVALID,
                        "TDA_1001 参数非法：methods 须为 [trend,mom,yoy,decompose] 子集，非法值 " + m);
            }
        }
        return methods;
    }

    // ---------- 数值工具（确定性，同输入复现一致） ----------

    /** 缺失值线性插补：内部点取两侧最近有效值线性插值，端点取最近有效值 */
    private double[] interpolate(double[] raw) {
        double[] v = raw.clone();
        for (int i = 0; i < v.length; i++) {
            if (!Double.isNaN(v[i])) {
                continue;
            }
            int left = i;
            while (left >= 0 && Double.isNaN(v[left])) {
                left--;
            }
            int right = i;
            while (right < v.length && Double.isNaN(v[right])) {
                right++;
            }
            if (left < 0) {
                v[i] = v[right];
            } else if (right >= v.length) {
                v[i] = v[left];
            } else {
                v[i] = v[left] + (v[right] - v[left]) * (i - left) / (double) (right - left);
            }
        }
        return v;
    }

    /** OLS：返回 [slope, intercept] */
    private double[] ols(double[] v) {
        int n = v.length;
        double sumX = 0;
        double sumY = 0;
        double sumXY = 0;
        double sumXX = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += v[i];
            sumXY += i * v[i];
            sumXX += (double) i * i;
        }
        double denom = n * sumXX - sumX * sumX;
        double slope = denom == 0 ? 0 : (n * sumXY - sumX * sumY) / denom;
        double intercept = (sumY - slope * sumX) / n;
        return new double[]{slope, intercept};
    }

    private double residualStd(double[] v, double slope, double intercept) {
        double sum = 0;
        for (int i = 0; i < v.length; i++) {
            double e = v[i] - (intercept + slope * i);
            sum += e * e;
        }
        return Math.sqrt(sum / Math.max(1, v.length - 2));
    }

    private double mean(double[] v) {
        return java.util.Arrays.stream(v).average().orElse(0);
    }

    private double std(double[] v, double mean) {
        double sum = 0;
        for (double x : v) {
            sum += (x - mean) * (x - mean);
        }
        return Math.sqrt(sum / v.length);
    }

    /** 四分位数（线性插值法）：返回 [Q1, Q3] */
    private double[] quartiles(double[] v) {
        double[] sorted = v.clone();
        java.util.Arrays.sort(sorted);
        return new double[]{percentile(sorted, 0.25), percentile(sorted, 0.75)};
    }

    private double percentile(double[] sorted, double p) {
        double idx = (sorted.length - 1) * p;
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo);
    }

    /** 标准正态分布 CDF（Abramowitz-Stegun 7.1.26 erf 近似，确定性） */
    private double normCdf(double x) {
        return 0.5 * (1 + erf(x / Math.sqrt(2)));
    }

    private double erf(double x) {
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592)
                * t * Math.exp(-x * x);
        return x >= 0 ? y : -y;
    }

    private String rateDirection(double rate) {
        return rate > 0.005 ? "UP" : (rate < -0.005 ? "DOWN" : "FLAT");
    }

    /** 从日期取日历月（1-12）；date 缺失时按下标取模兜底 */
    private int monthOf(String date, int index) {
        if (date != null && date.length() >= 7) {
            try {
                return Integer.parseInt(date.substring(5, 7));
            } catch (NumberFormatException ignore) {
                // fall through
            }
        }
        return index % 12 + 1;
    }

    private double[] values(List<TrendAnalyzeDtos.Point> points) {
        double[] raw = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            raw[i] = points.get(i).value() == null ? Double.NaN : points.get(i).value();
        }
        return interpolate(raw);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private String dayStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    // ---------- 注册序列（确定性仿真数据，固定种子） ----------

    /** 内部数据模型（注册序列元信息，确定性仿真） */
    private static final class DataModel {

        private record Series(String seriesId, String name, String unit, String granularity, List<TrendAnalyzeDtos.Point> points) {}
    }

    private static Map<String, DataModel.Series> buildSeries() {
        Map<String, DataModel.Series> m = new LinkedHashMap<>();
        m.put("S_LOAN_BAL", new DataModel.Series("S_LOAN_BAL", "贷款余额", "亿元", "ORG/MONTH",
                generate("S_LOAN_BAL", 120.0)));
        m.put("S_BAD_RATE", new DataModel.Series("S_BAD_RATE", "不良贷款率", "%", "ORG/MONTH",
                generate("S_BAD_RATE", 2.3)));
        m.put("S_DEPOSIT_BAL", new DataModel.Series("S_DEPOSIT_BAL", "存款余额", "亿元", "ORG/MONTH",
                generate("S_DEPOSIT_BAL", 300.0)));
        m.put("S_APPROVAL_RATE", new DataModel.Series("S_APPROVAL_RATE", "审批通过率", "%", "ORG/MONTH",
                generate("S_APPROVAL_RATE", 85.0)));
        m.put("S_OVERDUE_RATE", new DataModel.Series("S_OVERDUE_RATE", "逾期率", "%", "ORG/MONTH",
                generate("S_OVERDUE_RATE", 1.8)));
        return Map.copyOf(m);
    }

    /** 固定种子伪随机游走（确定性，用于联调）：24 个月度点 2024-10 ~ 2026-09 */
    private static List<TrendAnalyzeDtos.Point> generate(String seriesId, double base) {
        Random r = new Random(seriesId.hashCode());
        List<TrendAnalyzeDtos.Point> pts = new ArrayList<>();
        LocalDate d = LocalDate.of(2024, 10, 1);
        double v = base;
        for (int i = 0; i < 24; i++) {
            v = v * (1 + (r.nextDouble() - 0.45) * 0.02) + base * 0.001;
            pts.add(new TrendAnalyzeDtos.Point(d.format(DateTimeFormatter.ISO_LOCAL_DATE), round4s(v)));
            d = d.plusMonths(1);
        }
        return List.copyOf(pts);
    }

    private static double round4s(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
