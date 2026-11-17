package com.bai.trend;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 趋势分析原子接口（权限点 TDA_ANALYZE/TDA_FORECAST/TDA_ANOMALY/TDA_SERIES；
 * Bearer Token 由网关统一鉴权，Controller 仅强制请求头存在性，缺头由 bai-common 自动 400 PARAM_MISSING）
 */
@RestController
public class TrendAnalyzeController {

    private final TrendAnalyzeService service;
    /** 限流（条目约定）：analyze 50 QPS / forecast 10 QPS / anomaly 50 QPS */
    private final TokenBuckets analyzeLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets forecastLimiter = new TokenBuckets(10, 20);
    private final TokenBuckets anomalyLimiter = new TokenBuckets(50, 100);

    public TrendAnalyzeController(TrendAnalyzeService service) {
        this.service = service;
    }

    /** 趋势分析（MK 检验+OLS 斜率，mom/yoy/decompose 按需输出） */
    @PostMapping("/analyze")
    public BaiResponse<TrendAnalyzeDtos.AnalyzeResult> analyze(
            @RequestBody TrendAnalyzeDtos.AnalyzeRequest request,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("Authorization") String authorization) {
        if (!analyzeLimiter.allow(requestId)) {
            throw new BaiException(TrendAnalyzeErrorCodes.RATE_LIMITED, "TDA_1007 触发限流（analyze 50 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.analyze(request, requestId));
    }

    /** 趋势预测（horizon≤12，模型/置信区间/mape 为确定性仿真） */
    @PostMapping("/forecast")
    public BaiResponse<TrendAnalyzeDtos.ForecastResult> forecast(
            @RequestBody TrendAnalyzeDtos.ForecastRequest request,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("Authorization") String authorization) {
        if (!forecastLimiter.allow(requestId)) {
            throw new BaiException(TrendAnalyzeErrorCodes.RATE_LIMITED, "TDA_1007 触发限流（forecast 10 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.forecast(request, requestId));
    }

    /** 异常检测（3σ+IQR 双通道） */
    @PostMapping("/anomaly")
    public BaiResponse<TrendAnalyzeDtos.AnomalyResult> anomaly(
            @RequestBody TrendAnalyzeDtos.AnomalyRequest request,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("Authorization") String authorization) {
        if (!anomalyLimiter.allow(requestId)) {
            throw new BaiException(TrendAnalyzeErrorCodes.RATE_LIMITED, "TDA_1007 触发限流（anomaly 50 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.anomaly(request, requestId));
    }

    /** 注册序列元信息 */
    @GetMapping("/series")
    public BaiResponse<TrendAnalyzeDtos.SeriesResult> series(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.series());
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }
}
