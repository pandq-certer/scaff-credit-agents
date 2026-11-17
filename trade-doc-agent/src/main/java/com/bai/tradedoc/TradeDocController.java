package com.bai.tradedoc;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 贸易单证原子接口（权限点 TRD_CHECK/TRD_CONSISTENCY/TRD_DISCREPANCY/TRD_LC/TRD_FORFEIT/TRD_FINANCE，
 * Bearer Token，由网关统一鉴权）
 */
@RestController
public class TradeDocController {

    private final TradeDocService service;
    /** 限流：单用户 200 QPS（check）、50 QPS（consistency/discrepancy/lc/forfeit/trade-finance），
     * 突发翻倍，超限 TRD_1008 退避 */
    private final TokenBuckets limiter = new TokenBuckets(200, 400);
    private final TokenBuckets consistencyLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets discrepancyLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets lcLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets forfeitLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets tradeFinanceLimiter = new TokenBuckets(50, 100);

    public TradeDocController(TradeDocService service) {
        this.service = service;
    }

    /** 单证审核：单单相符/单证相符校验 → 不符点清单 → FORFAITING 买断价试算 */
    @PostMapping("/check")
    public BaiResponse<TradeDocDtos.CheckResult> check(@RequestBody TradeDocDtos.CheckRequest req,
                                                       @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(limiter, requestId, "200 QPS");
        return BaiResponse.ok(requestId, service.check(req, requestId));
    }

    /** 单单/单证相符校验（4.3）：docToDoc 单据间比对（pairs[]）+ docToLc 条款比对（clauseHits[]）+ 容差适用记录 */
    @PostMapping("/consistency")
    public BaiResponse<TradeDocDtos.ConsistencyResult> consistency(@RequestBody TradeDocDtos.ConsistencyRequest req,
                                                                   @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(consistencyLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.consistency(req, requestId));
    }

    /** 不符点清单生成（4.4）：差异记录归类分级，按 UCP600 16 条组织拒付通知草稿 */
    @PostMapping("/discrepancy")
    public BaiResponse<TradeDocDtos.DiscrepancyGenResult> discrepancy(@RequestBody TradeDocDtos.DiscrepancyRequest req,
                                                                      @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(discrepancyLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.discrepancy(req, requestId));
    }

    /** 信用证处理（4.5）：开证条款检查/通知登记/议付/出口押汇/进口押汇 */
    @PostMapping("/lc")
    public BaiResponse<TradeDocDtos.LcResult> lc(@RequestBody TradeDocDtos.LcRequest req,
                                                 @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(lcLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.lc(req, requestId));
    }

    /** 福费廷买断审核（4.6）：可买断性/贴现利息/买断价/无追索权条件确认/承诺函 */
    @PostMapping("/forfeit")
    public BaiResponse<TradeDocDtos.ForfeitResult> forfeit(@RequestBody TradeDocDtos.ForfeitRequest req,
                                                           @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(forfeitLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.forfeit(req, requestId));
    }

    /** 贸易融资单据核验（4.7）：打包贷款/出口押汇/进口押汇单据核验与可融金额 */
    @PostMapping("/trade-finance")
    public BaiResponse<TradeDocDtos.TradeFinanceResult> tradeFinance(@RequestBody TradeDocDtos.TradeFinanceRequest req,
                                                                     @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(tradeFinanceLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.tradeFinance(req, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private String resolveRequestId(String bodyId, String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : bodyId;
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：requestId 幂等请求号必填");
        }
        return requestId;
    }

    private void limit(TokenBuckets bucket, String requestId, String qps) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(TradeDocErrorCodes.RATE_LIMITED,
                    "TRD_1008 触发限流（单用户 " + qps + "），请按 Retry-After 退避重试");
        }
    }
}
