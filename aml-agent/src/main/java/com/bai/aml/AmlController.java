package com.bai.aml;

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
 * 反洗钱原子接口（权限点 AML_MONITOR/AML_REPORT/AML_QUERY/AML_FEEDBACK/AML_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class AmlController {

    private final AmlService service;
    /** 限流：monitor 单用户 50 QPS、report 20 QPS */
    private final TokenBuckets monitorLimiter = new TokenBuckets(50, 50);
    private final TokenBuckets reportLimiter = new TokenBuckets(20, 20);

    public AmlController(AmlService service) {
        this.service = service;
    }

    /** 窗口监测（大额/频繁/拆分/快进快出四类模式 + 模型评分 + 结论判定） */
    @PostMapping("/monitor")
    public BaiResponse<AmlDtos.MonitorResult> monitor(@RequestBody Map<String, Object> body,
                                                      @RequestHeader("X-Request-Id") String requestId) {
        limit(monitorLimiter, requestId, "AML_1008 触发限流（monitor 单用户 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.monitor(body, requestId));
    }

    /** 报告生成（SAR 草稿/大额报告，人工复核后上报） */
    @PostMapping("/report")
    public BaiResponse<AmlDtos.ReportResult> report(@RequestBody Map<String, Object> body,
                                                    @RequestHeader("X-Request-Id") String requestId) {
        limit(reportLimiter, requestId, "AML_1008 触发限流（report 单用户 20 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.report(body));
    }

    /** 监测记录查询（monitorId/requestId/suspicious 过滤） */
    @GetMapping("/query")
    public BaiResponse<List<AmlDtos.MonitorRecord>> query(
            @RequestParam(required = false) String monitorId,
            @RequestParam(required = false) String reqRequestId,
            @RequestParam(required = false) String suspicious,
            @RequestHeader("X-Request-Id") String requestId) {
        return BaiResponse.ok(requestId, service.query(monitorId, reqRequestId, suspicious));
    }

    /** 人工结论回流（SUSPICIOUS/NORMAL） */
    @PostMapping("/feedback")
    public BaiResponse<AmlDtos.FeedbackResult> feedback(@RequestBody Map<String, Object> body,
                                                        @RequestHeader("X-Request-Id") String requestId) {
        limit(reportLimiter, requestId, "AML_1008 触发限流（feedback 单用户 20 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.feedback(body));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(AmlErrorCodes.RATE_LIMITED, message);
        }
    }
}
