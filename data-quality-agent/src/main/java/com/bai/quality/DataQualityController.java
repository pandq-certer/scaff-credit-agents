package com.bai.quality;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据质量治理原子接口（权限点 DQL_CHECK/DQL_BATCH/DQL_RULES/DQL_ISSUES；
 * Bearer Token 由网关统一鉴权，Controller 仅强制请求头存在性，缺头由 bai-common 自动 400 PARAM_MISSING）
 */
@RestController
public class DataQualityController {

    private final DataQualityService service;
    /** 限流（条目约定）：check 50 QPS / batch 10 QPS */
    private final TokenBuckets checkLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets batchLimiter = new TokenBuckets(10, 20);

    public DataQualityController(DataQualityService service) {
        this.service = service;
    }

    /** 资产质检（五维度加权得分+降级标记+ERROR 工单） */
    @PostMapping("/check")
    public BaiResponse<DataQualityDtos.CheckResult> check(
            @RequestBody DataQualityDtos.CheckRequest request,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("Authorization") String authorization) {
        if (!checkLimiter.allow(requestId)) {
            throw new BaiException(DataQualityErrorCodes.RATE_LIMITED, "DQL_1007 触发限流（check 50 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.check(request, requestId));
    }

    /** 批量质检（单资产 + 规则集≤200 条） */
    @PostMapping("/batch")
    public BaiResponse<DataQualityDtos.BatchResult> batch(
            @RequestBody DataQualityDtos.BatchRequest request,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("Authorization") String authorization) {
        if (!batchLimiter.allow(requestId)) {
            throw new BaiException(DataQualityErrorCodes.RATE_LIMITED, "DQL_1007 触发限流（batch 10 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 规则库（五维度，sql 仅 SELECT 仿真） */
    @GetMapping("/rules")
    public BaiResponse<DataQualityDtos.RulesResult> rules(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.rules());
    }

    /** 工单列表（内存模拟工单库） */
    @GetMapping("/issues")
    public BaiResponse<DataQualityDtos.IssuesResult> issues(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.issues());
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }
}
