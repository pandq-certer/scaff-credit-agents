package com.bai.quality;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DataQualityService 单元测试：数据质量治理（确定性仿真）——规则执行、维度聚合、
 * 加权得分、错误码、幂等与确定性。
 */
class DataQualityServiceTest {

    private final DataQualityService service = new DataQualityService();

    @Test
    void 全量规则质检_返回10条规则结果与得分() {
        DataQualityDtos.CheckResult r = service.check(
                new DataQualityDtos.CheckRequest("dwd_sum.loan_daily", null, "2026-09-30"), "dq-ok1");
        assertNotNull(r.checkId());
        assertEquals("dwd_sum.loan_daily", r.assetId());
        assertEquals("2026-09-30", r.asOf());
        assertEquals(10, r.results().size());
        assertTrue(r.qualityScore() >= 0 && r.qualityScore() <= 100);
        assertNotNull(r.dimensionScores());
    }

    @Test
    void 指定单规则_仅执行该规则() {
        DataQualityDtos.CheckResult r = service.check(
                new DataQualityDtos.CheckRequest("dwd_sum.loan_daily", List.of("R_CMP_001"), "2026-09-30"), "dq-r1");
        assertEquals(1, r.results().size());
        assertEquals("R_CMP_001", r.results().get(0).ruleId());
        assertEquals("completeness", r.results().get(0).dimension());
    }

    @Test
    void 同入参不同requestId_得分结果一致() {
        DataQualityDtos.CheckRequest req = new DataQualityDtos.CheckRequest("dwd_sum.loan_daily", null, "2026-09-30");
        DataQualityDtos.CheckResult a = service.check(req, "dq-d1");
        DataQualityDtos.CheckResult b = service.check(req, "dq-d2");
        assertNotEquals(a.checkId(), b.checkId());
        assertEquals(a.qualityScore(), b.qualityScore());
        assertEquals(a.results(), b.results());
        assertEquals(a.degraded(), b.degraded());
    }

    @Test
    void 同requestId_返回首次结果() {
        String rid = "dq-idem";
        DataQualityDtos.CheckRequest req = new DataQualityDtos.CheckRequest("dwd_sum.loan_daily", null, "2026-09-30");
        DataQualityDtos.CheckResult a = service.check(req, rid);
        DataQualityDtos.CheckResult b = service.check(req, rid);
        assertEquals(a.checkId(), b.checkId());
    }

    @Test
    void 未注册资产_抛DQL1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.check(new DataQualityDtos.CheckRequest("ods_core.hacker", null, null), "dq-e1"));
        assertEquals("DQL_1006", e.getCode());
    }

    @Test
    void asOf格式非法_抛DQL1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.check(new DataQualityDtos.CheckRequest("dwd_sum.loan_daily", null, "2026/09/30"), "dq-e2"));
        assertEquals("DQL_1001", e.getCode());
    }

    @Test
    void 未知规则ID_抛DQL1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.check(new DataQualityDtos.CheckRequest("dwd_sum.loan_daily",
                        List.of("R_XXX"), null), "dq-e3"));
        assertEquals("DQL_1004", e.getCode());
    }

    @Test
    void 规则数超50_抛DQL1005() {
        List<String> many = IntStream.range(0, 51).mapToObj(i -> "R_CMP_001").toList();
        BaiException e = assertThrows(BaiException.class,
                () -> service.check(new DataQualityDtos.CheckRequest("dwd_sum.loan_daily", many, null), "dq-e4"));
        assertEquals("DQL_1005", e.getCode());
    }

    @Test
    void assetId缺失_抛DQL1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.check(new DataQualityDtos.CheckRequest(null, null, null), "dq-e5"));
        assertEquals("DQL_1001", e.getCode());
    }

    @Test
    void 批量质检_返回通过失败统计() {
        DataQualityDtos.BatchResult r = service.batch(new DataQualityDtos.BatchRequest(
                "dwd_sum.loan_daily", List.of("R_CMP_001", "R_ACC_001")), "dq-b1");
        assertEquals("dwd_sum.loan_daily", r.assetId());
        assertEquals(2, r.total());
        assertEquals(2, r.passed() + r.failed());
        assertEquals(2, r.results().size());
    }

    @Test
    void 规则库_五维度十规则() {
        DataQualityDtos.RulesResult r = service.rules();
        assertEquals("2026.09.01", r.version());
        assertEquals(10, r.rules().size());
        assertTrue(r.rules().stream().anyMatch(d -> "uniqueness".equals(d.dimension())));
    }

    @Test
    void 工单库_初始为空() {
        DataQualityDtos.IssuesResult r = service.issues();
        assertEquals(0, r.total());
    }
}
