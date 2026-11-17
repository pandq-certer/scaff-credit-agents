package com.bai.asr;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AsrService 单元测试（感知类代表性原子）：JSON 直连转写（确定性仿真）、脱敏、幂等、格式/大小校验。
 */
class AsrServiceTest {

    private final AsrService service = new AsrService();

    @Test
    void 语音转写_返回确定性文本() {
        AsrDtos.AsrResult r = service.transcribeRef(
                Map.of("audioRef", "AUD-CALL-1", "scene", "collection", "maskSensitive", false), "asr-t1");
        assertEquals("collection", r.scene());
        assertEquals(3, r.segments().size());
        assertNotNull(r.lang());
        assertFalse(r.fullText().isBlank());
        assertNotNull(r.status());
    }

    @Test
    void 同audioRef两次调用_结果确定性一致() {
        Map<String, Object> body = Map.of("audioRef", "AUD-CONST-1", "scene", "customer-service", "maskSensitive", false);
        AsrDtos.AsrResult a = service.transcribeRef(body, "asr-d1");
        AsrDtos.AsrResult b = service.transcribeRef(body, "asr-d2");
        assertEquals(a.segments(), b.segments());
        assertEquals(a.status(), b.status());
        assertEquals(a.fullText(), b.fullText());
    }

    @Test
    void 同requestId_幂等复用首次结果() {
        Map<String, Object> body = Map.of("audioRef", "AUD-IDEM-1", "scene", "general", "maskSensitive", false);
        String rid = "asr-idem";
        AsrDtos.AsrResult a = service.transcribeRef(body, rid);
        AsrDtos.AsrResult b = service.transcribeRef(body, rid);
        assertEquals(a.asrId(), b.asrId(), "同 requestId 应复用首次结果");
    }

    @Test
    void 敏感脱敏_手机号被掩码() {
        AsrDtos.AsrResult raw = service.transcribeRef(
                Map.of("audioRef", "AUD-MASK-1", "scene", "collection", "maskSensitive", false), "asr-m0");
        AsrDtos.AsrResult masked = service.transcribeRef(
                Map.of("audioRef", "AUD-MASK-1", "scene", "collection", "maskSensitive", true), "asr-m1");
        assertTrue(raw.fullText().contains("13812345678"), "未脱敏应含原始手机号");
        assertFalse(masked.fullText().contains("13812345678"), "脱敏后不应含原始手机号");
        assertTrue(masked.fullText().contains("138****5678"), "脱敏应保留前3后4并打码");
    }

    @Test
    void 缺audioRef_抛ASR1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.transcribeRef(Map.of(), "asr-x1"));
        assertEquals(AsrErrorCodes.PARAM_INVALID, e.getCode());
    }

    @Test
    void scene非法_抛ASR1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.transcribeRef(Map.of("audioRef", "AUD-1", "scene", "illegal-scene"), "asr-x2"));
        assertEquals(AsrErrorCodes.PARAM_INVALID, e.getCode());
    }

    @Test
    void 音频格式不支持_抛ASR1006() {
        MultipartFile audio = new MockMultipartFile("audio", "demo.txt", "text/plain", new byte[2048]);
        BaiException e = assertThrows(BaiException.class,
                () -> service.transcribe(audio, "general", null, false, null, true, "asr-x3"));
        assertEquals(AsrErrorCodes.FORMAT_UNSUPPORTED, e.getCode());
    }

    @Test
    void 音频小于1KB_抛ASR1007() {
        MultipartFile audio = new MockMultipartFile("audio", "demo.wav", "audio/wav", new byte[100]);
        BaiException e = assertThrows(BaiException.class,
                () -> service.transcribe(audio, "general", null, false, null, true, "asr-x4"));
        assertEquals(AsrErrorCodes.AUDIO_CORRUPTED, e.getCode());
    }

    @Test
    void 任务不存在_抛ASR1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.getTask("NO-TASK"));
        assertEquals(AsrErrorCodes.TASK_NOT_FOUND, e.getCode());
    }
}
