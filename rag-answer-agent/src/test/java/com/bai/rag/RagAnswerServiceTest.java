package com.bai.rag;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagAnswerService 单元测试：知识问答（确定性仿真）——依据判定 ANSWERED/PARTIAL/REJECTED、
 * 引用映射、质量评分、参数校验错误码、幂等。
 */
class RagAnswerServiceTest {

    private final RagAnswerService service = new RagAnswerService();

    private static final RagAnswerDtos.SourceRef SRC =
            new RagAnswerDtos.SourceRef("DOC202605020", "产品手册第4章", "提前还款按剩余本金的 1% 收取违约金");

    private static final RagAnswerDtos.ContextCard FAQ_CARD =
            new RagAnswerDtos.ContextCard("FAQ202600012", "提前还款是否收取违约金",
                    "贷款发放后满 12 个月提前还款的，免收违约金；未满 12 个月提前还款按剩余本金的 1% 收取违约金。",
                    0.95, SRC);

    private RagAnswerDtos.AnswerRequest ans(String question, List<RagAnswerDtos.ContextCard> context) {
        return new RagAnswerDtos.AnswerRequest(question, context, "s1", null, null, null, null);
    }

    @Test
    void 强相关上下文_返回ANSWERED且引用映射正确() {
        RagAnswerDtos.AnswerResult r = service.answer(ans("提前还款是否收取违约金", List.of(FAQ_CARD)), "rag-ok1");
        assertEquals("ANSWERED", r.answerMode());
        assertTrue(r.answer().endsWith("[1]"));
        assertEquals(1, r.citations().size());
        RagAnswerDtos.Citation c = r.citations().get(0);
        assertEquals(1, c.index());
        assertEquals("FAQ202600012", c.entryId());
        // 质量评分：faithfulness=0.82+0.95*0.15≈0.96，relevance=0.95
        assertEquals(0.96, r.quality().faithfulness(), 1e-9);
        assertEquals(0.95, r.quality().relevance(), 1e-9);
    }

    @Test
    void 无相关上下文_降级REJECTED引导话术() {
        RagAnswerDtos.AnswerResult r = service.answer(
                ans("今天天气怎么样", List.of(new RagAnswerDtos.ContextCard("C1", "无关卡片", "关于天气的内容", null, null))),
                "rag-rj1");
        assertEquals("REJECTED", r.answerMode());
        assertTrue(r.answer().contains("暂未在知识库中找到"));
        assertTrue(r.citations().isEmpty());
    }

    @Test
    void 部分相关_返回PARTIAL附提示() {
        RagAnswerDtos.ContextCard partial = new RagAnswerDtos.ContextCard(
                "FAQ202600012", "提前还款是否收取违约金",
                "贷款发放后满 12 个月提前还款的，免收违约金；未满 12 个月提前还款按剩余本金的 1% 收取违约金。",
                0.60, SRC);
        RagAnswerDtos.AnswerResult r = service.answer(ans("提前还款是否收取违约金", List.of(partial)), "rag-p1");
        assertEquals("PARTIAL", r.answerMode());
        assertTrue(r.answer().endsWith("（以上为部分匹配知识，仅供参考，请结合具体产品条款确认。）"));
    }

    @Test
    void 质量评估_低相关判CHECK() {
        RagAnswerDtos.ContextCard noScore = new RagAnswerDtos.ContextCard(
                "FAQ202600012", "提前还款是否收取违约金",
                "贷款发放后满 12 个月提前还款的，免收违约金；未满 12 个月提前还款按剩余本金的 1% 收取违约金。",
                null, SRC);
        RagAnswerDtos.EvaluateResult r = service.evaluate(new RagAnswerDtos.EvaluateRequest(
                "提前还款违约金怎么收", "提前还款按剩余本金的 1% 收取违约金[1]", List.of(noScore)), "rag-e1");
        assertNotNull(r.evaluateId());
        assertEquals("CHECK", r.verdict());
        assertTrue(r.quality().faithfulness() >= 0 && r.quality().faithfulness() <= 1);
    }

    @Test
    void 空问题_抛RAG1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.answer(ans("  ", null), "rag-e1"));
        assertEquals("RAG_1006", e.getCode());
    }

    @Test
    void 问题超500字_抛RAG1005() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.answer(ans("问".repeat(501), null), "rag-e2"));
        assertEquals("RAG_1005", e.getCode());
    }

    @Test
    void 上下文超8条_抛RAG1006() {
        List<RagAnswerDtos.ContextCard> many = java.util.stream.IntStream.range(0, 9)
                .mapToObj(i -> new RagAnswerDtos.ContextCard("C" + i, "t" + i, "内容", 0.9, null))
                .toList();
        BaiException e = assertThrows(BaiException.class,
                () -> service.answer(ans("提前还款", many), "rag-e3"));
        assertEquals("RAG_1006", e.getCode());
    }

    @Test
    void 非法风格_抛RAG1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.answer(new RagAnswerDtos.AnswerRequest("提前还款", null, "s1",
                        null, null, "fancy", null), "rag-e4"));
        assertEquals("RAG_1006", e.getCode());
    }

    @Test
    void maxChars超限_抛RAG1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.answer(new RagAnswerDtos.AnswerRequest("提前还款", null, "s1",
                        null, null, null, 900), "rag-e5"));
        assertEquals("RAG_1001", e.getCode());
    }

    @Test
    void 同requestId_返回首次结果() {
        String rid = "rag-idem";
        RagAnswerDtos.AnswerResult a = service.answer(ans("提前还款是否收取违约金", List.of(FAQ_CARD)), rid);
        RagAnswerDtos.AnswerResult b = service.answer(ans("提前还款是否收取违约金", List.of(FAQ_CARD)), rid);
        assertEquals(a.answerId(), b.answerId());
        assertEquals(a.answer(), b.answer());
    }

    @Test
    void 批量空列表_抛RAG1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.batch(new RagAnswerDtos.BatchRequest(List.of()), "rag-b1"));
        assertEquals("RAG_1001", e.getCode());
    }

    @Test
    void 模型列表_默认模型与降级兜底() {
        RagAnswerDtos.ModelsResult m = service.models();
        assertEquals("credit-rag-llm", m.defaultModel());
        assertEquals("retrieval-direct", m.fallback());
        assertEquals(2, m.models().size());
    }
}
