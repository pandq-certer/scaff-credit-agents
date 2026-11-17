package com.bai.assetsupervise;

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
 * 押品监管原子接口（提示词库 A-53：设计文档 4.1 接口总览 8 个端点；权限点 ASS_OBJECT/ASS_COLLECT/
 * ASS_MONITOR/ASS_LTV/ASS_ALERT/ASS_REVALUE/ASS_QUERY，Bearer Token 由网关统一鉴权）。
 * <p>限流（4.9）：默认 300 QPS、采集 1000 QPS，超限抛 ASS_5002 退避重试。
 */
@RestController
public class AssetSuperviseController {

    private final AssetSuperviseService service;
    /** 默认 300 QPS，突发 600 */
    private final TokenBuckets defaultLimiter = new TokenBuckets(300, 600);
    /** 采集 1000 QPS，突发 2000 */
    private final TokenBuckets collectLimiter = new TokenBuckets(1000, 2000);

    public AssetSuperviseController(AssetSuperviseService service) {
        this.service = service;
    }

    /** 押品监管对象登记/变更/终止（4.2）：action=CREATE|UPDATE|TERMINATE，输出台账状态与登记流水号 */
    @PostMapping("/object")
    public BaiResponse<AssetSuperviseDtos.ObjectResult> object(@RequestBody AssetSuperviseDtos.ObjectRequest req,
                                                               @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(requestId);
        return BaiResponse.ok(requestId, service.registerObject(req, requestId));
    }

    /** 监测数据采集（4.3）：dataSource=IOT|WAREHOUSE|REGISTRY|MANUAL，输出接收条数/质量报告/断连标记 */
    @PostMapping("/collect")
    public BaiResponse<AssetSuperviseDtos.CollectResult> collect(@RequestBody AssetSuperviseDtos.CollectRequest req,
                                                                 @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(collectLimiter, requestId, "ASS_5002 计算服务过载（采集 1000 QPS 触发限流），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.collect(req, requestId));
    }

    /** 实时监控（4.4）：monitorType=MOVABLE|REAL_ESTATE，输出结论与异常明细 */
    @PostMapping("/monitor")
    public BaiResponse<AssetSuperviseDtos.MonitorResult> monitor(@RequestBody AssetSuperviseDtos.MonitorRequest req,
                                                                 @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(requestId);
        return BaiResponse.ok(requestId, service.monitor(req, requestId));
    }

    /** 质押率盯市（4.5）：LTV=融资余额÷实时估值，按押品类型分档判定 NORMAL/WARNING/DISPOSAL（objectId+时间戳幂等） */
    @PostMapping("/ltv")
    public BaiResponse<AssetSuperviseDtos.LtvResult> ltv(@RequestBody AssetSuperviseDtos.LtvRequest req,
                                                         @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(requestId);
        return BaiResponse.ok(requestId, service.ltv(req, requestId));
    }

    /** 预警触发与处置协同（4.6）：alertType=VALUE_BREACH|QUANTITY_ABNORMAL|REGULATORY_LOSS|LTV_OVER|STATUS_CHANGE */
    @PostMapping("/alert")
    public BaiResponse<AssetSuperviseDtos.AlertResult> alert(@RequestBody AssetSuperviseDtos.AlertRequest req,
                                                             @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(requestId);
        return BaiResponse.ok(requestId, service.alert(req, requestId));
    }

    /** 押品重估联动（4.7）：triggerType=PERIODIC|MARKET_CHANGE|ALERT|REQUIRED，结果回流盯市基准 */
    @PostMapping("/revalue")
    public BaiResponse<AssetSuperviseDtos.RevalueResult> revalue(@RequestBody AssetSuperviseDtos.RevalueRequest req,
                                                                 @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(req == null ? null : req.requestId(), headerId);
        limit(requestId);
        return BaiResponse.ok(requestId, service.revalue(req, requestId));
    }

    /** 监管记录查询（4.8 查询）：type=OBJECT|MONITOR|LTV|ALERT，按 objectId/no 过滤分页 */
    @GetMapping("/query")
    public BaiResponse<AssetSuperviseDtos.QueryResult> query(
            @RequestParam("type") String type,
            @RequestParam(value = "objectId", required = false) String objectId,
            @RequestParam(value = "no", required = false) String no,
            @RequestParam(value = "page", required = false, defaultValue = "1") int page,
            @RequestParam(value = "size", required = false, defaultValue = "20") int size,
            @RequestHeader(value = "X-Request-Id", required = false) String headerId) {
        String requestId = resolveRequestId(null, headerId);
        limit(requestId);
        return BaiResponse.ok(requestId, service.query(type, objectId, no, page, size, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private String resolveRequestId(String bodyId, String headerId) {
        String requestId = headerId != null && !headerId.isBlank() ? headerId : bodyId;
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：requestId 幂等请求号必填");
        }
        return requestId;
    }

    private void limit(String requestId) {
        if (!defaultLimiter.allow(requestId)) {
            throw new BaiException(AssetSuperviseErrorCodes.SERVICE_OVERLOAD,
                    "ASS_5002 计算服务过载（默认 300 QPS 触发限流），请按 Retry-After 退避重试");
        }
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(AssetSuperviseErrorCodes.SERVICE_OVERLOAD, message);
        }
    }
}
