package com.bai.kprocess;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KnowledgeProcessService 单元测试：知识加工（确定性仿真）——条目化、FAQ/TRIPLE 加工、
 * 质量校验、参数校验错误码、幂等。
 */
class KnowledgeProcessServiceTest {

    private final KnowledgeProcessService service = new KnowledgeProcessService();

    private KnowledgeProcessDtos.ProcessRequest proc(String text, String docId, List<String> types, String source) {
        return new KnowledgeProcessDtos.ProcessRequest(text, docId, types, source, null);
    }

    @Test
    void 正常加工_生成CLAUSE与FAQ条目且全部通过质检() {
        String text = "第一章 总则\n小微企业贷款期限最长不超过 3 年，逾期应当按五级分类管理。\n本产品利率按年利率计算，客户逾期还款。";
        KnowledgeProcessDtos.ProcessResult r = service.process(proc(text, "DOC001", null, "policy"), "kp-ok1");
        assertNotNull(r.processId());
        assertEquals("DOC001", r.docId());
        assertEquals("2026.09.01", r.pipelineVersion());
        assertEquals(4, r.stats().total());
        assertEquals(2, r.stats().byType().get("CLAUSE"));
        assertEquals(2, r.stats().byType().get("FAQ"));
        assertTrue(r.entries().stream().allMatch(e -> e.quality().pass()));
        assertTrue(r.entries().stream().allMatch(e -> "DOC001".equals(e.source().docId())));
        // FAQ 答案 ≤500 字且附依据
        assertTrue(r.entries().stream().filter(e -> "FAQ".equals(e.type()))
                .allMatch(e -> e.content().length() <= 500));
    }

    @Test
    void 三元组加工_抽取实体关系实体() {
        String text = "借款人应当按期还款。";
        KnowledgeProcessDtos.ProcessResult r = service.process(
                proc(text, "DOC002", List.of("TRIPLE"), null), "kp-tr1");
        assertEquals(1, r.stats().total());
        KnowledgeProcessDtos.KnowledgeEntry e = r.entries().get(0);
        assertEquals("TRIPLE", e.type());
        assertEquals("借款人 | 应当 | 按期还款", e.content());
        assertEquals("借款人应当", e.title());
    }

    @Test
    void 噪声行与重复行_清洗去重() {
        String text = "第 3 页\n小微企业贷款期限最长不超过 3 年。\n小微企业贷款期限最长不超过 3 年。";
        KnowledgeProcessDtos.ProcessResult r = service.process(proc(text, "DOC003", List.of("CLAUSE"), "policy"), "kp-cl1");
        // 噪声行被剔除、重复行合并：仅 1 条 CLAUSE
        assertEquals(1, r.stats().total());
    }

    @Test
    void 质量校验_空内容条目置rejected() {
        KnowledgeProcessDtos.KnowledgeEntry bad = new KnowledgeProcessDtos.KnowledgeEntry(
                "ENT1", "CLAUSE", "t", "", new KnowledgeProcessDtos.SourceRef("D1", "正文"),
                List.of("制度"), null);
        KnowledgeProcessDtos.KnowledgeEntry good = new KnowledgeProcessDtos.KnowledgeEntry(
                "ENT2", "CLAUSE", "t", "有效内容", new KnowledgeProcessDtos.SourceRef("D1", "正文"),
                List.of("制度"), new KnowledgeProcessDtos.Quality(0.9, 0.9, true));
        KnowledgeProcessDtos.ValidateResult r = service.validate(
                new KnowledgeProcessDtos.ValidateRequest("D1", List.of(bad, good)), "kp-v1");
        assertEquals(2, r.total());
        assertEquals(1, r.passed());
        assertEquals(1, r.rejected());
        assertFalse(r.verdicts().get(0).pass());
        assertTrue(r.verdicts().get(1).pass());
    }

    @Test
    void 空文本_抛KNO1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.process(proc("  ", "DOC1", null, null), "kp-e1"));
        assertEquals("KNO_1006", e.getCode());
    }

    @Test
    void 非法知识类型_抛KNO1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.process(proc("有效内容", "DOC1", List.of("BAD"), null), "kp-e2"));
        assertEquals("KNO_1006", e.getCode());
    }

    @Test
    void 非法source_抛KNO1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.process(proc("有效内容", "DOC1", null, "hacker"), "kp-e3"));
        assertEquals("KNO_1001", e.getCode());
    }

    @Test
    void docId缺失_抛KNO1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.process(proc("有效内容", null, null, null), "kp-e4"));
        assertEquals("KNO_1001", e.getCode());
    }

    @Test
    void 文本超50000字_抛KNO1005() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.process(proc("知".repeat(50001), "DOC1", null, null), "kp-e5"));
        assertEquals("KNO_1005", e.getCode());
    }

    @Test
    void 同requestId_返回首次结果() {
        String rid = "kp-idem";
        KnowledgeProcessDtos.ProcessResult a = service.process(proc("小微企业贷款期限最长不超过 3 年。", "DOC9", null, null), rid);
        KnowledgeProcessDtos.ProcessResult b = service.process(proc("小微企业贷款期限最长不超过 3 年。", "DOC9", null, null), rid);
        assertEquals(a.processId(), b.processId());
        assertEquals(a.stats().total(), b.stats().total());
    }

    @Test
    void 批量空列表_抛KNO1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.batch(new KnowledgeProcessDtos.BatchRequest(List.of()), "kp-b1"));
        assertEquals("KNO_1001", e.getCode());
    }

    @Test
    void 流水线配置_五阶段且ACTIVE() {
        KnowledgeProcessDtos.PipelineInfo p = service.pipeline();
        assertEquals("ACTIVE", p.status());
        assertEquals(5, p.stages().size());
    }
}
