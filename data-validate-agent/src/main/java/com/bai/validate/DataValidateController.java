package com.bai.validate;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据校验原子接口（权限点 DVA_VALIDATE/DVA_BATCH/DVA_RULES/DVA_TEMPLATES；
 * Bearer Token 由网关统一鉴权，Controller 仅强制请求头存在性，缺头由 bai-common 自动 400 PARAM_MISSING）
 */
@RestController
public class DataValidateController {

    private final DataValidateService service;
    /** 限流（条目约定）：validate 100 QPS / batch 20 QPS */
    private final TokenBuckets validateLimiter = new TokenBuckets(100, 200);
    private final TokenBuckets batchLimiter = new TokenBuckets(20, 40);

    public DataValidateController(DataValidateService service) {
        this.service = service;
    }

    /** 单次校验（模板/自定义规则 + 八类算子 + 跨系统仿真通道） */
    @PostMapping("/validate")
    public BaiResponse<DataValidateDtos.ValidateResult> validate(
            @RequestBody DataValidateDtos.ValidateRequest request,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("Authorization") String authorization) {
        if (!validateLimiter.allow(requestId)) {
            throw new BaiException(DataValidateErrorCodes.RATE_LIMITED, "DVA_1007 触发限流（validate 100 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.validate(request, requestId));
    }

    /** 批量校验（≤5000 条） */
    @PostMapping("/batch")
    public BaiResponse<DataValidateDtos.BatchResult> batch(
            @RequestBody DataValidateDtos.BatchRequest request,
            @RequestHeader("X-Request-Id") String requestId,
            @RequestHeader("Authorization") String authorization) {
        if (!batchLimiter.allow(requestId)) {
            throw new BaiException(DataValidateErrorCodes.RATE_LIMITED, "DVA_1007 触发限流（batch 20 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 规则库（八类算子清单） */
    @GetMapping("/rules")
    public BaiResponse<DataValidateDtos.RulesResult> rules(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.rules());
    }

    /** 模板库（T_SUBMIT/T_LOAN/T_RECON/T_ROLLBACK/T_DOC） */
    @GetMapping("/templates")
    public BaiResponse<DataValidateDtos.TemplatesResult> templates(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.templates());
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }
}
