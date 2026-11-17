package com.bai.limit;

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
 * 额度计算原子接口（权限点 LMT_CALC / LMT_LIMIT / LMT_QUERY / LMT_USAGE，Bearer Token，由网关统一鉴权）
 */
@RestController
public class LimitController {

    private final LimitService service;
    /** 单用户 50 QPS（calc/limit），突发 100，超限 LMT_1008 退避 */
    private final TokenBuckets calcLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets limitLimiter = new TokenBuckets(50, 100);
    /** 单用户 100 QPS（query/usage），突发 200 */
    private final TokenBuckets queryLimiter = new TokenBuckets(100, 200);
    private final TokenBuckets usageLimiter = new TokenBuckets(100, 200);

    public LimitController(LimitService service) {
        this.service = service;
    }

    /** 额度测算与占用控制（controlStatus: PASS/OVER_LIMIT/REJECT） */
    @PostMapping("/calc")
    public BaiResponse<LimitDtos.LimitCalcResult> calc(@RequestBody LimitDtos.LimitCalcRequest req,
                                                       @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        enforceRate(calcLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.calc(req, requestId));
    }

    /** 限额管理（4.3）：action CREATE/UPDATE/GRAY/DISABLE 限额配置（异步语义，同步仿真） */
    @PostMapping("/limit")
    public BaiResponse<LimitDtos.LimitResult> limit(@RequestBody LimitDtos.LimitRequest req,
                                                    @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        enforceRate(limitLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.limit(req, requestId));
    }

    /** 试算查询（4.4）：?calcId= 精确查或按 customerId 分页查，无记录 LMT_1004 */
    @GetMapping("/query")
    public BaiResponse<LimitDtos.CalcQueryResult> query(@RequestParam(required = false) String calcId,
                                                        @RequestParam(required = false) String customerId,
                                                        @RequestParam(required = false) String limitType,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size,
                                                        @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(null, headerId);
        enforceRate(queryLimiter, requestId, "100 QPS");
        LimitDtos.CalcQueryRequest q = new LimitDtos.CalcQueryRequest(calcId, customerId, limitType, status, page, size);
        return BaiResponse.ok(requestId, service.query(q, requestId));
    }

    /** 占用查询（4.5）：按 customerId（+limitType）查询限额占用情况（确定性仿真） */
    @GetMapping("/usage")
    public BaiResponse<LimitDtos.UsageResult> usage(@RequestParam(required = false) String customerId,
                                                    @RequestParam(required = false) String limitType,
                                                    @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(null, headerId);
        enforceRate(usageLimiter, requestId, "100 QPS");
        LimitDtos.UsageQueryRequest q = new LimitDtos.UsageQueryRequest(customerId, limitType);
        return BaiResponse.ok(requestId, service.usage(q, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private String resolveRequestId(String bodyId, String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : bodyId;
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(LimitErrorCodes.PARAM_INVALID, "LMT_1001 参数缺失：requestId 幂等请求号必填");
        }
        return requestId;
    }

    private void enforceRate(TokenBuckets limiter, String requestId, String qps) {
        if (!limiter.allow(requestId)) {
            throw new BaiException(LimitErrorCodes.RATE_LIMITED,
                    "LMT_1008 触发限流（单用户 " + qps + "），请按 Retry-After 退避重试");
        }
    }
}
