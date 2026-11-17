package com.bai.entity;

import com.bai.common.BaiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EntityResolutionService 单元测试：黄金库匹配决策（MATCH/REVIEW/NEW）、证件冲突、候选检索、合并、幂等。
 * <p>黄金记录库为内存仿真（7 条种子记录），证件仅存 SHA-256 哈希与掩码。
 */
class EntityResolutionServiceTest {

    private EntityResolutionService service;

    @BeforeEach
    void setUp() {
        service = new EntityResolutionService(new ObjectMapper());
    }

    private EntityResolutionDtos.ResolveRequest req(Map<String, Object> record, String source, String entityType) {
        return new EntityResolutionDtos.ResolveRequest(record, source, entityType);
    }

    // ------------------------------------------------------------ 归一决策

    @Test
    void 证件号命中_手机一致提分至099() {
        // GRD00001 张伟：证件+手机均命中
        EntityResolutionDtos.ResolveResult r = service.resolve(req(Map.of(
                "name", "张伟", "idCard", "110101199001011234", "phone", "13801010101"),
                "core", "PERSON"), "ent-1");
        assertEquals("MATCH", r.match().decision());
        assertEquals(0.99, r.match().score(), 1e-9);
        assertEquals("GRD00001", r.goldenId());
        assertTrue(r.match().candidates().get(0).matchedFields().contains("phone"));
    }

    @Test
    void 证件命中_姓名不一致_抛ENT1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.resolve(req(Map.of("name", "李四", "idCard", "110101199001011234"),
                        "core", "PERSON"), "ent-2"));
        assertEquals("ENT_1006", e.getCode());
    }

    @Test
    void 同名不同证件_决策REVIEW() {
        // 姓名 张伟 与 GRD00001 一致（0.78）+ 同类型（0.03）= 0.81 → REVIEW
        EntityResolutionDtos.ResolveResult r = service.resolve(req(Map.of(
                "name", "张伟", "idCard", "320101199001011111"), "channel", "PERSON"), "ent-3");
        assertEquals("REVIEW", r.match().decision());
        assertEquals(0.81, r.match().score(), 1e-9);
        assertEquals("GRD00001", r.match().candidates().get(0).goldenId());
    }

    @Test
    void 无命中_决策NEW_新建黄金记录() {
        EntityResolutionDtos.ResolveResult r = service.resolve(req(Map.of(
                "name", "陈晨", "phone", "13511112222"), "core", "PERSON"), "ent-4");
        assertEquals("NEW", r.match().decision());
        assertEquals(0.0, r.match().score(), 1e-9);
        assertNull(r.goldenId());          // NEW 决策 goldenId 为 null
        assertEquals(8, service.entities().total());   // 新建 1 条（种子 7 条）
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    void 参数校验_record缺失_source非法_entityType非法() {
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.resolve(req(Map.of(), "core", null), "ent-5"));
        assertEquals("ENT_1001", e1.getCode());
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.resolve(req(Map.of("name", "张伟"), "bad-source", null), "ent-6"));
        assertEquals("ENT_1001", e2.getCode());
        BaiException e3 = assertThrows(BaiException.class,
                () -> service.resolve(req(Map.of("name", "张伟"), "core", "ROBOT"), "ent-7"));
        assertEquals("ENT_1001", e3.getCode());
    }

    // ------------------------------------------------------------ 候选检索

    @Test
    void match_证件强信号候选降序且topK生效() {
        EntityResolutionDtos.MatchResult r = service.match(new EntityResolutionDtos.MatchRequest(
                Map.of("name", "张伟", "idCard", "110101199001011234"), 1), "ent-m1");
        assertTrue(r.matchId().startsWith("ERM"));
        assertEquals("PERSON", r.entityType());
        assertEquals(1, r.candidates().size());
        assertEquals("GRD00001", r.candidates().get(0).goldenId());
        assertEquals(0.99, r.candidates().get(0).score(), 1e-9);   // 0.95(证件)+0.78(姓名) 封顶 0.99
        // topK 越界
        BaiException e = assertThrows(BaiException.class,
                () -> service.match(new EntityResolutionDtos.MatchRequest(Map.of("name", "张伟"), 0), "ent-m2"));
        assertEquals("ENT_1001", e.getCode());
    }

    // ------------------------------------------------------------ 合并

    @Test
    void merge_版本递增并更新字段() {
        EntityResolutionDtos.MergeResult r = service.merge(new EntityResolutionDtos.MergeRequest(
                "GRD00002", Map.of("phone", "13900009999", "name", "李娜娜"), "数据纠错", "admin"), "ent-g1");
        assertEquals("MERGED", r.status());
        assertEquals(2, r.version());
        EntityResolutionDtos.GoldenRecordView v = service.entities().entities().stream()
                .filter(e -> e.goldenId().equals("GRD00002")).findFirst().orElseThrow();
        assertEquals(2, v.version());
    }

    @Test
    void merge_goldenId不存在_抛ENT1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.merge(new EntityResolutionDtos.MergeRequest(
                        "GRD99999", Map.of("phone", "13900009999"), "数据纠错", "admin"), "ent-g2"));
        assertEquals("ENT_1004", e.getCode());
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void 同requestId同指纹_返回首次resolveId() {
        String rid = "ent-idem";
        EntityResolutionDtos.ResolveResult a = service.resolve(req(Map.of(
                "name", "张伟", "idCard", "110101199001011234"), "core", "PERSON"), rid);
        EntityResolutionDtos.ResolveResult b = service.resolve(req(Map.of(
                "name", "张伟", "idCard", "110101199001011234"), "core", "PERSON"), rid);
        assertEquals(a.resolveId(), b.resolveId());
    }
}
