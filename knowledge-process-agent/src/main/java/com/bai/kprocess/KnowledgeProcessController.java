package com.bai.kprocess;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识加工原子接口（权限点 KNO_PROCESS/KNO_BATCH/KNO_PIPELINE/KNO_VALIDATE/KNO_ADMIN；
 * Bearer Token 由网关统一鉴权，Controller 不校验 Token）
 */
@RestController
public class KnowledgeProcessController {

    private final KnowledgeProcessService service;
    /** 限流（条目约定）：process 20 QPS / batch 5 QPS / validate 20 QPS */
    private final TokenBuckets processLimiter = new TokenBuckets(20, 40);
    private final TokenBuckets batchLimiter = new TokenBuckets(5, 10);
    private final TokenBuckets validateLimiter = new TokenBuckets(20, 40);

    public KnowledgeProcessController(KnowledgeProcessService service) {
        this.service = service;
    }

    /** 同步加工（doc-parse 产物 → 结构化知识） */
    @PostMapping("/process")
    public BaiResponse<KnowledgeProcessDtos.ProcessResult> process(
            @RequestBody KnowledgeProcessDtos.ProcessRequest request,
            @RequestHeader("X-Request-Id") String requestId) {
        if (!processLimiter.allow(requestId)) {
            throw new BaiException(KnowledgeProcessErrorCodes.RATE_LIMITED, "KNO_1007 触发限流（process 20 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.process(request, requestId));
    }

    /** 批量异步加工（≤1000 篇） */
    @PostMapping("/batch")
    public BaiResponse<KnowledgeProcessDtos.BatchResult> batch(
            @RequestBody KnowledgeProcessDtos.BatchRequest request,
            @RequestHeader("X-Request-Id") String requestId) {
        if (!batchLimiter.allow(requestId)) {
            throw new BaiException(KnowledgeProcessErrorCodes.RATE_LIMITED, "KNO_1007 触发限流（batch 5 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 加工流水线配置查询 */
    @GetMapping("/pipeline")
    public BaiResponse<KnowledgeProcessDtos.PipelineInfo> pipeline(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.pipeline());
    }

    /** 质量校验 */
    @PostMapping("/validate")
    public BaiResponse<KnowledgeProcessDtos.ValidateResult> validate(
            @RequestBody KnowledgeProcessDtos.ValidateRequest request,
            @RequestHeader("X-Request-Id") String requestId) {
        if (!validateLimiter.allow(requestId)) {
            throw new BaiException(KnowledgeProcessErrorCodes.RATE_LIMITED, "KNO_1007 触发限流（validate 20 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.validate(request, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }
}
