package com.bai.carbon;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 碳账户核算原子接口（权限点 CRB_ACCOUNT / CRB_FACTOR，Bearer Token，由网关统一鉴权）
 */
@RestController
public class CarbonController {

    private final CarbonService service;
    /** 单用户 50 QPS（account），突发 100，超限 CARB_1008 退避 */
    private final TokenBuckets limiter = new TokenBuckets(50, 100);
    /** 分类判定/减排量/配额/估值/绿色统计/工具测算/洗绿识别 各 50 QPS，突发 100 */
    private final TokenBuckets classifyLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets reductionLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets quotaLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets valueLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets greenStatLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets toolCalcLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets greenwashLimiter = new TokenBuckets(50, 100);

    public CarbonController(CarbonService service) {
        this.service = service;
    }

    /** 碳排放核算：范围一/二/三 → 总排放 → 碳价情景加权碳资产与可质押额 */
    @PostMapping("/account")
    public BaiResponse<CarbonDtos.AccountResult> account(@RequestBody CarbonDtos.AccountRequest req,
                                                         @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(requestId);
        return BaiResponse.ok(requestId, service.account(req, requestId));
    }

    /** 排放因子库查询 */
    @GetMapping("/factors")
    public BaiResponse<List<CarbonDtos.FactorItem>> factors(@RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : "factors";
        return BaiResponse.ok(requestId, service.factors());
    }

    /** 绿色产业目录分类判定（4.2） */
    @PostMapping("/classify")
    public BaiResponse<CarbonDtos.ClassifyResult> classify(@RequestBody CarbonDtos.ClassifyRequest req,
                                                           @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(classifyLimiter, requestId, "classify");
        return BaiResponse.ok(requestId, service.classify(req, requestId));
    }

    /** 项目减排量测算（4.4） */
    @PostMapping("/reduction")
    public BaiResponse<CarbonDtos.ReductionResult> reduction(@RequestBody CarbonDtos.ReductionRequest req,
                                                             @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(reductionLimiter, requestId, "reduction");
        return BaiResponse.ok(requestId, service.reduction(req, requestId));
    }

    /** 碳配额测算（4.5） */
    @PostMapping("/quota")
    public BaiResponse<CarbonDtos.QuotaResult> quota(@RequestBody CarbonDtos.QuotaRequest req,
                                                     @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(quotaLimiter, requestId, "quota");
        return BaiResponse.ok(requestId, service.quota(req, requestId));
    }

    /** 碳资产估值（4.6） */
    @PostMapping("/value")
    public BaiResponse<CarbonDtos.ValueResult> value(@RequestBody CarbonDtos.ValueRequest req,
                                                     @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(valueLimiter, requestId, "value");
        return BaiResponse.ok(requestId, service.value(req, requestId));
    }

    /** 绿色贷款认定与统计测算（4.7） */
    @PostMapping("/green-stat")
    public BaiResponse<CarbonDtos.GreenStatResult> greenStat(@RequestBody CarbonDtos.GreenStatRequest req,
                                                             @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(greenStatLimiter, requestId, "green-stat");
        return BaiResponse.ok(requestId, service.greenStat(req, requestId));
    }

    /** 碳减排支持工具测算（4.8） */
    @PostMapping("/tool-calc")
    public BaiResponse<CarbonDtos.ToolCalcResult> toolCalc(@RequestBody CarbonDtos.ToolCalcRequest req,
                                                           @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(toolCalcLimiter, requestId, "tool-calc");
        return BaiResponse.ok(requestId, service.toolCalc(req, requestId));
    }

    /** 洗绿识别（4.9） */
    @PostMapping("/greenwash")
    public BaiResponse<CarbonDtos.GreenwashResult> greenwash(@RequestBody CarbonDtos.GreenwashRequest req,
                                                             @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(greenwashLimiter, requestId, "greenwash");
        return BaiResponse.ok(requestId, service.greenwash(req, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private String resolveRequestId(String bodyId, String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : bodyId;
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(CarbonErrorCodes.PARAM_INVALID, "CARB_1001 参数缺失：requestId 幂等请求号必填");
        }
        return requestId;
    }

    private void limit(String requestId) {
        if (!limiter.allow(requestId)) {
            throw new BaiException(CarbonErrorCodes.RATE_LIMITED,
                    "CARB_1008 触发限流（单用户 50 QPS），请按 Retry-After 退避重试");
        }
    }

    /** 接口级限流（各 50 QPS）：分类/减排/配额/估值/统计/工具/洗绿 */
    private void limit(TokenBuckets bucket, String requestId, String api) {
        if (!bucket.allow(requestId)) {
            throw new BaiException(CarbonErrorCodes.RATE_LIMITED,
                    "CARB_1008 触发限流（" + api + " 单用户 50 QPS），请按 Retry-After 退避重试");
        }
    }
}
