package com.bai.kretrieve;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KnowledgeRetrieveService 单元测试：知识检索（确定性仿真）——三通道召回与 RFF 融合、
 * 图谱一跳检索、过滤、参数校验错误码、幂等。
 */
class KnowledgeRetrieveServiceTest {

    /** provider=builtin：SunBrain 通道不启用（api-key/binding-ref 未配置即降级），走内置语料快照 */
    private final KnowledgeRetrieveService service = new KnowledgeRetrieveService(
            new SunbrainKbClient("http://127.0.0.1:1", "", "", "knowledge-retrieve", 5000), "builtin");

    private KnowledgeRetrieveDtos.RetrieveRequest req(String query) {
        return new KnowledgeRetrieveDtos.RetrieveRequest(query, null, null, null, null, null);
    }

    @Test
    void 关键词检索_融合通道命中FAQ卡片且分数确定() {
        KnowledgeRetrieveDtos.RetrieveResult r = service.retrieve(req("提前还款违约金"), "kr-ok1");
        assertEquals(1, r.total());
        KnowledgeRetrieveDtos.KnowledgeHit hit = r.hits().get(0);
        assertEquals("FAQ202600012", hit.entryId());
        assertEquals("faq", hit.library());
        // 归一融合分 = bestChannelScore（vector 通道：sim 0.25 → 0.25*0.85+0.30 → 0.513）
        assertEquals(0.513, hit.score(), 1e-9);
        assertEquals("FUSED", hit.channel());
        assertNotNull(hit.source().docId());
    }

    @Test
    void 图谱检索_一跳返回实体关联卡片() {
        KnowledgeRetrieveDtos.RetrieveResult r = service.graph(
                new KnowledgeRetrieveDtos.GraphRequest("提前还款", null, 1, 10), "kr-g1");
        assertEquals(2, r.total());
        assertTrue(r.hits().stream().allMatch(h -> "GRAPH".equals(h.channel())));
        assertEquals("提前还款—涉及费用→违约金", r.hits().get(0).title());
        assertEquals(0.72, r.hits().get(0).score(), 1e-9);
    }

    @Test
    void 全库无命中_返回0条() {
        KnowledgeRetrieveDtos.RetrieveResult r = service.retrieve(req("kpi2026xyz"), "kr-m1");
        assertEquals(0, r.total());
        assertTrue(r.hits().isEmpty());
    }

    @Test
    void 类型过滤_只返回指定类型() {
        KnowledgeRetrieveDtos.RetrieveResult r = service.retrieve(
                new KnowledgeRetrieveDtos.RetrieveRequest("提前还款违约金", 10, null,
                        Map.of("type", "RULE"), null, null), "kr-f1");
        assertEquals(0, r.total());
        KnowledgeRetrieveDtos.RetrieveResult f = service.retrieve(
                new KnowledgeRetrieveDtos.RetrieveRequest("提前还款违约金", 10, null,
                        Map.of("type", "FAQ"), null, null), "kr-f2");
        assertEquals(1, f.total());
        assertEquals("FAQ", f.hits().get(0).type());
    }

    @Test
    void 空查询_抛KRE1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.retrieve(req("  "), "kr-e1"));
        assertEquals("KRE_1006", e.getCode());
    }

    @Test
    void 非法检索库_抛KRE1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.retrieve(new KnowledgeRetrieveDtos.RetrieveRequest("还款", 10,
                        List.of("bad_lib"), null, null, null), "kr-e2"));
        assertEquals("KRE_1006", e.getCode());
    }

    @Test
    void 非法通道_抛KRE1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.retrieve(new KnowledgeRetrieveDtos.RetrieveRequest("还款", 10,
                        null, null, List.of("bad_chan"), null), "kr-e3"));
        assertEquals("KRE_1006", e.getCode());
    }

    @Test
    void topK超限_抛KRE1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.retrieve(new KnowledgeRetrieveDtos.RetrieveRequest("还款", 21,
                        null, null, null, null), "kr-e4"));
        assertEquals("KRE_1001", e.getCode());
    }

    @Test
    void 同requestId_返回首次结果() {
        String rid = "kr-idem";
        KnowledgeRetrieveDtos.RetrieveResult a = service.retrieve(req("提前还款违约金"), rid);
        KnowledgeRetrieveDtos.RetrieveResult b = service.retrieve(req("提前还款违约金"), rid);
        assertEquals(a.retrieveId(), b.retrieveId());
        assertEquals(a.hits(), b.hits());
    }

    @Test
    void 同入参不同requestId_命中结果一致() {
        KnowledgeRetrieveDtos.RetrieveResult a = service.retrieve(req("提前还款违约金"), "kr-d1");
        KnowledgeRetrieveDtos.RetrieveResult b = service.retrieve(req("提前还款违约金"), "kr-d2");
        assertNotEquals(a.retrieveId(), b.retrieveId());
        assertEquals(a.hits(), b.hits());
    }

    @Test
    void 检索配置_默认topK10且三通道权重() {
        KnowledgeRetrieveDtos.RetrieveConfig c = service.config();
        assertEquals(10, c.defaultTopK());
        assertEquals(20, c.maxTopK());
        assertEquals(3, c.channels().size());
    }
}
