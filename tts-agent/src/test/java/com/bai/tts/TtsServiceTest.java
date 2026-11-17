package com.bai.tts;

import com.bai.common.BaiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TtsService 单元测试：合成成功（默认场景 general）、确定性复现（同入参）、幂等（同 requestId）、
 * 错误码（TTS_1001/1004/1005/1006/1007）、语速钳制、场景默认音色路由、音色列表（≥8）、
 * 异步任务（>5000 字/批量）、任务查询、文本规整（金额/日期/时间读法）。
 */
class TtsServiceTest {

    private TtsService service;

    @BeforeEach
    void setUp() {
        service = new TtsService(new ObjectMapper());
    }

    // ------------------------------------------------------------ 合成成功

    @Test
    void 合成成功_默认场景字段完整() {
        Object r = service.synthesize("尊敬的客户您好，您名下贷款本期应还一万两千元。",
                null, null, null, null, null, null, null, "tts-1");
        assertTrue(r instanceof TtsDtos.TtsResult);
        TtsDtos.TtsResult res = (TtsDtos.TtsResult) r;
        assertTrue(res.ttsId().startsWith("TTS"));
        assertEquals("v_general_f", res.voiceId());
        assertEquals("general", res.scene());
        assertEquals("wav", res.format());
        assertEquals(24000, res.sampleRate());
        assertTrue(res.durationMs() > 0);
        assertFalse(res.segments().isEmpty());
        assertTrue(res.audioUrl().startsWith("https://"));
        assertTrue(res.audioUrl().contains(".wav"));
        assertTrue(res.audioUrl().contains(res.ttsId()));
    }

    // ------------------------------------------------------------ 确定性

