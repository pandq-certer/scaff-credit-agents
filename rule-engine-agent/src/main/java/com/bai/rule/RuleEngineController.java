package com.bai.rule;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规则引擎原子接口（权限点 RUL_EXECUTE/RUL_BATCH/RUL_TEST/RUL_RULESETS/RUL_ADMIN；
 * Bearer Token 由网关统一鉴权，Controller 不校验 Token）
 */
@RestController
public class RuleEngineController {

    private final RuleEngineService service;
    /** 限流（条目约定）：execute 500 QPS / batch 50 QPS / test 30 QPS */
    private final TokenBuckets executeLimiter = new TokenBuckets(500, 1000);
    private final TokenBuckets batchLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets testLimiter = new TokenBuckets(30, 60);

    public RuleEngineController(RuleEngineService service) {
        this.service = service;
    }

    /** 同步执行（事实+规则集→判定） */
    @PostMapping("/execute")
    public BaiResponse<RuleEngineDtos.RuleResult> execute(
            @RequestBody RuleEngineDtos.ExecuteRequest request,
            @RequestHeader("X-Request-Id") String requestId) {
        if (!executeLimiter.allow(requestId)) {
            throw new BaiException(RuleEngineErrorCodes.RATE_LIMITED, "RUL_1006 触发限流（execute 500 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.execute(request, requestId));
    }

    /** 批量异步执行（≤100000 条） */
    @PostMapping("/batch")
    public BaiResponse<RuleEngineDtos.BatchResult> batch(
            @RequestBody RuleEngineDtos.BatchRequest request,
            @RequestHeader("X-Request-Id") String requestId) {
        if (!batchLimiter.allow(requestId)) {
            throw new BaiException(RuleEngineErrorCodes.RATE_LIMITED, "RUL_1006 触发限流（batch 50 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 规则仿真（不落生产结论） */
    @PostMapping("/test")
    public BaiResponse<RuleEngineDtos.TestResult> test(
            @RequestBody RuleEngineDtos.TestRequest request,
            @RequestHeader("X-Request-Id") String requestId) {
        if (!testLimiter.allow(requestId)) {
            throw new BaiException(RuleEngineErrorCodes.RATE_LIMITED, "RUL_1006 触发限流（test 30 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.test(request, requestId));
    }

    /** 规则集查询（版本/状态/规则清单） */
    @GetMapping("/rulesets")
    public BaiResponse<RuleEngineDtos.RulesetsResult> rulesets(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.rulesets());
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }
}
