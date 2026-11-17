package com.bai.sensitivemask;

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
 * 敏感脱敏原子接口（权限点 SMK_DETECT/SMK_MASK/SMK_VERIFY/SMK_RULE/SMK_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class SensitiveMaskController {

    private final SensitiveMaskService service;
    /** 限流：mask 100 QPS、detect/verify 30 QPS、rule 20 QPS */
    private final TokenBuckets maskLimiter = new TokenBuckets(100, 100);
    private final TokenBuckets detectLimiter = new TokenBuckets(30, 30);
    private final TokenBuckets adminLimiter = new TokenBuckets(20, 20);

    public SensitiveMaskController(SensitiveMaskService service) {
        this.service = service;
    }

    /** 统一脱敏（FIELD/TEXT/BATCH × DISPLAY/EXPORT/LOG/DEV/TEST/STAT） */
    @PostMapping("/mask")
    public BaiResponse<SensitiveMaskDtos.MaskResult> mask(@RequestBody(required = false) Map<String, Object> body,
                                                          @RequestHeader("X-Request-Id") String requestId) {
        limit(maskLimiter, requestId, "SMK_1008 触发限流（mask 单调用方 100 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(SensitiveMaskErrorCodes.PARAM_INVALID, "SMK_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.mask(body, requestId));
    }

    /** 敏感识别（正则+NER 混合的确定性路径） */
    @PostMapping("/detect")
    public BaiResponse<SensitiveMaskDtos.DetectResult> detect(@RequestBody(required = false) Map<String, Object> body,
                                                              @RequestHeader("X-Request-Id") String requestId) {
        limit(detectLimiter, requestId, "SMK_1008 触发限流（detect 单调用方 30 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(SensitiveMaskErrorCodes.PARAM_INVALID, "SMK_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.detect(body));
    }

    /** 脱敏抽检（残敏感检测） */
    @PostMapping("/verify")
    public BaiResponse<SensitiveMaskDtos.VerifyResult> verify(@RequestBody(required = false) Map<String, Object> body,
                                                              @RequestHeader("X-Request-Id") String requestId) {
        limit(detectLimiter, requestId, "SMK_1008 触发限流（verify 单调用方 30 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(SensitiveMaskErrorCodes.PARAM_INVALID, "SMK_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.verify(body));
    }

    /** 脱敏规则管理（异步；重复 ruleId 返回 SMK_1005） */
    @PostMapping("/rule")
    public BaiResponse<SensitiveMaskDtos.RuleInfo> registerRule(@RequestBody(required = false) Map<String, Object> body,
                                                                @RequestHeader("X-Request-Id") String requestId) {
        limit(adminLimiter, requestId, "SMK_1008 触发限流（rule 单调用方 20 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(SensitiveMaskErrorCodes.PARAM_INVALID, "SMK_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.registerRule(body, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(SensitiveMaskErrorCodes.RATE_LIMITED, message);
        }
    }
}
