package com.bai.kretrieve;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识检索原子接口（权限点 KRE_RETRIEVE/KRE_GRAPH/KRE_BATCH/KRE_CONFIG/KRE_ADMIN；
 * Bearer Token 由网关统一鉴权，Controller 不校验 Token）
 */
@RestController
public class KnowledgeRetrieveController {

    private final KnowledgeRetrieveService service;
    /** 限流（条目约定）：retrieve 300 QPS / graph 50 QPS / batch 20 QPS */
    private final TokenBuckets retrieveLimiter = new TokenBuckets(300, 600);
    private final TokenBuckets graphLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets batchLimiter = new TokenBuckets(20, 40);

    public KnowledgeRetrieveController(KnowledgeRetrieveService service) {
        this.service = service;
    }

    /** 同步多通道检索（关键词+向量，可含图谱） */
    @PostMapping("/retrieve")
    public BaiResponse<KnowledgeRetrieveDtos.RetrieveResult> retrieve(
            @RequestBody KnowledgeRetrieveDtos.RetrieveRequest request,
            @RequestHeader("X-Request-Id") String requestId) {
        if (!retrieveLimiter.allow(requestId)) {
            throw new BaiException(KnowledgeRetrieveErrorCodes.RATE_LIMITED, "KRE_1007 触发限流（retrieve 300 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.retrieve(request, requestId));
    }

    /** 图谱检索（一跳 maxHop=1） */
    @PostMapping("/retrieve/graph")
    public BaiResponse<KnowledgeRetrieveDtos.RetrieveResult> graph(
            @RequestBody KnowledgeRetrieveDtos.GraphRequest request,
            @RequestHeader("X-Request-Id") String requestId) {
        if (!graphLimiter.allow(requestId)) {
            throw new BaiException(KnowledgeRetrieveErrorCodes.RATE_LIMITED, "KRE_1007 触发限流（graph 50 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.graph(request, requestId));
    }

    /** 批量异步检索（≤10000 条） */
    @PostMapping("/retrieve/batch")
    public BaiResponse<KnowledgeRetrieveDtos.BatchResult> batch(
            @RequestBody KnowledgeRetrieveDtos.BatchRequest request,
            @RequestHeader("X-Request-Id") String requestId) {
        if (!batchLimiter.allow(requestId)) {
            throw new BaiException(KnowledgeRetrieveErrorCodes.RATE_LIMITED, "KRE_1007 触发限流（batch 20 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 检索配置（RFF 权重/上限/缓存 TTL） */
    @GetMapping("/retrieve/config")
    public BaiResponse<KnowledgeRetrieveDtos.RetrieveConfig> config(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.config());
    }

    /** 探活（/retrieve/health 供网关共用前缀时区分路由） */
    @GetMapping({"/health", "/retrieve/health"})
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }
}
