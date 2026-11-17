package com.bai.pricing;

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
 * 定价计算原子接口（权限点 PRC_QUOTE / PRC_RULE / PRC_QUERY / PRC_FEEDBACK，Bearer Token，由网关统一鉴权）
 */
@RestController
public class PricingController {

    private final PricingService service;
    /** 限流：单用户 50 QPS（quote/rule/feedback）、100 QPS（query），突发 2 倍，超限 PRC_1008 退避 */
    private final TokenBuckets quoteLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets ruleLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets queryLimiter = new TokenBuckets(100, 200);
    private final TokenBuckets feedbackLimiter = new TokenBuckets(50, 100);

    public PricingController(PricingService service) {
        this.service = service;
    }

    /** 利率试算：finalRate = baseRate + riskPremium + costAddOn */
    @PostMapping("/quote")
    public BaiResponse<PricingDtos.QuoteResult> quote(@RequestBody PricingDtos.QuoteRequest req,
                                                      @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(quoteLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.quote(req, requestId));
    }

    /** 定价规则管理（4.3）：action=CREATE|UPDATE|GRAY|DISABLE */
    @PostMapping("/rule")
    public BaiResponse<PricingDtos.RuleResult> rule(@RequestBody PricingDtos.RuleRequest req,
                                                    @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(ruleLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.rule(req, requestId));
    }

    /** 试算查询（4.4）：?quoteId=&product=&customerId=&page=&size= */
    @GetMapping("/query")
    public BaiResponse<PricingDtos.QueryResult> query(@RequestParam(value = "quoteId", required = false) String quoteId,
                                                      @RequestParam(value = "product", required = false) String product,
                                                      @RequestParam(value = "customerId", required = false) String customerId,
                                                      @RequestParam(value = "page", required = false) Integer page,
                                                      @RequestParam(value = "size", required = false) Integer size,
                                                      @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : "query";
        limit(queryLimiter, requestId, "100 QPS");
        return BaiResponse.ok(requestId,
                service.query(new PricingDtos.QueryRequest(quoteId, product, customerId, page, size), requestId));
    }

    /** 定价反馈（4.5）：humanConclusion=ACCEPT|REJECT|ADJUST，一单一反馈 */
    @PostMapping("/feedback")
    public BaiResponse<PricingDtos.FeedbackResult> feedback(@RequestBody PricingDtos.FeedbackRequest req,
                                                            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(feedbackLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.feedback(req, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private String resolveRequestId(String bodyId, String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : bodyId;
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(PricingErrorCodes.PARAM_INVALID, "PRC_1001 参数缺失：requestId 幂等请求号必填");
        }
        return requestId;
    }

    private void limit(TokenBuckets limiter, String requestId, String qps) {
        if (!limiter.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(PricingErrorCodes.RATE_LIMITED,
                    "PRC_1008 触发限流（单用户 " + qps + "），请按 Retry-After 退避重试");
        }
    }
}
