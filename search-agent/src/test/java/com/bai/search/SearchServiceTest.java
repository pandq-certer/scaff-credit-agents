package com.bai.search;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SearchService 单元测试：多源搜索（确定性仿真）——源授权校验、黑名单/超限拒绝、
 * 结果生成、快照留存、幂等与确定性。
 */
class SearchServiceTest {

    private final SearchService service = new SearchService();

    private SearchDtos.SearchRequest req(String query) {
        return new SearchDtos.SearchRequest(query, null, null, null, null, null);
    }

    @Test
    void 默认源搜索_返回确定条数结果() {
        SearchDtos.SearchOutput r = service.search(req("小微企业贷款"), "se-ok1");
        assertEquals(10, r.total());
        assertEquals(10, r.results().size());
        assertNotNull(r.searchId());
        assertTrue(r.results().stream().allMatch(i -> "internet".equals(i.source())));
        assertTrue(r.results().stream().allMatch(i -> i.credibility() >= 0.50));
    }

    @Test
    void 同requestId_返回首次结果() {
        String rid = "se-idem";
        SearchDtos.SearchOutput a = service.search(req("小微企业贷款"), rid);
        SearchDtos.SearchOutput b = service.search(req("小微企业贷款"), rid);
        assertEquals(a.searchId(), b.searchId());
        assertEquals(a.results(), b.results());
    }

    @Test
    void 热门查询缓存_同query不同requestId复用结果() {
        SearchDtos.SearchOutput a = service.search(req("小微企业贷款"), "se-d1");
        SearchDtos.SearchOutput b = service.search(req("小微企业贷款"), "se-d2");
        // 热门缓存 key=sha256(query|sources|topK)，TTL 5min：同 query 直接复用首次结果
        assertEquals(a.searchId(), b.searchId());
        assertEquals(a.results().get(0).title(), b.results().get(0).title());
        assertEquals(a.results().get(0).url(), b.results().get(0).url());
    }

    @Test
    void 黑名单命中_抛SEA1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.search(req("身份证号查询"), "se-e1"));
        assertEquals("SEA_1006", e.getCode());
    }

    @Test
    void 空查询_抛SEA1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.search(req("  "), "se-e2"));
        assertEquals("SEA_1006", e.getCode());
    }

    @Test
    void 纯符号查询_抛SEA1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.search(req("！！！"), "se-e3"));
        assertEquals("SEA_1006", e.getCode());
    }

    @Test
    void query超200字_抛SEA1005() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.search(req("贷".repeat(201)), "se-e4"));
        assertEquals("SEA_1005", e.getCode());
    }

    @Test
    void 非法源枚举_抛SEA1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.search(new SearchDtos.SearchRequest("贷款", List.of("hacker"),
                        null, null, null, null), "se-e5"));
        assertEquals("SEA_1006", e.getCode());
    }

    @Test
    void topK非法_抛SEA1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.search(new SearchDtos.SearchRequest("贷款", null, 0,
                        null, null, null), "se-e6"));
        assertEquals("SEA_1001", e.getCode());
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.search(new SearchDtos.SearchRequest("贷款", null, 51,
                        null, null, null), "se-e7"));
        assertEquals("SEA_1001", e2.getCode());
    }

    @Test
    void 启用快照_前3条可查() {
        SearchDtos.SearchOutput r = service.search(
                new SearchDtos.SearchRequest("小微企业贷款", null, 10, null, true, null), "se-snap1");
        assertNotNull(r.results().get(0).snapshotId());
        SearchDtos.Snapshot snap = service.snapshot(r.results().get(0).snapshotId());
        assertNotNull(snap.url());
        assertTrue(snap.content().contains("仿真"));
    }

    @Test
    void 快照不存在_抛SEA1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.snapshot("SNAP-NOT-EXIST"), "se-snap2");
        assertEquals("SEA_1004", e.getCode());
    }

    @Test
    void 批量空列表_抛SEA1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.batch(new SearchDtos.BatchRequest(List.of()), "se-b1"));
        assertEquals("SEA_1001", e.getCode());
    }

    @Test
    void 搜索源信息_四源权重确定() {
        List<SearchDtos.SourceInfo> infos = service.sources();
        assertEquals(4, infos.size());
        assertTrue(infos.stream().allMatch(SearchDtos.SourceInfo::enabled));
        assertEquals(10, infos.get(0).qpsLimit());
    }
}
