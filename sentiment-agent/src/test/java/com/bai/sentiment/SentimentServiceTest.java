package com.bai.sentiment;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SentimentService 单元测试（感知类代表性原子）：信号/关键词情绪分析（确定性仿真）、强度判定、会话轨迹、幂等。
 */
class SentimentServiceTest {

    private final SentimentService service = new SentimentService();

    private SentimentDtos.SentimentResult analyze(String text, String sessionId, Integer turnSeq, String requestId) {
        return service.analyze(new SentimentDtos.AnalyzeRequest(text, null, sessionId, turnSeq, null), requestId);
    }

    @Test
    void 情绪分析_消极低强度() {
        SentimentDtos.SentimentResult r = analyze("我不满，服务太慢", null, null, "sen-1");
        assertEquals("NEGATIVE", r.emotion().category());
        assertEquals("angry", r.emotion().fineGrained());
        assertEquals(0.85, r.emotion().confidence());
        assertEquals(2, r.intensity().level());
        assertEquals("LOW", r.intensity().label());
        assertNull(r.signals());
    }

    @Test
    void 投诉信号_强度强制HIGH() {
        SentimentDtos.SentimentResult r = analyze("我要投诉你们，还要曝光到媒体", null, null, "sen-2");
        assertEquals("COMPLAINT", r.emotion().category());
        assertEquals("threaten", r.emotion().fineGrained());
        assertNotNull(r.signals());
        assertTrue(r.signals().stream().anyMatch(s -> s.type().equals("COMPLAINT_THREATEN")));
        assertEquals(4, r.intensity().level(), "信号命中强度强制 ≥4");
        assertEquals("HIGH", r.intensity().label());
    }

    @Test
    void 威胁词_强度叠加() {
        SentimentDtos.SentimentResult r = analyze("你再威胁我，我就去找你领导", null, null, "sen-3");
        assertEquals("HOSTILE", r.emotion().category());
        assertEquals(4, r.intensity().level(), "威胁词基线 2 级 +2");
        assertEquals("HIGH", r.intensity().label());
    }

    @Test
    void 正面情绪_识别感谢() {
        SentimentDtos.SentimentResult r = analyze("非常感谢您的帮助", null, null, "sen-4");
        assertEquals("POSITIVE", r.emotion().category());
        assertEquals("grateful", r.emotion().fineGrained());
        assertEquals(2, r.intensity().level());
    }

    @Test
    void 空文本_抛SEN1006() {
        BaiException e = assertThrows(BaiException.class, () -> analyze("   ", null, null, "sen-x1"));
        assertEquals(SentimentErrorCodes.EMPTY_TEXT, e.getCode());
    }

    @Test
    void 同requestId_幂等复用首次结果() {
        String rid = "sen-idem";
        SentimentDtos.SentimentResult a = analyze("我不满", null, null, rid);
        SentimentDtos.SentimentResult b = analyze("我不满", null, null, rid);
        assertEquals(a.sentimentId(), b.sentimentId(), "同 requestId 应复用首次结果");
    }

    @Test
    void 会话轨迹_累计与峰值() {
        analyze("我不满", "sess-1", 1, "sen-t1");
        SentimentDtos.SentimentResult r = analyze("我要投诉你们", "sess-1", 2, "sen-t2");
        assertNotNull(r.trajectory());
        assertEquals(2, r.trajectory().turns().size());
        assertEquals(4, r.trajectory().peak().level(), "峰值应为强度最高的投诉轮");
        assertEquals("STABLE", r.trajectory().trend(), "两点重叠窗口首尾均值相同");
    }

    @Test
    void 流式判定_空文本中性() {
        SentimentDtos.Emotion e = service.emotionOf(null);
        assertEquals("NEUTRAL", e.category());
        assertEquals(0.62, e.confidence());
        SentimentDtos.Emotion pos = service.emotionOf("谢谢您的帮助");
        assertEquals("POSITIVE", pos.category());
    }
}
