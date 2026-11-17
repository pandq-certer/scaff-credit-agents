package com.bai.rag;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识问答原子接口（权限点 RAG_ANSWER/RAG_BATCH/RAG_EVALUATE/RAG_MODELS/RAG_ADMIN；
 * Bearer Token 由网关统一鉴权，Controller 不校验 Token）
 */
@RestController
public class RagAnswerController {

    private final RagAnswerService service;
    /** 限流（条目约定）：answer 50 QPS / batch 10 QPS / evaluate 20 QPS */
    private final TokenBuckets answerLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets batchLimiter = new TokenBuckets(10, 20);
    private final TokenBuckets evaluateLimiter = new TokenBuckets(20, 40);

    public RagAnswerController(RagAnswerService service) {
        this.service = service;
    }

    /** 同步问答生成（带引用标注，无依据拒绝并引导） */
    @PostMapping("/answer")
    public BaiResponse<RagAnswerDtos.AnswerResult> answer(
            @RequestBody RagAnswerDtos.AnswerRequest request,
            @RequestHeader("X-Request-Id") String requestId) {
        if (!answerLimiter.allow(requestId)) {
            throw new BaiException(RagAnswerErrorCodes.RATE_LIMITED, "RAG_1007 触发限流（answer 50 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.answer(request, requestId));
    }

    /** 批量异步问答（≤10000 条） */
    @PostMapping("/batch")
    public BaiResponse<RagAnswerDtos.BatchResult> batch(
            @RequestBody RagAnswerDtos.BatchRequest request,
            @RequestHeader("X-Request-Id") String requestId) {
        if (!batchLimiter.allow(requestId)) {
            throw new BaiException(RagAnswerErrorCodes.RATE_LIMITED, "RAG_1007 触发限流（batch 10 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 质量评估（忠实度/相关性抽检） */
    @PostMapping("/evaluate")
    public BaiResponse<RagAnswerDtos.EvaluateResult> evaluate(
            @RequestBody RagAnswerDtos.EvaluateRequest request,
            @RequestHeader("X-Request-Id") String requestId) {
        if (!evaluateLimiter.allow(requestId)) {
            throw new BaiException(RagAnswerErrorCodes.RATE_LIMITED, "RAG_1007 触发限流（evaluate 20 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.evaluate(request, requestId));
    }

    /** 模型信息（生成模型与降级策略） */
    @GetMapping("/models")
    public BaiResponse<RagAnswerDtos.ModelsResult> models(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.models());
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }
}
