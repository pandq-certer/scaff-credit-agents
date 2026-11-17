package com.bai.intent;

import com.bai.common.BaiResponse;
import com.bai.common.BaiException;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 意图识别原子接口（权限点 INT_CLASSIFY/INT_BATCH/INT_SCHEMA/INT_STREAM/INT_ADMIN；
 * Bearer Token 由网关统一鉴权，Controller 不校验 Token）
 */
@RestController
public class IntentController {

    private final IntentService service;
    /** 限流（条目约定）：classify 100 QPS / batch 50 QPS；流式并发 100/实例（见 IntentStreamHandler） */
    private final TokenBuckets classifyLimiter = new TokenBuckets(100, 200);
    private final TokenBuckets batchLimiter = new TokenBuckets(50, 100);

    public IntentController(IntentService service) {
        this.service = service;
    }

    /** 同步单条识别（含多轮上下文） */
    @PostMapping("/classify")
    public BaiResponse<IntentDtos.IntentResult> classify(@RequestBody IntentDtos.ClassifyRequest request,
                                                         @RequestHeader("X-Request-Id") String requestId) {
        if (!classifyLimiter.allow(requestId)) {
            throw new BaiException(IntentErrorCodes.RATE_LIMITED, "INT_1007 触发限流（classify 100 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.classify(request, requestId));
    }

    /** 批量识别（≤10000 条，异步） */
    @PostMapping("/batch")
    public BaiResponse<IntentDtos.BatchResult> batch(@RequestBody IntentDtos.BatchRequest request,
                                                     @RequestHeader("X-Request-Id") String requestId) {
        if (!batchLimiter.allow(requestId)) {
            throw new BaiException(IntentErrorCodes.RATE_LIMITED, "INT_1007 触发限流（batch 50 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 意图体系（意图树版本化） */
    @GetMapping("/schema")
    public BaiResponse<IntentDtos.IntentSchema> schema(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.schema());
    }

    /** 探活（流式健康走同一实例） */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }
}
