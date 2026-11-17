package com.bai.graphpenetrate;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 图谱穿透原子接口（权限点 GPT_PENETRATE/GPT_CONTROL/GPT_RELATIONS/GPT_BATCH/GPT_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class GraphPenetrateController {

    private final GraphPenetrateService service;
    /** 限流：penetrate/control 10 QPS、relations 20 QPS、batch 5 QPS */
    private final TokenBuckets penetrateLimiter = new TokenBuckets(10, 10);
    private final TokenBuckets relationsLimiter = new TokenBuckets(20, 20);
    private final TokenBuckets batchLimiter = new TokenBuckets(5, 5);

    public GraphPenetrateController(GraphPenetrateService service) {
        this.service = service;
    }

    /** 股权穿透（同步；直接=边比例、间接=路径乘积、多路径加总，加权≥50% 认定实控人） */
    @PostMapping("/penetrate")
    public BaiResponse<GraphPenetrateDtos.PenetrateResult> penetrate(@RequestBody(required = false) Map<String, Object> body,
                                                                     @RequestHeader("X-Request-Id") String requestId) {
        limit(penetrateLimiter, requestId, "GPT_1008 触发限流（penetrate 单调用方 10 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(GraphPenetrateErrorCodes.PARAM_INVALID, "GPT_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.penetrate(body, requestId));
    }

    /** 实控人认定（同步；一致行动人/控制链补充认定） */
    @PostMapping("/control")
    public BaiResponse<GraphPenetrateDtos.ControlResult> control(@RequestBody(required = false) Map<String, Object> body,
                                                                 @RequestHeader("X-Request-Id") String requestId) {
        limit(penetrateLimiter, requestId, "GPT_1008 触发限流（control 单调用方 10 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(GraphPenetrateErrorCodes.PARAM_INVALID, "GPT_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.control(body, requestId));
    }

    /** 关系查询（同步；担保链/资金链/亲属/任职/关联交易方） */
    @PostMapping("/relations")
    public BaiResponse<GraphPenetrateDtos.RelationsResult> relations(@RequestBody(required = false) Map<String, Object> body,
                                                                     @RequestHeader("X-Request-Id") String requestId) {
        limit(relationsLimiter, requestId, "GPT_1008 触发限流（relations 单调用方 20 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(GraphPenetrateErrorCodes.PARAM_INVALID, "GPT_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.relations(body, requestId));
    }

    /** 批量穿透（异步；items ≤ 50，超限 GPT_1006） */
    @PostMapping("/batch")
    public BaiResponse<GraphPenetrateDtos.BatchResult> batch(@RequestBody(required = false) Map<String, Object> body,
                                                             @RequestHeader("X-Request-Id") String requestId) {
        limit(batchLimiter, requestId, "GPT_1008 触发限流（batch 单调用方 5 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(GraphPenetrateErrorCodes.PARAM_INVALID, "GPT_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.batch(body, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(GraphPenetrateErrorCodes.RATE_LIMITED, message);
        }
    }
}
