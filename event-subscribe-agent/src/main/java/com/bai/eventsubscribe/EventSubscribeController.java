package com.bai.eventsubscribe;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 事件订阅原子接口（权限点 EVS_TYPE/EVS_SUB/EVS_PUB/EVS_QUERY/EVS_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class EventSubscribeController {

    private final EventSubscribeService service;
    /** 限流：publish 单调用方 500 QPS */
    private final TokenBuckets publishLimiter = new TokenBuckets(500, 500);
    /** 限流：订阅/类型/查询管理类 50 QPS */
    private final TokenBuckets adminLimiter = new TokenBuckets(50, 50);

    public EventSubscribeController(EventSubscribeService service) {
        this.service = service;
    }

    /** 事件发布（异步分发：订阅匹配 → 条件过滤 → 入队，幂等键 requestId+bizKey+eventType） */
    @PostMapping("/publish")
    public BaiResponse<EventSubscribeDtos.PublishResult> publish(@RequestBody Map<String, Object> body,
                                                                 @RequestHeader("X-Request-Id") String requestId,
                                                                 @RequestHeader(value = "Authorization", required = false) String authorization) {
        limit(publishLimiter, requestId,
                "EVS_1007 触发限流（publish 单调用方 500 QPS），请按 Retry-After 退避重试");
        String caller = authorization == null ? "" : authorization.replaceFirst("Bearer\\s+", "");
        return BaiResponse.ok(requestId, service.publish(body, requestId, caller));
    }

    /** 订阅管理（CREATE/CANCEL，condition 表达式 + endpoint 白名单 url/retry/timeoutMs） */
    @PostMapping("/subscribe")
    public BaiResponse<EventSubscribeDtos.SubscribeResult> subscribe(@RequestBody Map<String, Object> body,
                                                                     @RequestHeader("X-Request-Id") String requestId,
                                                                     @RequestHeader(value = "Authorization", required = false) String authorization) {
        limit(adminLimiter, requestId,
                "EVS_1007 触发限流（subscribe 单调用方 50 QPS），请按 Retry-After 退避重试");
        String caller = authorization == null ? "" : authorization.replaceFirst("Bearer\\s+", "");
        return BaiResponse.ok(requestId, service.subscribe(body, requestId, caller));
    }

    /** 事件类型注册（类型冲突拒绝，schema 必填字段声明） */
    @PostMapping("/type")
    public BaiResponse<EventSubscribeDtos.TypeResult> registerType(@RequestBody Map<String, Object> body,
                                                                   @RequestHeader("X-Request-Id") String requestId) {
        limit(adminLimiter, requestId,
                "EVS_1007 触发限流（type 单调用方 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.registerType(body));
    }

    /** 订阅查询（eventType/subscriberId/status 过滤） */
    @GetMapping("/subscriptions")
    public BaiResponse<EventSubscribeDtos.SubscriptionQuery> subscriptions(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String subscriberId,
            @RequestParam(required = false) String status,
            @RequestHeader("X-Request-Id") String requestId) {
        return BaiResponse.ok(requestId, service.querySubscriptions(Map.of(
                "eventType", eventType == null ? "" : eventType,
                "subscriberId", subscriberId == null ? "" : subscriberId,
                "status", status == null ? "" : status)));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(EventSubscribeErrorCodes.RATE_LIMITED, message);
        }
    }
}
