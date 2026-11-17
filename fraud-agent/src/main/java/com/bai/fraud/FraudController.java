package com.bai.fraud;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 反欺诈原子接口（权限点 FRD_CHECK/FRD_BATCH/FRD_EXPLAIN/FRD_FEEDBACK/FRD_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class FraudController {

    private final FraudService service;
    /** 限流：check 单用户 50 QPS、explain 20 QPS */
    private final TokenBuckets checkLimiter = new TokenBuckets(50, 50);
    private final TokenBuckets explainLimiter = new TokenBuckets(20, 20);

    public FraudController(FraudService service) {
        this.service = service;
    }

    /** 同步实时决策（规则→图→模型级联融合，P99≤100ms） */
    @PostMapping("/check")
    public BaiResponse<FraudDtos.CheckResult> check(@RequestBody Map<String, Object> body,
                                                    @RequestHeader("X-Request-Id") String requestId) {
        limit(checkLimiter, requestId, "FRD_1008 触发限流（check 单用户 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.check(body, requestId));
    }

    /** 异步批量筛查（单批 ≤100 条） */
    @PostMapping("/batch-check")
    public BaiResponse<FraudDtos.BatchResult> batchCheck(@RequestBody Map<String, Object> body,
                                                         @RequestHeader("X-Request-Id") String requestId) {
        limit(checkLimiter, requestId, "FRD_1008 触发限流（batch-check 单用户 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.batchCheck(body, requestId));
    }

    /** 同步决策解释（命中明细 + 特征贡献 + 版本快照） */
    @PostMapping("/explain")
    public BaiResponse<FraudDtos.ExplainResult> explain(@RequestBody Map<String, Object> body,
                                                        @RequestHeader("X-Request-Id") String requestId) {
        limit(explainLimiter, requestId, "FRD_1008 触发限流（explain 单用户 20 QPS），请按 Retry-After 退避重试");
        if (body == null || isBlank(body.get("caseId"))) {
            throw new BaiException(FraudErrorCodes.PARAM_INVALID, "FRD_1001 参数缺失：caseId 必填");
        }
        return BaiResponse.ok(requestId, service.explain(str(body.get("caseId"))));
    }

    /** 异步样本回流（人工标注 FRAUD/NORMAL，按月增量训练） */
    @PostMapping("/feedback")
    public BaiResponse<FraudDtos.FeedbackResult> feedback(@RequestBody Map<String, Object> body,
                                                          @RequestHeader("X-Request-Id") String requestId) {
        limit(explainLimiter, requestId, "FRD_1008 触发限流（feedback 单用户 20 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.feedback(body));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(FraudErrorCodes.RATE_LIMITED, message);
        }
    }

    private static boolean isBlank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
