package com.bai.auth;

import java.util.List;

/**
 * 身份认证输出统一结构（提示词库 A-48：AuthResult）。
 * <p>tokens 仅认证通过后返回（accessToken 30 分钟 / refreshToken 7 天轮换制）；
 * MEDIUM 追加第二因子（OTP），HIGH 强认证（生物+OTP），CRITICAL 阻断；
 * verify 返回主体上下文（userId/roleIds/orgId）；令牌不落明文日志。
 */
public final class AuthDtos {

    private AuthDtos() {}

    /** 身份要素：账号/手机/证件 */
    public record Identity(String userId, String mobile, String certNo, String name) {}

    /** 认证因子：type=PASSWORD/OTP/BIOMETRIC/CERT/SSO + credential */
    public record Factor(String type, String credential) {}

    /** 登录环境上下文：设备指纹/IP/UA/地理 */
    public record AuthContext(String deviceId, String deviceFp, String ip, String userAgent, String geo) {}

    /** 登录入参：scene=CHANNEL/BACKOFFICE/AGENT/API */
    public record LoginRequest(Identity identity, Factor factor, AuthContext context, String scene) {}

    /** 凭证核验/二次因子入参：token 校验或 authId+factor 完成 MFA 追加因子 */
    public record VerifyRequest(String token, String authId, Factor factor) {}

    /** 刷新令牌入参（轮换制：旧 refreshToken 立即失效） */
    public record RefreshRequest(String refreshToken) {}

    /** 登出入参（亦可仅凭 Authorization 头） */
    public record LogoutRequest(String token) {}

    /** 令牌对 */
    public record Tokens(String accessToken, String refreshToken, long expiresIn, String tokenType) {}

    /** 主体上下文 */
    public record Subject(String userId, List<String> roleIds, String orgId) {}

    /** 认证结论 */
    public record AuthResult(String authId, boolean authenticated, String riskLevel, boolean mfaRequired,
                             String nextFactor, Tokens tokens, Subject subject, long elapsedMs) {}
}
