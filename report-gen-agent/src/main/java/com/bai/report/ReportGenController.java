package com.bai.report;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报表生成原子接口（权限点 RPG_GENERATE/RPG_BATCH/RPG_SUBMIT/RPG_TEMPLATES；
 * Bearer Token 由网关统一鉴权，Controller 仅强制请求头存在性，缺头由 bai-common 自动 400 PARAM_MISSING）
 */
@RestController
public class ReportGenController {

    private final ReportGenService service;
    /** 限流（条目约定）：generate 30 QPS / batch 10 QPS / submissions 20 QPS */
    private final TokenBuckets generateLimiter = new TokenBuckets(30, 60);
    private final TokenBuckets batchLimiter = new TokenBuckets(10, 20);
    private final TokenBuckets submissionLimiter = new TokenBuckets(20, 40);

    public ReportGenController(ReportGenService service) {
        this.service = service;
    }

    /** 生成报表（模板解析+口径解析+数据绑定/渲染仿真+三校验） */
    @PostMapping("/generate")
    public BaiResponse<ReportGenDtos.GenerateResult> generate(
            @RequestBody ReportGenDtos.GenerateRequest request,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("Authorization") String authorization) {
        if (!generateLimiter.allow(requestId)) {
            throw new BaiException(ReportGenErrorCodes.RATE_LIMITED, "RPG_1007 触发限流（generate 30 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.generate(request, requestId));
    }

    /** 批量生成（≤200 张） */
    @PostMapping("/batch")
    public BaiResponse<ReportGenDtos.BatchResult> batch(
            @RequestBody ReportGenDtos.BatchRequest request,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("Authorization") String authorization) {
        if (!batchLimiter.allow(requestId)) {
            throw new BaiException(ReportGenErrorCodes.RATE_LIMITED, "RPG_1007 触发限流（batch 10 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 报送登记（内存模拟，重报同 reportId 不新增） */
    @PostMapping("/submissions")
    public BaiResponse<ReportGenDtos.SubmissionResult> submissions(
            @RequestBody ReportGenDtos.SubmissionRequest request,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("Authorization") String authorization) {
        if (!submissionLimiter.allow(requestId)) {
            throw new BaiException(ReportGenErrorCodes.RATE_LIMITED, "RPG_1007 触发限流（submissions 20 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.submissions(request, requestId));
    }

    /** 模板库 */
    @GetMapping("/templates")
    public BaiResponse<ReportGenDtos.TemplatesResult> templates(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.templates());
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }
}
