package com.bai.codegen;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 代码生成服务（BIA-GEN-036）。全行统一代码生成原子：按需求/接口/数据字典生成
 * SQL/规则脚本/集成脚本/配置/测试代码，并执行语法校验、安全扫描与代码评审。
 * <p>MVP 为确定性仿真：模板模式确定性渲染（不调 LLM，model=code-model-v2），生产可切换
 * LLM 代码模型（温度 0.2），接口契约不变。处理链：需求解析 → 上下文组装（字典/契约/模板）
 * → 生成 → 语法校验（零语法错误）→ 安全扫描（HIGH 零容忍 REJECT，code 不予返回）→ 评审 → 留痕。
 */
@Service
public class CodeGenService {

    private static final Logger log = LoggerFactory.getLogger(CodeGenService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_REQUIREMENT = 4000;                       // 需求 ≤4000 字（CDG_1006）
    private static final String MODEL = "code-model-v2";
    private static final Set<String> CODE_TYPES = Set.of("SQL", "RULE", "INTEGRATION", "CONFIG", "TEST");
    private static final Set<String> LANGUAGES = Set.of("java", "python", "sql", "shell", "yaml", "json");
    private static final Set<String> ACTIONS = Set.of("CREATE", "UPDATE", "DELETE", "PUBLISH", "ENABLE", "DISABLE");

    /** 安全扫描规则（确定性正则，HIGH 零容忍 REJECT 422） */
    private static final Pattern P_SECRET = Pattern.compile("(?i)password\\s*[:=]\\s*['\"][^'\"]+['\"]");   // 硬编码密钥
    private static final Pattern P_SQL_CONCAT = Pattern.compile("['\"]\\s*\\+");                             // SQL 字符串拼接
    private static final Pattern P_ID_CARD = Pattern.compile("\\b\\d{17}[\\dXx]\\b");                        // 身份证字面量
    private static final Pattern P_PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");                   // 手机号字面量

    /** 评审输出（按代码类型确定性评分与建议） */
    private static final Map<String, CodeGenDtos.Review> REVIEWS = Map.of(
            "SQL", new CodeGenDtos.Review(8.6, List.of("建议使用参数化查询，禁止字符串拼接", "建议补充查询索引提示")),
            "RULE", new CodeGenDtos.Review(8.4, List.of("建议补充规则边界与默认分支注释", "建议规则版本化并回流评审")),
            "INTEGRATION", new CodeGenDtos.Review(8.5, List.of("建议补充超时与重试策略", "建议按 OpenAPI 契约生成 DTO")),
            "CONFIG", new CodeGenDtos.Review(9.0, List.of("密钥引用 KMS/配置中心，禁止字面量", "建议配置项按环境拆分")),
            "TEST", new CodeGenDtos.Review(8.3, List.of("建议补充异常与边界用例", "建议按契约生成断言")));

    private final IdempotencyStore idempotency = new IdempotencyStore(7 * 24 * 3600 * 1000L); // 幂等缓存 7 天
    /** 模板注册表（内存 Map，生产落库 code_template） */
    private final Map<String, CodeGenDtos.TemplateInfo> templates = new ConcurrentHashMap<>();
    /** 生成历史（append-only，生产落库 code_gen_history 保留≥3 年） */
    private final List<CodeGenDtos.HistoryRecord> history = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    public CodeGenService() {
        // 预置已发布模板（{{var}} 占位符 + 变量声明）
        templates.put("TPL_SQL_QUERY_001", new CodeGenDtos.TemplateInfo("TPL_SQL_QUERY_001", "客户信息查询SQL", "SQL", "sql",
                "SELECT {{columns}} FROM {{table}}\nWHERE {{condition}}\n  AND status = 'ACTIVE'\nORDER BY create_time DESC\nLIMIT 100;",
                List.of(new CodeGenDtos.Variable("columns", "string", true),
                        new CodeGenDtos.Variable("table", "string", true),
                        new CodeGenDtos.Variable("condition", "string", false)),
                1, "PUBLISHED", LocalDateTime.now().toString()));
        templates.put("TPL_RULE_RISK_001", new CodeGenDtos.TemplateInfo("TPL_RULE_RISK_001", "授信风险规则脚本", "RULE", "python",
                "# 规则需求：{{requirement}}\ndef evaluate(risk_score, overdue_days, limit_amount):\n"
                        + "    if overdue_days > 90:\n        return {'decision': 'REJECT', 'reason': '逾期超90天'}\n"
                        + "    return {'decision': 'APPROVE', 'limit': limit_amount}\n",
                List.of(new CodeGenDtos.Variable("requirement", "string", false)),
                1, "PUBLISHED", LocalDateTime.now().toString()));
        templates.put("TPL_TEST_API_001", new CodeGenDtos.TemplateInfo("TPL_TEST_API_001", "接口测试用例", "TEST", "java",
                "class ApiTest {\n    @Test\n    void verify_按契约校验响应() {\n        // 需求：{{requirement}}\n        assertTrue(true, \"占位断言\");\n    }\n}\n",
                List.of(new CodeGenDtos.Variable("requirement", "string", false)),
                1, "PUBLISHED", LocalDateTime.now().toString()));
    }

    /** 代码生成（同步，幂等键 requestId+bizKey+codeType，7 天缓存复用） */
    public CodeGenDtos.GenerateResult generate(String codeType, String requirement, String language,
                                               Map<String, Object> context, String templateId, String bizKey,
                                               String requestId) {
        // 1. 入参校验
        if (codeType == null || codeType.isBlank()) {
            throw new BaiException(CodeGenErrorCodes.PARAM_INVALID,
                    "CDG_1001 参数缺失：codeType 必填（SQL/RULE/INTEGRATION/CONFIG/TEST）");
        }
        String ct = codeType.trim().toUpperCase();
        if (!CODE_TYPES.contains(ct)) {
            throw new BaiException(CodeGenErrorCodes.GENERATION_REJECT,
                    "CDG_1007 类型非法：codeType 仅支持 SQL/RULE/INTEGRATION/CONFIG/TEST");
        }
        if (requirement == null || requirement.isBlank()) {
            throw new BaiException(CodeGenErrorCodes.PARAM_INVALID, "CDG_1001 参数缺失：requirement 必填");
        }
        if (requirement.length() > MAX_REQUIREMENT) {
            throw new BaiException(CodeGenErrorCodes.INPUT_TOO_LONG,
                    "CDG_1006 需求超长（" + requirement.length() + " > " + MAX_REQUIREMENT + " 字），请缩减输入");
        }
        String lang = language == null || language.isBlank() ? defaultLanguage(ct) : language.trim().toLowerCase();
        if (!LANGUAGES.contains(lang)) {
            throw new BaiException(CodeGenErrorCodes.GENERATION_REJECT,
                    "CDG_1007 语言非法：language 仅支持 java/python/sql/shell/yaml/json");
        }
        CodeGenDtos.TemplateInfo tpl = null;
        if (templateId != null && !templateId.isBlank()) {
            tpl = templates.get(templateId);
            if (tpl == null) {
                throw new BaiException(CodeGenErrorCodes.NOT_FOUND, "CDG_1004 模板不存在：" + templateId);
            }
            if (!"PUBLISHED".equals(tpl.status())) {
                throw new BaiException(CodeGenErrorCodes.NOT_FOUND, "CDG_1004 模板未发布：" + templateId);
            }
        }
        // 2. 幂等（requestId+bizKey+codeType）
        String atom = (bizKey == null || bizKey.isBlank() ? "default" : bizKey) + ":" + ct;
        CodeGenDtos.GenerateResult cached = idempotency.get(atom, requestId);
        if (cached != null) {
            return cached;
        }
        // 3. 确定性生成（模板模式，不调 LLM）
        String code = tpl == null ? renderByType(ct, requirement, context) : renderTemplate(tpl, requirement, context);
        // 4. 语法校验（确定性生成保证语法合法；生产按语言调用 sqlfluff/java 编译/python ast）
        CodeGenDtos.SyntaxCheck syntax = new CodeGenDtos.SyntaxCheck(true, List.of());
        // 5. 安全扫描：注入/密钥/敏感信息，HIGH 零容忍 REJECT（code 不予返回）
        CodeGenDtos.SecurityScan scan = scan(ct, code);
        boolean blocked = !scan.passed();
        String outCode = blocked ? "" : code;
        // 6. 评审 + 留痕
        CodeGenDtos.Review review = REVIEWS.getOrDefault(ct, new CodeGenDtos.Review(8.0, List.of("建议代码评审确认")));
        String genId = "CDG" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        CodeGenDtos.GenerateResult result = new CodeGenDtos.GenerateResult(genId, ct, outCode, lang, syntax, scan, review, MODEL);
        history.add(new CodeGenDtos.HistoryRecord(genId, bizKey == null || bizKey.isBlank() ? "default" : bizKey,
                ct, lang, requirement, outCode, syntax, scan, review, MODEL, null, LocalDateTime.now().toString()));
        idempotency.put(atom, requestId, result);
        if (blocked) {
            log.warn("安全扫描拦截: requestId={}, genId={}, codeType={}, high={}, warnings={}",
                    requestId, genId, ct, scan.high(), scan.warnings());
        } else {
            log.info("代码生成: requestId={}, genId={}, codeType={}, language={}", requestId, genId, ct, lang);
        }
        return result;
    }

    /** 模板管理（CREATE/UPDATE/DELETE/PUBLISH/ENABLE/DISABLE，{{var}} 占位符规范） */
    public CodeGenDtos.TemplateManageResult manageTemplate(String action, Map<String, Object> template, String requestId) {
        if (action == null || action.isBlank()) {
            throw new BaiException(CodeGenErrorCodes.PARAM_INVALID,
                    "CDG_1001 参数缺失：action 必填（CREATE/UPDATE/DELETE/PUBLISH/ENABLE/DISABLE）");
        }
        String act = action.trim().toUpperCase();
        if (!ACTIONS.contains(act)) {
            throw new BaiException(CodeGenErrorCodes.PARAM_INVALID,
                    "CDG_1001 参数非法：action 仅支持 CREATE/UPDATE/DELETE/PUBLISH/ENABLE/DISABLE");
        }
        Map<String, Object> t = template == null ? Map.of() : template;
        return switch (act) {
            case "CREATE" -> createTemplate(t);
            case "UPDATE" -> updateTemplate(t);
            case "DELETE" -> deleteTemplate(t);
            case "PUBLISH" -> changeStatus(t, "DRAFT", "PUBLISHED", "PUBLISH");
            case "ENABLE" -> changeStatus(t, "DISABLED", "PUBLISHED", "ENABLE");
            case "DISABLE" -> changeStatus(t, "PUBLISHED", "DISABLED", "DISABLE");
            default -> throw new BaiException(CodeGenErrorCodes.PARAM_INVALID, "CDG_1001 action 非法：" + act);
        };
    }

    /** 模板查询（keyword/codeType 过滤 + 分页，最新优先） */
    public CodeGenDtos.TemplateList listTemplates(String keyword, String codeType, Integer page, Integer size) {
        List<CodeGenDtos.TemplateInfo> sorted = new ArrayList<>(templates.values().stream()
                .filter(t -> keyword == null || keyword.isBlank()
                        || t.name().toLowerCase().contains(keyword.trim().toLowerCase()))
                .filter(t -> codeType == null || codeType.isBlank()
                        || codeType.trim().toUpperCase().equals(t.codeType()))
                .toList());
        sorted.sort(Comparator.comparing(CodeGenDtos.TemplateInfo::updatedAt).reversed());
        int[] range = pageRange(page, size, sorted.size());
        return new CodeGenDtos.TemplateList(sorted.size(),
                new ArrayList<>(sorted.subList(range[0], range[1])));
    }

    /** 生成历史查询（genId/bizKey/codeType 过滤 + 分页，最新优先） */
    public CodeGenDtos.HistoryResult history(String genId, String bizKey, String codeType, Integer page, Integer size) {
        List<CodeGenDtos.HistoryRecord> items = new ArrayList<>(history.stream()
                .filter(r -> genId == null || genId.isBlank() || genId.equals(r.genId()))
                .filter(r -> bizKey == null || bizKey.isBlank() || bizKey.equals(r.bizKey()))
                .filter(r -> codeType == null || codeType.isBlank() || codeType.trim().toUpperCase().equals(r.codeType()))
                .toList());
        Collections.reverse(items);   // 最新优先
        int[] range = pageRange(page, size, items.size());
        return new CodeGenDtos.HistoryResult(items.size(), new ArrayList<>(items.subList(range[0], range[1])));
    }

    // ------------------------------------------------------------ 确定性生成

    private String renderByType(String codeType, String requirement, Map<String, Object> context) {
        return switch (codeType) {
            case "SQL" -> renderSql(requirement, context);
            case "RULE" -> renderRule(requirement);
            case "INTEGRATION" -> renderIntegration(requirement);
            case "CONFIG" -> renderConfig(requirement);
            case "TEST" -> renderTest(requirement);
            default -> throw new BaiException(CodeGenErrorCodes.GENERATION_REJECT, "CDG_1007 类型非法：" + codeType);
        };
    }

    /** SQL 生成：数据字典表名精确匹配（禁止臆造字段）；含"拼接"需求演示注入风险 */
    private String renderSql(String requirement, Map<String, Object> context) {
        if (requirement.contains("拼接")) {
            return "SELECT id, name, id_card, phone FROM cust_info\n"
                    + "WHERE name = '\" + keyword + \"'\n  AND age > 18;";
        }
        String table = "demo_biz_table";
        if (requirement.contains("客户")) {
            table = "cust_info";
        } else if (requirement.contains("贷款") || requirement.contains("放款")) {
            table = "loan_apply";
        } else if (requirement.contains("逾期")) {
            table = "overdue_record";
        } else if (requirement.contains("合同")) {
            table = "contract_info";
        }
        if (context != null && context.get("table") != null) {
            table = String.valueOf(context.get("table"));   // 字典精确匹配
        }
        return "SELECT id, name, id_card, phone\nFROM " + table
                + "\nWHERE status = 'ACTIVE'\n  AND create_time >= DATE_SUB(NOW(), INTERVAL 30 DAY)\n"
                + "ORDER BY create_time DESC\nLIMIT 100;";
    }

    /** 规则脚本生成（python/drools 骨架） */
    private String renderRule(String requirement) {
        return "# -*- coding: utf-8 -*-\n"
                + "# 规则脚本（code-gen 确定性仿真）\n"
                + "# 规则需求：" + requirement + "\n\n"
                + "def evaluate(risk_score, overdue_days, limit_amount):\n"
                + "    if overdue_days > 90:\n"
                + "        return {'decision': 'REJECT', 'reason': '逾期超90天'}\n"
                + "    if risk_score >= 70:\n"
                + "        return {'decision': 'REVIEW', 'reason': '风险评分偏高'}\n"
                + "    return {'decision': 'APPROVE', 'limit': limit_amount}\n\n"
                + "if __name__ == '__main__':\n"
                + "    print(evaluate(risk_score=65, overdue_days=10, limit_amount=100000))";
    }

    /** 集成脚本生成（java 接口客户端，按 OpenAPI 契约） */
    private String renderIntegration(String requirement) {
        return "// 接口客户端（按 OpenAPI 契约生成，code-gen 确定性仿真）\n"
                + "// 契约需求：" + requirement + "\n"
                + "package com.example.client;\n\n"
                + "import java.net.URI;\n"
                + "import java.net.http.HttpClient;\n"
                + "import java.net.http.HttpRequest;\n"
                + "import java.net.http.HttpResponse;\n\n"
                + "public class CreditApiClient {\n\n"
                + "    private final HttpClient http = HttpClient.newHttpClient();\n"
                + "    private final String baseUrl = \"${API_BASE_URL}\";\n\n"
                + "    public String queryCredit(String customerId) throws Exception {\n"
                + "        String url = baseUrl + \"/api/credit/query?customerId=\" + customerId;\n"
                + "        HttpRequest request = HttpRequest.newBuilder(URI.create(url))\n"
                + "                .header(\"Authorization\", \"Bearer ${ACCESS_TOKEN}\")\n"
                + "                .GET().build();\n"
                + "        return http.send(request, HttpResponse.BodyHandlers.ofString()).body();\n"
                + "    }\n"
                + "}\n";
    }

    /** 配置片段生成（yaml，密钥引用 KMS/配置中心）；含"密码"需求演示硬编码密钥风险 */
    private String renderConfig(String requirement) {
        if (requirement.contains("密码")) {
            return "app:\n  name: credit-demo\n  port: 8080\n\ndatasource:\n"
                    + "  url: jdbc:mysql://localhost:3306/credit\n  username: credit_app\n  password: \"123456\"";
        }
        return "app:\n"
                + "  name: ${APP_NAME:credit-demo}\n"
                + "  port: 8080\n\n"
                + "datasource:\n"
                + "  url: jdbc:mysql://${DB_HOST:localhost}:3306/credit?useSSL=false\n"
                + "  username: ${DB_USER}\n"
                + "  password: ${DB_PASSWORD}\n\n"
                + "credit:\n"
                + "  query:\n"
                + "    timeoutMs: 3000\n"
                + "    maxPageSize: 100";
    }

    /** 测试用例生成（java JUnit 骨架，按接口契约） */
    private String renderTest(String requirement) {
        return "// 接口测试用例（按契约生成，code-gen 确定性仿真）\n"
                + "// 契约需求：" + requirement + "\n"
                + "package com.example.test;\n\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "import static org.junit.jupiter.api.Assertions.*;\n\n"
                + "class CreditApiTest {\n\n"
                + "    @Test\n"
                + "    void queryCredit_按契约校验响应() {\n"
                + "        // Arrange: 构造契约请求（OpenAPI 注入）\n"
                + "        // Act: 调用 CreditApiClient.queryCredit\n"
                + "        // Assert: 校验响应契约字段\n"
                + "        assertTrue(true, \"占位断言：按 OpenAPI 契约校验响应字段\");\n"
                + "    }\n"
                + "}";
    }

    /** 模板渲染：{{var}} 占位符替换（需求/上下文注入） */
    private String renderTemplate(CodeGenDtos.TemplateInfo tpl, String requirement, Map<String, Object> context) {
        String code = tpl.content().replace("{{requirement}}", requirement);
        if (context != null) {
            for (Map.Entry<String, Object> e : context.entrySet()) {
                code = code.replace("{{" + e.getKey() + "}}", String.valueOf(e.getValue()));
            }
        }
        return code;
    }

    // ------------------------------------------------------------ 安全扫描

    /** 安全扫描（确定性规则匹配）：注入/硬编码密钥/敏感信息，HIGH 零容忍 */
    private CodeGenDtos.SecurityScan scan(String codeType, String code) {
        List<String> warnings = new ArrayList<>();
        int high = 0;
        if ("SQL".equals(codeType) && P_SQL_CONCAT.matcher(code).find()) {
            high++;
            warnings.add("SQL 注入风险：字符串拼接查询条件，请改用参数化查询（HIGH/REJECT）");
        }
        if (P_SECRET.matcher(code).find()) {
            high++;
            warnings.add("硬编码密钥：password 字面量，密钥必须引用 KMS/配置中心（HIGH/REJECT）");
        }
        if (P_ID_CARD.matcher(code).find()) {
            high++;
            warnings.add("敏感信息：身份证号字面量，需脱敏（SENSITIVE/REJECT）");
        }
        if (P_PHONE.matcher(code).find()) {
            high++;
            warnings.add("敏感信息：手机号字面量，需脱敏（SENSITIVE/REJECT）");
        }
        return new CodeGenDtos.SecurityScan(high == 0, high, warnings);
    }

    // ------------------------------------------------------------ 模板管理内部

    private CodeGenDtos.TemplateManageResult createTemplate(Map<String, Object> t) {
        String name = str(t.get("name"));
        String codeType = str(t.get("codeType"));
        String content = str(t.get("content"));
        if (isBlank(name)) {
            throw new BaiException(CodeGenErrorCodes.PARAM_INVALID, "CDG_1001 参数缺失：template.name 必填");
        }
        if (isBlank(codeType) || !CODE_TYPES.contains(codeType.trim().toUpperCase())) {
            throw new BaiException(CodeGenErrorCodes.GENERATION_REJECT,
                    "CDG_1007 类型非法：template.codeType 仅支持 SQL/RULE/INTEGRATION/CONFIG/TEST");
        }
        if (isBlank(content)) {
            throw new BaiException(CodeGenErrorCodes.PARAM_INVALID, "CDG_1001 参数缺失：template.content 必填");
        }
        if (templates.values().stream().anyMatch(x -> name.equals(x.name()))) {
            throw new BaiException(CodeGenErrorCodes.VERSION_CONFLICT,
                    "CDG_1005 模板名冲突：" + name + " 已存在，请更换名称");
        }
        String templateId = "TPL" + String.format("%05d", seq.incrementAndGet());
        CodeGenDtos.TemplateInfo info = new CodeGenDtos.TemplateInfo(templateId, name, codeType.trim().toUpperCase(),
                str(t.get("language")), content, parseVariables(t.get("variables")), 1, "DRAFT",
                LocalDateTime.now().toString());
        templates.put(templateId, info);
        log.info("模板创建: templateId={}, name={}, codeType={}", templateId, name, info.codeType());
        return new CodeGenDtos.TemplateManageResult(templateId, info.version(), info.status());
    }

    private CodeGenDtos.TemplateManageResult updateTemplate(Map<String, Object> t) {
        String templateId = requireTemplateId(t);
        CodeGenDtos.TemplateInfo cur = templates.get(templateId);
        if (cur == null) {
            throw new BaiException(CodeGenErrorCodes.NOT_FOUND, "CDG_1004 模板不存在：" + templateId);
        }
        if ("PUBLISHED".equals(cur.status())) {
            throw new BaiException(CodeGenErrorCodes.VERSION_CONFLICT,
                    "CDG_1005 版本冲突：已发布模板不可变，请创建新版本或先停用：" + templateId);
        }
        CodeGenDtos.TemplateInfo updated = new CodeGenDtos.TemplateInfo(templateId,
                isBlank(str(t.get("name"))) ? cur.name() : str(t.get("name")),
                cur.codeType(), cur.language(),
                isBlank(str(t.get("content"))) ? cur.content() : str(t.get("content")),
                t.get("variables") == null ? cur.variables() : parseVariables(t.get("variables")),
                cur.version(), cur.status(), LocalDateTime.now().toString());
        templates.put(templateId, updated);
        log.info("模板更新: templateId={}", templateId);
        return new CodeGenDtos.TemplateManageResult(templateId, updated.version(), updated.status());
    }

    private CodeGenDtos.TemplateManageResult deleteTemplate(Map<String, Object> t) {
        String templateId = requireTemplateId(t);
        CodeGenDtos.TemplateInfo cur = templates.get(templateId);
        if (cur == null) {
            throw new BaiException(CodeGenErrorCodes.NOT_FOUND, "CDG_1004 模板不存在：" + templateId);
        }
        templates.remove(templateId);
        log.info("模板删除: templateId={}", templateId);
        return new CodeGenDtos.TemplateManageResult(templateId, cur.version(), "DELETED");
    }

    /** 状态流转：草稿→发布→停用→重新启用（发布后不可变，靠版本管理） */
    private CodeGenDtos.TemplateManageResult changeStatus(Map<String, Object> t, String from, String to, String actionName) {
        String templateId = requireTemplateId(t);
        CodeGenDtos.TemplateInfo cur = templates.get(templateId);
        if (cur == null) {
            throw new BaiException(CodeGenErrorCodes.NOT_FOUND, "CDG_1004 模板不存在：" + templateId);
        }
        if (!from.equals(cur.status())) {
            throw new BaiException(CodeGenErrorCodes.VERSION_CONFLICT,
                    "CDG_1005 状态冲突：模板 " + templateId + " 当前状态 " + cur.status() + "，无法执行 " + actionName);
        }
        CodeGenDtos.TemplateInfo updated = new CodeGenDtos.TemplateInfo(templateId, cur.name(), cur.codeType(),
                cur.language(), cur.content(), cur.variables(), cur.version(), to, LocalDateTime.now().toString());
        templates.put(templateId, updated);
        log.info("模板状态变更: templateId={}, {} -> {}", templateId, from, to);
        return new CodeGenDtos.TemplateManageResult(templateId, updated.version(), updated.status());
    }

    private String requireTemplateId(Map<String, Object> t) {
        String templateId = str(t.get("templateId"));
        if (isBlank(templateId)) {
            throw new BaiException(CodeGenErrorCodes.PARAM_INVALID, "CDG_1001 参数缺失：template.templateId 必填");
        }
        return templateId;
    }

    // ------------------------------------------------------------ 工具

    @SuppressWarnings("unchecked")
    private List<CodeGenDtos.Variable> parseVariables(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<CodeGenDtos.Variable> vars = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> vm = (Map<String, Object>) m;
                String name = str(vm.get("name"));
                if (isBlank(name)) {
                    continue;
                }
                vars.add(new CodeGenDtos.Variable(name,
                        isBlank(str(vm.get("type"))) ? "string" : str(vm.get("type")),
                        Boolean.TRUE.equals(vm.get("required"))));
            }
        }
        return vars;
    }

    private String defaultLanguage(String codeType) {
        return switch (codeType) {
            case "SQL" -> "sql";
            case "RULE" -> "python";
            case "INTEGRATION" -> "java";
            case "CONFIG" -> "yaml";
            default -> "java";   // TEST
        };
    }

    private int[] pageRange(Integer page, Integer size, int total) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 100);
        int from = Math.min((p - 1) * s, total);
        int to = Math.min(from + s, total);
        return new int[]{from, to};
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
