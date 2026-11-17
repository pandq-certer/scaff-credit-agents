package com.bai.dataquery;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一数据查询原子接口（权限点 DTQ_QUERY/DTQ_NL/DTQ_BATCH/DTQ_SOURCES/DTQ_ADMIN；
 * Bearer Token 由网关统一鉴权，Controller 不校验 Token）
 */
@RestController
public class DataQueryController {

    private final DataQueryService service;
    /** 限流（条目约定）：query 200 QPS / nl 30 QPS / batch 20 QPS */
    private final TokenBuckets queryLimiter = new TokenBuckets(200, 400);
    private final TokenBuckets nlLimiter = new TokenBuckets(30, 60);
    private final TokenBuckets batchLimiter = new TokenBuckets(20, 40);

    public DataQueryController(DataQueryService service) {
        this.service = service;
    }

    /** 参数化查询（白名单+只读+参数绑定；requestId body 优先、header 兜底） */
    @PostMapping("/query")
    public BaiResponse<DataQueryDtos.QueryResult> query(
            @RequestBody DataQueryDtos.QueryRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(request == null ? null : request.requestId(), headerId);
        if (!queryLimiter.allow(requestId)) {
            throw new BaiException(DataQueryErrorCodes.RATE_LIMITED, "DTQ_1007 触发限流（query 200 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.query(request, requestId));
    }

    /** 自然语言查询（NL2SQL，只读校验；requestId body 优先、header 兜底） */
    @PostMapping("/nl")
    public BaiResponse<DataQueryDtos.NlResult> nl(
            @RequestBody DataQueryDtos.NlRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(request == null ? null : request.requestId(), headerId);
        if (!nlLimiter.allow(requestId)) {
            throw new BaiException(DataQueryErrorCodes.RATE_LIMITED, "DTQ_1007 触发限流（nl 30 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.nl(request, requestId));
    }

    /** 批量异步查询（≤1000 条；requestId body 优先、header 兜底） */
    @PostMapping("/batch")
    public BaiResponse<DataQueryDtos.BatchResult> batch(
            @RequestBody DataQueryDtos.BatchRequest request,
            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(request == null ? null : request.requestId(), headerId);
        if (!batchLimiter.allow(requestId)) {
            throw new BaiException(DataQueryErrorCodes.RATE_LIMITED, "DTQ_1007 触发限流（batch 20 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 数据源信息（白名单快照；只读接口，header 缺失时服务端生成 UUID） */
    @GetMapping("/datasources")
    public BaiResponse<DataQueryDtos.DatasourcesResult> datasources(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        String effective = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
        return BaiResponse.ok(effective, service.datasources());
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private String resolveRequestId(String bodyId, String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : bodyId;
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(DataQueryErrorCodes.PARAM_INVALID, "DTQ_1001 参数缺失：requestId 幂等请求号必填");
        }
        return requestId;
    }
}
