package com.bai.deriv;

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
 * 衍生品定价原子接口（权限点 DRV_PRICE，Bearer Token，由网关统一鉴权）
 */
@RestController
public class DerivController {

    private final DerivService service;
    /** 单用户 50 QPS（price），突发 100，超限 DERIV_1008 退避 */
    private final TokenBuckets limiter = new TokenBuckets(50, 100);
    /** 单用户 50 QPS（mark/hedge/curve/vol/effectiveness），突发 100 */
    private final TokenBuckets markLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets hedgeLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets curveLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets volLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets effectivenessLimiter = new TokenBuckets(50, 100);
    /** 单用户 100 QPS（exposure），突发 200 */
    private final TokenBuckets exposureLimiter = new TokenBuckets(100, 200);

    public DerivController(DerivService service) {
        this.service = service;
    }

    /** 衍生品定价（4.2）：IRS/FORWARD/CDS/OPTION 的 NPV、希腊字母、CVA 与市场数据状态标记 */
    @PostMapping("/pricing")
    public BaiResponse<DerivDtos.PriceResult> price(@RequestBody DerivDtos.PriceRequest req,
                                                    @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(requestId);
        return BaiResponse.ok(requestId, service.price(req, requestId));
    }

    /** 每日盯市估值（4.3）：SINGLE/BATCH 持仓盯市，输出市值/损益/告警与对账摘要，批量任务以 batchNo 幂等 */
    @PostMapping("/mark")
    public BaiResponse<DerivDtos.MarkResult> mark(@RequestBody DerivDtos.MarkRequest req,
                                                  @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(markLimiter, requestId, "DERIV_1008 触发限流（mark 单用户 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.mark(req, requestId));
    }

    /** 对冲策略计算（4.4）：按风险因子输出对冲比例/头寸建议/Delta 与残余风险，附套保关系认定结论 */
    @PostMapping("/hedge")
    public BaiResponse<DerivDtos.HedgeResult> hedge(@RequestBody DerivDtos.HedgeRequest req,
                                                    @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(hedgeLimiter, requestId, "DERIV_1008 触发限流（hedge 单用户 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.hedge(req, requestId));
    }

    /** 利率曲线构建与发布（4.5）：Bootstrapping 输出期限结构，质量检查不通过阻止发布（DERIV_4103） */
    @PostMapping("/curve")
    public BaiResponse<DerivDtos.CurveResult> curve(@RequestBody DerivDtos.CurveRequest req,
                                                    @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(curveLimiter, requestId, "DERIV_1008 触发限流（curve 单用户 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.curve(req, requestId));
    }

    /** 波动率曲面构建与发布（4.6）：SVI/SPLINE 网格化，无套利检查不通过阻止发布（DERIV_4103） */
    @PostMapping("/vol")
    public BaiResponse<DerivDtos.VolResult> vol(@RequestBody DerivDtos.VolRequest req,
                                                @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(volLimiter, requestId, "DERIV_1008 触发限流（vol 单用户 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.vol(req, requestId));
    }

    /** 敞口查询（4.7）：?portfolioId=/counterpartyId= 或按 asOfDate 汇总盯市留痕，返回当前敞口/Delta/VaR/PFE/CVA，无记录 DERIV_1004 */
    @GetMapping("/exposure")
    public BaiResponse<DerivDtos.ExposureResult> exposure(
            @RequestParam(value = "portfolioId", required = false) String portfolioId,
            @RequestParam(value = "counterpartyId", required = false) String counterpartyId,
            @RequestParam(value = "currency", required = false) String currency,
            @RequestParam(value = "asOfDate", required = false) String asOfDate,
            @RequestParam(value = "confidenceLevel", required = false) String confidenceLevel,
            @RequestParam(value = "includeCva", required = false) Boolean includeCva,
            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(null, headerId);
        limit(exposureLimiter, requestId, "DERIV_1008 触发限流（exposure 单用户 100 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.exposure(portfolioId, counterpartyId, currency, asOfDate,
                confidenceLevel, includeCva, requestId));
    }

    /** 套期有效性评估（4.8）：回溯/前瞻两法，ratio 落入 80%~125% 判定 EFFECTIVE */
    @PostMapping("/effectiveness")
    public BaiResponse<DerivDtos.EffectivenessResult> effectiveness(@RequestBody DerivDtos.EffectivenessRequest req,
                                                                    @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(effectivenessLimiter, requestId, "DERIV_1008 触发限流（effectiveness 单用户 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.effectiveness(req, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private String resolveRequestId(String bodyId, String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : bodyId;
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：requestId 幂等请求号必填");
        }
        return requestId;
    }

    private void limit(String requestId) {
        if (!limiter.allow(requestId)) {
            throw new BaiException(DerivErrorCodes.RATE_LIMITED,
                    "DERIV_1008 触发限流（单用户 50 QPS），请按 Retry-After 退避重试");
        }
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(DerivErrorCodes.RATE_LIMITED, message);
        }
    }
}
