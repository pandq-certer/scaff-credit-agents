package com.bai.semantic;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SemanticService 单元测试（感知类代表性原子）：Dice 相似度匹配（确定性仿真）、四级分级、语义检索、幂等、参数校验。
 */
class SemanticServiceTest {

    private final SemanticService service = new SemanticService();

    @Test
    void 完全一致_判IDENTICAL() {
        SemanticDtos.MatchResult r = service.match(
                new SemanticDtos.MatchRequest("还款计划查询", "还款计划查询", null, true), "sem-1");
        assertEquals(1.0, r.score());
        assertEquals("IDENTICAL", r.level());
        assertEquals(0.95, r.threshold().identical());
        assertNotNull(r.reasons());
        assertFalse(r.reasons().isEmpty());
    }

    @Test
    void 完全无关_判DIFFERENT() {
        SemanticDtos.MatchResult r = service.match(
                new SemanticDtos.MatchRequest("abcdef", "uvwxyz", null, true), "sem-2");
        assertEquals(0.0, r.score());
        assertEquals("DIFFERENT", r.level());
    }

    @Test
    void 同入参两次调用_确定性一致() {
        SemanticDtos.MatchResult a = service.match(
                new SemanticDtos.MatchRequest("什么是抵押贷款", "抵押贷款如何申请", null, true), "sem-d1");
        SemanticDtos.MatchResult b = service.match(
                new SemanticDtos.MatchRequest("什么是抵押贷款", "抵押贷款如何申请", null, true), "sem-d2");
        assertEquals(a.score(), b.score());
        assertEquals(a.level(), b.level());
    }

    @Test
    void 同requestId_幂等复用首次结果() {
        String rid = "sem-idem";
        SemanticDtos.MatchResult a = service.match(
                new SemanticDtos.MatchRequest("什么是抵押贷款", "抵押贷款如何申请", null, true), rid);
        SemanticDtos.MatchResult b = service.match(
                new SemanticDtos.MatchRequest("什么是抵押贷款", "抵押贷款如何申请", null, true), rid);
        assertEquals(a.matchId(), b.matchId(), "同 requestId 应复用首次结果");
    }

    @Test
    void 语义检索_返回降序候选() {
        SemanticDtos.SearchResult r = service.search(
                new SemanticDtos.SearchRequest("什么是抵押贷款", "knowledge_faq", 5, false, null), "sem-s1");
        assertEquals(5, r.hits().size());
        for (int i = 0; i < r.hits().size() - 1; i++) {
            assertTrue(r.hits().get(i).score() >= r.hits().get(i + 1).score(), "命中得分应降序");
            assertEquals(i + 1, r.hits().get(i).rank());
        }
        assertTrue(r.hits().get(0).docId().startsWith("DOC"));
        assertEquals("VECTOR", r.hits().get(0).source());
        assertTrue(r.hits().get(0).score() >= 0.80 && r.hits().get(0).score() <= 0.94);
    }

    @Test
    void 语料库不存在_抛SEM1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.search(new SemanticDtos.SearchRequest("查询", "no-corpus", 5, false, null), "sem-x1"));
        assertEquals(SemanticErrorCodes.NOT_FOUND, e.getCode());
    }

    @Test
    void 空文本_抛SEM1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.match(new SemanticDtos.MatchRequest("   ", "abc", null, true), "sem-x2"));
        assertEquals(SemanticErrorCodes.EMPTY_TEXT, e.getCode());
    }

    @Test
    void topK越界_抛SEM1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.search(new SemanticDtos.SearchRequest("查询", "knowledge_faq", 0, false, null), "sem-x3"));
        assertEquals(SemanticErrorCodes.PARAM_INVALID, e.getCode());
    }

    @Test
    void 检索幂等_同requestId复用() {
        String rid = "sem-s-idem";
        SemanticDtos.SearchResult a = service.search(
                new SemanticDtos.SearchRequest("什么是抵押贷款", "knowledge_faq", 5, false, null), rid);
        SemanticDtos.SearchResult b = service.search(
                new SemanticDtos.SearchRequest("什么是抵押贷款", "knowledge_faq", 5, false, null), rid);
        assertEquals(a.matchId(), b.matchId(), "同 requestId 应复用首次结果");
    }
}
