package com.bai.structuregen;

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
 * 结构化生成原子接口（权限点 STG_GEN/STG_SCHEMA/STG_QUERY/STG_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class StructureGenController {

    private final StructureGenService service;
    /** 限流：generate 50 QPS、Schema 管理与查询 50 QPS */
    private final TokenBuckets genLimiter = new TokenBuckets(50, 50);
    private final TokenBuckets adminLimiter = new TokenBuckets(50, 50);
    private final TokenBuckets queryLimiter = new TokenBuckets(50, 50);

    public StructureGenController(StructureGenService service) {
        this.service = service;
    }

    /** 结构化生成（同步；语义抽取+字段映射+校验组装） */
    @PostMapping("/generate")
    public BaiResponse<StructureGenDtos.GenerateResult> generate(@RequestBody(required = false) Map<String, Object> body,
                                                                 @RequestHeader("X-Request-Id") String requestId) {
        limit(genLimiter, requestId, "STG_1008 触发限流（generate 单调用方 50 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(StructureGenErrorCodes.PARAM_INVALID, "STG_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.generate(body, requestId));
    }

    /** Schema 管理（注册/发布；名或版本冲突返回 STG_1005） */
    @PostMapping("/schema")
    public BaiResponse<StructureGenDtos.SchemaInfo> registerSchema(@RequestBody(required = false) Map<String, Object> body,
                                                                   @RequestHeader("X-Request-Id") String requestId) {
        limit(adminLimiter, requestId, "STG_1008 触发限流（schema 单调用方 50 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(StructureGenErrorCodes.PARAM_INVALID, "STG_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.registerSchema(body, requestId));
    }

    /** Schema 查询 */
    @GetMapping("/schemas")
    public BaiResponse<List<StructureGenDtos.SchemaInfo>> schemas(@RequestHeader("X-Request-Id") String requestId) {
        limit(queryLimiter, requestId, "STG_1008 触发限流（schemas 单调用方 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.schemas());
    }

    /** 生成历史查询（requestId 可选过滤） */
    @GetMapping("/history")
    public BaiResponse<List<StructureGenDtos.HistoryItem>> history(
            @RequestParam(value = "requestId", required = false) String requestId,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
            @RequestHeader("X-Request-Id") String requestIdHeader) {
        limit(queryLimiter, requestIdHeader, "STG_1008 触发限流（history 单调用方 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestIdHeader, service.history(requestId, limit));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(StructureGenErrorCodes.RATE_LIMITED, message);
        }
    }
}
