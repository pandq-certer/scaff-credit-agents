package com.bai.metric;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 指标计算原子接口（权限点 MTC_CALC/MTC_BATCH/MTC_INDICATORS/MTC_VERSIONS；
 * Bearer Token 由网关统一鉴权，Controller 仅强制请求头存在性，缺头由 bai-common 自动 400 PARAM_MISSING）
 */
@RestController
public class MetricCalcController {

    private final MetricCalcService service;
    /** 限流（条目约定）：calc 50 QPS / batch 10 QPS */
    private final TokenBuckets calcLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets batchLimiter = new TokenBuckets(10, 20);

    public MetricCalcController(MetricCalcService service) {
        this.service = service;
    }

    /** 单指标计算（口径解析+确定性计算+指纹；requestId body 优先、header 兜底） */
    @PostMapping("/calc")
    public BaiResponse<MetricCalcDtos.CalcResult> calc(
            @RequestBody MetricCalcDtos.CalcRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(request == null ? null : request.requestId(), headerId);
        if (!calcLimiter.allow(requestId)) {
            throw new BaiException(MetricCalcErrorCodes.RATE_LIMITED, "MTC_1007 触发限流（calc 50 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.calc(request, requestId));
    }

    /** 批量计算（≤2000 条；requestId body 优先、header 兜底） */
    @PostMapping("/batch")
    public BaiResponse<MetricCalcDtos.BatchCalcResult> batch(
            @RequestBody MetricCalcDtos.BatchCalcRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(request == null ? null : request.requestId(), headerId);
        if (!batchLimiter.allow(requestId)) {
            throw new BaiException(MetricCalcErrorCodes.RATE_LIMITED, "MTC_1007 触发限流（batch 10 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 指标字典（确定性仿真快照） */
    @GetMapping("/indicators")
    public BaiResponse<MetricCalcDtos.IndicatorsResult> indicators(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.indicators());
    }

    /** 口径版本列表 */
    @GetMapping("/versions")
    public BaiResponse<MetricCalcDtos.VersionsResult> versions(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.versions());
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private String resolveRequestId(String bodyId, String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : bodyId;
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(MetricCalcErrorCodes.PARAM_INVALID, "MTC_1001 参数缺失：requestId 幂等请求号必填");
        }
        return requestId;
    }
}
