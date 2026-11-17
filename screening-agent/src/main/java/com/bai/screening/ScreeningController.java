package com.bai.screening;

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
 * 名单筛查原子接口（权限点 SCR_SCREEN/SCR_LIST/SCR_QUERY/SCR_FEEDBACK/SCR_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class ScreeningController {

    private final ScreeningService service;
    /** 限流：screen 100 QPS、list 20 QPS、query/feedback 50 QPS */
    private final TokenBuckets screenLimiter = new TokenBuckets(100, 100);
    private final TokenBuckets listLimiter = new TokenBuckets(20, 20);
    private final TokenBuckets queryLimiter = new TokenBuckets(50, 50);

    public ScreeningController(ScreeningService service) {
        this.service = service;
    }

    /** 名单筛查（同步；三模匹配，命中不静默放行） */
    @PostMapping("/screen")
    public BaiResponse<ScreeningDtos.ScreenResult> screen(@RequestBody(required = false) Map<String, Object> body,
                                                          @RequestHeader("X-Request-Id") String requestId) {
        limit(screenLimiter, requestId, "SCR_1008 触发限流（screen 单调用方 100 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(ScreeningErrorCodes.PARAM_INVALID, "SCR_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.screen(body, requestId));
    }

    /** 名单入库（异步；重复条目返回 SCR_1005） */
    @PostMapping("/list")
    public BaiResponse<ScreeningDtos.ListEntry> registerList(@RequestBody(required = false) Map<String, Object> body,
                                                             @RequestHeader("X-Request-Id") String requestId) {
        limit(listLimiter, requestId, "SCR_1008 触发限流（list 单调用方 20 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(ScreeningErrorCodes.PARAM_INVALID, "SCR_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.registerList(body, requestId));
    }

    /** 筛查记录查询 */
    @GetMapping("/query")
    public BaiResponse<ScreeningDtos.ScreenResult> query(@RequestParam("screenId") String screenId,
                                                         @RequestHeader("X-Request-Id") String requestId) {
        limit(queryLimiter, requestId, "SCR_1008 触发限流（query 单调用方 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.query(screenId));
    }

    /** 人工反馈回流（异步） */
    @PostMapping("/feedback")
    public BaiResponse<ScreeningDtos.FeedbackResult> feedback(@RequestBody(required = false) Map<String, Object> body,
                                                              @RequestHeader("X-Request-Id") String requestId) {
        limit(queryLimiter, requestId, "SCR_1008 触发限流（feedback 单调用方 50 QPS），请按 Retry-After 退避重试");
        if (body == null || body.get("screenId") == null) {
            throw new BaiException(ScreeningErrorCodes.PARAM_INVALID, "SCR_1001 参数缺失：screenId 必填");
        }
        return BaiResponse.ok(requestId, service.feedback(String.valueOf(body.get("screenId")), body, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(ScreeningErrorCodes.RATE_LIMITED, message);
        }
    }
}
