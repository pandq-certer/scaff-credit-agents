package com.bai.summary;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SummaryService 单元测试（感知类代表性原子）：抽取式摘要（确定性仿真）、关键信息提取归一、质量评估、幂等、参数校验。
 */
class SummaryServiceTest {

    private final SummaryService service = new SummaryService();

    private static final String TEXT = "借款人张三申请经营贷50万元，期限36个月，采用抵押担保。";

    @Test
    void 摘要生成_关键信息提取() {
        SummaryDtos.SummaryResult r = service.generate(
                new SummaryDtos.GenerateRequest(TEXT, "overview", null, null, null), "sum-1");
        assertEquals("overview", r.mode());
        assertFalse(r.chunked());
        assertFalse(r.summary().isBlank());
        assertNotNull(r.keyInfo());
        assertTrue(r.keyInfo().stream().anyMatch(k -> k.type().equals("AMOUNT") && k.normalized().equals("500000.00")),
                "50万元应归一到元保留 2 位小数");
        assertTrue(r.keyInfo().stream().anyMatch(k -> k.type().equals("TERM") && k.normalized().equals("36")),
                "36个月应归一到月");
        assertTrue(r.keyInfo().stream().anyMatch(k -> k.type().equals("PARTY") && k.normalized().equals("张三")));
        assertTrue(r.stats().outputChars() > 0);
    }

    @Test
    void terse模式_首句极简() {
        SummaryDtos.SummaryResult r = service.generate(
                new SummaryDtos.GenerateRequest(TEXT, "terse", null, null, null), "sum-2");
        assertEquals("terse", r.mode());
        assertTrue(r.summary().length() <= 100, "terse 模式上限 100 字");
        assertFalse(r.summary().isBlank());
    }

    @Test
    void bullet模式_编号要点() {
        SummaryDtos.SummaryResult r = service.generate(
                new SummaryDtos.GenerateRequest("第一句。第二句。第三句。", "bullet", null, null, null), "sum-3");
        assertEquals("bullet", r.mode());
        assertTrue(r.summary().startsWith("1. "), "bullet 要点应编号");
        assertTrue(r.summary().contains("第一句"));
    }

    @Test
    void 质量评估_确定性评分() {
        SummaryDtos.QualityScore q = service.evaluate(
                new SummaryDtos.EvaluateRequest(TEXT, "借款人申请贷款"), "sum-e1");
        assertTrue(q.consistency() >= 0.80 && q.consistency() <= 0.99);
        assertTrue(q.faithfulness() >= 0.85 && q.faithfulness() <= 0.97);
        assertTrue(q.coverage() >= 0.70 && q.coverage() <= 0.97);
        assertEquals(Math.round((q.consistency() + q.faithfulness() + q.coverage()) / 3 * 10000.0) / 10000.0,
                q.overall(), "综合分应为三维均值");
    }

    @Test
    void 空文本_抛SUM1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate(new SummaryDtos.GenerateRequest("   ", null, null, null, null), "sum-x1"));
        assertEquals(SummaryErrorCodes.EMPTY_TEXT, e.getCode());
    }

    @Test
    void mode非法_抛SUM1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate(new SummaryDtos.GenerateRequest(TEXT, "long", null, null, null), "sum-x2"));
        assertEquals(SummaryErrorCodes.PARAM_INVALID, e.getCode());
    }

    @Test
    void maxChars越界_抛SUM1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate(new SummaryDtos.GenerateRequest(TEXT, "overview", null, 0, null), "sum-x3"));
        assertEquals(SummaryErrorCodes.PARAM_INVALID, e.getCode());
    }

    @Test
    void 同requestId_幂等复用首次结果() {
        String rid = "sum-idem";
        SummaryDtos.SummaryResult a = service.generate(
                new SummaryDtos.GenerateRequest(TEXT, "overview", null, null, null), rid);
        SummaryDtos.SummaryResult b = service.generate(
                new SummaryDtos.GenerateRequest(TEXT, "overview", null, null, null), rid);
        assertEquals(a.summaryId(), b.summaryId(), "同 requestId 应复用首次结果");
    }

    @Test
    void 文本超长_抛SUM1005() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate(new SummaryDtos.GenerateRequest("a".repeat(20001), null, null, null, null), "sum-x4"));
        assertEquals(SummaryErrorCodes.TEXT_TOO_LONG, e.getCode());
    }
}
