package com.bai.compliance;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 合规审查原子接口（权限点 CMP_CHECK/CMP_RULE/CMP_QUERY/CMP_FEEDBACK/CMP_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class ComplianceController {

    private final ComplianceService service;
    /** 限流：check 20 QPS、rule/feedback 20 QPS、query 50 QPS */
    private final TokenBuckets checkLimiter = new TokenBuckets(20, 20);
    private final TokenBuckets adminLimiter = new TokenBuckets(20, 20);
    private final TokenBuckets queryLimiter = new TokenBuckets(50, 50);

    public ComplianceController(ComplianceService service) {
        this.service = service;
    }

    /** 合规审查（同步；任一 RED_LINE 命中即 NON_COMPLIANT 不放行） */
    @PostMapping("/check")
    public BaiResponse<ComplianceDtos.CheckResult> check(@RequestBody(required = false) Map<String, Object> body,
                                                         @RequestHeader("X-Request-Id") String requestId) {
        limit(checkLimiter, requestId, "CMP_1008 触发限流（check 单调用方 20 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(ComplianceErrorCodes.PARAM_INVALID, "CMP_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.check(body, requestId));
    }

    /** 规则入库（异步规则管理；重复 ruleId 返回 CMP_1005） */
    @PostMapping("/rule")
    public BaiResponse<ComplianceDtos.RuleInfo> registerRule(@RequestBody(required = false) Map<String, Object> body,
                                                             @RequestHeader("X-Request-Id") String requestId) {
        limit(adminLimiter, requestId, "CMP_1008 触发限流（rule 单调用方 20 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(ComplianceErrorCodes.PARAM_INVALID, "CMP_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.registerRule(body, requestId));
    }

    /** 审查结论查询 */
    @GetMapping("/query")
    public BaiResponse<ComplianceDtos.CheckResult> query(@RequestParam("checkId") String checkId,
                                                         @RequestHeader("X-Request-Id") String requestId) {
        limit(queryLimiter, requestId, "CMP_1008 触发限流（query 单调用方 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.query(checkId));
    }

    /** 人工反馈回流（异步） */
    @PostMapping("/feedback")
    public BaiResponse<ComplianceDtos.FeedbackResult> feedback(@RequestBody(required = false) Map<String, Object> body,
                                                               @RequestHeader("X-Request-Id") String requestId) {
        limit(adminLimiter, requestId, "CMP_1008 触发限流（feedback 单调用方 20 QPS），请按 Retry-After 退避重试");
        if (body == null || body.get("checkId") == null) {
            throw new BaiException(ComplianceErrorCodes.PARAM_INVALID, "CMP_1001 参数缺失：checkId 必填");
        }
        return BaiResponse.ok(requestId, service.feedback(String.valueOf(body.get("checkId")), body, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(ComplianceErrorCodes.RATE_LIMITED, message);
        }
    }
}
