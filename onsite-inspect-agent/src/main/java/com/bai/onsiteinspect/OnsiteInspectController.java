package com.bai.onsiteinspect;

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
 * 现场尽调原子接口（权限点 OIS_TASK/OIS_CHECKLIST/OIS_EVIDENCE/OIS_INTERVIEW/OIS_SUMMARY，Bearer Token 由网关统一鉴权）
 * <p>限流（设计 4.8）：task/checklist/interview/summary 200 QPS、evidence 500 QPS。
 */
@RestController
public class OnsiteInspectController {

    private final OnsiteInspectService service;
    /** 限流：默认 200 QPS，证据采集 500 QPS */
    private final TokenBuckets taskLimiter = new TokenBuckets(200, 200);
    private final TokenBuckets checklistLimiter = new TokenBuckets(200, 200);
    private final TokenBuckets evidenceLimiter = new TokenBuckets(500, 500);
    private final TokenBuckets interviewLimiter = new TokenBuckets(200, 200);
    private final TokenBuckets summaryLimiter = new TokenBuckets(200, 200);

    public OnsiteInspectController(OnsiteInspectService service) {
        this.service = service;
    }

    /** 下户任务创建/更新/状态流转（action=CREATE/UPDATE/START/COMPLETE/ABORT，幂等 requestId） */
    @PostMapping("/task")
    public BaiResponse<OnsiteInspectDtos.TaskView> task(@RequestBody(required = false) Map<String, Object> body,
                                                        @RequestHeader("X-Request-Id") String requestId) {
        limit(taskLimiter, requestId, "OIS_4005 触发限流（task 单调用方 200 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.task(body, requestId));
    }

    /** 查勘清单/提纲获取（inspectType 必填；COLLATERAL 时 collateralType 必填；version 默认当前生效） */
    @GetMapping("/checklist")
    public BaiResponse<OnsiteInspectDtos.ChecklistResult> checklist(
            @RequestParam(value = "inspectType", required = false) String inspectType,
            @RequestParam(value = "collateralType", required = false) String collateralType,
            @RequestParam(value = "version", required = false) String version,
            @RequestHeader("X-Request-Id") String requestId) {
        limit(checklistLimiter, requestId, "OIS_4005 触发限流（checklist 单调用方 200 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.checklist(inspectType, collateralType, version));
    }

    /** 现场证据采集/归集（幂等 taskId+mediaRef；时间位置一致性校验） */
    @PostMapping("/evidence")
    public BaiResponse<OnsiteInspectDtos.EvidenceResult> evidence(@RequestBody(required = false) Map<String, Object> body,
                                                                  @RequestHeader("X-Request-Id") String requestId) {
        limit(evidenceLimiter, requestId, "OIS_4005 触发限流（evidence 单调用方 500 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.evidence(body, requestId));
    }

    /** 访谈采集与双录登记（layer 三层；双录须授权文件） */
    @PostMapping("/interview")
    public BaiResponse<OnsiteInspectDtos.InterviewResult> interview(@RequestBody(required = false) Map<String, Object> body,
                                                                    @RequestHeader("X-Request-Id") String requestId) {
        limit(interviewLimiter, requestId, "OIS_4005 触发限流（interview 单调用方 200 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.interview(body, requestId));
    }

    /** 现场结论与素材生成（fiveViews 必填；异常项证据须完整） */
    @PostMapping("/summary")
    public BaiResponse<OnsiteInspectDtos.SummaryResult> summary(@RequestBody(required = false) Map<String, Object> body,
                                                                @RequestHeader("X-Request-Id") String requestId) {
        limit(summaryLimiter, requestId, "OIS_4005 触发限流（summary 单调用方 200 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.summary(body, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(OnsiteInspectErrorCodes.RATE_LIMITED, message);
        }
    }
}
