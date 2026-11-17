package com.bai.graph;

import com.bai.common.BaiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphBuildService 单元测试：异步任务受理（PENDING→DONE，mock 仿真统计）、幂等（requestId+dataVersion）、
 * 参数校验、节点/边确定性列表。SungraphClient 用 Mockito mock（graph.provider 缺省 mock 不触发真实调用）。
 */
class GraphBuildServiceTest {

    private GraphBuildService service;

    @BeforeEach
    void setUp() {
        service = new GraphBuildService(Mockito.mock(SungraphClient.class));
    }

    private GraphBuildDtos.BuildRequest fullReq(String dataVersion) {
        return new GraphBuildDtos.BuildRequest("FULL", new GraphBuildDtos.Scope("ORG001",
                List.of("EQUITY", "GUARANTEE")), dataVersion);
    }

    /** 轮询任务至 DONE（worker 200ms 仿真写入，超时 3s） */
    private GraphBuildDtos.JobView awaitDone(String jobId) {
        for (int i = 0; i < 30; i++) {
            GraphBuildDtos.JobView v = service.job(jobId);
            if ("DONE".equals(v.status())) {
                return v;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                fail("等待任务完成被中断");
            }
        }
        fail("任务 " + jobId + " 3s 内未完成");
        return null;
    }

    // ------------------------------------------------------------ 构建

    @Test
    void FULL构建_受理并完成DONE且统计确定() {
        GraphBuildDtos.BuildResult r = service.build(fullReq(null), "grb-1");
        assertTrue(r.jobId().startsWith("GRJ"));
        assertEquals("FULL", r.mode());
        // 异步任务：返回瞬时可能已流转至 RUNNING/DONE（worker 200ms 仿真），以轮询终态为准
        GraphBuildDtos.JobView done = awaitDone(r.jobId());
        assertEquals("DONE", done.status());
        assertNotNull(done.stats());
        assertTrue(done.stats().nodesAdded() >= 1200 && done.stats().nodesAdded() < 2000);
        assertTrue(done.stats().edgesAdded() >= 3500 && done.stats().edgesAdded() < 5000);
        assertEquals(0, done.stats().nodesUpdated());
        assertEquals(Math.round(done.stats().edgesAdded() * 0.99), done.stats().goldenLinked());
        // 确定性：同 dataVersion 不同 requestId → 统计一致
        GraphBuildDtos.BuildResult r2 = service.build(fullReq(null), "grb-1b");
        GraphBuildDtos.JobView done2 = awaitDone(r2.jobId());
        assertEquals(done.stats(), done2.stats());
    }

    @Test
    void INCREMENTAL构建_事件数参与统计且goldenLinked取整() {
        GraphBuildDtos.BuildResult r = service.increment(new GraphBuildDtos.IncrementRequest(List.of(
                Map.of("op", "NODE_UPSERT", "nodeId", "N100", "nodeType", "PERSON", "name", "测试客户"),
                Map.of("op", "EDGE_UPSERT", "edgeId", "E100", "fromNode", "N100", "toNode", "N00001",
                        "relationType", "EQUITY")), "20261110-0930"), "grb-i1");
        assertEquals("INCREMENTAL", r.mode());
        GraphBuildDtos.JobView done = awaitDone(r.jobId());
        assertTrue(done.stats().nodesAdded() >= 80 && done.stats().nodesAdded() < 200);
        assertTrue(done.stats().edgesAdded() >= 150 && done.stats().edgesAdded() < 350);
        assertTrue(done.stats().nodesUpdated() >= 40 && done.stats().nodesUpdated() < 100);
        assertEquals(Math.round(done.stats().edgesAdded() * 0.99), done.stats().goldenLinked());
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void 同requestId同dataVersion_返回首次jobId() {
        String rid = "grb-idem";
        GraphBuildDtos.BuildResult a = service.build(fullReq("20261110-0800"), rid);
        GraphBuildDtos.BuildResult b = service.build(fullReq("20261110-0800"), rid);
        assertEquals(a.jobId(), b.jobId());
        GraphBuildDtos.BuildResult c = service.increment(new GraphBuildDtos.IncrementRequest(
                List.of(Map.of("op", "NODE_UPSERT", "nodeId", "N200")), "20261110-0800"), "grb-idem-i");
        GraphBuildDtos.BuildResult d = service.increment(new GraphBuildDtos.IncrementRequest(
                List.of(Map.of("op", "NODE_UPSERT", "nodeId", "N200")), "20261110-0800"), "grb-idem-i");
        assertEquals(c.jobId(), d.jobId());
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    void mode缺失或非法_抛GRB1001() {
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.build(new GraphBuildDtos.BuildRequest(null, null, null), "grb-e1"));
        assertEquals("GRB_1001", e1.getCode());
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.build(new GraphBuildDtos.BuildRequest("PARTIAL", null, null), "grb-e2"));
        assertEquals("GRB_1001", e2.getCode());
    }

    @Test
    void INCREMENTAL缺失dataVersion_抛GRB1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.build(new GraphBuildDtos.BuildRequest("INCREMENTAL", null, null), "grb-e3"));
        assertEquals("GRB_1006", e.getCode());
    }

    @Test
    void dataVersion格式非法_抛GRB1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.build(fullReq("2026-1-1"), "grb-e4"));
        assertEquals("GRB_1006", e.getCode());
    }

    @Test
    void relationTypes未知_抛GRB1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.build(new GraphBuildDtos.BuildRequest("FULL",
                        new GraphBuildDtos.Scope("ORG001", List.of("EQUITY", "UNKNOWN_REL")), null), "grb-e5"));
        assertEquals("GRB_1006", e.getCode());
    }

    @Test
    void increment_events缺失或超10000条_抛错() {
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.increment(new GraphBuildDtos.IncrementRequest(null, "20261110-0800"), "grb-e6"));
        assertEquals("GRB_1001", e1.getCode());
        java.util.ArrayList<Map<String, Object>> many = new java.util.ArrayList<>();
        for (int i = 0; i < 10001; i++) {
            many.add(Map.of("op", "NODE_UPSERT", "nodeId", "N" + i));
        }
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.increment(new GraphBuildDtos.IncrementRequest(many, "20261110-0800"), "grb-e7"));
        assertEquals("GRB_1005", e2.getCode());
    }

    @Test
    void jobId不存在_抛GRB1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.job("NO-JOB"));
        assertEquals("GRB_1004", e.getCode());
    }

    // ------------------------------------------------------------ 节点/边

    @Test
    void 节点与边列表_确定性仿真() {
        GraphBuildDtos.NodesResult nodes = service.nodes();
        assertEquals(11, nodes.total());
        assertEquals(11, nodes.nodes().size());
        assertEquals("N00001", nodes.nodes().get(0).nodeId());
        assertEquals("北京华信科技有限公司", nodes.nodes().get(0).name());
        GraphBuildDtos.EdgesResult edges = service.edges();
        assertEquals(18, edges.total());
        assertEquals("E00001", edges.edges().get(0).edgeId());
        assertEquals("EQUITY", edges.edges().get(0).relationType());
    }
}
