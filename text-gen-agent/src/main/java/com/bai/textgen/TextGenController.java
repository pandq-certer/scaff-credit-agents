package com.bai.textgen;

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

import java.util.List;
import java.util.Map;

/**
 * 文本生成原子接口（权限点 TXG_GEN/TXG_TMPL/TXG_QUERY/TXG_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class TextGenController {

    private final TextGenService service;
    /** 限流：generate 单调用方 50 QPS */
    private final TokenBuckets generateLimiter = new TokenBuckets(50, 50);

    public TextGenController(TextGenService service) {
        this.service = service;
    }

    /** 文本生成（TEMPLATE 模板渲染/FREE 要点成文/MIXED 混合，RED 红线命中 422 不返回文本） */
    @PostMapping("/generate")
    public ResponseEntity<BaiResponse<TextGenDtos.GenerateResult>> generate(@RequestBody Map<String, Object> body,
                                                                            @RequestHeader("X-Request-Id") String requestId,
                                                                            @RequestHeader(value = "Authorization", required = false) String authorization) {
        limit(requestId);
        String caller = authorization == null ? "anonymous" : authorization.replaceFirst("Bearer\\s+", "");
        TextGenDtos.GenerateResult result = service.generate(
                str(body.get("mode")),
                str(body.get("templateId")),
                castList(body.get("points")),
                castMap(body.get("facts")),
                str(body.get("style")),
                intOf(body.get("maxLength")),
                str(body.get("bizKey")),
                requestId);
        if (result.compliance() != null && !result.compliance().passed()) {
            // RED 红线零容忍：422 + TXG_1007，compliance.passed=false 并附 riskWords，不返回文本
            BaiResponse<TextGenDtos.GenerateResult> blocked =
                    BaiResponse.fail(requestId, TextGenErrorCodes.COMPLIANCE_REJECT,
                            "TXG_1007 合规未过：命中红线词 " + result.compliance().riskWords() + "，文本不予返回");
            blocked.setData(result);
            return ResponseEntity.status(422).body(blocked);
        }
        return ResponseEntity.ok(BaiResponse.ok(requestId, result));
    }

    /** 模板管理：注册/更新（{{var}} 占位符） */
    @PostMapping("/template")
    public BaiResponse<TextGenDtos.TemplateInfo> registerTemplate(@RequestBody Map<String, Object> body,
                                                                  @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.registerTemplate(
                str(body.get("templateId")),
                str(body.get("name")),
                str(body.get("scene")),
                str(body.get("content"))));
    }

    /** 模板查询 */
    @GetMapping("/templates")
    public BaiResponse<TextGenDtos.TemplateList> listTemplates(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.listTemplates());
    }

    /** 生成历史查询（genId/requestId 过滤，最新优先） */
    @GetMapping("/history")
    public BaiResponse<TextGenDtos.HistoryResult> history(@RequestParam(value = "requestId", required = false) String requestId,
                                                          @RequestParam(value = "genId", required = false) String genId,
                                                          @RequestHeader(value = "X-Request-Id", required = false) String headerRequestId) {
        return BaiResponse.ok(headerRequestId, service.history(requestId, genId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(String requestId) {
        if (!generateLimiter.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(TextGenErrorCodes.RATE_LIMITED,
                    "TXG_1008 触发限流（generate 单调用方 50 QPS），请按 Retry-After 退避重试");
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private Integer intOf(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? null : Integer.valueOf(String.valueOf(o));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private List<?> castList(Object o) {
        return o instanceof List ? (List<?>) o : null;
    }
}
