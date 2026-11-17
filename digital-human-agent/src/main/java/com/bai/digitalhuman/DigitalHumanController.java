package com.bai.digitalhuman;

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
 * 数字员工原子接口（权限点 DHM_RENDER/DHM_CTRL/DHM_AVATAR/DHM_QUERY/DHM_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class DigitalHumanController {

    private final DigitalHumanService service;
    /** 限流：渲染单调用方 20 QPS（设计文档 4.7） */
    private final TokenBuckets renderLimiter = new TokenBuckets(20, 20);
    /** 限流：单实例并发 100 QPS */
    private final TokenBuckets instanceLimiter = new TokenBuckets(100, 100);

    public DigitalHumanController(DigitalHumanService service) {
        this.service = service;
    }

    /** 渲染驱动：会话/语音/文本/情感 → 展示指令序列（幂等指纹 5min） */
    @PostMapping("/render")
    public BaiResponse<DigitalHumanDtos.RenderResult> render(@RequestBody Map<String, Object> body,
                                                             @RequestHeader("X-Request-Id") String requestId) {
        limitInstance();
        limitRender(caller(body));
        return BaiResponse.ok(requestId, service.render(
                str(body.get("avatarId")),
                str(body.get("driverType")),
                body.get("content"),
                str(body.get("sessionId")),
                requestId));
    }

    /** 展示控制：开始/暂停/恢复/结束/复位（状态机流转） */
    @PostMapping("/control")
    public BaiResponse<DigitalHumanDtos.ControlResult> control(@RequestBody Map<String, Object> body,
                                                               @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        limitInstance();
        return BaiResponse.ok(requestId, service.control(
                str(body.get("renderId")),
                str(body.get("action")),
                str(body.get("reason"))));
    }

    /** 形象管理：形象/资产增删改（CREATE/UPDATE/DELETE/PUBLISH/UPLOAD_ASSET） */
    @PostMapping("/avatar")
    public BaiResponse<DigitalHumanDtos.AvatarResult> manageAvatar(@RequestBody Map<String, Object> body,
                                                                   @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        limitInstance();
        return BaiResponse.ok(requestId, service.manageAvatar(
                str(body.get("action")),
                str(body.get("avatarId")),
                map(body.get("avatar")),
                requestId));
    }

    /** 形象列表查询（keyword/scenario 过滤 + 分页） */
    @GetMapping("/avatars")
    public BaiResponse<DigitalHumanDtos.AvatarList> listAvatars(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "scenario", required = false) String scenario,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.listAvatars(keyword, scenario, page));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limitRender(String callerKey) {
        if (!renderLimiter.allow(callerKey)) {
            throw new BaiException(DigitalHumanErrorCodes.RATE_LIMITED,
                    "DHM_1008 触发限流（单调用方 20 QPS），请按 Retry-After 退避重试");
        }
    }

    private void limitInstance() {
        if (!instanceLimiter.allow("instance-global")) {
            throw new BaiException(DigitalHumanErrorCodes.RATE_LIMITED,
                    "DHM_1008 触发限流（单实例并发 100 QPS），请按 Retry-After 退避重试");
        }
    }

    private String caller(Map<String, Object> body) {
        Object clientId = body == null ? null : body.get("clientId");
        return clientId == null ? "anonymous" : String.valueOf(clientId);
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
