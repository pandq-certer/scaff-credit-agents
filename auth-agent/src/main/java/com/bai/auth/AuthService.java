package com.bai.auth;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 身份认证服务（提示词库 A-48，BIA-RSK-047）。
 * <p>处理链：入参校验 → 身份核验（凭证核验为确定性仿真，用于联调）→ 风险评估（设备指纹/IP/地理，仿真）→
 * 自适应 MFA（LOW 单因子、MEDIUM 双因子、HIGH 强认证、CRITICAL 阻断）→
 * 令牌签发（accessToken 30 分钟，refreshToken 7 天轮换制）→ 留痕告警（令牌不落明文日志）。
 * <p>幂等：requestId+identity+context 不重复登录；连续 5 次凭证错误锁定 15 分钟；限流登录 5 QPS/用户、核验 50 QPS/用户。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final Set<String> FACTOR_TYPES = Set.of("PASSWORD", "OTP", "BIOMETRIC", "CERT", "SSO");
    private static final Set<String> SCENES = Set.of("CHANNEL", "BACKOFFICE", "AGENT", "API");
    private static final Set<String> SECOND_FACTORS = Set.of("OTP", "BIOMETRIC");
    private static final Pattern OTP_PATTERN = Pattern.compile("\\d{6}");
    private static final long ACCESS_TTL_MILLIS = 30 * 60 * 1000L;          // accessToken 30 分钟
    private static final long REFRESH_TTL_MILLIS = 7 * 24 * 3600 * 1000L;   // refreshToken 7 天
    private static final long LOCK_MILLIS = 15 * 60 * 1000L;                // 连续失败锁定 15 分钟
    private static final int MAX_FAILS = 5;
    private static final long MFA_PENDING_TTL_MILLIS = 10 * 60 * 1000L;
    private static final String SIGN_KEY = "bai-auth-sim"; // 仿真签名密钥，生产替换为 KMS 托管密钥（国密 SM4 凭证加密/令牌签名）

    private final IdempotencyStore idempotency = new IdempotencyStore();
    /** accessToken → 令牌条目；refreshToken → 令牌条目（轮换制：旧令牌立即失效） */
    private final Map<String, TokenEntry> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, TokenEntry> refreshTokens = new ConcurrentHashMap<>();
    /** MFA 待完成会话：authId → 暂存认证 */
    private final Map<String, PendingAuth> pendingMfa = new ConcurrentHashMap<>();
    /** 连续失败锁定：身份键 → 计数/锁定截止 */
    private final Map<String, LockState> lockStates = new ConcurrentHashMap<>();
    /** 已登记设备指纹（新设备/异地强制 MFA，仿真） */
    private final Set<String> knownDevices = ConcurrentHashMap.newKeySet();
    private final AtomicLong seq = new AtomicLong();

    // ------------------------------------------------------------------ login

    /** 登录（同步）：凭证核验 → 风险评估 → 自适应 MFA → 令牌签发 */
    public AuthDtos.AuthResult login(AuthDtos.LoginRequest request, String requestId) {
        long start = System.currentTimeMillis();
        AuthDtos.Identity identity = request == null ? null : request.identity();
        AuthDtos.Factor factor = request == null ? null : request.factor();
        AuthDtos.AuthContext context = request == null ? null : request.context();
        String idKey = identityKey(identity);

        // 幂等：requestId+identity+context 同键返回首次结果，不重复登录
        AuthDtos.AuthResult cached = idempotency.get("login", requestId + ":" + idKey + ":" + contextKey(context));
        if (cached != null) {
            return cached;
        }
        validateLogin(identity, factor, context, request == null ? null : request.scene());
        String lockKey = idKey;
        LockState lock = lockStates.computeIfAbsent(lockKey, k -> new LockState());
        synchronized (lock) {
            if (lock.lockUntil > System.currentTimeMillis()) {
                throw new BaiException(AuthErrorCodes.LOCKED,
                        "AUT_1003 账户已锁定（连续 " + MAX_FAILS + " 次凭证错误），请 15 分钟后重试");
            }
        }

        // 1. 身份核验（仿真，用于联调）：credential="wrong"/"error" 视为凭证错误
        boolean credentialOk = checkCredential(factor);
        // 2. 风险评估（仿真）：新设备 +30、异地/海外 +30、UA 缺失 +10
        String riskLevel = assessRisk(context);
        String authId = "AUT" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000);
        long elapsed = System.currentTimeMillis() - start;

        AuthDtos.AuthResult result;
        if (!credentialOk) {
            registerFail(lock);
            result = new AuthDtos.AuthResult(authId, false, "HIGH", false, null, null, subjectOf(identity), elapsed);
        } else if ("CRITICAL".equals(riskLevel)) {
            // CRITICAL 阻断并提示
            result = new AuthDtos.AuthResult(authId, false, riskLevel, false, null, null, subjectOf(identity), elapsed);
        } else if ("LOW".equals(riskLevel)) {
            // LOW 单因子通过：直接签发令牌
            AuthDtos.Subject subject = subjectOf(identity);
            result = new AuthDtos.AuthResult(authId, true, riskLevel, false, null,
                    issueTokens(authId, subject), subject, elapsed);
            resetFail(lock);
        } else {
            // MEDIUM 追加第二因子（OTP）；HIGH 追加强认证（生物+OTP → 下一因子 BIOMETRIC）
            String nextFactor = "HIGH".equals(riskLevel) ? "BIOMETRIC" : "OTP";
            pendingMfa.put(authId, new PendingAuth(authId, subjectOf(identity), riskLevel,
                    System.currentTimeMillis() + MFA_PENDING_TTL_MILLIS));
            result = new AuthDtos.AuthResult(authId, false, riskLevel, true, nextFactor, null, subjectOf(identity), elapsed);
        }
        idempotency.put("login", requestId + ":" + idKey + ":" + contextKey(context), result);
        // 留痕：仅记录身份标识与结论，令牌/凭证不落明文日志
        log.info("登录认证完成: requestId={}, scene={}, riskLevel={}, authenticated={}, mfaRequired={}",
                requestId, request.scene(), result.riskLevel(), result.authenticated(), result.mfaRequired());
        return result;
    }

    // ----------------------------------------------------------------- verify

    /** 凭证核验（同步）：authId+factor 完成 MFA 追加因子；否则校验令牌签名与有效期并返回主体上下文 */
    public AuthDtos.AuthResult verify(AuthDtos.VerifyRequest request, String bearerToken, String requestId) {
        long start = System.currentTimeMillis();
        if (request == null || (isBlank(request.authId()) && isBlank(request.token()) && isBlank(bearerToken))) {
            throw new BaiException(AuthErrorCodes.PARAM_INVALID, "AUT_1001 参数缺失：token 或 authId+factor 必填其一");
        }
        long elapsed = System.currentTimeMillis() - start;
        // MFA 追加因子完成认证（调用方凭 authId 携带 OTP/生物特征再次 verify）
        if (!isBlank(request.authId())) {
            return completeMfa(request, start);
        }
        String token = isBlank(request.token()) ? bearerToken : request.token();
        TokenEntry entry = resolveAccessToken(token);
        return new AuthDtos.AuthResult(entry.authId, true, "LOW", false, null, null, entry.subject, elapsed);
    }

    private AuthDtos.AuthResult completeMfa(AuthDtos.VerifyRequest request, long start) {
        AuthDtos.Factor factor = request.factor();
        if (factor == null || isBlank(factor.type())) {
            throw new BaiException(AuthErrorCodes.PARAM_INVALID, "AUT_1001 参数缺失：factor 必填");
        }
        String type = factor.type().toUpperCase();
        if (!SECOND_FACTORS.contains(type)) {
            throw new BaiException(AuthErrorCodes.FACTOR_INVALID, "AUT_1007 追加因子类型非法，允许 OTP/BIOMETRIC");
        }
        PendingAuth pending = pendingMfa.get(request.authId());
        if (pending == null || pending.expireAt < System.currentTimeMillis()) {
            pendingMfa.remove(request.authId());
            throw new BaiException(AuthErrorCodes.IDENTITY_NOT_FOUND, "AUT_1004 认证记录不存在或已过期，请重新登录");
        }
        boolean ok = "BIOMETRIC".equals(type) || OTP_PATTERN.matcher(factor.credential() == null ? "" : factor.credential()).matches();
        if (!ok) {
            String nextFactor = "HIGH".equals(pending.riskLevel) ? "BIOMETRIC" : "OTP";
            return new AuthDtos.AuthResult(pending.authId, false, pending.riskLevel, true,
                    nextFactor, null, pending.subject, System.currentTimeMillis() - start);
        }
        pendingMfa.remove(request.authId());
        return new AuthDtos.AuthResult(pending.authId, true, pending.riskLevel, false, null,
                issueTokens(pending.authId, pending.subject), pending.subject, System.currentTimeMillis() - start);
    }

    // ---------------------------------------------------------------- refresh

    /** 令牌刷新（同步）：轮换制，旧 accessToken/refreshToken 立即失效 */
    public AuthDtos.AuthResult refresh(String refreshToken, String requestId) {
        long start = System.currentTimeMillis();
        if (isBlank(refreshToken)) {
            throw new BaiException(AuthErrorCodes.TOKEN_INVALID, "AUT_1002 Token 缺失：refreshToken 必填（refresh 必带）");
        }
        TokenEntry entry = refreshTokens.get(refreshToken);
        if (entry == null || entry.revoked) {
            throw new BaiException(AuthErrorCodes.TOKEN_INVALID, "AUT_1002 refreshToken 无效或已轮换，请重新登录");
        }
        if (entry.refreshExpAt < System.currentTimeMillis()) {
            refreshTokens.remove(refreshToken);
            throw new BaiException(AuthErrorCodes.TOKEN_INVALID, "AUT_1002 refreshToken 已过期，请重新登录");
        }
        // 轮换：旧令牌立即失效
        entry.revoked = true;
        accessTokens.remove(entry.accessToken);
        refreshTokens.remove(refreshToken);
        AuthDtos.AuthResult result = new AuthDtos.AuthResult(entry.authId, true, "LOW", false, null,
                issueTokens(entry.authId, entry.subject), entry.subject, System.currentTimeMillis() - start);
        log.info("令牌刷新完成（轮换制）: requestId={}", requestId);
        return result;
    }

    // ----------------------------------------------------------------- logout

    /** 登出（同步）：吊销 accessToken 与关联 refreshToken */
    public AuthDtos.AuthResult logout(String token, String requestId) {
        long start = System.currentTimeMillis();
        if (isBlank(token)) {
            throw new BaiException(AuthErrorCodes.TOKEN_INVALID, "AUT_1002 Token 缺失（logout 必带 Authorization）");
        }
        TokenEntry entry = accessTokens.get(token);
        if (entry == null || entry.revoked) {
            throw new BaiException(AuthErrorCodes.TOKEN_INVALID, "AUT_1002 Token 无效或已登出");
        }
        entry.revoked = true;
        accessTokens.remove(token);
        if (entry.refreshToken != null) {
            TokenEntry r = refreshTokens.get(entry.refreshToken);
            if (r != null) {
                r.revoked = true;
            }
            refreshTokens.remove(entry.refreshToken);
        }
        log.info("登出完成: requestId={}", requestId);
        return new AuthDtos.AuthResult(entry.authId, false, "LOW", false, null, null, null,
                System.currentTimeMillis() - start);
    }

    // --------------------------------------------------------------- internal

    private void validateLogin(AuthDtos.Identity identity, AuthDtos.Factor factor,
                               AuthDtos.AuthContext context, String scene) {
        if (identity == null || (isBlank(identity.userId()) && isBlank(identity.mobile()) && isBlank(identity.certNo()))) {
            throw new BaiException(AuthErrorCodes.PARAM_INVALID, "AUT_1001 参数缺失：identity（账号/手机/证件）必填");
        }
        if (factor == null || isBlank(factor.type())) {
            throw new BaiException(AuthErrorCodes.PARAM_INVALID, "AUT_1001 参数缺失：factor 必填");
        }
        if (isBlank(factor.credential())) {
            throw new BaiException(AuthErrorCodes.PARAM_INVALID, "AUT_1001 参数缺失：factor.credential 必填");
        }
        if (!FACTOR_TYPES.contains(factor.type().toUpperCase())) {
            throw new BaiException(AuthErrorCodes.FACTOR_INVALID,
                    "AUT_1007 因子类型非法，允许 PASSWORD/OTP/BIOMETRIC/CERT/SSO");
        }
        if (context == null) {
            throw new BaiException(AuthErrorCodes.PARAM_INVALID, "AUT_1001 参数缺失：context（deviceId/deviceFp/ip/userAgent/geo）必填");
        }
        if (isBlank(scene)) {
            throw new BaiException(AuthErrorCodes.PARAM_INVALID, "AUT_1001 参数缺失：scene 必填");
        }
        if (!SCENES.contains(scene.toUpperCase())) {
            throw new BaiException(AuthErrorCodes.PARAM_INVALID, "AUT_1001 scene 非法，允许 CHANNEL/BACKOFFICE/AGENT/API");
        }
    }

    /** 凭证核验（仿真，用于联调）：PASSWORD credential="wrong"/"error" 为错；OTP 须 6 位数字；生物/证书/SSO 通过 */
    private boolean checkCredential(AuthDtos.Factor factor) {
        String type = factor.type().toUpperCase();
        String credential = factor.credential() == null ? "" : factor.credential();
        return switch (type) {
            case "PASSWORD" -> !credential.equals("wrong") && !credential.equals("error");
            case "OTP" -> OTP_PATTERN.matcher(credential).matches();
            case "BIOMETRIC" -> true;  // 生物比对仿真：比对得分 0.95
            case "CERT", "SSO" -> !credential.isBlank();
            default -> false;
        };
    }

    /** 风险评估（仿真，用于联调）：新设备/异地/海外/UA 缺失加权 → LOW/MEDIUM/HIGH/CRITICAL */
    private String assessRisk(AuthDtos.AuthContext context) {
        int score = 20;
        String deviceKey = isBlank(context.deviceFp()) ? context.deviceId() : context.deviceFp();
        boolean newDevice = isBlank(deviceKey) || !knownDevices.contains(deviceKey);
        if (newDevice) {
            score += 30;
        }
        String geo = context.geo() == null ? "" : context.geo();
        if (geo.contains("异地") || geo.contains("海外") || geo.contains("境外")) {
            score += 30;
        }
        if (isBlank(context.userAgent())) {
            score += 10;
        }
        if (geo.contains("境外") && newDevice) {
            score += 40; // 异地+新设备组合高危
        }
        if (score >= 100) {
            return "CRITICAL";
        }
        if (score >= 70) {
            return "HIGH";
        }
        if (score >= 40) {
            return "MEDIUM";
        }
        if (deviceKey != null && !isBlank(deviceKey)) {
            knownDevices.add(deviceKey);
        }
        return "LOW";
    }

    /** 签发令牌对（仿真签名）：accessToken 30 分钟 / refreshToken 7 天 */
    private AuthDtos.Tokens issueTokens(String authId, AuthDtos.Subject subject) {
        String accessBody = UUID.randomUUID().toString().replace("-", "");
        String refreshBody = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        String accessToken = "AT." + accessBody + "." + sign(accessBody);
        String refreshToken = "RT." + refreshBody + "." + sign(refreshBody);
        TokenEntry entry = new TokenEntry(authId, subject, accessToken, refreshToken,
                now + ACCESS_TTL_MILLIS, now + REFRESH_TTL_MILLIS);
        accessTokens.put(accessToken, entry);
        refreshTokens.put(refreshToken, entry);
        return new AuthDtos.Tokens(accessToken, refreshToken, ACCESS_TTL_MILLIS / 1000, "Bearer");
    }

    private TokenEntry resolveAccessToken(String token) {
        if (isBlank(token)) {
            throw new BaiException(AuthErrorCodes.TOKEN_INVALID, "AUT_1002 Token 缺失，请重新登录");
        }
        TokenEntry entry = accessTokens.get(token);
        if (entry == null || entry.revoked) {
            throw new BaiException(AuthErrorCodes.TOKEN_INVALID, "AUT_1002 Token 无效，请重新登录");
        }
        if (entry.accessExpAt < System.currentTimeMillis()) {
            accessTokens.remove(token);
            throw new BaiException(AuthErrorCodes.TOKEN_INVALID, "AUT_1002 Token 已过期，请重新登录或刷新");
        }
        return entry;
    }

    private AuthDtos.Subject subjectOf(AuthDtos.Identity identity) {
        String userId = isBlank(identity.userId())
                ? (isBlank(identity.mobile()) ? identity.certNo() : identity.mobile())
                : identity.userId();
        // 主体上下文仿真：角色/机构按场景确定性生成，用于联调
        return new AuthDtos.Subject(userId, List.of("ROLE_CUSTOMER_MANAGER"), "ORG_CREDIT_001");
    }

    private String sign(String body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((body + ":" + SIGN_KEY).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return "00000000";
        }
    }

    private String identityKey(AuthDtos.Identity identity) {
        if (identity == null) {
            return "anonymous";
        }
        return !isBlank(identity.userId()) ? identity.userId()
                : !isBlank(identity.mobile()) ? identity.mobile()
                : isBlank(identity.certNo()) ? "anonymous" : identity.certNo();
    }

    private String contextKey(AuthDtos.AuthContext context) {
        if (context == null) {
            return "-";
        }
        String fp = isBlank(context.deviceFp()) ? context.deviceId() : context.deviceFp();
        return (fp == null ? "-" : fp) + ":" + (context.ip() == null ? "-" : context.ip());
    }

    private void registerFail(LockState lock) {
        synchronized (lock) {
            lock.fails++;
            if (lock.fails >= MAX_FAILS) {
                lock.lockUntil = System.currentTimeMillis() + LOCK_MILLIS;
                lock.fails = 0;
            }
        }
    }

    private void resetFail(LockState lock) {
        synchronized (lock) {
            lock.fails = 0;
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static final class TokenEntry {
        final String authId;
        final AuthDtos.Subject subject;
        final String accessToken;
        final String refreshToken;
        final long accessExpAt;
        final long refreshExpAt;
        volatile boolean revoked;

        TokenEntry(String authId, AuthDtos.Subject subject, String accessToken, String refreshToken,
                   long accessExpAt, long refreshExpAt) {
            this.authId = authId;
            this.subject = subject;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.accessExpAt = accessExpAt;
            this.refreshExpAt = refreshExpAt;
        }
    }

    private record PendingAuth(String authId, AuthDtos.Subject subject, String riskLevel, long expireAt) {}

    private static final class LockState {
        int fails;
        long lockUntil;
    }
}
