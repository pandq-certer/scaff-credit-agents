package com.bai.callexec;

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
 * 调用执行原子接口（权限点 CEX_EXEC/CEX_DEF/CEX_QUERY/CEX_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class CallExecController {

    private final CallExecService service;
    /** 限流：execute 单调用方 300 QPS */
    private final TokenBuckets execLimiter = new TokenBuckets(300, 300);
    /** 限流：定义/历史管理查询类 50 QPS */
    private final TokenBuckets adminLimiter = new TokenBuckets(50, 50);

    public CallExecController(CallExecService service) {
        this.service = service;
    }

    /** 执行调用（同步；幂等键 requestId+definitionId+params 指纹，24h 命中返回原结果） */
    @PostMapping("/execute")
    public BaiResponse<CallExecDtos.CallResult> execute(@RequestBody Map<String, Object> body,
                                                        @RequestHeader("X-Request-Id") String requestId,
                                                        @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                                        @RequestHeader(value = "Authorization", required = false) String authorization) {
        limit(execLimiter, requestId, "CEX_1008 触发限流（execute 单调用方 300 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.execute(body, requestId, traceId, authorization));
    }

    /** 接口定义增删改（CREATE/UPDATE/PUBLISH/OFFLINE） */
    @PostMapping("/definition")
    public BaiResponse<CallExecDtos.DefinitionResult> definition(@RequestBody Map<String, Object> body,
                                                                 @RequestHeader("X-Request-Id") String requestId) {
        limit(adminLimiter, requestId, "CEX_1008 触发限流（definition 单调用方 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.manageDefinition(body));
    }

    /** 定义列表查询（targetService/status 过滤） */
    @GetMapping("/definitions")
    public BaiResponse<List<CallExecDtos.DefinitionInfo>> definitions(
            @RequestParam(required = false) String targetService,
            @RequestParam(required = false) String status,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.queryDefinitions(targetService, status));
    }

    /** 调用历史查询（callId/definitionId/status 过滤） */
    @GetMapping("/history")
    public BaiResponse<CallExecDtos.HistoryQuery> history(
            @RequestParam(required = false) String callId,
            @RequestParam(required = false) String definitionId,
            @RequestParam(required = false) String status,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.queryHistory(callId, definitionId, status));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(CallExecErrorCodes.RATE_LIMITED, message);
        }
    }
}
