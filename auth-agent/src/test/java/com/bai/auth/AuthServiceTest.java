package com.bai.auth;

import com.bai.common.BaiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuthService 单元测试：自适应 MFA 分级（新设备 MEDIUM/异地 HIGH/境外高危 CRITICAL）、凭证核验、
 * 连续失败锁定、参数校验、MFA 追加因子完成、令牌轮换刷新。
 */
class AuthServiceTest {

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService();
    }

    private AuthDtos.LoginRequest loginReq(String userId, String credential, AuthDtos.AuthContext ctx, String scene) {
        return new AuthDtos.LoginRequest(new AuthDtos.Identity(userId, null, null, "张三"),
                new AuthDtos.Factor("PASSWORD", credential), ctx, scene);
    }

    private AuthDtos.AuthContext ctx(String deviceId, String geo, String ua) {
        return new AuthDtos.AuthContext(deviceId, null, "10.0.0.1", ua, geo);
    }

    @Test
    void 首次登录新设备_触发MEDIUM双因子OTP() {
        AuthDtos.AuthResult r = service.login(loginReq("u001", "pass123", ctx("dev1", "上海", "Mozilla"), "CHANNEL"), "l1");
        assertEquals("MEDIUM", r.riskLevel());
        assertFalse(r.authenticated());
        assertTrue(r.mfaRequired());
        assertEquals("OTP", r.nextFactor());
        assertNull(r.tokens());
        assertNotNull(r.authId());
    }

    @Test
    void 凭证错误_认证失败且不签发令牌() {
        AuthDtos.AuthResult r = service.login(loginReq("u002", "wrong", ctx("dev2", "上海", "Mozilla"), "CHANNEL"), "l2");
        assertFalse(r.authenticated());
        assertEquals("HIGH", r.riskLevel());
        assertNull(r.tokens());
    }

    @Test
    void 连续5次凭证错误_账户锁定抛AUT_1003() {
        for (int i = 1; i <= 5; i++) {
            AuthDtos.AuthResult r = service.login(
                    loginReq("u003", "wrong", ctx("dev3", "上海", "Mozilla"), "CHANNEL"), "lock-" + i);
            assertFalse(r.authenticated());
        }
        BaiException e = assertThrows(BaiException.class,
                () -> service.login(loginReq("u003", "right", ctx("dev3", "上海", "Mozilla"), "CHANNEL"), "lock-6"));
        assertEquals("AUT_1003", e.getCode());
    }

    @Test
    void 异地登录_触发HIGH强认证BIOMETRIC() {
        AuthDtos.AuthResult r = service.login(loginReq("u004", "pass123", ctx("dev4", "异地", "Mozilla"), "BACKOFFICE"), "l3");
        assertEquals("HIGH", r.riskLevel());
        assertFalse(r.authenticated());
        assertTrue(r.mfaRequired());
        assertEquals("BIOMETRIC", r.nextFactor());
    }

    @Test
    void 境外新设备缺UA_CRITICAL阻断() {
        AuthDtos.AuthResult r = service.login(loginReq("u005", "pass123", ctx("dev5", "境外", null), "CHANNEL"), "l4");
        assertEquals("CRITICAL", r.riskLevel());
        assertFalse(r.authenticated());
        assertFalse(r.mfaRequired());
        assertNull(r.tokens());
    }

    @Test
    void identity缺失_抛AUT_1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.login(new AuthDtos.LoginRequest(null,
                        new AuthDtos.Factor("PASSWORD", "x"), ctx("dev6", "上海", "UA"), "CHANNEL"), "e1"));
        assertEquals("AUT_1001", e.getCode());
    }

    @Test
    void 因子类型非法_抛AUT_1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.login(new AuthDtos.LoginRequest(new AuthDtos.Identity("u006", null, null, "李四"),
                        new AuthDtos.Factor("FACE", "x"), ctx("dev7", "上海", "UA"), "CHANNEL"), "e2"));
        assertEquals("AUT_1007", e.getCode());
    }

    @Test
    void MFA完成_OTP通过签发令牌() {
        AuthDtos.AuthResult login = service.login(loginReq("u007", "pass123", ctx("dev8", "上海", "Mozilla"), "CHANNEL"), "m1");
        assertTrue(login.mfaRequired());
        AuthDtos.AuthResult done = service.verify(
                new AuthDtos.VerifyRequest(null, login.authId(), new AuthDtos.Factor("OTP", "123456")), null, "m2");
        assertTrue(done.authenticated());
        assertNotNull(done.tokens());
        assertTrue(done.tokens().accessToken().startsWith("AT."));
        assertTrue(done.tokens().refreshToken().startsWith("RT."));
        assertEquals("MEDIUM", done.riskLevel());
    }

    @Test
    void 令牌刷新_轮换制旧令牌立即失效() {
        AuthDtos.AuthResult login = service.login(loginReq("u008", "pass123", ctx("dev9", "上海", "Mozilla"), "CHANNEL"), "m3");
        AuthDtos.AuthResult done = service.verify(
                new AuthDtos.VerifyRequest(null, login.authId(), new AuthDtos.Factor("OTP", "654321")), null, "m4");
        AuthDtos.Tokens oldTokens = done.tokens();
        AuthDtos.AuthResult refreshed = service.refresh(oldTokens.refreshToken(), "r1");
        assertTrue(refreshed.authenticated());
        AuthDtos.Tokens newTokens = refreshed.tokens();
        assertNotEquals(oldTokens.accessToken(), newTokens.accessToken());
        // 旧 accessToken 已随轮换失效
        BaiException e = assertThrows(BaiException.class,
                () -> service.verify(new AuthDtos.VerifyRequest(oldTokens.accessToken(), null, null), null, "m5"));
        assertEquals("AUT_1002", e.getCode());
        // 新令牌可用
        AuthDtos.AuthResult ok = service.verify(new AuthDtos.VerifyRequest(newTokens.accessToken(), null, null), null, "m6");
        assertTrue(ok.authenticated());
    }
}
