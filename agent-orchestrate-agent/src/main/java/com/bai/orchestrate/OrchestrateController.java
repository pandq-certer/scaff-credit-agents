package com.bai.orchestrate;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 多智能体编排原子接口（提示词库 A-41：plan/execute/intervene/instances/health）。
 * <p>权限点 AGO_PLAN/AGO_EXEC/AGO_CTRL/AGO_QUERY/AGO_ADMIN，Bearer Token 由网关统一鉴权（越权 403）；
 * 介入操作需对应级别授权，P0 介入需双人复核；审计 append-only 保留≥3 年。
 */
@RestController
public class OrchestrateController {

    private final OrchestrateService service;
    /** 限流：单调用方 20 QPS（plan/execute，介入同为写操作共用），突发 40 */
    private final TokenBuckets limiter = new TokenBuckets(20, 40);

    public OrchestrateController(OrchestrateService service) {
        this.service = service;
    }

    /** 计划生成（同步）：goal≤2000 字、constraints、templateId */
    @PostMapping("/plan")
    public BaiResponse<OrchestrateDtos.PlanResult> plan(@RequestBody OrchestrateDtos.PlanRequest request,
                                                        @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId);
        return BaiResponse.ok(requestId, service.plan(request, requestId));
    }

    /** 编排执行（异步受理）：planId/templateId 加 bizKey 启动实例，幂等 requestId+bizKey */
    @PostMapping("/execute")
    public BaiResponse<OrchestrateDtos.InstanceResult> execute(@RequestBody OrchestrateDtos.ExecuteRequest request,
                                                               @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId);
        return BaiResponse.ok(requestId, service.execute(request, requestId));
    }

    /** 人工介入（同步）：PAUSE/RESUME/SKIP_STEP/REDIRECT_STEP/CANCEL */
    @PostMapping("/intervene")
    public BaiResponse<OrchestrateDtos.InstanceResult> intervene(@RequestBody OrchestrateDtos.InterveneRequest request,
                                                                 @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId);
        return BaiResponse.ok(requestId, service.intervene(request, requestId));
    }

    /** 编排实例查询 */
    @GetMapping("/instances")
    public BaiResponse<List<OrchestrateDtos.InstanceSummary>> instances(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.instances());
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(String requestId) {
        if (!limiter.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(OrchestrateErrorCodes.RATE_LIMITED,
                    "AGO_1008 触发限流（单调用方 20 QPS），请按 Retry-After 退避重试");
        }
    }
}
