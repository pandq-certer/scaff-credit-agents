package com.bai.external;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 外部数据接入原子接口（权限点 EXD_FETCH/EXD_SRC/EXD_REC/EXD_CRED/EXD_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class ExternalDataController {

    private final ExternalDataService service;
    /** 限流：单调用方 20 QPS（fetch） */
    private final TokenBuckets fetchLimiter = new TokenBuckets(20, 20);

    public ExternalDataController(ExternalDataService service) {
        this.service = service;
    }

    /** 统一外部数据同步调用（sourceType 九类白名单，场景准入/贷后/尽调） */
    @PostMapping("/fetch")
    public BaiResponse<ExternalDtos.ExternalFetchResult> fetch(@RequestBody Map<String, Object> body,
                                                               @RequestHeader("X-Request-Id") String requestId,
                                                               @RequestHeader(value = "Authorization", required = false) String authorization) {
        limit(requestId);
        String caller = authorization == null ? "anonymous" : authorization.replaceFirst("Bearer\\s+", "");
        return BaiResponse.ok(requestId, service.fetch(
                str(body.get("sourceType")),
                castMap(body.get("query")),
                str(body.get("scenario")),
                requestId,
                caller));
    }

    /** 数据源管理：注册/启用/停用/授权 */
    @PostMapping("/sources")
    public BaiResponse<ExternalDtos.SourceInfo> manageSource(@RequestBody Map<String, Object> body,
                                                             @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.manageSource(
                str(body.get("sourceType")),
                bool(body.get("enabled")),
                bool(body.get("authorized")),
                str(body.get("sourceVersion")),
                intVal(body.get("dailyQuota"))));
    }

    /** 调用记录查询（callId 或 requestId 至少一项） */
    @GetMapping("/records")
    public BaiResponse<ExternalDtos.CallRecord> getRecord(@RequestParam(value = "callId", required = false) String callId,
                                                          @RequestParam(value = "requestId", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.getRecord(callId, requestId));
    }

    /** 令牌管理：KMS 密文托管注册（仅存引用 ID，绝不回传明文） */
    @PostMapping("/credentials")
    public BaiResponse<ExternalDtos.CredentialInfo> registerCredential(@RequestBody Map<String, Object> body,
                                                                       @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.registerCredential(
                str(body.get("sourceType")), str(body.get("credentialRef"))));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(String requestId) {
        if (!fetchLimiter.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(ExternalErrorCodes.RATE_LIMITED,
                    "EXD_1007 触发频控（单调用方 20 QPS），请按 Retry-After 退避重试");
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private Boolean bool(Object o) {
        return o instanceof Boolean b ? b : (o == null ? null : Boolean.parseBoolean(String.valueOf(o)));
    }

    private Integer intVal(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        return o == null ? null : Integer.valueOf(String.valueOf(o));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }
}
