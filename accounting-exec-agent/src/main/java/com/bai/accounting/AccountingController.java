package com.bai.accounting;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账务执行原子接口（权限点 ACC_ENTRY / ACC_ACCRUAL / ACC_SETTLE / ACC_QUERY，Bearer Token，由网关统一鉴权）
 */
@RestController
public class AccountingController {

    private final AccountingService service;
    /** 限流：entry 100 QPS、accrual/settle 各 50 QPS、query 100 QPS，突发 2 倍，超限 ACC_1008 退避 */
    private final TokenBuckets entryLimiter = new TokenBuckets(100, 200);
    private final TokenBuckets accrualLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets settleLimiter = new TokenBuckets(50, 100);
    private final TokenBuckets queryLimiter = new TokenBuckets(100, 200);

    public AccountingController(AccountingService service) {
        this.service = service;
    }

    /** 记账执行：借贷平衡校验 → 生成凭证 → 过账总账 */
    @PostMapping("/entry")
    public BaiResponse<AccountingDtos.EntryResult> entry(@RequestBody AccountingDtos.EntryRequest req,
                                                         @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(requestId, entryLimiter, "100 QPS");
        return BaiResponse.ok(requestId, service.entry(req, requestId));
    }

    /** 计提处理：利息/减值/应收应付计提 → 生成计提单与凭证（accrualId 唯一，幂等留痕） */
    @PostMapping("/accrual")
    public BaiResponse<AccountingDtos.AccrualResult> accrual(@RequestBody AccountingDtos.AccrualRequest req,
                                                             @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(requestId, accrualLimiter, "50 QPS");
        return BaiResponse.ok(requestId, service.accrual(req, requestId));
    }

    /** 结息处理：周期结息/结息入账/转实收 → 生成结息单与凭证（settleId 唯一，幂等留痕） */
    @PostMapping("/settle")
    public BaiResponse<AccountingDtos.SettleResult> settle(@RequestBody AccountingDtos.SettleRequest req,
                                                           @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(requestId, settleLimiter, "50 QPS");
        return BaiResponse.ok(requestId, service.settle(req, requestId));
    }

    /** 记账查询（同步）：entryId 精确或 loanNo/科目/事件/状态/日期范围组合条件分页；无记录 ACC_1004 */
    @GetMapping("/query")
    public BaiResponse<AccountingDtos.QueryResult> query(
            @RequestParam(value = "entryId", required = false) String entryId,
            @RequestParam(value = "loanNo", required = false) String loanNo,
            @RequestParam(value = "contractId", required = false) String contractId,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "glStatus", required = false) String glStatus,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "20") Integer size,
            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : "anonymous";
        limit(requestId, queryLimiter, "100 QPS");
        AccountingDtos.QueryRequest q = new AccountingDtos.QueryRequest(entryId,
                loanNo != null ? loanNo : contractId, subject, eventType, glStatus, startDate, endDate, page, size);
        return BaiResponse.ok(requestId, service.query(q, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private String resolveRequestId(String bodyId, String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : bodyId;
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(AccountingErrorCodes.PARAM_INVALID, "ACC_1001 参数缺失：requestId 幂等请求号必填");
        }
        return requestId;
    }

    private void limit(String requestId, TokenBuckets limiter, String qps) {
        if (!limiter.allow(requestId)) {
            throw new BaiException(AccountingErrorCodes.RATE_LIMITED,
                    "ACC_1008 触发限流（单用户 " + qps + "），请按 Retry-After 退避重试");
        }
    }
}
