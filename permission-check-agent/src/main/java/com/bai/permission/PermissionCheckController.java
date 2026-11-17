package com.bai.permission;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权限校验原子接口（设计文档 46-H 表 4-1：check/data-check/query/grant/health）。
 * <p>权限点 PMC_CHECK/PMC_DATA/PMC_QUERY/PMC_GRANT/PMC_ADMIN，Bearer Token 由网关统一鉴权（auth-agent 签发）；
 * DENY 结论统一 403/PMC_1003 + 提示申请授权；授权申请异步（approval-route-agent 路由 → 结果回写）。
 */
@RestController
public class PermissionCheckController {

    private final PermissionCheckService service;
    /** 限流：操作校验 200 QPS/用户（高吞吐）、数据校验 50 QPS/用户；query 10 QPS、grant 5 QPS */
    private final TokenBuckets checkLimiter = new TokenBuckets(200, 400);
    private final TokenBuckets dataLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets queryLimiter = new TokenBuckets(10, 20);
    private final TokenBuckets grantLimiter = new TokenBuckets(5, 10);

    public PermissionCheckController(PermissionCheckService service) {
        this.service = service;
    }

    /** 操作权限校验（同步）：身份+操作 → ALLOW/DENY（Deny-Override），DENY 抛 403/PMC_1003 */
    @PostMapping("/check")
    public BaiResponse<PermissionCheckDtos.CheckResult> check(@RequestBody PermissionCheckDtos.OperationCheckRequest request,
                                                              @RequestHeader("X-Request-Id") String requestId) {
        String userKey = request != null && request.identity() != null && request.identity().userId() != null
                ? request.identity().userId() : "anonymous";
        if (!checkLimiter.allow(userKey)) {
            throw new BaiException(PermissionCheckErrorCodes.RATE_LIMITED,
                    "PMC_1008 触发限流（操作校验 200 QPS/用户），请按 Retry-After 退避重试");
        }
        return BaiResponse.ok(requestId, service.check(request, requestId));
    }

    /** 数据权限校验（同步）：身份+数据请求 → 数据范围表达式 + 列级可见性（最小授权） */
    @PostMapping("/data-check")
    public BaiResponse<PermissionCheckDtos.DataCheckResult> dataCheck(@RequestBody PermissionCheckDtos.DataCheckRequest request,
                                                                      @RequestHeader("X-Request-Id") String requestId) {
        String userKey = request != null && request.identity() != null && request.identity().userId() != null
                ? request.identity().userId() : "anonymous";
        if (!dataLimiter.allow(userKey)) {
            throw new BaiException(PermissionCheckErrorCodes.RATE_LIMITED,
                    "PMC_1008 触发限流（数据校验 50 QPS/用户），请按 Retry-After 退避重试");
        }
        return BaiResponse.ok(requestId, service.dataCheck(request, requestId));
    }

    /** 权限策略查询（同步分页）：?userId=&roleId=&operation=&page=&size= */
    @GetMapping("/query")
    public BaiResponse<PermissionCheckDtos.QueryResult> query(@RequestParam(value = "userId", required = false) String userId,
                                                              @RequestParam(value = "roleId", required = false) String roleId,
                                                              @RequestParam(value = "operation", required = false) String operation,
                                                              @RequestParam(value = "page", defaultValue = "1") int page,
                                                              @RequestParam(value = "size", defaultValue = "20") int size,
                                                              @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        String key = userId == null || userId.isBlank() ? (roleId == null || roleId.isBlank() ? "query-all" : roleId) : userId;
        if (!queryLimiter.allow(key)) {
            throw new BaiException(PermissionCheckErrorCodes.RATE_LIMITED,
                    "PMC_1008 触发限流（权限查询 10 QPS/用户），请按 Retry-After 退避重试");
        }
        return BaiResponse.ok(resolveRequestId(requestId), service.query(userId, roleId, operation, page, size));
    }

    /** 授权申请（异步）：按级别路由审批（L1 自助 / L2 直属上级 / L3 部门负责人 / L4 双人+分管），按 applyId 幂等 */
    @PostMapping("/grant")
    public BaiResponse<PermissionCheckDtos.GrantResult> grant(@RequestBody PermissionCheckDtos.GrantRequest request,
                                                              @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(headerId);
        if (!grantLimiter.allow(request == null || request.applicant() == null ? "anonymous" : request.applicant())) {
            throw new BaiException(PermissionCheckErrorCodes.RATE_LIMITED,
                    "PMC_1008 触发限流（授权申请 5 QPS/用户），请按 Retry-After 退避重试");
        }
        return BaiResponse.ok(requestId, service.grant(request, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private String resolveRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(PermissionCheckErrorCodes.PARAM_INVALID,
                    "PMC_1001 参数缺失：requestId 幂等请求号必填");
        }
        return requestId;
    }
}
