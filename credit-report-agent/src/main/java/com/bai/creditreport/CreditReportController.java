package com.bai.creditreport;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 征信管理原子接口（提示词库 A-52：authorize/query/report/dispute/health）。
 * <p>权限点 CRR_AUTH/CRR_QUERY/CRR_REPORT/CRR_DISPUTE/CRR_ADMIN，Bearer Token 由网关统一鉴权；
 * 征信使用严格授权前置，超范围用途拒绝并告警；报告数据脱敏存储；审计 append-only 保留≥5 年。
 */
@RestController
public class CreditReportController {

    private final CreditReportService service;
    /** 限流：单用户 10 QPS（authorize/query）、5 QPS（report/dispute） */
    private final TokenBuckets queryLimiter = new TokenBuckets(10, 20);
    private final TokenBuckets reportLimiter = new TokenBuckets(5, 10);

    public CreditReportController(CreditReportService service) {
        this.service = service;
    }

    /** 授权管理（同步）：action CREATE/VALIDATE/RENEW/EXPIRE */
    @PostMapping("/authorize")
    public BaiResponse<CreditReportDtos.AuthorizeResult> authorize(@RequestBody CreditReportDtos.AuthorizeRequest request,
                                                                   @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(request == null ? null : request.requestId(), headerId);
        limit(queryLimiter, requestId, "10 QPS");
        return BaiResponse.ok(requestId, service.authorize(request, requestId));
    }

    /** 同步报告查询：凭有效授权组解报文 */
    @PostMapping("/query")
    public BaiResponse<CreditReportDtos.ReportResult> query(@RequestBody CreditReportDtos.ReportQueryRequest request,
                                                            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(request == null ? null : request.requestId(), headerId);
        limit(queryLimiter, requestId, "10 QPS");
        return BaiResponse.ok(requestId, service.query(request, requestId));
    }

    /** 异步报告生成/解析 */
    @PostMapping("/report")
    public BaiResponse<CreditReportDtos.ReportTask> report(@RequestBody CreditReportDtos.ReportTaskRequest request,
                                                           @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(request == null ? null : request.requestId(), headerId);
        limit(reportLimiter, requestId, "5 QPS");
        return BaiResponse.ok(requestId, service.report(request, requestId));
    }

    /** 异议受理（异步）：20 日反馈时限跟踪 */
    @PostMapping("/dispute")
    public BaiResponse<CreditReportDtos.DisputeResult> dispute(@RequestBody CreditReportDtos.DisputeRequest request,
                                                               @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(request == null ? null : request.requestId(), headerId);
        limit(reportLimiter, requestId, "5 QPS");
        return BaiResponse.ok(requestId, service.dispute(request, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets limiter, String requestId, String qps) {
        if (!limiter.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(CreditReportErrorCodes.RATE_LIMITED,
                    "CRR_1008 触发限流（单用户 " + qps + "），请按 Retry-After 退避重试");
        }
    }

    private String resolveRequestId(String bodyId, String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : bodyId;
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(CreditReportErrorCodes.PARAM_INVALID, "CRR_1001 参数缺失：requestId 幂等请求号必填");
        }
        return requestId;
    }
}
