package com.bai.tag;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能打标原子接口（权限点 TAG_GENERATE/TAG_BATCH/TAG_TAXONOMY/TAG_MODELS/TAG_ADMIN；
 * Bearer Token 由网关统一鉴权，Controller 不校验 Token）
 */
@RestController
public class TagController {

    private final TagService service;
    /** 限流（条目约定）：generate 200 QPS / batch 20 QPS */
    private final TokenBuckets generateLimiter = new TokenBuckets(200, 400);
    private final TokenBuckets batchLimiter = new TokenBuckets(20, 40);

    public TagController(TagService service) {
        this.service = service;
    }

    /** 同步打标 */
    @PostMapping("/generate")
    public BaiResponse<TagDtos.TagResult> generate(@RequestBody TagDtos.GenerateRequest request,
                                                   @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId, generateLimiter, "generate 200 QPS");
        return BaiResponse.ok(requestId, service.generate(request, requestId));
    }

    /** 批量打标（≤100000 条，异步） */
    @PostMapping("/batch")
    public BaiResponse<TagDtos.BatchResult> batch(@RequestBody TagDtos.BatchRequest request,
                                                  @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId, batchLimiter, "batch 20 QPS");
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 标签体系 */
    @GetMapping("/taxonomy")
    public BaiResponse<TagDtos.Taxonomy> taxonomy(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.taxonomy());
    }

    /** 模型信息 */
    @GetMapping("/models")
    public BaiResponse<TagDtos.TagModels> models(
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
            throw new BaiException(TagErrorCodes.RATE_LIMITED, "TAG_1007 触发限流（" + detail + "），请按 Retry-After 退避");
        }
    }
}
