package com.bai.modelinfer;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModelInferService 单元测试：评分卡规则打分、特征字典校验、场景/类型校验、聚类分群确定性、模型注册治理、可解释查询。
 * <p>评分卡口径：40 + min(25,收入/50) + min(20,净现比×25) − max(0,(负债率−50)×0.75)；等级分段 ≥65 BBB。
 */
class ModelInferServiceTest {

    private final ModelInferService service = new ModelInferService();

    @Test
    void 评分卡推理_数值正确() {
        ModelInferDtos.ScoreResult r = service.score(Map.of(
                "scene", "RATING", "modelType", "SCORECARD", "modelId", "CRB-SC-2025-V3",
                "customerId", "C001",
                "features", Map.of("annualIncome", 1000000, "assetLiabRatio", 60, "netCashRatio", 0.8)), "s1");
        // 40 + 25 + 20 − 7.5 = 77.5
        assertEquals(77.5, r.score());
        assertEquals("BBB", r.grade());
        assertEquals(0.0162, r.probability());
        assertEquals("CRB-SC-2025-V3.2", r.modelVersion());
        assertEquals(3, r.explanation().size());
    }

    @Test
    void 必填特征缺失_抛MDI_1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.score(Map.of(
                        "scene", "RATING", "modelType", "SCORECARD", "modelId", "CRB-SC-2025-V3",
                        "customerId", "C001",
                        "features", Map.of("annualIncome", 1000000)), "e1"));
        assertEquals("MDI_1001", e.getCode());
    }

    @Test
    void RATING场景缺customerId_抛MDI_1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.score(Map.of(
                        "scene", "RATING", "modelType", "SCORECARD", "modelId", "CRB-SC-2025-V3",
                        "features", Map.of("annualIncome", 1000000, "assetLiabRatio", 60, "netCashRatio", 0.8)), "e2"));
        assertEquals("MDI_1001", e.getCode());
    }

    @Test
    void 模型非ACTIVE_抛MDI_1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.score(Map.of(
                        "scene", "RATING", "modelType", "SCORECARD", "modelId", "CRB-SC-2024-V2",
                        "customerId", "C002",
                        "features", Map.of("annualIncome", 1000000, "assetLiabRatio", 60, "netCashRatio", 0.8)), "e3"));
        assertEquals("MDI_1004", e.getCode());
    }

    @Test
    void 场景或模型类型非法_抛MDI_1007() {
        BaiException scene = assertThrows(BaiException.class,
                () -> service.score(Map.of("scene", "XXX", "modelType", "SCORECARD",
                        "modelId", "CRB-SC-2025-V3", "customerId", "C003",
                        "features", Map.of("annualIncome", 1000000)), "e4"));
        assertEquals("MDI_1007", scene.getCode());
        BaiException type = assertThrows(BaiException.class,
                () -> service.score(Map.of("scene", "RATING", "modelType", "SVM",
                        "modelId", "CRB-SC-2025-V3", "customerId", "C003",
                        "features", Map.of("annualIncome", 1000000)), "e5"));
        assertEquals("MDI_1007", type.getCode());
    }

    @Test
    void 聚类分群_确定性一致() {
        Map<String, Object> body = Map.of("scene", "ECL", "modelId", "ECL-CL-2026-V1", "k", 5,
                "features", Map.of("exposure", 1000000, "pd", 0.05));
        ModelInferDtos.ClusterResult a = service.cluster(body, "c1");
        ModelInferDtos.ClusterResult b = service.cluster(body, "c2");
        assertNotNull(a.clusterId());
        assertNotNull(a.clusterName());
        assertEquals(a.clusterId(), b.clusterId());
        assertEquals(a.clusterName(), b.clusterName());
        assertEquals(a.distance(), b.distance());
        assertEquals(2, a.clusterProfile().size());
    }

    @Test
    void 聚类k超限_抛MDI_1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.cluster(Map.of("scene", "ECL", "modelId", "ECL-CL-2026-V1", "k", 21,
                        "features", Map.of("exposure", 1000000, "pd", 0.05)), "e6"));
        assertEquals("MDI_1001", e.getCode());
    }

    @Test
    void 模型注册_成功且重名冲突() {
        ModelInferDtos.ModelManageResult r = service.modelManage(Map.of(
                "action", "REGISTER",
                "model", Map.of("name", "风控模型A", "type", "SCORECARD", "scene", "RATING")), "m1");
        assertEquals("风控模型A", r.modelId());
        assertEquals("ACTIVE", r.status());
        assertEquals("1.0.0", r.modelVersion());
        BaiException dup = assertThrows(BaiException.class,
                () -> service.modelManage(Map.of(
                        "action", "REGISTER",
                        "model", Map.of("name", "风控模型A", "type", "SCORECARD", "scene", "RATING")), "m2"));
        assertEquals("MDI_1005", dup.getCode());
    }

    @Test
    void 可解释查询_评分卡贡献明细() {
        ModelInferDtos.ExplainResult r = service.explain("inf-1", "CRB-SC-2025-V3", "SCORECARD_CONTRIB", "x1");
        assertEquals("SCORECARD_CONTRIB", r.method());
        assertEquals(3, r.contributions().size());
        assertEquals("assetLiabRatio", r.contributions().get(0).feature());
        assertEquals("负向", r.contributions().get(0).direction());
        assertEquals(0.28, r.contributions().get(0).contribution());
        assertTrue(r.conclusion().contains("assetLiabRatio"));
    }
}
