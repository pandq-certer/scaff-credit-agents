package com.bai.graph;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 图谱构建原子接口（权限点 GRB_BUILD/GRB_INCREMENT/GRB_JOB/GRB_NODES/GRB_EDGES；
 * Bearer Token 由网关统一鉴权，Controller 仅强制请求头存在性，缺头由 bai-common 自动 400 PARAM_MISSING）
 */
@RestController
public class GraphBuildController {

    private final GraphBuildService service;
    /** 限流（条目约定）：10 QPS（build/increment/jobs/nodes/edges 统一口径） */
    private final TokenBuckets apiLimiter = new TokenBuckets(10, 20);

    public GraphBuildController(GraphBuildService service) {
        this.service = service;
    }

    /** 全量/增量构建（异步任务，初始 PENDING，GET /jobs/{jobId} 轮询） */
    @PostMapping("/build")
    public BaiResponse<GraphBuildDtos.BuildResult> build(
            @RequestBody GraphBuildDtos.BuildRequest request,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("Authorization") String authorization) {
        if (!apiLimiter.allow(requestId)) {
            throw new BaiException(GraphBuildErrorCodes.RATE_LIMITED, "GRB_1007 触发限流（10 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.build(request, requestId));
    }

    /** 增量事件构建（events≤10000 条） */
    @PostMapping("/increment")
    public BaiResponse<GraphBuildDtos.BuildResult> increment(
            @RequestBody GraphBuildDtos.IncrementRequest request,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("Authorization") String authorization) {
        if (!apiLimiter.allow(requestId)) {
            throw new BaiException(GraphBuildErrorCodes.RATE_LIMITED, "GRB_1007 触发限流（10 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.increment(request, requestId));
    }

    /** 任务状态查询 */
    @GetMapping("/jobs/{jobId}")
    public BaiResponse<GraphBuildDtos.JobView> job(
            @PathVariable String jobId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        if (!apiLimiter.allow(limiterKey(requestId, "job"))) {
            throw new BaiException(GraphBuildErrorCodes.RATE_LIMITED, "GRB_1007 触发限流（10 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.job(jobId));
    }

    /** 节点列表（确定性仿真） */
    @GetMapping("/nodes")
    public BaiResponse<GraphBuildDtos.NodesResult> nodes(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        if (!apiLimiter.allow(limiterKey(requestId, "nodes"))) {
            throw new BaiException(GraphBuildErrorCodes.RATE_LIMITED, "GRB_1007 触发限流（10 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.nodes());
    }

    /** 边列表（确定性仿真） */
    @GetMapping("/edges")
    public BaiResponse<GraphBuildDtos.EdgesResult> edges(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        if (!apiLimiter.allow(limiterKey(requestId, "edges"))) {
            throw new BaiException(GraphBuildErrorCodes.RATE_LIMITED, "GRB_1007 触发限流（10 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.edges());
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    /** 限流键：requestId 缺省时按端点名兜底（避免 null 键） */
    private String limiterKey(String requestId, String fallback) {
        return requestId == null || requestId.isBlank() ? fallback : requestId;
    }
}
