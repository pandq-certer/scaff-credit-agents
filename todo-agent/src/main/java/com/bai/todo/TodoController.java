package com.bai.todo;

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
 * 任务待办原子接口（权限点 TDO_CREATE/TDO_ACTION/TDO_LIST/TDO_STATS/TDO_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class TodoController {

    private final TodoService service;
    /** 限流：create/action 100 QPS、list/stats 50 QPS */
    private final TokenBuckets createLimiter = new TokenBuckets(100, 100);
    private final TokenBuckets actionLimiter = new TokenBuckets(100, 100);
    private final TokenBuckets queryLimiter = new TokenBuckets(50, 50);

    public TodoController(TodoService service) {
        this.service = service;
    }

    /** 待办创建（source/bizId/title/assignee 必填，幂等 requestId+source+bizId 7 天） */
    @PostMapping("/create")
    public BaiResponse<TodoDtos.TodoView> create(@RequestBody(required = false) Map<String, Object> body,
                                                 @RequestHeader("X-Request-Id") String requestId) {
        limit(createLimiter, requestId, "TDO_1007 触发限流（create 单调用方 100 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(TodoErrorCodes.PARAM_INVALID, "TDO_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.create(body, requestId));
    }

    /** 待办动作（CLAIM/START/COMPLETE/CANCEL/TRANSFER，状态机严格流转） */
    @PostMapping("/action")
    public BaiResponse<TodoDtos.ActionResult> action(@RequestBody(required = false) Map<String, Object> body,
                                                     @RequestHeader("X-Request-Id") String requestId) {
        limit(actionLimiter, requestId, "TDO_1007 触发限流（action 单调用方 100 QPS），请按 Retry-After 退避重试");
        if (body == null) {
            throw new BaiException(TodoErrorCodes.PARAM_INVALID, "TDO_1001 参数缺失：请求体必填");
        }
        return BaiResponse.ok(requestId, service.action(body, requestId));
    }

    /** 待办列表（scope=MINE&userId= / scope=ORG&orgId=） */
    @GetMapping("/list")
    public BaiResponse<java.util.List<TodoDtos.TodoView>> list(
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "userId", required = false) String userId,
            @RequestParam(value = "orgId", required = false) String orgId,
            @RequestHeader("X-Request-Id") String requestId) {
        limit(queryLimiter, requestId, "TDO_1007 触发限流（list 单调用方 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.list(scope, userId, orgId));
    }

    /** 待办统计（按状态/按来源） */
    @GetMapping("/stats")
    public BaiResponse<TodoDtos.Stats> stats(@RequestHeader("X-Request-Id") String requestId) {
        limit(queryLimiter, requestId, "TDO_1007 触发限流（stats 单调用方 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.stats());
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(TodoErrorCodes.RATE_LIMITED, message);
        }
    }
}
