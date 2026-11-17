package com.bai.notify;

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
 * 消息通知原子接口（权限点 NTF_SEND/NTF_TPL/NTF_REC/NTF_OPTOUT/NTF_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class NotifyController {

    private final NotifyService service;
    /** 限流：单调用方 200 QPS（send），频控叠加防骚扰 */
    private final TokenBuckets sendLimiter = new TokenBuckets(200, 200);

    public NotifyController(NotifyService service) {
        this.service = service;
    }

    /** 单发/群发受理（模板注册校验、占位符渲染、频控防骚扰、渠道优先级） */
    @PostMapping("/send")
    public BaiResponse<NotifyDtos.SendResult> send(@RequestBody Map<String, Object> body,
                                                   @RequestHeader("X-Request-Id") String requestId,
                                                   @RequestHeader(value = "Authorization", required = false) String authorization) {
        limit(requestId);
        String caller = authorization == null ? "anonymous" : authorization.replaceFirst("Bearer\\s+", "");
        return BaiResponse.ok(requestId, service.send(
                str(body.get("templateCode")),
                castMap(body.get("receiver")),
                castMap(body.get("params")),
                strList(body.get("channels")),
                str(body.get("priority")),
                requestId,
                caller));
    }

    /** 模板管理：注册/更新/停用 */
    @PostMapping("/template")
    public BaiResponse<NotifyDtos.TemplateInfo> manageTemplate(@RequestBody Map<String, Object> body,
                                                               @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        boolean disable = "DISABLE".equalsIgnoreCase(str(body.get("action")));
        return BaiResponse.ok(requestId, service.manageTemplate(
                str(body.get("templateCode")),
                str(body.get("name")),
                str(body.get("category")),
                strList(body.get("channels")),
                str(body.get("content")),
                disable));
    }

    /** 发送记录查询（messageId 或 requestId 至少一项） */
    @GetMapping("/records")
    public BaiResponse<NotifyDtos.SendRecord> getRecord(@RequestParam(value = "messageId", required = false) String messageId,
                                                        @RequestParam(value = "requestId", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.getRecord(messageId, requestId));
    }

    /** 退订管理：登记退订（即时生效 ≤1s） */
    @PostMapping("/optout")
    public BaiResponse<NotifyDtos.OptOutEntry> optOut(@RequestBody Map<String, Object> body,
                                                      @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.optOut(castMap(body.get("receiver")), str(body.get("scope"))));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(String requestId) {
        if (!sendLimiter.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(NotifyErrorCodes.RATE_LIMITED,
                    "NTF_1007 触发频控（单调用方 200 QPS），请按 Retry-After 退避重试");
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private List<String> strList(Object o) {
        return o instanceof List ? (List<String>) o : null;
    }
}
