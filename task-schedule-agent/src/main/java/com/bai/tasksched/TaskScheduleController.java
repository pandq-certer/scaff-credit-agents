package com.bai.tasksched;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 任务调度原子接口（权限点 TSK_SCHEDULE/TSK_TRIGGER/TSK_INSTANCE/TSK_QUERY/TSK_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class TaskScheduleController {

    private final TaskScheduleService service;
    /** 限流：管理接口单调用方 50 QPS */
    private final TokenBuckets limiter = new TokenBuckets(50, 50);

    public TaskScheduleController(TaskScheduleService service) {
        this.service = service;
    }

    /** 调度创建/更新（name、6 段秒级 cron、target 智能体+动作+参数、policy 错峰/优先级/日历/重试） */
    @PostMapping("/schedule")
    public BaiResponse<TaskScheduleDtos.ScheduleResult> schedule(@RequestBody Map<String, Object> body,
                                                                 @RequestHeader("X-Request-Id") String requestId,
                                                                 @RequestHeader(value = "Authorization", required = false) String authorization) {
        limit(requestId);
        String caller = authorization == null ? "anonymous" : authorization.replaceFirst("Bearer\\s+", "");
        TaskScheduleDtos.ScheduleResult result = service.schedule(
                str(body.get("name")),
                str(body.get("cron")),
                castMap(body.get("target")),
                castMap(body.get("policy")),
                requestId);
        logAccess(caller, result.scheduleId());
        return BaiResponse.ok(requestId, result);
    }

    /** 立即触发（生成调度实例 RUNNING） */
    @PostMapping("/trigger")
    public BaiResponse<TaskScheduleDtos.TriggerResult> trigger(@RequestBody Map<String, Object> body,
                                                               @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId);
        return BaiResponse.ok(requestId, service.trigger(str(body.get("scheduleId"))));
    }

    /** 实例动作（PAUSE/RESUME/CANCEL/RETRY，终态不可逆） */
    @PostMapping("/instances/{id}/action")
    public BaiResponse<TaskScheduleDtos.InstanceInfo> action(@PathVariable("id") String instanceId,
                                                             @RequestBody(required = false) Map<String, Object> body,
                                                             @RequestParam(value = "action", required = false) String actionParam,
                                                             @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        limit(requestId);
        String action = body != null && body.get("action") != null ? str(body.get("action")) : actionParam;
        return BaiResponse.ok(requestId, service.instanceAction(instanceId, action));
    }

    /** 实例与历史查询（可按 scheduleId 过滤） */
    @GetMapping("/instances")
    public BaiResponse<TaskScheduleDtos.InstanceList> instances(
            @RequestParam(value = "scheduleId", required = false) String scheduleId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.instances(scheduleId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(String requestId) {
        if (!limiter.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(TaskScheduleErrorCodes.RATE_LIMITED,
                    "TSK_1007 触发限流（管理接口单调用方 50 QPS），请按 Retry-After 退避重试");
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private void logAccess(String caller, String scheduleId) {
        org.slf4j.LoggerFactory.getLogger(TaskScheduleController.class)
                .info("调度管理留痕: caller={}, scheduleId={}", caller, scheduleId);
    }
}
