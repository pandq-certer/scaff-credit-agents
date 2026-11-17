package com.bai.codegen;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeGenService 单元测试：五类代码生成、语法校验、安全扫描（HIGH REJECT）、模板管理、
 * 历史查询、参数校验错误码、幂等与确定性。
 * <p>确定性仿真：模板模式不调 LLM（model=code-model-v2），同入参必得同代码，可直接断言。
 */
class CodeGenServiceTest {

    private final CodeGenService service = new CodeGenService();

    // ------------------------------------------------------------ 五类代码生成

    @Test
    void SQL生成_字典表名参数化查询通过() {
        CodeGenDtos.GenerateResult r = service.generate("SQL", "查询近30天活跃客户信息", null,
                null, null, "biz-sql", "req-sql");
        assertEquals("SQL", r.codeType());
        assertTrue(r.code().contains("FROM cust_info"));           // 字典精确匹配表名
        assertTrue(r.code().contains("DATE_SUB(NOW(), INTERVAL 30 DAY)"));
        assertTrue(r.syntaxCheck().passed());
        assertTrue(r.syntaxCheck().issues().isEmpty());
        assertTrue(r.securityScan().passed());                     // 参数化查询零 HIGH
        assertEquals(0, r.securityScan().high());
        assertEquals("code-model-v2", r.model());
        assertEquals("sql", r.language());
    }

    @Test
    void RULE生成_规则脚本() {
        CodeGenDtos.GenerateResult r = service.generate("RULE", "逾期90天拒绝的授信规则", null,
                null, null, "biz-rule", "req-rule");
        assertTrue(r.code().contains("def evaluate(risk_score, overdue_days, limit_amount)"));
        assertTrue(r.code().contains("'REJECT'"));
        assertEquals("python", r.language());
        assertTrue(r.securityScan().passed());
    }

    @Test
    void INTEGRATION生成_接口客户端() {
        CodeGenDtos.GenerateResult r = service.generate("INTEGRATION", "生成征信查询接口客户端", null,
                null, null, "biz-int", "req-int");
        assertTrue(r.code().contains("class CreditApiClient"));
        assertTrue(r.code().contains("queryCredit"));
        assertEquals("java", r.language());
        assertTrue(r.securityScan().passed());
    }

    @Test
    void CONFIG生成_密钥引用KMS() {
        CodeGenDtos.GenerateResult r = service.generate("CONFIG", "生成应用与数据源配置片段", null,
                null, null, "biz-cfg", "req-cfg");
        assertTrue(r.code().contains("password: ${DB_PASSWORD}"));   // 密钥引用配置中心，无字面量
        assertTrue(r.code().contains("${DB_USER}"));
        assertEquals("yaml", r.language());
        assertTrue(r.securityScan().passed());
        assertEquals(9.0, r.review().score());
    }

    @Test
    void TEST生成_接口测试用例() {
        CodeGenDtos.GenerateResult r = service.generate("TEST", "按契约生成信用查询测试用例", null,
                null, null, "biz-test", "req-test");
        assertTrue(r.code().contains("class CreditApiTest"));
        assertTrue(r.code().contains("@Test"));
        assertEquals("java", r.language());
        assertTrue(r.syntaxCheck().passed());
    }

    // ------------------------------------------------------------ 安全扫描（HIGH 零容忍）

    @Test
    void SQL拼接需求_安全扫描REJECT且代码不返回() {
        CodeGenDtos.GenerateResult r = service.generate("SQL", "按用户输入拼接查询条件", null,
                null, null, "biz-inj", "req-inj");
        assertFalse(r.securityScan().passed());
        assertTrue(r.securityScan().high() > 0);
        assertEquals("", r.code());                                  // HIGH REJECT：code 不予返回
        assertTrue(r.securityScan().warnings().stream().anyMatch(w -> w.contains("SQL 注入")));
        // 拦截同样留痕（append-only）
        CodeGenDtos.HistoryResult h = service.history(r.genId(), null, null, 1, 20);
        assertEquals(1, h.total());
        assertEquals("", h.records().get(0).code());
    }

    @Test
    void CONFIG密码字面量_硬编码密钥REJECT() {
        CodeGenDtos.GenerateResult r = service.generate("CONFIG", "配置数据库账号密码", null,
                null, null, "biz-sec", "req-sec");
        assertFalse(r.securityScan().passed());
        assertEquals("", r.code());
        assertTrue(r.securityScan().warnings().stream().anyMatch(w -> w.contains("硬编码密钥")));
    }

    // ------------------------------------------------------------ 参数校验错误码

