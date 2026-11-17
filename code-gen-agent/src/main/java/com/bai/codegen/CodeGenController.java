package com.bai.codegen;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 代码生成原子接口（权限点 CDG_GEN/CDG_TMPL/CDG_QUERY/CDG_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class CodeGenController {

    private final CodeGenService service;
    /** 限流：generate 单调用方 30 QPS（设计文档 4.7） */
    private final TokenBuckets generateLimiter = new TokenBuckets(30, 30);

    public CodeGenController(CodeGenService service) {
        this.service = service;
    }

    /** 代码生成（SQL/RULE/INTEGRATION/CONFIG/TEST；安全扫描 HIGH 零容忍 422，code 不予返回） */
    @PostMapping("/generate")
    public ResponseEntity<BaiResponse<CodeGenDtos.GenerateResult>> generate(@RequestBody Map<String, Object> body,
                                                                            @RequestHeader("X-Request-Id") String requestId,
                                                                            @RequestHeader(value = "Authorization", required = false) String authorization) {
        limit(authorization == null ? requestId : authorization.replaceFirst("Bearer\\s+", ""));
        CodeGenDtos.GenerateResult result = service.generate(
                str(body.get("codeType")),
                str(body.get("requirement")),
                str(body.get("language")),
                castMap(body.get("context")),
                str(body.get("templateId")),
                str(body.get("bizKey")),
                requestId);
        if (result.securityScan() != null && !result.securityScan().passed()) {
            // 安全扫描 HIGH 零容忍：422 + CDG_1007，code 不予返回
            BaiResponse<CodeGenDtos.GenerateResult> blocked =
                    BaiResponse.fail(requestId, CodeGenErrorCodes.GENERATION_REJECT,
                            "CDG_1007 安全扫描未过：命中 HIGH 风险 " + result.securityScan().high() + " 项，代码不予返回");
            blocked.setData(result);
            return ResponseEntity.status(422).body(blocked);
        }
        return ResponseEntity.ok(BaiResponse.ok(requestId, result));
    }

    /** 模板管理（CREATE/UPDATE/DELETE/PUBLISH/ENABLE/DISABLE，{{var}} 占位符） */
    @PostMapping("/template")
    public BaiResponse<CodeGenDtos.TemplateManageResult> manageTemplate(@RequestBody Map<String, Object> body,
                                                                        @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.manageTemplate(str(body.get("action")), castMap(body.get("template")), requestId));
    }

    /** 模板查询（keyword/codeType 过滤 + 分页，最新优先） */
    @GetMapping("/templates")
    public BaiResponse<CodeGenDtos.TemplateList> listTemplates(@RequestParam(value = "keyword", required = false) String keyword,
                                                               @RequestParam(value = "codeType", required = false) String codeType,
                                                               @RequestParam(value = "page", required = false) Integer page,
                                                               @RequestParam(value = "size", required = false) Integer size,
                                                               @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.listTemplates(keyword, codeType, page, size));
    }

    /** 生成历史查询（genId/bizKey/codeType 过滤 + 分页，最新优先） */
    @GetMapping("/history")
    public BaiResponse<CodeGenDtos.HistoryResult> history(@RequestParam(value = "genId", required = false) String genId,
                                                          @RequestParam(value = "bizKey", required = false) String bizKey,
                                                          @RequestParam(value = "codeType", required = false) String codeType,
                                                          @RequestParam(value = "page", required = false) Integer page,
                                                          @RequestParam(value = "size", required = false) Integer size,
                                                          @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.history(genId, bizKey, codeType, page, size));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(String callerKey) {
        if (!generateLimiter.allow(callerKey == null ? "anonymous" : callerKey)) {
            throw new BaiException(CodeGenErrorCodes.RATE_LIMITED,
                    "CDG_1008 触发限流（generate 单调用方 30 QPS），请按 Retry-After 退避重试");
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }
}
