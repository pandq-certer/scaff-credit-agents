package com.bai.validate;

import com.bai.common.BaiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DataValidateService 单元测试：数据校验（确定性仿真）——模板展开、八类算子求值、
 * FAIL/WARN/PASS 汇总、跨系统跳过、错误码、幂等。
 */
class DataValidateServiceTest {

    private final DataValidateService service = new DataValidateService(new ObjectMapper());

    private DataValidateDtos.ValidateRequest submit(Map<String, Object> data, DataValidateDtos.Context ctx) {
        return new DataValidateDtos.ValidateRequest("T_SUBMIT", null, data, ctx);
    }

    private Map<String, Object> fullData() {
        return Map.of(
                "name", "张三", "idCard", "110101199001011234", "phone", "13812345678",
                "applyDate", "2026-10-01", "applyAmount", 500000, "term", 36,
                "endDate", "2026-10-30", "startDate", "2026-10-01", "ltv", 0.5);
    }

    @Test
    void 模板校验_全通过返回PASS() {
        DataValidateDtos.ValidateResult r = service.validate(submit(fullData(), null), "dv-ok1");
        assertEquals("PASS", r.result());
        assertEquals("T_SUBMIT", r.templateId());
        assertEquals(9, r.summary().passed()); // T_SUBMIT 10 条规则，跨系统算子不计数
        assertEquals(0, r.summary().failed());
        assertFalse(r.crossSystemSkipped());
    }

    @Test
    void 缺必填字段_返回FAIL含明细() {
        Map<String, Object> data = Map.of(
                "idCard", "110101199001011234", "phone", "13812345678",
                "applyDate", "2026-10-01", "applyAmount", 500000, "term", 36,
                "endDate", "2026-10-30", "startDate", "2026-10-01", "ltv", 0.5);
        DataValidateDtos.ValidateResult r = service.validate(submit(data, null), "dv-f1");
        assertEquals("FAIL", r.result());
        assertEquals(1, r.summary().failed());
        assertEquals("R_REQ_CUST_NAME", r.details().get(0).ruleId());
        assertEquals("ERROR", r.details().get(0).level());
    }

    @Test
    void LTV超阈值且跨系统_返回WARN并置跳过标记() {
        Map<String, Object> data = new java.util.HashMap<>(fullData());
        data.put("ltv", 0.8);
        DataValidateDtos.ValidateResult r = service.validate(submit(data, new DataValidateDtos.Context("ORG1", "CORE")), "dv-w1");
        assertEquals("WARN", r.result());
        assertTrue(r.crossSystemSkipped());
        assertEquals(2, r.summary().warned()); // R_THR_LTV + 跨系统跳过
        assertEquals(0, r.summary().failed());
        assertTrue(r.details().stream().anyMatch(d -> "R_XSYS_001".equals(d.ruleId())));
    }

    @Test
    void 自定义规则_按规则ID执行() {
        DataValidateDtos.ValidateResult r = service.validate(
                new DataValidateDtos.ValidateRequest(null, List.of("R_FMT_PHONE"),
                        Map.of("phone", "123"), null), "dv-c1");
        assertEquals("CUSTOM", r.templateId());
        assertEquals("FAIL", r.result());
        assertEquals("R_FMT_PHONE", r.details().get(0).ruleId());
    }

    @Test
    void 模板不存在_抛DVA1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.validate(new DataValidateDtos.ValidateRequest("T_X", null, fullData(), null), "dv-e1"));
        assertEquals("DVA_1004", e.getCode());
    }

    @Test
    void 规则ID不存在_抛DVA1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.validate(new DataValidateDtos.ValidateRequest(null, List.of("R_X"),
                        fullData(), null), "dv-e2"));
        assertEquals("DVA_1004", e.getCode());
    }

    @Test
    void data缺失_抛DVA1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.validate(new DataValidateDtos.ValidateRequest("T_SUBMIT", null,
                        Map.of(), null), "dv-e3"));
        assertEquals("DVA_1001", e.getCode());
    }

    @Test
    void templateId与rules都缺_抛DVA1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.validate(new DataValidateDtos.ValidateRequest(null, null, fullData(), null), "dv-e4"));
        assertEquals("DVA_1001", e.getCode());
    }

    @Test
    void 同requestId同数据_返回首次结果() {
        String rid = "dv-idem";
        DataValidateDtos.ValidateResult a = service.validate(submit(fullData(), null), rid);
        DataValidateDtos.ValidateResult b = service.validate(submit(fullData(), null), rid);
        assertEquals(a.validateId(), b.validateId());
        assertEquals(a.result(), b.result());
    }

    @Test
    void 不同requestId_校验结果一致() {
        DataValidateDtos.ValidateResult a = service.validate(submit(fullData(), null), "dv-d1");
        DataValidateDtos.ValidateResult b = service.validate(submit(fullData(), null), "dv-d2");
        assertNotEquals(a.validateId(), b.validateId());
        assertEquals(a.result(), b.result());
        assertEquals(a.summary().passed(), b.summary().passed());
    }

    @Test
    void 批量空列表_抛DVA1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.batch(new DataValidateDtos.BatchRequest(List.of()), "dv-b1"));
        assertEquals("DVA_1001", e.getCode());
    }

    @Test
    void 规则库_版本固定且非空() {
        DataValidateDtos.RulesResult r = service.rules();
        assertEquals("2026.09.01", r.version());
        assertFalse(r.rules().isEmpty());
    }
}
