package com.bai.bill;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 票据业务原子接口（权限点 BIL_VERIFY/BIL_ACCEPT/BIL_DISCOUNT/BIL_PAY/BIL_POOL/BIL_ENDORSE/BIL_PLEDGE/BIL_RATE/BIL_QUERY，
 * Bearer Token，由网关统一鉴权）
 */
@RestController
public class BillController {

    private final BillService service;
    /** 单用户 200 QPS（discount），突发 400，超限 BILL_1008 退避 */
    private final TokenBuckets limiter = new TokenBuckets(200, 400);
    /** 各接口独立限流：单用户 50 QPS，突发 100，超限 BILL_1008 退避 */
    private final TokenBuckets verifyLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets acceptLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets payLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets poolLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets endorseLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets pledgeLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets rateLimiter = new TokenBuckets(50, 100);

    public BillController(BillService service) {
        this.service = service;
    }

    /** 票据贴现：票交所核验 → 剩余天数/贴现利息/实付计算 → 90% 下限校验 */
    @PostMapping("/discount")
    public BaiResponse<BillDtos.DiscountResult> discount(@RequestBody BillDtos.DiscountRequest req,
                                                         @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(requestId);
        return BaiResponse.ok(requestId, service.discount(req, requestId));
    }

    /** 票据验真（4.2）：票交所/防伪/背书链/限制性多通道核验，任一通道 FAIL 整体 FAIL */
    @PostMapping("/verify")
    public BaiResponse<BillDtos.VerifyResult> verify(@RequestBody BillDtos.VerifyRequest req,
                                                     @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(verifyLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.verify(req, requestId));
    }

    /** 承兑管理（4.3）：保证金核定（按承兑人评级查表）→ 敞口计算 → 敞口限额校验，超限 BILL_4103 阻断 */
    @PostMapping("/accept")
    public BaiResponse<BillDtos.AcceptResult> accept(@RequestBody BillDtos.AcceptRequest req,
                                                     @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(acceptLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.accept(req, requestId));
    }

    /** 兑付管理（4.5）：到期判定 → 余额检查 → PAID/OVERDUE/ADVANCE（垫款登记 + 追索任务） */
    @PostMapping("/pay")
    public BaiResponse<BillDtos.PayResult> pay(@RequestBody BillDtos.PayRequest req,
                                               @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(payLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.pay(req, requestId));
    }

    /** 票据池管理（4.6）：IN_POOL/PLEDGE/SWAP/COLLECT，质押额度 = Σ(票面×质押率)，换票差额校验阻断 */
    @PostMapping("/pool")
    public BaiResponse<BillDtos.PoolResult> pool(@RequestBody BillDtos.PoolRequest req,
                                                 @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(poolLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.pool(req, requestId));
    }

    /** 背书审查（4.7）：背书链连续性校验 + 限制性背书核查，输出瑕疵清单与 PASS/REJECT/REVIEW 结论 */
    @PostMapping("/endorse-check")
    public BaiResponse<BillDtos.EndorseCheckResult> endorseCheck(@RequestBody BillDtos.EndorseCheckRequest req,
                                                                 @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(endorseLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.endorseCheck(req, requestId));
    }

    /** 票据质押（4.8）：质押要素校验 → 质押率校验（超限 BILL_4103 阻断）→ 盯市与处置建议 */
    @PostMapping("/pledge")
    public BaiResponse<BillDtos.PledgeResult> pledge(@RequestBody BillDtos.PledgeRequest req,
                                                     @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(pledgeLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.pledge(req, requestId));
    }

    /** 贴现利率定价（4.9）：r = 市场基准 + 客户溢价 + 承兑人溢价 + 期限溢价（年化%），版本化可复算 */
    @PostMapping("/rate")
    public BaiResponse<BillDtos.RateResult> rate(@RequestBody BillDtos.RateRequest req,
                                                 @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(rateLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.rate(req, requestId));
    }

    /** 票据登记信息查询（内存票据库，仿真用于联调） */
    @GetMapping("/bills/{billNo}")
    public BaiResponse<BillDtos.BillInfo> bill(@PathVariable String billNo,
                                               @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : "bill-query";
        return BaiResponse.ok(requestId, service.bill(billNo));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private String resolveRequestId(String bodyId, String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : bodyId;
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(BillErrorCodes.PARAM_INVALID, "BILL_1001 参数缺失：requestId 幂等请求号必填");
        }
        return requestId;
    }

    private void limit(String requestId) {
        if (!limiter.allow(requestId)) {
            throw new BaiException(BillErrorCodes.RATE_LIMITED,
                    "BILL_1008 触发限流（单用户 200 QPS），请按 Retry-After 退避重试");
        }
    }

    private void limit(TokenBuckets bucket, String requestId, String qps) {
        if (!bucket.allow(requestId)) {
            throw new BaiException(BillErrorCodes.RATE_LIMITED,
                    "BILL_1008 触发限流（单用户 " + qps + "），请按 Retry-After 退避重试");
        }
    }
}
