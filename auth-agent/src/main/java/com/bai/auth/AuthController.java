package com.bai.auth;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 身份认证原子接口（提示词库 A-48：login/verify/refresh/logout/health）。
 * <p>auth-agent 本身承担认证签发职责；操作授权（permission-check-agent）、脱敏、欺诈决策不在本原子边界内。
 * 权限点 AUT_LOGIN/AUT_VERIFY/AUT_REFRESH/AUT_LOGOUT/AUT_ADMIN；接口传输 TLS 1.2+（网关侧终止）。
 */
@RestController
public class AuthController {

    private final AuthService service;
    /** 限流：登录 5 QPS/用户（refresh/logout 为敏感写操作共用该档）、核验 50 QPS/用户 */
    private final TokenBuckets loginLimiter = new TokenBuckets(5, 10);
    private final TokenBuckets verifyLimiter = new TokenBuckets(50, 100);

    public AuthController(AuthService service) {
        this.service = service;
    }

    /** 登录（同步）：identity + factor{type,credential} + context + scene */
    @PostMapping("/login")
    public BaiResponse<AuthDtos.AuthResult> login(@RequestBody AuthDtos.LoginRequest request,
                                                  @RequestHeader("X-Request-Id") String requestId) {
        String userKey = request != null && request.identity() != null
                ? identityKey(request.identity()) : "anonymous";
        if (!loginLimiter.allow(userKey)) {
            throw new BaiException(AuthErrorCodes.RATE_LIMITED,
                    "AUT_1008 触发限流（登录 5 QPS/用户），请按 Retry-After 退避重试");
        }
        return BaiResponse.ok(requestId, service.login(request, requestId));
    }

    /** 凭证核验（同步）：token 校验返回主体上下文；或 authId+factor 完成 MFA 追加因子 */
    @PostMapping("/verify")
    public BaiResponse<AuthDtos.AuthResult> verify(@RequestBody(required = false) AuthDtos.VerifyRequest request,
                                                   @RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @RequestHeader("X-Request-Id") String requestId) {
        if (!verifyLimiter.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(AuthErrorCodes.RATE_LIMITED,
                    "AUT_1008 触发限流（核验 50 QPS/用户），请按 Retry-After 退避重试");
        }
        String bearer = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        return BaiResponse.ok(requestId, service.verify(request, bearer, requestId));
    }

    /** 令牌刷新（同步）：轮换制，旧令牌立即失效；Authorization/refreshToken 必填 */
    @PostMapping("/refresh")
    public BaiResponse<AuthDtos.AuthResult> refresh(@RequestBody(required = false) AuthDtos.RefreshRequest request,
                                                    @RequestHeader(value = "Authorization", required = false) String authorization,
                                                    @RequestHeader("X-Request-Id") String requestId) {
        if (!loginLimiter.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(AuthErrorCodes.RATE_LIMITED,
                    "AUT_1008 触发限流，请按 Retry-After 退避重试");
        }
        String refreshToken = request != null && request.refreshToken() != null ? request.refreshToken()
                : (authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization);
        return BaiResponse.ok(requestId, service.refresh(refreshToken, requestId));
    }

    /** 登出（同步）：Authorization 必填 */
    @PostMapping("/logout")
    public BaiResponse<AuthDtos.AuthResult> logout(@RequestBody(required = false) AuthDtos.LogoutRequest request,
                                                   @RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @RequestHeader("X-Request-Id") String requestId) {
        if (!loginLimiter.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(AuthErrorCodes.RATE_LIMITED,
                    "AUT_1008 触发限流，请按 Retry-After 退避重试");
        }
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        return BaiResponse.ok(requestId, service.logout(token, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private String identityKey(AuthDtos.Identity identity) {
        if (identity.userId() != null && !identity.userId().isBlank()) {
            return identity.userId();
        }
        if (identity.mobile() != null && !identity.mobile().isBlank()) {
            return identity.mobile();
        }
        return identity.certNo() == null || identity.certNo().isBlank() ? "anonymous" : identity.certNo();
    }
}
