package com.bai.search;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 多源搜索原子接口（权限点 SEA_SEARCH/SEA_BATCH/SEA_SNAPSHOT/SEA_SOURCES/SEA_ADMIN，
 * EXTERNAL 源按接口授权；Bearer Token 由网关统一鉴权，Controller 不校验 Token）
 */
@RestController
public class SearchController {

    private final SearchService service;
    /** 限流（条目约定）：search 50 QPS / batch 10 QPS */
    private final TokenBuckets searchLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets batchLimiter = new TokenBuckets(10, 20);

    public SearchController(SearchService service) {
        this.service = service;
    }

    /** 同步搜索（internet/internal/external/knowledge 四源） */
    @PostMapping("/search")
    public BaiResponse<SearchDtos.SearchOutput> search(@RequestBody SearchDtos.SearchRequest request,
                                                       @RequestHeader("X-Request-Id") String requestId) {
        if (!searchLimiter.allow(requestId)) {
            throw new BaiException(SearchErrorCodes.RATE_LIMITED, "SEA_1007 触发限流（search 50 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.search(request, requestId));
    }

    /** 批量异步搜索（≤5000 条） */
    @PostMapping("/batch")
    public BaiResponse<SearchDtos.BatchResult> batch(@RequestBody SearchDtos.BatchRequest request,
                                                     @RequestHeader("X-Request-Id") String requestId) {
        if (!batchLimiter.allow(requestId)) {
            throw new BaiException(SearchErrorCodes.RATE_LIMITED, "SEA_1007 触发限流（batch 10 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 快照查询（尽调留证） */
    @GetMapping("/snapshot")
    public BaiResponse<SearchDtos.Snapshot> snapshot(@RequestParam(value = "snapshotId", required = false) String snapshotId,
                                                     @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.snapshot(snapshotId));
    }

    /** 搜索源信息 */
    @GetMapping("/sources")
    public BaiResponse<List<SearchDtos.SourceInfo>> sources(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.sources());
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }
}
