package com.bai.auditlog;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 审计留痕原子接口（权限点 ALG_WRITE/ALG_QUERY/ALG_EXPORT/ALG_CHECK/ALG_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class AuditLogController {

    private final AuditLogService service;
    /** 限流：write 单调用方 1000 QPS；查询/导出 100 QPS */
    private final TokenBuckets writeLimiter = new TokenBuckets(1000, 1000);
    private final TokenBuckets queryLimiter = new TokenBuckets(100, 100);

    public AuditLogController(AuditLogService service) {
        this.service = service;
    }

    /** 审计事件写入（单条/批量 ≤1000 条/批，who/what/when/where/result 五元组，哈希链防篡改） */
    @PostMapping("/write")
    public BaiResponse<AuditLogDtos.WriteResult> write(@RequestBody Map<String, Object> body,
                                                       @RequestHeader("X-Request-Id") String requestId,
                                                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        limit(writeLimiter, requestId,
                "ALG_1007 触发限流（write 单调用方 1000 QPS），请按 Retry-After 退避重试");
        String caller = authorization == null ? "anonymous" : authorization.replaceFirst("Bearer\\s+", "");
        Object events = body == null ? null : body.get("events");
        return BaiResponse.ok(requestId, service.write(events instanceof List<?> list ? list : null, requestId, caller));
    }

    /** 审计日志多维查询（操作人/对象/时间/类型，单次 ≤90 天，分页返回） */
    @PostMapping("/query")
    public BaiResponse<AuditLogDtos.QueryResult> query(@RequestBody(required = false) Map<String, Object> body,
                                                       @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        limit(queryLimiter, requestId,
                "ALG_1007 触发限流（query 单调用方 100 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.query(body));
    }

    /** 审计日志导出（CSV，≤100 万条/次，导出操作本身留痕） */
    @PostMapping("/export")
    public BaiResponse<AuditLogDtos.ExportResult> export(@RequestBody(required = false) Map<String, Object> body,
                                                         @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                                                         @RequestHeader(value = "Authorization", required = false) String authorization) {
        limit(queryLimiter, requestId,
                "ALG_1007 触发限流（export 单调用方 100 QPS），请按 Retry-After 退避重试");
        String caller = authorization == null ? "anonymous" : authorization.replaceFirst("Bearer\\s+", "");
        return BaiResponse.ok(requestId, service.export(body, requestId, caller));
    }

    /** 防篡改校验：重算哈希链比对，断链定位 brokenAt */
    @GetMapping("/tamper-check")
    public BaiResponse<AuditLogDtos.TamperCheckResult> tamperCheck(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.tamperCheck());
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(AuditLogErrorCodes.RATE_LIMITED, message);
        }
    }
}
