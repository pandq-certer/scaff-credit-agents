package com.bai.valuation;

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
 * 资产估值原子接口（权限点 VAL_VALUE / VAL_QUERY / VAL_FEEDBACK / VAL_MARKET，Bearer Token，由网关统一鉴权）
 */
@RestController
public class ValuationController {

    private final ValuationService service;
    /** 单用户 50 QPS（value），突发 100，超限 VAL_1008 退避 */
    private final TokenBuckets limiter = new TokenBuckets(50, 100);
    /** 单用户 100 QPS（query），突发 200 */
    private final TokenBuckets queryLimiter = new TokenBuckets(100, 200);
    /** 单用户 50 QPS（feedback），突发 100 */
    private final TokenBuckets feedbackLimiter = new TokenBuckets(50, 100);
    /** 单用户 50 QPS（market），突发 100 */
    private final TokenBuckets marketLimiter = new TokenBuckets(50, 100);

    public ValuationController(ValuationService service) {
        this.service = service;
    }

    /** 估值计算：市场法/收益法/成本法，输出 value、range(low, high) 与结论 */
    @PostMapping("/value")
    public BaiResponse<ValuationDtos.ValuationResult> value(@RequestBody ValuationDtos.ValuationRequest req,
                                                            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(requestId);
        return BaiResponse.ok(requestId, service.value(req, requestId));
    }

    /** 估值查询（4.3）：?valueId= 或按押品/资产（assetId/assetType）查历史估值记录，分页返回 */
    @GetMapping("/query")
    public BaiResponse<ValuationDtos.ValuationQueryResult> query(
            @RequestParam(value = "valueId", required = false) String valueId,
            @RequestParam(value = "assetId", required = false) String assetId,
            @RequestParam(value = "assetType", required = false) String assetType,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size,
            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(null, headerId);
        limit(queryLimiter, requestId, "VAL_1008 触发限流（query 单用户 100 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.query(valueId, assetId, assetType, page, size, requestId));
    }

    /** 估值反馈（4.4）：人工结论回流（ACCEPT|ADJUST|REJECT），受理返回 feedbackId+status */
    @PostMapping("/feedback")
    public BaiResponse<ValuationDtos.FeedbackResult> feedback(@RequestBody ValuationDtos.FeedbackRequest req,
                                                              @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(feedbackLimiter, requestId, "VAL_1008 触发限流（feedback 单用户 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.feedback(req, requestId));
    }

    /** 市场数据（4.5）：action=REFRESH|QUERY|UPDATE 接入/查询价格指数与成交均价，返回数据版本与状态 */
    @PostMapping("/market")
    public BaiResponse<ValuationDtos.MarketResult> market(@RequestBody ValuationDtos.MarketRequest req,
                                                          @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(marketLimiter, requestId, "VAL_1008 触发限流（market 单用户 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.market(req, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private String resolveRequestId(String bodyId, String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : bodyId;
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(ValuationErrorCodes.PARAM_INVALID, "VAL_1001 参数缺失：requestId 幂等请求号必填");
        }
        return requestId;
    }

    private void limit(String requestId) {
        if (!limiter.allow(requestId)) {
            throw new BaiException(ValuationErrorCodes.RATE_LIMITED,
                    "VAL_1008 触发限流（单用户 50 QPS），请按 Retry-After 退避重试");
        }
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(ValuationErrorCodes.RATE_LIMITED, message);
        }
    }
}
