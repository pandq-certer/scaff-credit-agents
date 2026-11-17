package com.bai.ner;

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
 * 实体抽取原子接口（权限点 NER_EXTRACT/NER_BATCH/NER_TYPES/NER_TASKS/NER_ADMIN/NER_PLAIN_READ；
 * Bearer Token 由网关统一鉴权，Controller 不校验 Token）
 */
@RestController
public class NerController {

    private final NerService service;
    /** 限流（条目约定）：extract 100 QPS / batch 20 QPS */
    private final TokenBuckets extractLimiter = new TokenBuckets(100, 200);
    private final TokenBuckets batchLimiter = new TokenBuckets(20, 40);

    public NerController(NerService service) {
        this.service = service;
    }

    /** 同步单条抽取 */
    @PostMapping("/extract")
    public BaiResponse<NerDtos.NerResult> extract(@RequestBody NerDtos.ExtractRequest request,
                                                  @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId, extractLimiter, "extract 100 QPS");
        return BaiResponse.ok(requestId, service.extract(request, requestId));
    }

    /** 批量抽取（≤100000 条，异步） */
    @PostMapping("/batch")
    public BaiResponse<NerDtos.BatchTask> batch(@RequestBody NerDtos.BatchRequest request,
                                                @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId, batchLimiter, "batch 20 QPS");
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 实体类型体系 */
    @GetMapping("/types")
    public BaiResponse<NerDtos.NerTypes> types(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.types());
    }

    /** 异步任务查询 */
    @GetMapping("/tasks/{taskId}")
    public BaiResponse<NerDtos.BatchTask> getTask(@PathVariable String taskId,
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
            throw new BaiException(NerErrorCodes.RATE_LIMITED, "NER_1007 触发限流（" + detail + "），请按 Retry-After 退避");
        }
    }
}
