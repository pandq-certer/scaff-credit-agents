package com.bai.recommend;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能推荐原子接口（权限点 RCM_RECOMMEND / RCM_QUERY / RCM_FEEDBACK / RCM_STRATEGY / RCM_ADMIN，Bearer Token，由网关统一鉴权）
 */
@RestController
public class RecommendController {

    private final RecommendService service;
    /** 单用户 50 QPS（在线推荐 /items），突发 100，超限 RCM_1008 退避 */
    private final TokenBuckets limiter = new TokenBuckets(50, 100);
    /** 限流（设计文档 4.7）：recommend/query 100 QPS、feedback/strategy 50 QPS，突发翻倍 */
    private final TokenBuckets recommendLimiter = new TokenBuckets(100, 200);
    private final TokenBuckets queryLimiter = new TokenBuckets(100, 200);
    private final TokenBuckets feedbackLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets strategyLimiter = new TokenBuckets(50, 100);

    public RecommendController(RecommendService service) {
        this.service = service;
    }

    /** 推荐列表：按 algo 打分排序，输出 topN（itemId/name/score/reason） */
    @PostMapping("/items")
    public BaiResponse<RecommendDtos.RecommendResult> items(@RequestBody RecommendDtos.RecommendRequest req,
                                                            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(requestId);
        return BaiResponse.ok(requestId, service.items(req, requestId));
    }

    /** 推荐执行（4.2）：场景/上下文/topN 必填，strategyId 可选（缺省用当前生效策略），输出 TopN+理由并留痕 */
    @PostMapping("/recommend")
    public BaiResponse<RecommendDtos.RecommendExecuteResult> recommend(
            @RequestBody RecommendDtos.RecommendExecuteRequest req,
            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(recommendLimiter, requestId, "100 QPS");
        return BaiResponse.ok(requestId, service.recommend(req, requestId));
    }

    /** 推荐查询（4.3）：recommendId 精确或按 scene/customerId/algoUsed 过滤分页，无记录返回 RCM_1004 */
    @GetMapping("/query")
    public BaiResponse<RecommendDtos.RecommendQueryResult> query(
            @RequestParam(value = "recommendId", required = false) String recommendId,
            @RequestParam(value = "scene", required = false) String scene,
            @RequestParam(value = "customerId", required = false) String customerId,
            @RequestParam(value = "algoUsed", required = false) String algoUsed,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(null, headerId);
        limit(queryLimiter, requestId, "100 QPS");
        return BaiResponse.ok(requestId, service.query(
                new RecommendDtos.RecommendQueryRequest(recommendId, scene, customerId, algoUsed, page, size),
                requestId));
    }

    /** 推荐反馈（4.4）：action 为 CLICK|ACCEPT|REJECT|CONVERT，受理返回 feedbackId/status */
    @PostMapping("/feedback")
    public BaiResponse<RecommendDtos.FeedbackResult> feedback(@RequestBody RecommendDtos.FeedbackRequest req,
                                                              @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(feedbackLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.feedback(req, requestId));
    }

    /** 策略管理（4.5）：action 为 CREATE|UPDATE|ACTIVATE|ROLLBACK（兼容 ENABLE|DISABLE），内存存储 */
    @PostMapping("/strategy")
    public BaiResponse<RecommendDtos.StrategyResult> strategy(@RequestBody RecommendDtos.StrategyRequest req,
                                                              @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(strategyLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.strategy(req, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private String resolveRequestId(String bodyId, String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : bodyId;
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(RecommendErrorCodes.PARAM_INVALID, "RCM_1001 参数缺失：requestId 幂等请求号必填");
        }
        return requestId;
    }

    /** 原 /items 限流（单用户 50 QPS） */
    private void limit(String requestId) {
        if (!limiter.allow(requestId)) {
            throw new BaiException(RecommendErrorCodes.RATE_LIMITED,
                    "RCM_1008 触发限流（单用户 50 QPS），请按 Retry-After 退避重试");
        }
    }

    /** 新端点限流（设计文档 4.7）：recommend/query 100 QPS、feedback/strategy 50 QPS */
    private void limit(TokenBuckets bucket, String requestId, String qps) {
        if (!bucket.allow(requestId)) {
            throw new BaiException(RecommendErrorCodes.RATE_LIMITED,
                    "RCM_1008 触发限流（单用户 " + qps + "），请按 Retry-After 退避重试");
        }
    }
}
