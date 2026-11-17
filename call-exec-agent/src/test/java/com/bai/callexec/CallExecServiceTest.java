package com.bai.callexec;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CallExecService 单元测试：调用执行（确定性哈希驱动状态）、参数契约、白名单/鉴权、定义管理、幂等。
 * <p>确定性仿真：状态由 requestId+definitionId+params 指纹哈希决定，测试内复算同一公式断言状态一致。
 */
class CallExecServiceTest {

    private final CallExecService service = new CallExecService();

    /** 复算 Service 内 shaKey（UUID.nameUUIDFromBytes().hashCode()） */
    private static int shaKey(String s) {
        return UUID.nameUUIDFromBytes(s.getBytes()).hashCode();
    }

    /** 按 Service 公式计算期望状态（disbursement-api 为 P0，无 DEGRADED 分支） */
    private static String expectedStatus(String requestId, String definitionId, Map<String, Object> params) {
        String fingerprint = Integer.toHexString(shaKey(definitionId + "|" + params));
        int h = Math.abs(shaKey(requestId + "|" + definitionId + "|" + fingerprint));
        if (h % 97 == 0) {
            return "TIMEOUT";
        }
        if (h % 53 == 0) {
            return "FAILED";
        }
        if (h % 25 == 7) {
            return "RETRIED";
        }
        return "SUCCESS";
    }

    private Map<String, Object> params(String loanNo, Object amount, String accountNo) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("loanNo", loanNo);
        p.put("amount", amount);
        p.put("accountNo", accountNo);
        return p;
    }

    private Map<String, Object> execBody(String definitionId, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("definitionId", definitionId);
        body.put("params", params);
        return body;
    }

    // ------------------------------------------------------------ 主能力

    @Test
    void 放款指令执行_回执含交易流水号() {
        // REQ-DIS-001 经复算期望 SUCCESS（哈希非 97/53/25 命中）
        CallExecDtos.CallResult r = service.execute(execBody("DEF_DISBURSE_001",
                        params("LN001", 100000, "6222020202020202")), "REQ-DIS-001", "T-001", "Bearer x");
        assertEquals("SUCCESS", r.status());
        assertEquals(expectedStatus("REQ-DIS-001", "DEF_DISBURSE_001",
                params("LN001", 100000, "6222020202020202")), r.status());
        assertEquals("disbursement-api", r.targetService());
        assertEquals(0, r.retryCount());
        assertTrue(String.valueOf(r.result().get("txnNo")).startsWith("TXN"));
        assertEquals("LN001", r.result().get("loanNo"));
    }

    @Test
    void 执行状态_与哈希公式复算一致() {
        // 任意 requestId 下，Service 判定状态与复算公式一致（确定性）
        String rid = "REQ-ANY-42";
        Map<String, Object> p = params("LN002", 50000, "6222000011112222");
        CallExecDtos.CallResult r = service.execute(execBody("DEF_DISBURSE_001", p), rid, "T-1", "Bearer x");
        assertEquals(expectedStatus(rid, "DEF_DISBURSE_001", p), r.status());
    }

    @Test
    void P0资金类_永不降级() {
        // P0 优先级无 DEGRADED 分支，多个 requestId 断言
        for (String rid : new String[]{"REQ-DIS-001", "REQ-DIS-002", "REQ-DIS-003", "REQ-DIS-004"}) {
            CallExecDtos.CallResult r = service.execute(execBody("DEF_DISBURSE_001",
                    params("LN0", 100, "6222020202020202")), rid, "T-1", "Bearer x");
            assertNotEquals("DEGRADED", r.status());
        }
    }

    @Test
    void 短信下发_手机号脱敏() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("phone", "13800138000");
        p.put("templateId", "TMPL_001");
        CallExecDtos.CallResult r = service.execute(execBody("DEF_SMS_001", p), "REQ-SMS-001", "T-1", "Bearer x");
        assertEquals("sms-api", r.targetService());
        // 脱敏保留前 3 后 4
        assertEquals("138****8000", r.result().get("phone"));
    }

    // ------------------------------------------------------------ 参数校验/鉴权

    @Test
    void 缺definitionId_抛CEX1001() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("params", Map.of("a", 1));
        BaiException e = assertThrows(BaiException.class,
                () -> service.execute(body, "req-e1", "T-1", "Bearer x"));
        assertEquals("CEX_1001", e.getCode());
    }

    @Test
    void 缺traceId_抛CEX1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.execute(execBody("DEF_SMS_001", Map.of("phone", "138", "templateId", "T1")),
                        "req-e2", null, "Bearer x"));
        assertEquals("CEX_1001", e.getCode());
    }

    @Test
    void 缺Token_抛CEX1002() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.execute(execBody("DEF_SMS_001", Map.of("phone", "138", "templateId", "T1")),
                        "req-e3", "T-1", null));
        assertEquals("CEX_1002", e.getCode());
    }

    @Test
    void 定义不存在_抛CEX1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.execute(execBody("DEF_NOPE", Map.of("a", 1)), "req-e4", "T-1", "Bearer x"));
        assertEquals("CEX_1004", e.getCode());
    }

    @Test
    void 缺必填参数_抛CEX1007() {
        // DEF_SMS_001 必填 phone+templateId，缺 templateId
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("phone", "13800138000");
        BaiException e = assertThrows(BaiException.class,
                () -> service.execute(execBody("DEF_SMS_001", p), "req-e5", "T-1", "Bearer x"));
        assertEquals("CEX_1007", e.getCode());
    }

    @Test
    void 单参数值超长_抛CEX1006() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("loanNo", "LN1");
        p.put("amount", "x".repeat(300));   // > 256
        p.put("accountNo", "622");
        BaiException e = assertThrows(BaiException.class,
                () -> service.execute(execBody("DEF_DISBURSE_001", p), "req-e6", "T-1", "Bearer x"));
        assertEquals("CEX_1006", e.getCode());
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void 同requestId同参数_返回首次callId() {
        String rid = "idem-c1";
        Map<String, Object> p = params("LN100", 100000, "6222020202020202");
        CallExecDtos.CallResult a = service.execute(execBody("DEF_DISBURSE_001", p), rid, "T-1", "Bearer x");
        CallExecDtos.CallResult b = service.execute(execBody("DEF_DISBURSE_001", p), rid, "T-1", "Bearer x");
        assertEquals(a.callId(), b.callId());
        assertEquals(a.status(), b.status());
    }

    // ------------------------------------------------------------ 定义管理/历史

    @Test
    void 定义创建_白名单外抛CEX1003() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "CREATE");
        body.put("definitionId", "DEF_X_001");
        body.put("name", "外挂");
        body.put("targetService", "hack-api");
        BaiException e = assertThrows(BaiException.class, () -> service.manageDefinition(body));
        assertEquals("CEX_1003", e.getCode());
    }

    @Test
    void 定义重复创建_抛CEX1005() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "CREATE");
        body.put("definitionId", "DEF_X_002");
        body.put("name", "自定义");
        body.put("targetService", "sms-api");
        service.manageDefinition(body);
        BaiException e = assertThrows(BaiException.class, () -> service.manageDefinition(body));
        assertEquals("CEX_1005", e.getCode());
    }

    @Test
    void 历史查询_不存在callId抛CEX1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.queryHistory("CL-GHOST", null, null));
        assertEquals("CEX_1004", e.getCode());
    }
}
