package com.bai.summary;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文本摘要原子接口（权限点 SUM_GENERATE/SUM_BATCH/SUM_EVALUATE/SUM_MODELS/SUM_ADMIN；
 * Bearer Token 由网关统一鉴权，Controller 不校验 Token）
 */
@RestController
public class SummaryController {

    private final SummaryService service;
    /** 限流（条目约定）：generate 30 QPS / batch 10 QPS / evaluate 10 QPS */
    private final TokenBuckets generateLimiter = new TokenBuckets(30, 60);
    private final TokenBuckets batchLimiter = new TokenBuckets(10, 20);
    private final TokenBuckets evaluateLimiter = new TokenBuckets(10, 20);

    public SummaryController(SummaryService service) {
        this.service = service;
    }

    /** 同步生成摘要 */
    @PostMapping("/generate")
    public BaiResponse<SummaryDtos.SummaryResult> generate(@RequestBody SummaryDtos.GenerateRequest request,
                                                           @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId, generateLimiter, "generate 30 QPS");
        return BaiResponse.ok(requestId, service.generate(request, requestId));
    }

    /** 批量摘要（≤5000 篇，异步） */
    @PostMapping("/batch")
    public BaiResponse<SummaryDtos.BatchResult> batch(@RequestBody SummaryDtos.BatchRequest request,
                                                      @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId, batchLimiter, "batch 10 QPS");
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 质量评估（consistency/faithfulness/coverage/overall 0—1） */
    @PostMapping("/evaluate")
    public BaiResponse<SummaryDtos.QualityScore> evaluate(@RequestBody SummaryDtos.EvaluateRequest request,
                                                          @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId, evaluateLimiter, "evaluate 10 QPS");
        return BaiResponse.ok(requestId, service.evaluate(request, requestId));
    }

    /** 模型信息 */
    @GetMapping("/models")
    public BaiResponse<SummaryDtos.SummaryModels> models(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.models());
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(String requestId, TokenBuckets limiter, String detail) {
        if (!limiter.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(SummaryErrorCodes.RATE_LIMITED, "SUM_1007 触发限流（" + detail + "），请按 Retry-After 退避");
        }
    }
}
