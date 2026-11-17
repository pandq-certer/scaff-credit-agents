package com.bai.approvalroute;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 审批流转原子接口（权限点 APR_ROUTE/APR_APPROVE/APR_TASK/APR_CHAIN/APR_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class ApprovalRouteController {

    private final ApprovalRouteService service;
    /** 限流：route/approve 单调用方 50 QPS */
    private final TokenBuckets limiter = new TokenBuckets(50, 50);

    public ApprovalRouteController(ApprovalRouteService service) {
        this.service = service;
    }

    /** 解析审批人与模式（规则按优先级升序命中即停，创建审批任务） */
    @PostMapping("/route")
    public BaiResponse<ApprovalRouteDtos.RouteResult> route(@RequestBody Map<String, Object> body,
                                                            @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId, "APR_1007 触发限流（route 单调用方 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.route(body, requestId));
    }

    /** 审批动作（同意/驳回/退回/撤回，仅任务审批人可发起，版本乐观锁防重复） */
    @PostMapping("/approve")
    public BaiResponse<ApprovalRouteDtos.ApproveResult> approve(@RequestBody Map<String, Object> body,
                                                                @RequestHeader("X-Request-Id") String requestId,
                                                                @RequestHeader(value = "Authorization", required = false) String authorization) {
        limit(requestId, "APR_1007 触发限流（approve 单调用方 50 QPS），请按 Retry-After 退避重试");
        String operator = authorization == null ? "" : authorization.replaceFirst("Bearer\\s+", "");
        return BaiResponse.ok(requestId, service.approve(body, requestId, operator));
    }

    /** 审批任务查询（status/bizType/approver 过滤） */
    @GetMapping("/tasks")
    public BaiResponse<List<ApprovalRouteDtos.TaskInfo>> tasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String approver,
            @RequestHeader("X-Request-Id") String requestId) {
        return BaiResponse.ok(requestId, service.queryTasks(Map.of(
                "status", status == null ? "" : status,
                "bizType", bizType == null ? "" : bizType,
                "approver", approver == null ? "" : approver)));
    }

    /** 审批链查询（路由决策 + 任务 + 全量审批记录） */
    @GetMapping("/chain")
    public BaiResponse<ApprovalRouteDtos.ChainResult> chain(
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String routeId,
            @RequestHeader("X-Request-Id") String requestId) {
        return BaiResponse.ok(requestId, service.chain(taskId, routeId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(String requestId, String message) {
        if (!limiter.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(ApprovalRouteErrorCodes.RATE_LIMITED, message);
        }
    }
}
