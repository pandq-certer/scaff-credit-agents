package com.bai.finstmt;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 财务报表分析原子接口（权限点 FST_ANALYZE 等，Bearer Token，由网关统一鉴权）。
 * <p>analyze 综合分析（4.8）+ parse/reconcile/metric/trend/fraud/cross（4.2~4.7）。
 */
@RestController
public class FinStmtController {

    private final FinStmtService service;
    /** 单用户 200 QPS（analyze），突发 400，超限 FST_1008 退避 */
    private final TokenBuckets limiter = new TokenBuckets(200, 400);
    /** 6 个专项接口各单用户 50 QPS（突发 100），超限 FST_1008 退避 */
    private final TokenBuckets parseLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets reconcileLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets metricLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets trendLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets fraudLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets crossLimiter = new TokenBuckets(50, 100);

    public FinStmtController(FinStmtService service) {
        this.service = service;
    }

    /** 报表分析：勾稽校验 → 四维指标 → 造假信号评分 → 四流验证 */
    @PostMapping("/analyze")
    public BaiResponse<FinStmtDtos.AnalyzeResult> analyze(@RequestBody FinStmtDtos.AnalyzeRequest req,
                                                          @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(requestId);
        return BaiResponse.ok(requestId, service.analyze(req, requestId));
    }

    /** 三大报表解析（4.2）：标准化科目 + 合计平衡校验 + 解析质量 */
    @PostMapping("/parse")
    public BaiResponse<FinStmtDtos.ParseResult> parse(@RequestBody FinStmtDtos.ParseRequest req,
                                                      @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(parseLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.parse(req, requestId));
    }

    /** 勾稽关系校验（4.3）：恒等式/留存收益/现金流/货币资金四类检查项 */
    @PostMapping("/reconcile")
    public BaiResponse<FinStmtDtos.ReconcileResult> reconcile(@RequestBody FinStmtDtos.ReconcileRequest req,
                                                              @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(reconcileLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.reconcile(req, requestId));
    }

    /** 财务指标测算（4.4）：四维 20 项指标 + 异常标记 */
    @PostMapping("/metric")
    public BaiResponse<FinStmtDtos.MetricResult> metric(@RequestBody FinStmtDtos.MetricRequest req,
                                                        @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(metricLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.metric(req, requestId));
    }

    /** 指标趋势分析（4.5）：各期值 + 环比/同比 + 趋势判断 */
    @PostMapping("/trend")
    public BaiResponse<FinStmtDtos.TrendResult> trend(@RequestBody FinStmtDtos.TrendRequest req,
                                                      @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(trendLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.trend(req, requestId));
    }

    /** 造假信号识别（4.6）：十大信号 + 评分分级 */
    @PostMapping("/fraud")
    public BaiResponse<FinStmtDtos.FraudResult> fraud(@RequestBody FinStmtDtos.FraudRequest req,
                                                      @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(fraudLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.fraud(req, requestId));
    }

    /** 四流合一交叉验证（4.7）：主体/金额/时间序列校验 */
    @PostMapping("/cross")
    public BaiResponse<FinStmtDtos.CrossResult> cross(@RequestBody FinStmtDtos.CrossRequest req,
                                                      @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(crossLimiter, requestId, "50 QPS");
        return BaiResponse.ok(requestId, service.cross(req, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private String resolveRequestId(String bodyId, String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : bodyId;
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(FinStmtErrorCodes.PARAM_INVALID, "FST_1001 参数缺失：requestId 幂等请求号必填");
        }
        return requestId;
    }

    private void limit(String requestId) {
        if (!limiter.allow(requestId)) {
            throw new BaiException(FinStmtErrorCodes.RATE_LIMITED,
                    "FST_1008 触发限流（单用户 200 QPS），请按 Retry-After 退避重试");
        }
    }

    private void limit(TokenBuckets bucket, String requestId, String qps) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(FinStmtErrorCodes.RATE_LIMITED,
                    "FST_1008 触发限流（单用户 " + qps + "），请按 Retry-After 退避重试");
        }
    }
}
