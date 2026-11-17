package com.bai.classify;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文本分类原子接口（权限点 TXC_CLASSIFY/TXC_BATCH/TXC_SCHEMAS/TXC_TASKS/TXC_ADMIN；
 * Bearer Token 由网关统一鉴权，Controller 不校验 Token）
 */
@RestController
public class ClassifyController {

    private final ClassifyService service;
    /** 限流（条目约定）：classify 100 QPS / batch 20 QPS */
    private final TokenBuckets classifyLimiter = new TokenBuckets(100, 200);
    private final TokenBuckets batchLimiter = new TokenBuckets(20, 40);

    public ClassifyController(ClassifyService service) {
        this.service = service;
    }

    /** 同步单条分类 */
    @PostMapping("/classify")
    public BaiResponse<ClassifyDtos.ClassifyResult> classify(@RequestBody ClassifyDtos.ClassifyRequest request,
                                                             @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId, classifyLimiter, "classify 100 QPS");
        return BaiResponse.ok(requestId, service.classify(request, requestId));
    }

    /** 批量分类（≤100000 条，异步） */
    @PostMapping("/batch")
    public BaiResponse<ClassifyDtos.ClassifyTask> batch(@RequestBody ClassifyDtos.BatchRequest request,
                                                        @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId, batchLimiter, "batch 20 QPS");
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 分类体系（六类任务） */
    @GetMapping("/schemas")
    public BaiResponse<ClassifyDtos.ClassifySchemas> schemas(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.schemas());
    }

    /** 异步任务查询 */
    @GetMapping("/tasks/{taskId}")
    public BaiResponse<ClassifyDtos.ClassifyTask> getTask(@PathVariable String taskId,
                                                          @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.getTask(taskId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(String requestId, TokenBuckets limiter, String detail) {
        if (!limiter.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(ClassifyErrorCodes.RATE_LIMITED, "TXC_1007 触发限流（" + detail + "），请按 Retry-After 退避");
        }
    }
}
