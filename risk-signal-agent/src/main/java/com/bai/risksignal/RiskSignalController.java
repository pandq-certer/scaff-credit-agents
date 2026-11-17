package com.bai.risksignal;

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
 * 风险信号识别原子接口（权限点 RSG_DETECT/RSG_BATCH/RSG_QUERY/RSG_FEEDBACK/RSG_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class RiskSignalController {

    private final RiskSignalService service;
    /** 限流：detect 20 QPS、batch 10 QPS、query/feedback 50 QPS */
    private final TokenBuckets detectLimiter = new TokenBuckets(20, 20);
    private final TokenBuckets batchLimiter = new TokenBuckets(10, 10);
    private final TokenBuckets queryLimiter = new TokenBuckets(50, 50);

    public RiskSignalController(RiskSignalService service) {
        this.service = service;
    }

    /** 单主体风险检测（同步；五类信号源四级分级+加权聚合） */
    @PostMapping("/detect")
    public BaiResponse<RiskSignalDtos.DetectResult> detect(@RequestBody(required = false) Map<String, Object> body,
                                                           @RequestHeader("X-Request-Id") String requestId) {
        limit(detectLimiter, requestId, "RSG_1008 触发限流（detect 单调用方 20 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(RiskSignalErrorCodes.PARAM_INVALID, "RSG_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.detect(body, requestId));
    }

    /** 批量风险检测（异步；items ≤ 100，超限 RSG_1006） */
    @PostMapping("/batch-detect")
    public BaiResponse<RiskSignalDtos.BatchDetectResult> batchDetect(@RequestBody(required = false) Map<String, Object> body,
                                                                     @RequestHeader("X-Request-Id") String requestId) {
        limit(batchLimiter, requestId, "RSG_1008 触发限流（batch-detect 单调用方 10 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(RiskSignalErrorCodes.PARAM_INVALID, "RSG_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.batchDetect(body, requestId));
    }

    /** 检测记录查询 */
    @GetMapping("/query")
    public BaiResponse<RiskSignalDtos.DetectResult> query(@RequestParam("signalId") String signalId,
                                                          @RequestHeader("X-Request-Id") String requestId) {
        limit(queryLimiter, requestId, "RSG_1008 触发限流（query 单调用方 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.query(signalId));
    }

    /** 人工反馈回流（确认/误报标注） */
    @PostMapping("/feedback")
    public BaiResponse<RiskSignalDtos.FeedbackResult> feedback(@RequestBody(required = false) Map<String, Object> body,
                                                               @RequestHeader("X-Request-Id") String requestId) {
        limit(queryLimiter, requestId, "RSG_1008 触发限流（feedback 单调用方 50 QPS），请按 Retry-After 退避重试");
        if (body == null || body.get("signalId") == null) {
            throw new BaiException(RiskSignalErrorCodes.PARAM_INVALID, "RSG_1001 参数缺失：signalId 必填");
        }
        return BaiResponse.ok(requestId, service.feedback(String.valueOf(body.get("signalId")), body, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(RiskSignalErrorCodes.RATE_LIMITED, message);
        }
    }
}
