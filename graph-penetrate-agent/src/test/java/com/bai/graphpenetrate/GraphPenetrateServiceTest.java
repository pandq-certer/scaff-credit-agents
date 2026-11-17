package com.bai.graphpenetrate;

import com.bai.common.BaiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * GraphPenetrateService 单元测试：仿真穿透（哈希合成股东图）、sungraph 真实穿透（Mockito mock 图快照）、
 * 实控人认定、环检测、参数校验、幂等、批量。
 * <p>mock 为默认 provider；sungraph 路径经反射切换 provider 并 mock {@link GraphSnapshotStore}。
 */
class GraphPenetrateServiceTest {

    private GraphSnapshotStore snapshotStore;
    private GraphPenetrateService service;

    @BeforeEach
    void setUp() {
        snapshotStore = Mockito.mock(GraphSnapshotStore.class);
        service = new GraphPenetrateService(snapshotStore);
    }

    private Map<String, Object> entity(String entityType, String entityId) {
        return Map.of("entityType", entityType, "entityId", entityId);
    }

    @Test
    void 上行穿透_实控人加权认定成立() {
        GraphPenetrateDtos.PenetrateResult r = service.penetrate(Map.of(
                "entity", entity("ORG", "G001"), "direction", "UPSTREAM", "includeControl", true), "g1");
        // 仿真上行恒生成 5 条路径（直接/间接 × 股东平台自然人 + 其余股东）
        assertEquals(5, r.paths().size());
        assertEquals(4, r.equitySummary().size());
        // 自然人间接加权 = r0×70% + rp×60% 恒 ≥ 0.50，必认定实控人
        assertFalse(r.controllers().isEmpty());
        for (GraphPenetrateDtos.Controller c : r.controllers()) {
            assertTrue(c.weightedRatio() >= 0.50);
            assertEquals("WEIGHTED_SUM", c.method());
        }
        // 汇总项加权 = 直接 + 间接
        for (GraphPenetrateDtos.EquityItem item : r.equitySummary()) {
            assertEquals(item.weighted(), item.direct() + item.indirect());
        }
    }

    @Test
    void 下行穿透_对外投资路径与实控人() {
        GraphPenetrateDtos.PenetrateResult r = service.penetrate(Map.of(
                "entity", entity("PERSON", "G002"), "direction", "DOWNSTREAM", "includeControl", true), "g2");
        int n = r.paths().size();
        assertTrue(n >= 2 && n <= 4);
        // 被投企业持股比例 0.51~0.75 恒 ≥ 50%，逐户认定实控人
        assertEquals(n, r.controllers().size());
        for (GraphPenetrateDtos.Path p : r.paths()) {
            assertTrue(p.ratio() >= 0.51);
            assertEquals(1, p.hops());
        }
    }

    @Test
    void 参数校验_entity或方向缺失_抛GPT_1001() {
        BaiException entity = assertThrows(BaiException.class,
                () -> service.penetrate(Map.of("direction", "UPSTREAM"), "e1"));
        assertEquals("GPT_1001", entity.getCode());
        BaiException direction = assertThrows(BaiException.class,
                () -> service.penetrate(Map.of("entity", entity("ORG", "G003")), "e2"));
        assertEquals("GPT_1001", direction.getCode());
    }

    @Test
    void 方向或深度非法_抛GPT_1007() {
        BaiException illegalDir = assertThrows(BaiException.class,
                () -> service.penetrate(Map.of("entity", entity("ORG", "G003"),
                        "direction", "SIDE"), "e3"));
        assertEquals("GPT_1007", illegalDir.getCode());
        BaiException deep = assertThrows(BaiException.class,
                () -> service.penetrate(Map.of("entity", entity("ORG", "G004"),
                        "direction", "UPSTREAM", "maxDepth", 11), "e4"));
        assertEquals("GPT_1007", deep.getCode());
    }

