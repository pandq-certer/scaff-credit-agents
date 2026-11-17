package com.bai.structuregen;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StructureGenService 单元测试：文本/要点/数据/主题四类输入的结构化生成、字段级校验、Schema 管理、幂等。
 * <p>确定性仿真：正则规则抽取（LLM 降级兜底路径），同入参必得同字段，可直接断言。
 */
class StructureGenServiceTest {

    private final StructureGenService service = new StructureGenService();

    private Map<String, Object> body(String schemaId, String inputType, String outputType, Object input,
                                     Map<String, Object> facts, String bizKey) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("schemaId", schemaId);
        b.put("inputType", inputType);
        b.put("outputType", outputType);
        b.put("input", input);
        if (facts != null) {
            b.put("facts", facts);
        }
        if (bizKey != null) {
            b.put("bizKey", bizKey);
        }
        return b;
    }

    // ------------------------------------------------------------ 主能力

    @Test
    void 文本抽取_尽调卡片字段与校验通过() {
        String text = "XX机械制造有限公司成立于2010年，主营机械加工。2025年度营业收入 5000 万，"
                + "净利润 300 万。企业申请流动资金贷款 800 万，以厂房抵押担保，抵押率 72%。";
        StructureGenDtos.GenerateResult r = service.generate(
                body("SCHEMA_DD_CARD_V2", "TEXT", "JSON", text, Map.of("customerId", "C000000001"), "biz-s1"), "req-s1");
        assertTrue(r.validation().passed(), "必填 customerName/applyAmount/applyPurpose 均应抽取到");
        assertTrue(r.validation().issues().isEmpty());
        assertEquals("struct-gen-v2", r.model());
        Map<?, ?> result = (Map<?, ?>) r.result();
        assertEquals("XX机械制造有限公司", result.get("customerName"));
        assertEquals("800.0", String.valueOf(result.get("applyAmount")));
        assertEquals("流动资金贷款", result.get("applyPurpose"));
        assertEquals("MEDIUM", String.valueOf(result.get("riskLevel")));   // 72% >= 70 → MEDIUM
        assertEquals("机械设备制造", result.get("business"));
    }

    @Test
    void 要点输入_按字段顺序映射() {
        StructureGenDtos.GenerateResult r = service.generate(body("SCHEMA_DD_CARD_V2", "POINTS", "JSON",
                List.of("C001", "张三", "商贸流通", "1000", "100", "500", "流动资金贷款"), null, "biz-s2"), "req-s2");
        assertTrue(r.validation().passed());
        Map<?, ?> result = (Map<?, ?>) r.result();
        assertEquals("C001", result.get("customerId"));
        assertEquals("张三", result.get("customerName"));
        assertEquals("流动资金贷款", result.get("applyPurpose"));
        assertEquals(7, r.entityMapped());
    }

    @Test
    void 数据输入_贷款要素表生成TABLE() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("customerName", "李四");
        input.put("product", "经营贷款");
        input.put("amount", 100);
        input.put("term", 12);
        input.put("rate", 3.5);
        input.put("guarantee", "保证担保");
        StructureGenDtos.GenerateResult r = service.generate(
                body("SCHEMA_LOAN_TABLE_V1", "DATA", "TABLE", input, null, "biz-s3"), "req-s3");
        assertTrue(r.validation().passed());
        Map<?, ?> result = (Map<?, ?>) r.result();
        assertEquals(List.of("customerName", "product", "amount", "term", "rate", "guarantee"), result.get("columns"));
        assertTrue(result.get("rows") instanceof List<?> rows && rows.size() == 1);
    }

    @Test
    void 主题输入_生成报告大纲() {
        StructureGenDtos.GenerateResult r = service.generate(body("SCHEMA_REPORT_OUTLINE_V1", "TOPIC", "OUTLINE",
                Map.of("topic", "XX公司尽调报告"), null, "biz-s4"), "req-s4");
        assertTrue(r.validation().passed());
        Map<?, ?> result = (Map<?, ?>) r.result();
        assertEquals("XX公司尽调报告", result.get("title"));
        assertTrue(result.get("sections") instanceof List<?> sections && sections.size() == 5);
    }

    @Test
    void 文本缺必填要素_校验未过() {
        StructureGenDtos.GenerateResult r = service.generate(
                body("SCHEMA_DD_CARD_V2", "TEXT", "CARD", "今天天气很好。", null, "biz-s5"), "req-s5");
        assertFalse(r.validation().passed());
        assertFalse(r.validation().issues().isEmpty());
        // CARD 组装以 schema 名作标题
        Map<?, ?> result = (Map<?, ?>) r.result();
        assertEquals("尽调摘要卡片", result.get("title"));
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    void schemaId缺失_抛STG1001() {
        Map<String, Object> b = body("SCHEMA_DD_CARD_V2", "TEXT", "JSON", "a", null, null);
        b.remove("schemaId");
        BaiException e = assertThrows(BaiException.class, () -> service.generate(b, "req-e1"));
        assertEquals("STG_1001", e.getCode());
    }

    @Test
    void schema不存在_抛STG1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate(body("SCHEMA_NOPE", "TEXT", "JSON", "a", null, null), "req-e2"));
        assertEquals("STG_1004", e.getCode());
    }

    @Test
    void outputType非法_抛STG1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate(body("SCHEMA_DD_CARD_V2", "TEXT", "XML", "a", null, null), "req-e3"));
        assertEquals("STG_1007", e.getCode());
    }

    @Test
    void input缺失_抛STG1001() {
        Map<String, Object> b = body("SCHEMA_DD_CARD_V2", "TEXT", "JSON", "a", null, null);
        b.remove("input");
        BaiException e = assertThrows(BaiException.class, () -> service.generate(b, "req-e4"));
        assertEquals("STG_1001", e.getCode());
    }

    @Test
    void 输入超5000字符_抛STG1006() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5001; i++) {
            sb.append('a');
        }
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate(body("SCHEMA_DD_CARD_V2", "TEXT", "JSON", sb.toString(), null, null), "req-e5"));
        assertEquals("STG_1006", e.getCode());
    }

    @Test
    void Schema重复注册_抛STG1005() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("schemaId", "SCHEMA_TEST_01");
        b.put("name", "测试");
        b.put("fields", List.of("a", "b"));
        service.registerSchema(b, "req-r1");
        BaiException e = assertThrows(BaiException.class, () -> service.registerSchema(b, "req-r2"));
        assertEquals("STG_1005", e.getCode());
    }

    // ------------------------------------------------------------ 幂等/历史

    @Test
    void 同requestId同bizKey同schema_返回首次genId() {
        String rid = "idem-s1";
        StructureGenDtos.GenerateResult a = service.generate(
                body("SCHEMA_DD_CARD_V2", "TEXT", "JSON", "XX科技有限公司 申请流动资金贷款 500 万", null, "biz-idem"), rid);
        StructureGenDtos.GenerateResult b = service.generate(
                body("SCHEMA_DD_CARD_V2", "TEXT", "JSON", "XX科技有限公司 申请流动资金贷款 500 万", null, "biz-idem"), rid);
        assertEquals(a.genId(), b.genId());
        assertEquals(String.valueOf(a.result().toString()), String.valueOf(b.result().toString()));
    }

    @Test
    void 历史查询_未命中抛STG1004() {
        service.generate(body("SCHEMA_DD_CARD_V2", "DATA", "JSON",
                Map.of("customerName", "张三", "applyAmount", 100, "applyPurpose", "流动资金贷款"), null, "biz-s6"), "req-h1");
        assertEquals(1, service.history("req-h1", 10).size());
        BaiException e = assertThrows(BaiException.class, () -> service.history("req-ghost", 10));
        assertEquals("STG_1004", e.getCode());
    }
}
