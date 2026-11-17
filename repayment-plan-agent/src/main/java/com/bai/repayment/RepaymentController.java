package com.bai.repayment;

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
 * 还款计划原子接口（权限点 RPP_PLAN / RPP_RECALC / RPP_QUERY / RPP_PENALTY，Bearer Token，由网关统一鉴权）
 */
@RestController
public class RepaymentController {

    private final RepaymentService service;
    /** 限流：单用户 100 QPS（计划生成/计划重算/计划查询/罚息试算），突发 200，超限 RPP_1008 退避 */
    private final TokenBuckets limiter = new TokenBuckets(100, 200);
    private final TokenBuckets recalcLimiter = new TokenBuckets(100, 200);
    private final TokenBuckets queryLimiter = new TokenBuckets(100, 200);
    private final TokenBuckets penaltyLimiter = new TokenBuckets(100, 200);

    public RepaymentController(RepaymentService service) {
        this.service = service;
    }

    /** 计划生成/重算：四种还款法 + PREPAY/RATE_ADJUST/EXTEND/REFINANCE 四类重算 */
    @PostMapping("/plan")
    public BaiResponse<RepaymentDtos.PlanResult> plan(@RequestBody RepaymentDtos.PlanRequest req,
                                                      @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(limiter, requestId, "100 QPS");
        return BaiResponse.ok(requestId, service.plan(req, requestId));
    }

    /** 计划重算（4.3，异步）：基于已生成计划 planId 按四类重算，触发要素经 trigger 传入 */
    @PostMapping("/recalculate")
    public BaiResponse<RepaymentDtos.RecalcResult> recalculate(@RequestBody RepaymentDtos.RecalcRequest req,
                                                               @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(recalcLimiter, requestId, "100 QPS");
        return BaiResponse.ok(requestId, service.recalculate(req, requestId));
    }

    /** 计划查询（4.4，同步）：按 planId/contractId（可叠加 method/status，分页）检索历史生成计划，无记录 RPP_1004 */
    @GetMapping("/query")
    public BaiResponse<RepaymentDtos.PlanQueryResult> query(@RequestParam(required = false) String planId,
                                                            @RequestParam(required = false) String contractId,
                                                            @RequestParam(required = false) String method,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(required = false) Integer page,
                                                            @RequestParam(required = false) Integer size,
                                                            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(null, headerId);
        limit(queryLimiter, requestId, "100 QPS");
        RepaymentDtos.PlanQueryRequest q = new RepaymentDtos.PlanQueryRequest(planId, contractId, method, status, page, size);
        return BaiResponse.ok(requestId, service.query(q, requestId));
    }

    /** 罚息试算（4.5，异步）：罚息/复利按日计算，响应逐日明细 */
    @PostMapping("/penalty")
    public BaiResponse<RepaymentDtos.PenaltyResult> penalty(@RequestBody RepaymentDtos.PenaltyRequest req,
                                                            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(penaltyLimiter, requestId, "100 QPS");
        return BaiResponse.ok(requestId, service.penalty(req, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private String resolveRequestId(String bodyId, String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : bodyId;
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(RepaymentErrorCodes.PARAM_INVALID, "RPP_1001 参数缺失：requestId 幂等请求号必填");
        }
        return requestId;
    }

    private void limit(TokenBuckets bucket, String requestId, String qps) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(RepaymentErrorCodes.RATE_LIMITED,
                    "RPP_1008 触发限流（单用户 " + qps + "），请按 Retry-After 退避重试");
        }
    }
}