    @Test
    void 确定性_同入参同复现() {
        String text = "尊敬的客户您好，本期应还一万两千元，还款日 2026-09-07。";
        TtsDtos.TtsResult a = (TtsDtos.TtsResult) service.synthesize(text, null, null,
                "collection", null, null, "mp3", 16000, "tts-d1");
        TtsDtos.TtsResult b = (TtsDtos.TtsResult) service.synthesize(text, null, null,
                "collection", null, null, "mp3", 16000, "tts-d2");
        assertEquals(a.durationMs(), b.durationMs());
        assertEquals(a.segments(), b.segments());
        assertEquals(a.voiceId(), b.voiceId());
        // 确定性指合成内容复现；ttsId 为落库记录 ID（唯一），audioUrl 随 ttsId 变化
        assertTrue(a.audioUrl().contains(".mp3"));
        assertTrue(b.audioUrl().contains(".mp3"));
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void 幂等_同requestId返回首次结果() {
        String rid = "tts-idem";
        TtsDtos.TtsResult a = (TtsDtos.TtsResult) service.synthesize("尊敬的客户您好。",
                null, null, null, null, null, null, null, rid);
        TtsDtos.TtsResult b = (TtsDtos.TtsResult) service.synthesize("尊敬的客户您好。",
                null, null, null, null, null, null, null, rid);
        assertEquals(a.ttsId(), b.ttsId());
        assertEquals(a.durationMs(), b.durationMs());
        assertEquals(a.segments(), b.segments());
    }

    // ------------------------------------------------------------ 错误码

    @Test
    void 错误码_多场景() {
        // text 缺失 → TTS_1001
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.synthesize(null, null, null, null, null, null, null, null, "tts-e1"));
        assertEquals("TTS_1001", e1.getCode());
        // 空文本 → TTS_1007
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.synthesize("   ", null, null, null, null, null, null, null, "tts-e2"));
        assertEquals("TTS_1007", e2.getCode());
        // 纯符号 → TTS_1007
        BaiException e3 = assertThrows(BaiException.class,
                () -> service.synthesize("！！！", null, null, null, null, null, null, null, "tts-e3"));
        assertEquals("TTS_1007", e3.getCode());
        // 超 10000 字 → TTS_1005
        BaiException e4 = assertThrows(BaiException.class,
                () -> service.synthesize("啊".repeat(10001), null, null, null, null, null, null, null, "tts-e4"));
        assertEquals("TTS_1005", e4.getCode());
        // 格式非法 → TTS_1006
        BaiException e5 = assertThrows(BaiException.class,
                () -> service.synthesize("你好", null, null, null, null, null, "aac", null, "tts-e5"));
        assertEquals("TTS_1006", e5.getCode());
        // 采样率非法 → TTS_1006
        BaiException e6 = assertThrows(BaiException.class,
                () -> service.synthesize("你好", null, null, null, null, null, null, 8000, "tts-e6"));
        assertEquals("TTS_1006", e6.getCode());
        // scene 非法 → TTS_1001
        BaiException e7 = assertThrows(BaiException.class,
                () -> service.synthesize("你好", null, null, "xxx", null, null, null, null, "tts-e7"));
        assertEquals("TTS_1001", e7.getCode());
        // style 非法 → TTS_1001
        BaiException e8 = assertThrows(BaiException.class,
                () -> service.synthesize("你好", null, null, null, "HAPPY", null, null, null, "tts-e8"));
        assertEquals("TTS_1001", e8.getCode());
        // voiceId 不存在 → TTS_1004
        BaiException e9 = assertThrows(BaiException.class,
                () -> service.synthesize("你好", null, "v_none", null, null, null, null, null, "tts-e9"));
        assertEquals("TTS_1004", e9.getCode());
        // requestId 超 32 位 → TTS_1001
        BaiException e10 = assertThrows(BaiException.class,
                () -> service.synthesize("你好", null, null, null, null, null, null, null, "x".repeat(33)));
        assertEquals("TTS_1001", e10.getCode());
    }

    // ------------------------------------------------------------ 语速钳制

    @Test
    void 语速钳制_超出05到20截断() {
        String text = "尊敬的客户您好，本期应还一万两千元。";
        TtsDtos.TtsResult fast = (TtsDtos.TtsResult) service.synthesize(text, null, null,
                null, null, 5.0, null, null, "tts-r1");
        TtsDtos.TtsResult capped = (TtsDtos.TtsResult) service.synthesize(text, null, null,
                null, null, 2.0, null, null, "tts-r2");
        assertEquals(capped.durationMs(), fast.durationMs());   // 5.0 截断为 2.0
        TtsDtos.TtsResult slow = (TtsDtos.TtsResult) service.synthesize(text, null, null,
                null, null, 0.1, null, null, "tts-r3");
        TtsDtos.TtsResult floored = (TtsDtos.TtsResult) service.synthesize(text, null, null,
                null, null, 0.5, null, null, "tts-r4");
        assertEquals(floored.durationMs(), slow.durationMs());  // 0.1 截断为 0.5
        assertTrue(fast.durationMs() < slow.durationMs());
    }

    // ------------------------------------------------------------ 场景路由

    @Test
    void 场景默认音色路由() {
        TtsDtos.TtsResult c = (TtsDtos.TtsResult) service.synthesize("话术内容", null, null,
                "collection", null, null, null, null, "tts-c1");
        assertEquals("v_collection_f", c.voiceId());
        TtsDtos.TtsResult b = (TtsDtos.TtsResult) service.synthesize("公告内容", null, null,
                "broadcast", null, null, null, null, "tts-c2");
        assertEquals("v_broadcast_f", b.voiceId());
        TtsDtos.TtsResult d = (TtsDtos.TtsResult) service.synthesize("对话内容", null, null,
                "digital-human", null, null, null, null, "tts-c3");
        assertEquals("v_dh_f", d.voiceId());
        // 显式 voiceId 优先于场景默认
        TtsDtos.TtsResult v = (TtsDtos.TtsResult) service.synthesize("话术内容", null, "v_general_m",
                "collection", null, null, null, null, "tts-c4");
        assertEquals("v_general_m", v.voiceId());
    }

    // ------------------------------------------------------------ 音色列表

    @Test
    void 音色列表_至少8个字段完整() {
        java.util.List<TtsDtos.Voice> voices = service.listVoices();
        assertTrue(voices.size() >= 8);
        for (TtsDtos.Voice v : voices) {
            assertFalse(v.voiceId().isBlank());
            assertFalse(v.name().isBlank());
            assertTrue(v.gender().equals("male") || v.gender().equals("female"));
            assertFalse(v.style().isEmpty());
            assertTrue(v.sampleRates().contains(24000));
        }
        TtsDtos.Voice collection = voices.stream()
                .filter(v -> v.voiceId().equals("v_collection_f")).findFirst().orElseThrow();
        assertTrue(collection.scene().contains("collection"));
        assertTrue(collection.style().contains("PROFESSIONAL"));
    }

    // ------------------------------------------------------------ 异步任务

    @Test
    void 超5000字_自动转异步并可查询() {
        Object submit = service.synthesize("客".repeat(6000), null, null, null, null, null, null, null, "tts-async1");
        assertTrue(submit instanceof TtsDtos.TtsTaskSubmit);
        TtsDtos.TtsTaskSubmit s = (TtsDtos.TtsTaskSubmit) submit;
        assertEquals("PENDING", s.status());
        assertTrue(s.taskId().startsWith("TTSK"));
        TtsDtos.TtsTask task = service.getTask(s.taskId());
        assertEquals("DONE", task.status());
        assertNotNull(task.result());
        assertTrue(task.result().durationMs() > 0);
        assertTrue(task.result().segments().size() >= 1);
        // texts 批量（JSON 数组）→ 自动异步
        Object batch = service.synthesize(null, "[\"第一段话\",\"第二段话\"]", null, null, null,
                null, null, null, "tts-async2");
        assertTrue(batch instanceof TtsDtos.TtsTaskSubmit);
        TtsDtos.TtsTask bt = service.getTask(((TtsDtos.TtsTaskSubmit) batch).taskId());
        assertEquals("DONE", bt.status());
        assertTrue(bt.result().segments().size() >= 2);
        // texts 非 JSON → TTS_1001
        BaiException e = assertThrows(BaiException.class,
                () -> service.synthesize(null, "不是数组", null, null, null, null, null, null, "tts-async3"));
        assertEquals("TTS_1001", e.getCode());
    }

    @Test
    void 任务不存在_抛TTS1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.getTask("TTSK-NOT-EXIST"));
        assertEquals("TTS_1004", e.getCode());
    }

    // ------------------------------------------------------------ 文本规整

    @Test
    void 文本规整_金额日期时间读法入段() {
        TtsDtos.TtsResult res = (TtsDtos.TtsResult) service.synthesize(
                "本期待还12000元，还款日2026-09-07，14:30前到账。", null, "v_general_f", "general",
                null, null, null, null, "tts-n1");
        String segText = res.segments().get(0).text();
        assertTrue(segText.contains("一万二千元"), segText);
        assertTrue(segText.contains("二零二六年九月七日"), segText);
        assertTrue(segText.contains("十四点三十分"), segText);
    }
}
