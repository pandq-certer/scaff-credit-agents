package com.bai.dialog;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 对话管理原子接口（权限点 DLG_SESS/DLG_MSG/DLG_QUERY/DLG_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class DialogController {

    private final DialogService service;
    /** 限流：单用户 5 QPS（message） */
    private final TokenBuckets userLimiter = new TokenBuckets(5, 5);
    /** 限流：单实例并发 100 QPS */
    private final TokenBuckets instanceLimiter = new TokenBuckets(100, 100);

    public DialogController(DialogService service) {
        this.service = service;
    }

    /** 会话创建/恢复/结束（action：CREATE/RESUME/END） */
    @PostMapping("/session")
    public BaiResponse<DialogDtos.SessionInfo> manageSession(@RequestBody Map<String, Object> body,
                                                             @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.manageSession(
                str(body.get("action")),
                str(body.get("sessionId")),
                str(body.get("userId")),
                str(body.get("channel")),
                str(body.get("scene")),
                requestId));
    }

    /** 消息处理（会话状态校验、意图/槽位联动口径、策略决策、合规检查、记忆更新） */
    @PostMapping("/message")
    public BaiResponse<DialogDtos.DialogReply> handleMessage(@RequestBody Map<String, Object> body,
                                                             @RequestHeader("X-Request-Id") String requestId) {
        String userId = str(body.get("userId"));
        limitInstance();
        limitUser(userId);
        return BaiResponse.ok(requestId, service.handleMessage(
                str(body.get("sessionId")),
                str(body.get("message")),
                str(body.get("channel")),
                userId,
                requestId));
    }

    /** 会话列表查询（userId 可选过滤，仅本人/授权坐席可见由网关鉴权） */
    @GetMapping("/sessions")
    public BaiResponse<List<DialogDtos.SessionInfo>> listSessions(@RequestParam(value = "userId", required = false) String userId,
                                                                  @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.listSessions(userId));
    }

    /** 对话历史查询（滑动窗口最近 20 轮 + 状态快照） */
    @GetMapping("/history")
    public BaiResponse<DialogDtos.DialogHistory> getHistory(@RequestParam("sessionId") String sessionId,
                                                            @RequestParam(value = "userId", required = false) String userId,
                                                            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.getHistory(sessionId, userId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limitUser(String userId) {
        if (!userLimiter.allow(userId == null ? "anonymous" : userId)) {
            throw new BaiException(DialogErrorCodes.RATE_LIMITED,
                    "DLG_1008 触发限流（单用户 5 QPS），请按 Retry-After 退避重试");
        }
    }

    private void limitInstance() {
        if (!instanceLimiter.allow("instance-global")) {
            throw new BaiException(DialogErrorCodes.RATE_LIMITED,
                    "DLG_1008 触发限流（单实例并发 100 QPS），请按 Retry-After 退避重试");
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
