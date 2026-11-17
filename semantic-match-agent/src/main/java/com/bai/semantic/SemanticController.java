package com.bai.semantic;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 语义匹配原子接口（权限点 SEM_MATCH/SEM_SEARCH/SEM_BATCH/SEM_MODELS/SEM_ADMIN；
 * Bearer Token 由网关统一鉴权，Controller 不校验 Token）
 */
@RestController
public class SemanticController {

    private final SemanticService service;
    /** 限流（条目约定）：match 100 QPS / search 100 QPS / batch 20 QPS */
    private final TokenBuckets matchLimiter = new TokenBuckets(100, 200);
    private final TokenBuckets searchLimiter = new TokenBuckets(100, 200);
    private final TokenBuckets batchLimiter = new TokenBuckets(20, 40);

    public SemanticController(SemanticService service) {
        this.service = service;
    }

    /** 文本对相似度与四级分级 */
    @PostMapping("/match")
    public BaiResponse<SemanticDtos.MatchResult> match(@RequestBody SemanticDtos.MatchRequest request,
                                                       @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId, matchLimiter, "match 100 QPS");
        return BaiResponse.ok(requestId, service.match(request, requestId));
    }

    /** 语义检索/重排 */
    @PostMapping("/search")
    public BaiResponse<SemanticDtos.SearchResult> search(@RequestBody SemanticDtos.SearchRequest request,
                                                         @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId, searchLimiter, "search 100 QPS");
        return BaiResponse.ok(requestId, service.search(request, requestId));
    }

    /** 批量匹配（≤100000 对，异步） */
    @PostMapping("/batch")
    public BaiResponse<SemanticDtos.BatchResult> batch(@RequestBody SemanticDtos.BatchRequest request,
                                                       @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId, batchLimiter, "batch 20 QPS");
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 模型信息 */
    @GetMapping("/models")
    public BaiResponse<SemanticDtos.SemanticModels> models(
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
            throw new BaiException(SemanticErrorCodes.RATE_LIMITED, "SEM_1007 触发限流（" + detail + "），请按 Retry-After 退避");
        }
    }
}
