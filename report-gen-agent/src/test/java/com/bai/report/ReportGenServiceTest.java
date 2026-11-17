package com.bai.report;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReportGenService 单元测试：覆盖生成（仿真渲染）、参数校验、幂等、批量、报送登记。
 * <p>确定性仿真：同入参必得同结果；渲染校验三要素 PASS；勾稽不平可用 flag=UNBALANCE 触发 RPG_1006。
 */
class ReportGenServiceTest {

    private final ReportGenService service = new ReportGenService();

    private ReportGenDtos.GenerateRequest req(String templateId, Map<String, Object> params, String format) {
        return new ReportGenDtos.GenerateRequest(templateId, params, format);
    }

    private Map<String, Object> params(String date) {
        return Map.of("orgId", "ORG001", "date", date, "granularity", "MONTH");
    }

    // ------------------------------------------------------------ 生成

    @Test
    void 生成报表_成功路径_前缀与三校验PASS() {
        ReportGenDtos.GenerateResult r = service.generate(
                req("T_MGMT_MONTHLY", params("2026-10-31"), null), "rpg-1");
        assertNotNull(r.reportId());
        assertTrue(r.reportId().startsWith("RPT"));
        assertTrue(r.fileId().startsWith("OBJ"));
        assertEquals("EXCEL", r.format());          // format 缺省 EXCEL
        assertEquals("2026-09-01", r.templateVersion());
        assertEquals("PASS", r.checks().caliber());
        assertEquals("PASS", r.checks().required());
        assertEquals("PASS", r.checks().balance());
        assertNull(r.submissionId());               // 报送登记前为 null
        // 确定性：同入参两次调用（不同 requestId）三校验/版本/格式一致
        ReportGenDtos.GenerateResult b = service.generate(
                req("T_MGMT_MONTHLY", params("2026-10-31"), null), "rpg-1b");
        assertEquals(r.checks(), b.checks());
        assertEquals(r.templateVersion(), b.templateVersion());
        assertEquals(r.format(), b.format());
    }

    @Test
    void 模板不存在或未发布_抛RPG1004() {
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.generate(req("T_NO_EXIST", params("2026-10-31"), null), "rpg-2"));
        assertEquals("RPG_1004", e1.getCode());
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.generate(req("T_1104_G03_DRAFT", params("2026-10-31"), null), "rpg-3"));
        assertEquals("RPG_1004", e2.getCode());
    }

    @Test
    void params缺date或格式非法_抛RPG1001() {
        Map<String, Object> noDate = Map.of("orgId", "ORG001", "granularity", "MONTH");
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.generate(req("T_1104_G01", noDate, null), "rpg-4"));
        assertEquals("RPG_1001", e1.getCode());
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.generate(req("T_1104_G01", params("2026/10/31"), null), "rpg-5"));
        assertEquals("RPG_1001", e2.getCode());
    }

    @Test
    void format非法_抛RPG1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate(req("T_1104_G01", params("2026-10-31"), "PPT"), "rpg-6"));
        assertEquals("RPG_1001", e.getCode());
    }

    @Test
    void 勾稽不平_flagUNBALANCE_抛RPG1006() {
        Map<String, Object> p = new java.util.HashMap<>(params("2026-10-31"));
        p.put("flag", "UNBALANCE");
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate(req("T_1104_G01", p, null), "rpg-7"));
        assertEquals("RPG_1006", e.getCode());
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void 同requestId同入参_返回首次reportId() {
        String rid = "rpg-idem";
        ReportGenDtos.GenerateResult a = service.generate(req("T_1104_G21", params("2026-09-30"), null), rid);
        ReportGenDtos.GenerateResult b = service.generate(req("T_1104_G21", params("2026-09-30"), null), rid);
        assertEquals(a.reportId(), b.reportId());
        assertEquals(a.fileId(), b.fileId());
    }

    // ------------------------------------------------------------ 批量

    @Test
    void 批量_空items或超200张_抛错() {
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.batch(new ReportGenDtos.BatchRequest(List.of()), "rpg-b1"));
        assertEquals("RPG_1001", e1.getCode());
        List<ReportGenDtos.GenerateRequest> items = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            items.add(req("T_1104_G01", params("2026-10-31"), null));
        }
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.batch(new ReportGenDtos.BatchRequest(items), "rpg-b2"));
        assertEquals("RPG_1005", e2.getCode());
    }

    @Test
    void 批量成功_受理ACCEPTED且total正确() {
        ReportGenDtos.BatchResult r = service.batch(new ReportGenDtos.BatchRequest(List.of(
                req("T_1104_G01", params("2026-10-31"), null),
                req("T_1104_G21", params("2026-10-31"), null))), "rpg-b3");
        assertTrue(r.batchId().startsWith("RPB"));
        assertEquals("ACCEPTED", r.status());
        assertEquals(2, r.total());
    }

    // ------------------------------------------------------------ 报送登记

    @Test
    void 报送登记_成功与重报幂等_不存在抛RPG1004() {
        ReportGenDtos.GenerateResult g = service.generate(
                req("T_1104_G01", params("2026-10-31"), null), "rpg-s0");
        ReportGenDtos.SubmissionResult s1 = service.submissions(
                new ReportGenDtos.SubmissionRequest(g.reportId(), "EAST"), "rpg-s1");
        assertTrue(s1.submissionId().startsWith("SUB"));
        assertEquals("SUBMITTED", s1.status());
        ReportGenDtos.SubmissionResult s2 = service.submissions(
                new ReportGenDtos.SubmissionRequest(g.reportId(), "EAST"), "rpg-s2");
        assertEquals(s1.submissionId(), s2.submissionId());   // 重报不新增
        BaiException e = assertThrows(BaiException.class,
                () -> service.submissions(new ReportGenDtos.SubmissionRequest("NO-EXIST", "EAST"), "rpg-s3"));
        assertEquals("RPG_1004", e.getCode());
    }
}
