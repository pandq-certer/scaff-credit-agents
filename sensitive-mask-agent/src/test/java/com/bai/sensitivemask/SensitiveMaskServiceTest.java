package com.bai.sensitivemask;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SensitiveMaskService 单元测试：FIELD/TEXT/BATCH 三类数据、场景→算法固定映射（DISPLAY→MASK、
 * EXPORT→FPE、LOG→HASH、DEV/TEST→REPLACE、STAT→TRUNCATE）、识别/抽检、规则管理、幂等。
 * <p>确定性仿真：正则识别 + 固定算法映射，可直接断言脱敏结果。
 */
class SensitiveMaskServiceTest {

    private final SensitiveMaskService service = new SensitiveMaskService();

    private Map<String, Object> fieldBody(String scenario, Map<String, Object> fields) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("dataType", "FIELD");
        b.put("scenario", scenario);
        b.put("fields", fields);
        return b;
    }

    // ------------------------------------------------------------ 主能力（场景→算法）

    @Test
    void 字段脱敏_DISPLAY掩码保留首尾() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("mobile", "13800138000");
        fields.put("idCard", "110101199001011234");
        SensitiveMaskDtos.MaskResult r = service.mask(fieldBody("DISPLAY", fields), "req-m1");
        assertEquals("138**8000", ((Map<?, ?>) r.maskedFields()).get("mobile"));
        assertEquals("110***1234", ((Map<?, ?>) r.maskedFields()).get("idCard"));
        assertEquals(2, r.detectedCount());
        assertTrue(r.ruleVersions().contains("RULE_MOBILE_V2"));
        assertTrue(r.ruleVersions().contains("RULE_IDCARD_V5"));
        assertNotNull(r.maskId());
    }

    @Test
    void 文本脱敏_DISPLAY检出手机号() {
        SensitiveMaskDtos.MaskResult r = service.mask(
                Map.of("dataType", "TEXT", "scenario", "DISPLAY", "text", "客户张三 手机 13800138000"), "req-m2");
        assertNotNull(r.maskedText());
        assertTrue(r.maskedText().contains("138**8000"));
        assertFalse(r.maskedText().contains("13800138000"));
        assertEquals(2, r.detectedCount());   // 姓名 + 手机号
    }

    @Test
    void 文本脱敏_EXPORT格式保留加密() {
        String text = "手机号 13800138000";
        SensitiveMaskDtos.MaskResult r = service.mask(
                Map.of("dataType", "TEXT", "scenario", "EXPORT", "text", text), "req-m3");
        assertEquals(text.length(), r.maskedText().length());
        assertNotEquals(text, r.maskedText());
    }

    @Test
    void 文本脱敏_LOG哈希摘要() {
        SensitiveMaskDtos.MaskResult r = service.mask(
                Map.of("dataType", "TEXT", "scenario", "LOG", "text", "手机 13800138000"), "req-m4");
        assertTrue(r.maskedText().contains("SM3*"));
        assertFalse(r.maskedText().contains("13800138000"));
    }

    @Test
    void 字段脱敏_DEV彻底替换() {
        SensitiveMaskDtos.MaskResult r = service.mask(
                fieldBody("DEV", Map.of("name", "张三", "mobile", "13800138000")), "req-m5");
        assertEquals("REDACTED", ((Map<?, ?>) r.maskedFields()).get("mobile"));
        assertEquals("REDACTED", ((Map<?, ?>) r.maskedFields()).get("name"));
    }

    @Test
    void 字段脱敏_STAT截断聚合口径() {
        SensitiveMaskDtos.MaskResult r = service.mask(
                fieldBody("STAT", Map.of("mobile", "13800138000")), "req-m6");
        assertEquals("138****", ((Map<?, ?>) r.maskedFields()).get("mobile"));
    }

    @Test
    void 批量脱敏_逐行按字段规则() {
        List<Map<String, Object>> rows = List.of(
                Map.of("mobile", "13800138000"),
                Map.of("idCard", "110101199001011234"));
        SensitiveMaskDtos.MaskResult r = service.mask(
                Map.of("dataType", "BATCH", "scenario", "DISPLAY", "fields", rows), "req-m7");
        assertEquals(2, r.detectedCount());
        assertTrue(r.maskedFields() instanceof List<?> maskedRows && maskedRows.size() == 2);
    }

    // ------------------------------------------------------------ 识别/抽检

    @Test
    void 敏感识别_检出类型与位置() {
        SensitiveMaskDtos.DetectResult r = service.detect(
                Map.of("text", "客户张三 手机 13800138000"));
        assertEquals(2, r.detectedCount());
        assertTrue(r.detections().stream().anyMatch(d -> "MOBILE".equals(d.type())));
        assertTrue(r.detections().stream().anyMatch(d -> "NAME".equals(d.type())));
        assertEquals("138**8000", r.detections().stream()
                .filter(d -> "MOBILE".equals(d.type())).findFirst().orElseThrow().masked());
    }

    @Test
    void 抽检_脱敏后无残敏感() {
        SensitiveMaskDtos.MaskResult m = service.mask(
                Map.of("dataType", "TEXT", "scenario", "DISPLAY", "text", "手机 13800138000"), "req-v1");
        SensitiveMaskDtos.VerifyResult v = service.verify(Map.of("maskId", m.maskId()));
        assertTrue(v.clean());
        assertEquals(0, v.residuals().size());
    }

    @Test
    void 抽检_maskId不存在抛SMK1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.verify(Map.of("maskId", "SMK-GHOST")));
        assertEquals("SMK_1004", e.getCode());
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    void dataType缺失_抛SMK1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.mask(Map.of("scenario", "DISPLAY", "fields", Map.of("a", "1")), "req-e1"));
        assertEquals("SMK_1001", e.getCode());
    }

    @Test
    void dataType非法_抛SMK1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.mask(Map.of("dataType", "XML", "scenario", "DISPLAY", "fields", Map.of("a", "1")), "req-e2"));
        assertEquals("SMK_1007", e.getCode());
    }

    @Test
    void scenario非法_抛SMK1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.mask(Map.of("dataType", "FIELD", "scenario", "WEB", "fields", Map.of("a", "1")), "req-e3"));
        assertEquals("SMK_1007", e.getCode());
    }

    @Test
    void FIELD缺fields_抛SMK1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.mask(Map.of("dataType", "FIELD", "scenario", "DISPLAY"), "req-e4"));
        assertEquals("SMK_1007", e.getCode());
    }

    @Test
    void TEXT缺text_抛SMK1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.mask(Map.of("dataType", "TEXT", "scenario", "DISPLAY"), "req-e5"));
        assertEquals("SMK_1007", e.getCode());
    }

    // ------------------------------------------------------------ 规则管理/幂等

    @Test
    void 规则重复注册_抛SMK1005() {
        service.registerRule(Map.of("ruleId", "RULE_NEW_01", "name", "新规则", "sensitiveType", "NAME"), "req-r1");
        BaiException e = assertThrows(BaiException.class,
                () -> service.registerRule(Map.of("ruleId", "RULE_NEW_01", "name", "重复", "sensitiveType", "NAME"), "req-r2"));
        assertEquals("SMK_1005", e.getCode());
    }

    @Test
    void 规则algorithm非法_抛SMK1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.registerRule(Map.of("ruleId", "RULE_X", "name", "x", "sensitiveType", "NAME",
                        "algorithm", "AES"), "req-r3"));
        assertEquals("SMK_1001", e.getCode());
    }

    @Test
    void 同requestId同内容_返回首次maskId() {
        String rid = "idem-s1";
        Map<String, Object> body = fieldBody("DISPLAY", Map.of("mobile", "13800138000"));
        SensitiveMaskDtos.MaskResult a = service.mask(body, rid);
        SensitiveMaskDtos.MaskResult b = service.mask(body, rid);
        assertEquals(a.maskId(), b.maskId());
        assertEquals(a.maskedFields(), b.maskedFields());
    }

    @Test
    void 同入参两次调用_脱敏结果一致() {
        SensitiveMaskDtos.MaskResult a = service.mask(
                Map.of("dataType", "TEXT", "scenario", "DISPLAY", "text", "手机 13800138000"), "req-d1");
        SensitiveMaskDtos.MaskResult b = service.mask(
                Map.of("dataType", "TEXT", "scenario", "DISPLAY", "text", "手机 13800138000"), "req-d2");
        assertEquals(a.maskedText(), b.maskedText());
    }
}