    @Test
    void 实体未对齐_抛GPT_1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.penetrate(Map.of("entity", entity("ORG", "UNKNOWN-001"),
                        "direction", "UPSTREAM"), "e5"));
        assertEquals("GPT_1004", e.getCode());
    }

    @Test
    void 同requestId_穿透幂等复用首次结果() {
        String rid = "idem-1";
        Map<String, Object> body = Map.of("entity", entity("ORG", "G005"), "direction", "UPSTREAM");
        GraphPenetrateDtos.PenetrateResult a = service.penetrate(body, rid);
        GraphPenetrateDtos.PenetrateResult b = service.penetrate(body, rid);
        assertEquals(a.penetrateId(), b.penetrateId());
    }

    @Test
    void 批量穿透_超50条抛GPT_1006() {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            items.add(Map.of("entity", entity("ORG", "B" + i), "direction", "UPSTREAM"));
        }
        BaiException e = assertThrows(BaiException.class,
                () -> service.batch(Map.of("items", items), "batch-x"));
        assertEquals("GPT_1006", e.getCode());
    }

    @Test
    void sungraph真实穿透_路径乘积实控人与担保圈检测() throws Exception {
        setProvider("sungraph");
        GraphSnapshotStore.Snapshot snap = new GraphSnapshotStore.Snapshot(
                Map.of("N1", new GraphSnapshotStore.GNode("N1", "COMPANY", "甲公司"),
                        "N2", new GraphSnapshotStore.GNode("N2", "COMPANY", "乙公司"),
                        "P1", new GraphSnapshotStore.GNode("P1", "PERSON", "张三"),
                        "N3", new GraphSnapshotStore.GNode("N3", "COMPANY", "丙公司")),
                List.of(new GraphSnapshotStore.GEdge("E1", "P1", "N1", "EQUITY", 0.8, "core", "2020-01-01"),
                        new GraphSnapshotStore.GEdge("E2", "N1", "N2", "EQUITY", 0.7, "core", "2020-01-01"),
                        new GraphSnapshotStore.GEdge("E3", "N2", "N3", "GUARANTEE", 1.0, "core", null),
                        new GraphSnapshotStore.GEdge("E4", "N3", "N2", "GUARANTEE", 1.0, "core", null)));
        when(snapshotStore.get()).thenReturn(snap);

        GraphPenetrateDtos.PenetrateResult r = service.penetrate(Map.of(
                "entity", entity("ORG", "N2"), "direction", "UPSTREAM", "maxDepth", 5, "includeControl", true), "sg1");
        assertEquals(2, r.paths().size());
        // 直接 0.7，间接 0.7×0.8=0.56
        assertEquals(0.7, r.paths().get(0).ratio());
        assertEquals(0.56, r.paths().get(1).ratio());
        assertEquals(1, r.controllers().size());
        assertEquals("张三", r.controllers().get(0).person());
        assertEquals(0.56, r.controllers().get(0).weightedRatio());
        // 张三经 N2→N3→N2 担保圈闭环
        assertTrue(r.cycleDetected());
    }

    @Test
    void sungraph异常_节点类型不一致或快照失败() throws Exception {
        setProvider("sungraph");
        // 节点类型与契约 entityType 不一致 → GPT_1001
        GraphSnapshotStore.Snapshot snap = new GraphSnapshotStore.Snapshot(
                Map.of("P1", new GraphSnapshotStore.GNode("P1", "PERSON", "张三")),
                List.of());
        when(snapshotStore.get()).thenReturn(snap);
        BaiException mismatch = assertThrows(BaiException.class,
                () -> service.penetrate(Map.of("entity", entity("ORG", "P1"),
                        "direction", "UPSTREAM"), "sg2"));
        assertEquals("GPT_1001", mismatch.getCode());
        // sungraph 不可达 → GPT_2001 降级确定性仿真，链路不中断
        when(snapshotStore.get()).thenThrow(new IOException("sungraph 不可达"));
        GraphPenetrateDtos.PenetrateResult degraded = service.penetrate(Map.of(
                "entity", entity("ORG", "G006"), "direction", "UPSTREAM"), "sg3");
        assertEquals(5, degraded.paths().size());
    }

    private void setProvider(String value) throws Exception {
        Field f = GraphPenetrateService.class.getDeclaredField("provider");
        f.setAccessible(true);
        f.set(service, value);
    }
}