    @Test
    void codeType缺失_抛CDG1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate(null, "查询客户", null, null, null, "biz-e1", "req-e1"));
        assertEquals("CDG_1001", e.getCode());
    }

    @Test
    void codeType非法_抛CDG1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate("YAML", "查询客户", null, null, null, "biz-e2", "req-e2"));
        assertEquals("CDG_1007", e.getCode());
    }

    @Test
    void requirement超长_抛CDG1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate("SQL", "需".repeat(4001), null, null, null, "biz-e3", "req-e3"));
        assertEquals("CDG_1006", e.getCode());
    }

    @Test
    void 模板不存在_抛CDG1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate("SQL", "查询客户", null, null, "TPL_NOPE", "biz-e4", "req-e4"));
        assertEquals("CDG_1004", e.getCode());
    }

    @Test
    void 语言非法_抛CDG1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate("RULE", "授信规则", "cobol", null, null, "biz-e5", "req-e5"));
        assertEquals("CDG_1007", e.getCode());
    }

    // ------------------------------------------------------------ 模板管理

    @Test
    void 模板创建发布停用启用_列表可见() {
        CodeGenDtos.TemplateManageResult created = service.manageTemplate("CREATE", Map.of(
                "name", "逾期报表SQL",
                "codeType", "SQL",
                "language", "sql",
                "content", "SELECT {{columns}} FROM overdue_record WHERE {{condition}};",
                "variables", List.of(Map.of("name", "columns", "type", "string", "required", true),
                        Map.of("name", "condition", "type", "string", "required", false))), "req-tm1");
        assertEquals("DRAFT", created.status());
        assertEquals(1, created.version());

        CodeGenDtos.TemplateManageResult published = service.manageTemplate("PUBLISH",
                Map.of("templateId", created.templateId()), "req-tm2");
        assertEquals("PUBLISHED", published.status());

        CodeGenDtos.TemplateManageResult disabled = service.manageTemplate("DISABLE",
                Map.of("templateId", created.templateId()), "req-tm3");
        assertEquals("DISABLED", disabled.status());

        CodeGenDtos.TemplateManageResult enabled = service.manageTemplate("ENABLE",
                Map.of("templateId", created.templateId()), "req-tm4");
        assertEquals("PUBLISHED", enabled.status());

        // 关键词 + 类型过滤
        CodeGenDtos.TemplateList list = service.listTemplates("逾期", "SQL", 1, 20);
        assertEquals(1, list.total());
        assertTrue(list.templates().stream().anyMatch(t -> t.templateId().equals(created.templateId())));
    }

    @Test
    void 模板名冲突_抛CDG1005() {
        service.manageTemplate("CREATE", Map.of("name", "催收策略规则脚本", "codeType", "RULE",
                "content", "def f():\n    pass"), "req-c1");
        BaiException e = assertThrows(BaiException.class, () -> service.manageTemplate("CREATE",
                Map.of("name", "催收策略规则脚本", "codeType", "RULE", "content", "def g():\n    pass"), "req-c2"));
        assertEquals("CDG_1005", e.getCode());
    }

    @Test
    void 已发布模板更新_抛CDG1005() {
        BaiException e = assertThrows(BaiException.class, () -> service.manageTemplate("UPDATE",
                Map.of("templateId", "TPL_SQL_QUERY_001", "content", "SELECT 1;"), "req-u1"));
        assertEquals("CDG_1005", e.getCode());
    }

    @Test
    void 模板ID缺失_抛CDG1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.manageTemplate("DELETE", Map.of(), "req-u2"));
        assertEquals("CDG_1001", e.getCode());
    }

    // ------------------------------------------------------------ 幂等/确定性/历史

    @Test
    void 同requestId同bizKey同codeType_返回首次genId() {
        String rid = "idem-req-1";
        CodeGenDtos.GenerateResult a = service.generate("SQL", "查询客户信息", null, null, null, "biz-idem", rid);
        CodeGenDtos.GenerateResult b = service.generate("SQL", "查询客户信息", null, null, null, "biz-idem", rid);
        assertEquals(a.genId(), b.genId());
        assertEquals(a.code(), b.code());
    }

    @Test
    void 同入参两次调用_代码完全一致() {
        CodeGenDtos.GenerateResult a = service.generate("RULE", "授信风险规则", null, null, null, "biz-det", "req-d1");
        CodeGenDtos.GenerateResult b = service.generate("RULE", "授信风险规则", null, null, null, "biz-det", "req-d2");
        assertEquals(a.code(), b.code());
        assertEquals(a.genId().substring(0, 3), "CDG");
    }

    @Test
    void 历史查询_按genId和codeType过滤() {
        CodeGenDtos.GenerateResult sql = service.generate("SQL", "查询贷款信息", null, null, null, "biz-h1", "req-h1");
        CodeGenDtos.GenerateResult rule = service.generate("RULE", "授信规则", null, null, null, "biz-h2", "req-h2");

        CodeGenDtos.HistoryResult byGenId = service.history(sql.genId(), null, null, 1, 20);
        assertEquals(1, byGenId.total());
        assertEquals(sql.genId(), byGenId.records().get(0).genId());

        CodeGenDtos.HistoryResult byType = service.history(null, null, "SQL", 1, 20);
        assertEquals(1, byType.total());
        assertEquals("SQL", byType.records().get(0).codeType());

        CodeGenDtos.HistoryResult byBiz = service.history(null, "biz-h2", null, 1, 20);
        assertEquals(1, byBiz.total());
        assertEquals(rule.genId(), byBiz.records().get(0).genId());
    }

    @Test
    void 模板ID生成_渲染模板变量() {
        CodeGenDtos.GenerateResult r = service.generate("SQL", "查询客户", null,
                Map.of("table", "cust_info", "columns", "id, name", "condition", "age > 18"),
                "TPL_SQL_QUERY_001", "biz-tpl", "req-tpl");
        assertTrue(r.code().contains("SELECT id, name FROM cust_info"));
        assertTrue(r.code().contains("age > 18"));
        assertTrue(r.securityScan().passed());
    }
}
