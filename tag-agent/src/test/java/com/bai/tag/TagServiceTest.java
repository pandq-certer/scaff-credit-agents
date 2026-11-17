package com.bai.tag;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TagService 单元测试：智能打标（确定性仿真）——多标签推理、组阈值过滤、top5 截断、
 * 参数校验错误码、幂等与确定性。
 */
class TagServiceTest {

    private final TagService service = new TagService();

    private TagDtos.GenerateRequest req(String text) {
        return new TagDtos.GenerateRequest(text, null, null, null);
    }

    @Test
    void 命中多标签_按置信度降序截断top5() {
        TagDtos.TagResult r = service.generate(req("客户表示生意亏，暂时无力偿还，申请办理分期还款，想了解相关反馈"), "t-ok1");
        assertEquals("2026.09.01", r.taxonomyVersion());
        assertNotNull(r.tagId());
        assertEquals(5, r.tags().size());
        assertEquals("TAG_RISK_PAY_DIFFICULTY", r.tags().get(0).tagId());
        assertEquals(0.95, r.tags().get(0).confidence());
        assertEquals("risk", r.tags().get(0).group());
        assertEquals("TAG_BIZ_REPAYMENT", r.tags().get(1).tagId());
        assertEquals("TAG_BIZ_APPLY", r.tags().get(2).tagId());
        assertEquals("TAG_CONTENT_FEEDBACK", r.tags().get(3).tagId());
        assertEquals("TAG_CONTENT_CONSULT", r.tags().get(4).tagId());
        // 解释片段 ≤50 字
        assertNotNull(r.tags().get(0).explain());
        assertTrue(r.tags().get(0).explain().length() <= 50);
    }

    @Test
    void 风险文本_命中逾期标签且全部为风险组() {
        TagDtos.TagResult r = service.generate(req("贷款已逾期，客户表示要投诉银保监"), "t-risk1");
        assertEquals(2, r.tags().size());
        assertEquals("TAG_RISK_OVERDUE", r.tags().get(0).tagId());
        assertEquals(0.98, r.tags().get(0).confidence());
        assertTrue(r.tags().stream().allMatch(h -> "risk".equals(h.group())));
    }

    @Test
    void 禁用解释_explain为null() {
        TagDtos.TagResult r = service.generate(
                new TagDtos.GenerateRequest("客户申请办理还款分期", null, null, false), "t-exp0");
        assertTrue(r.tags().stream().allMatch(h -> h.explain() == null));
    }

    @Test
    void 空文本_抛TAG1006() {
        BaiException e = assertThrows(BaiException.class, () -> service.generate(req("  "), "t-e1"));
        assertEquals("TAG_1006", e.getCode());
    }

    @Test
    void 纯符号文本_抛TAG1006() {
        BaiException e = assertThrows(BaiException.class, () -> service.generate(req("！！！"), "t-e2"));
        assertEquals("TAG_1006", e.getCode());
    }

    @Test
    void 文本超5000字_抛TAG1005() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate(req("贷".repeat(5001)), "t-e3"));
        assertEquals("TAG_1005", e.getCode());
    }

    @Test
    void 未知体系版本_抛TAG1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate(new TagDtos.GenerateRequest("客户申请还款", "2025.01.01", null, null), "t-e4"));
        assertEquals("TAG_1004", e.getCode());
    }

    @Test
    void 非法组_抛TAG1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate(new TagDtos.GenerateRequest("客户申请还款", null, List.of("hacker"), null), "t-e5"));
        assertEquals("TAG_1006", e.getCode());
    }

    @Test
    void 同requestId_返回首次结果() {
        String rid = "t-idem";
        TagDtos.TagResult a = service.generate(req("客户申请办理分期还款"), rid);
        TagDtos.TagResult b = service.generate(req("客户申请办理分期还款"), rid);
        assertEquals(a.tagId(), b.tagId());
        assertEquals(a.tags(), b.tags());
    }

    @Test
    void 同入参不同requestId_标签结果一致() {
        TagDtos.TagResult a = service.generate(req("客户申请办理分期还款"), "t-det-a");
        TagDtos.TagResult b = service.generate(req("客户申请办理分期还款"), "t-det-b");
        assertNotEquals(a.tagId(), b.tagId());
        assertEquals(a.tags(), b.tags());
    }

    @Test
    void 批量空列表_抛TAG1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.batch(new TagDtos.BatchRequest(List.of()), "t-b1"));
        assertEquals("TAG_1001", e.getCode());
    }

    @Test
    void 批量受理_返回ACCEPTED与总数() {
        TagDtos.BatchResult r = service.batch(new TagDtos.BatchRequest(
                List.of(req("客户申请还款"), req("客户反馈良好"))), "t-b2");
        assertEquals("ACCEPTED", r.status());
        assertEquals(2, r.total());
        assertNotNull(r.batchId());
    }

    @Test
    void 标签体系_非空且版本最新() {
        TagDtos.Taxonomy t = service.taxonomy();
        assertEquals("2026.09.01", t.version());
        assertFalse(t.tags().isEmpty());
    }
}
