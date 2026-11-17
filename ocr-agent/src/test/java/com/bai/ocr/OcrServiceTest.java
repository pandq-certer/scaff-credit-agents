package com.bai.ocr;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OcrService 单元测试（感知类代表性原子）：JSON 直连识别（确定性仿真）、字段抽取、脱敏、字段白名单、幂等。
 */
class OcrServiceTest {

    private final OcrService service = new OcrService();

    @Test
    void 身份证识别_抽取姓名证件号() {
        OcrDtos.JsonRecognizeResult r = service.recognizeJson(
                Map.of("imageRef", "IMG-ID-001", "docType", "ID_CARD", "mask", false), "ocr-1");
        assertEquals("ID_CARD", r.docType());
        assertTrue(r.fields().containsKey("姓名"));
        assertTrue(r.fields().containsKey("证件号"));
        assertTrue(r.confidence() >= 0.90 && r.confidence() <= 0.99);
    }

    @Test
    void 同imageRef_确定性一致() {
        Map<String, Object> body = Map.of("imageRef", "IMG-CONST-1", "docType", "ID_CARD", "mask", false);
        OcrDtos.JsonRecognizeResult a = service.recognizeJson(body, "ocr-a");
        OcrDtos.JsonRecognizeResult b = service.recognizeJson(body, "ocr-b");
        assertEquals(a.fields(), b.fields());
        assertEquals(a.confidence(), b.confidence());
    }

    @Test
    void 脱敏_证件号被掩码() {
        OcrDtos.JsonRecognizeResult raw = service.recognizeJson(
                Map.of("imageRef", "IMG-MASK-1", "docType", "ID_CARD", "mask", false), "ocr-m0");
        OcrDtos.JsonRecognizeResult masked = service.recognizeJson(
                Map.of("imageRef", "IMG-MASK-1", "docType", "ID_CARD", "mask", true), "ocr-m1");
        String rawNo = raw.fields().get("证件号");
        String maskedNo = masked.fields().get("证件号");
        assertNotEquals(rawNo, maskedNo, "脱敏后证件号应被掩码");
        assertTrue(maskedNo.contains("*"), "脱敏值应含掩码字符");
    }

    @Test
    void 字段白名单_仅返回指定字段() {
        OcrDtos.JsonRecognizeResult r = service.recognizeJson(
                Map.of("imageRef", "IMG-WL-1", "docType", "ID_CARD", "mask", false,
                        "fields", List.of("姓名")), "ocr-wl");
        assertEquals(1, r.fields().size());
        assertTrue(r.fields().containsKey("姓名"));
    }

    @Test
    void 缺imageRef_抛OCR1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.recognizeJson(Map.of(), "ocr-x"));
        assertEquals("OCR_1001", e.getCode());
    }

    @Test
    void 未知taskId_抛OCR1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.getTask("NO-TASK"));
        assertEquals("OCR_1004", e.getCode());
    }

    @Test
    void 发票识别_含金额字段() {
        OcrDtos.JsonRecognizeResult r = service.recognizeJson(
                Map.of("imageRef", "IMG-INV-1", "docType", "INVOICE", "mask", false), "ocr-inv");
        assertTrue(r.fields().containsKey("发票号"));
        assertTrue(r.fields().containsKey("金额"));
    }

    @Test
    void 未知docType_默认AUTO() {
        OcrDtos.JsonRecognizeResult r = service.recognizeJson(
                Map.of("imageRef", "IMG-AUTO-1", "docType", "UNKNOWN_TYPE", "mask", false), "ocr-au");
        // 未知类型回退 AUTO 通用单据识别
        assertNotNull(r.docType());
        assertFalse(r.fields().isEmpty());
    }
}
