package com.bai.docparse;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 文档解析原子接口（权限点 DCP_PARSE/DCP_SCHEMAS/DCP_TASKS/DCP_VALIDATE/DCP_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class DocParseController {

    private final DocParseService service;
    /** 限流：parse 20 QPS、validate/schemas/tasks 50 QPS */
    private final TokenBuckets parseLimiter = new TokenBuckets(20, 20);
    private final TokenBuckets queryLimiter = new TokenBuckets(50, 50);

    public DocParseController(DocParseService service) {
        this.service = service;
    }

    /** 全流程解析（分类/版面/表格/KV，同步；>20 页生产转异步） */
    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaiResponse<DocParseDtos.ParseResult> parse(@RequestPart(value = "file", required = false) MultipartFile file,
                                                       @RequestParam(value = "docType", required = false) String docType,
                                                       @RequestParam(value = "schemaVersion", required = false) String schemaVersion,
                                                       @RequestParam(value = "extractMode", required = false, defaultValue = "FULL") String extractMode,
                                                       @RequestParam(value = "enableLlm", required = false, defaultValue = "false") Boolean enableLlm,
                                                       @RequestHeader("X-Request-Id") String requestId) {
        limit(parseLimiter, requestId, "DCP_1008 触发限流（parse 单调用方 20 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.parse(file, docType, schemaVersion, extractMode, enableLlm, requestId));
    }

    /** 全流程解析（JSON 通道：编排层 scene-bff 调用；与 multipart /parse 同路径，按 Content-Type 分流） */
    @PostMapping(value = "/parse", consumes = "application/json")
    public BaiResponse<DocParseDtos.ParseResult> parseJson(@RequestBody Map<String, Object> body,
                                                           @RequestHeader("X-Request-Id") String requestId) {
        limit(parseLimiter, requestId, "DCP_1008 触发限流（parse 单调用方 20 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.parseJson(body, requestId));
    }

    /** Schema 查询 */
    @GetMapping("/schemas")
    public BaiResponse<List<DocParseDtos.SchemaInfo>> schemas(@RequestHeader("X-Request-Id") String requestId) {
        limit(queryLimiter, requestId, "DCP_1008 触发限流（schemas 单调用方 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.schemas());
    }

    /** 异步任务查询 */
    @GetMapping("/tasks/{taskId}")
    public BaiResponse<DocParseDtos.TaskResult> task(@PathVariable("taskId") String taskId,
                                                     @RequestHeader("X-Request-Id") String requestId) {
        limit(queryLimiter, requestId, "DCP_1008 触发限流（tasks 单调用方 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.task(taskId));
    }

    /** 解析校验（低置信字段/转人工判定） */
    @PostMapping("/validate")
    public BaiResponse<DocParseDtos.ValidateResult> validate(@RequestBody(required = false) Map<String, Object> body,
                                                             @RequestHeader("X-Request-Id") String requestId) {
        limit(queryLimiter, requestId, "DCP_1008 触发限流（validate 单调用方 50 QPS），请按 Retry-After 退避重试");
        if (body == null || isBlank(body.get("docId"))) {
            throw new BaiException(DocParseErrorCodes.PARAM_INVALID, "DCP_1001 参数缺失：docId 必填");
        }
        return BaiResponse.ok(requestId, service.validate(str(body.get("docId")), body.get("fields"), requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(DocParseErrorCodes.RATE_LIMITED, message);
        }
    }

    private static boolean isBlank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
