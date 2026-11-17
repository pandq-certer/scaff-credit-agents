package com.bai.modelinfer;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模型推理原子接口（权限点 MDI_SCORE/MDI_CLUSTER/MDI_MODEL/MDI_EXPLAIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class ModelInferController {

    private final ModelInferService service;
    /** 限流：score 200 QPS、cluster 50 QPS、model 20 QPS、explain 100 QPS */
    private final TokenBuckets scoreLimiter = new TokenBuckets(200, 200);
    private final TokenBuckets clusterLimiter = new TokenBuckets(50, 50);
    private final TokenBuckets modelLimiter = new TokenBuckets(20, 20);
    private final TokenBuckets explainLimiter = new TokenBuckets(100, 100);

    public ModelInferController(ModelInferService service) {
        this.service = service;
    }

    /** 统一模型推理（评分卡/ML/LLM/聚类；requestId 取 header，缺失回退 body.requestId） */
    @PostMapping("/score")
    public BaiResponse<ModelInferDtos.ScoreResult> score(@RequestBody(required = false) Map<String, Object> body,
                                                         @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        limit(scoreLimiter, requestId, "MDI_1008 触发限流（score 单调用方 200 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID, "MDI_1001 参数缺失：请求体必填");
        }
        String reqId = resolveRequestId(requestId, body);
        return BaiResponse.ok(reqId, service.score(body, reqId));
    }

    /** 聚类分群（4.3；scene 取 SEGMENT 等聚类场景，k 可选，按特征哈希推导分群） */
    @PostMapping("/cluster")
    public BaiResponse<ModelInferDtos.ClusterResult> cluster(@RequestBody(required = false) Map<String, Object> body,
                                                             @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        limit(clusterLimiter, requestId, "MDI_1008 触发限流（cluster 单调用方 50 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID, "MDI_1001 参数缺失：请求体必填");
        }
        String reqId = resolveRequestId(requestId, body);
        return BaiResponse.ok(reqId, service.cluster(body, reqId));
    }

    /** 模型注册/治理（4.4；REGISTER/UPDATE/GRAY/PROMOTE/ROLLBACK/DISABLE） */
    @PostMapping("/model")
    public BaiResponse<ModelInferDtos.ModelManageResult> modelManage(@RequestBody(required = false) Map<String, Object> body,
                                                                     @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        limit(modelLimiter, requestId, "MDI_1008 触发限流（model 单调用方 20 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID, "MDI_1001 参数缺失：请求体必填");
        }
        String reqId = resolveRequestId(requestId, body);
        return BaiResponse.ok(reqId, service.modelManage(body, reqId));
    }

    /** 可解释查询（4.5；?inferId=&modelId=&method=SHAP|SCORECARD_CONTRIB；requestId 取 header，缺失按匿名幂等） */
    @GetMapping("/explain")
    public BaiResponse<ModelInferDtos.ExplainResult> explain(@RequestParam(value = "inferId", required = false) String inferId,
                                                             @RequestParam(value = "modelId", required = false) String modelId,
                                                             @RequestParam(value = "method", required = false) String method,
                                                             @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        limit(explainLimiter, requestId, "MDI_1008 触发限流（explain 单调用方 100 QPS），请按 Retry-After 退避重试");
        String reqId = requestId == null || requestId.isBlank() ? "anon" : requestId;
        return BaiResponse.ok(reqId, service.explain(inferId, modelId, method, reqId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    /** requestId 解析：header X-Request-Id 回退 body.requestId，两者皆缺 MDI_1001 */
    private String resolveRequestId(String requestId, Map<String, Object> body) {
        String reqId = requestId == null || requestId.isBlank() ? str(body.get("requestId")) : requestId;
        if (reqId == null || reqId.isBlank()) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID,
                    "MDI_1001 参数缺失：requestId 必填（header X-Request-Id 或 body.requestId）");
        }
        return reqId;
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(ModelInferErrorCodes.RATE_LIMITED, message);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
