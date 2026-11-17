package com.bai.entity;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实体归一原子接口（权限点 ENT_RESOLVE/ENT_MATCH/ENT_MERGE/ENT_ENTITIES；
 * Bearer Token 由网关统一鉴权，Controller 仅强制请求头存在性，缺头由 bai-common 自动 400 PARAM_MISSING）
 */
@RestController
public class EntityResolutionController {

    private final EntityResolutionService service;
    /** 限流（条目约定）：resolve 50 QPS / match 50 QPS / merge 10 QPS */
    private final TokenBuckets resolveLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets matchLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets mergeLimiter = new TokenBuckets(10, 20);

    public EntityResolutionController(EntityResolutionService service) {
        this.service = service;
    }

    /** 实体归一（规范化+指纹+黄金库匹配决策） */
    @PostMapping("/resolve")
    public BaiResponse<EntityResolutionDtos.ResolveResult> resolve(
            @RequestBody EntityResolutionDtos.ResolveRequest request,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("Authorization") String authorization) {
        if (!resolveLimiter.allow(requestId)) {
            throw new BaiException(EntityResolutionErrorCodes.RATE_LIMITED, "ENT_1007 触发限流（resolve 50 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.resolve(request, requestId));
    }

    /** 候选检索（topK≤50） */
    @PostMapping("/match")
    public BaiResponse<EntityResolutionDtos.MatchResult> match(
            @RequestBody EntityResolutionDtos.MatchRequest request,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("Authorization") String authorization) {
        if (!matchLimiter.allow(requestId)) {
            throw new BaiException(EntityResolutionErrorCodes.RATE_LIMITED, "ENT_1007 触发限流（match 50 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.match(request, requestId));
    }

    /** 黄金记录合并（版本递增） */
    @PostMapping("/merge")
    public BaiResponse<EntityResolutionDtos.MergeResult> merge(
            @RequestBody EntityResolutionDtos.MergeRequest request,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("Authorization") String authorization) {
        if (!mergeLimiter.allow(requestId)) {
            throw new BaiException(EntityResolutionErrorCodes.RATE_LIMITED, "ENT_1007 触发限流（merge 10 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.merge(request, requestId));
    }

    /** 黄金记录列表（证件号脱敏前 4 后 4+****） */
    @GetMapping("/entities")
    public BaiResponse<EntityResolutionDtos.EntitiesResult> entities(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.entities());
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }
}
