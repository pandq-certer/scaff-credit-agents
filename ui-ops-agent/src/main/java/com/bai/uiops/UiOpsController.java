package com.bai.uiops;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 界面操作原子接口（权限点 UIO_OP/UIO_SYNC/UIO_ROLLBACK/UIO_QUERY/UIO_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class UiOpsController {

    private final UiOpsService service;
    /** 限流：operate/sync 单用户 10 QPS */
    private final TokenBuckets opLimiter = new TokenBuckets(10, 10);
    /** 限流：element/rollback 管理类 50 QPS */
    private final TokenBuckets adminLimiter = new TokenBuckets(50, 50);

    public UiOpsController(UiOpsService service) {
        this.service = service;
    }

    /** 元素定位（三级策略 ID→XPATH→视觉兜底，置信度≥0.9 才执行操作） */
    @PostMapping("/element")
    public BaiResponse<UiOpsDtos.LocateResult> element(@RequestBody Map<String, Object> body,
                                                       @RequestHeader("X-Request-Id") String requestId) {
        limit(adminLimiter, requestId, "UIO_1008 触发限流（element 单用户 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.locate(body, requestId));
    }

    /** 页面操作（FILL/SELECT/CLICK/SUBMIT/UPLOAD，幂等键 requestId+sessionId+action） */
    @PostMapping("/operate")
    public BaiResponse<UiOpsDtos.OpResult> operate(@RequestBody Map<String, Object> body,
                                                   @RequestHeader("X-Request-Id") String requestId) {
        limit(opLimiter, requestId, "UIO_1008 触发限流（operate 单用户 10 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.operate(body, requestId));
    }

    /** 交易同步（页面提交→后台调用→一致性比对，txnRef 幂等防重复提交） */
    @PostMapping("/sync")
    public BaiResponse<UiOpsDtos.SyncResult> sync(@RequestBody Map<String, Object> body,
                                                  @RequestHeader("X-Request-Id") String requestId) {
        limit(opLimiter, requestId, "UIO_1008 触发限流（sync 单用户 10 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.sync(body, requestId));
    }

    /** 回滚处理（BUSINESS 逆向交易/DATA 快照恢复，P0 双人授权） */
    @PostMapping("/rollback")
    public BaiResponse<UiOpsDtos.RollbackResult> rollback(@RequestBody Map<String, Object> body,
                                                          @RequestHeader("X-Request-Id") String requestId,
                                                          @RequestHeader(value = "Authorization", required = false) String authorization) {
        limit(adminLimiter, requestId, "UIO_1008 触发限流（rollback 单用户 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.rollback(body, requestId, authorization));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(UiOpsErrorCodes.RATE_LIMITED, message);
        }
    }
}
