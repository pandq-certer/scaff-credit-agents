package com.bai.rule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 规则 DSL 与编译器（真实可执行）：DSL 文本 → AST（规则集/规则/条件），
 * 由 RuleEngineService 加载为 RETE 匹配网络并缓存（LRU 容量 100），编译失败报错并给出行号。
 * <p>DSL 语法：
 * <pre>
 * ruleset &lt;ID&gt; name "&lt;名称&gt;" category &lt;分类&gt; version &lt;版本&gt; status ACTIVE|DISABLED
 * requires &lt;字段&gt;:number|string|bool|date[, ...]
 * rule &lt;ID&gt; "&lt;名称&gt;" salience &lt;整数&gt;
 * when &lt;字段&gt; &lt;op&gt; &lt;字面量&gt; [ and ... ]        // op: &gt; &gt;= &lt; &lt;= == != in not-in
 * then &lt;PASS|REJECT|REVIEW|WARN|APPROVE&gt; "&lt;提示&gt;" &lt;业务码&gt;
 * </pre>
 */
public final class RuleDsl {

    private RuleDsl() {}

    /** 规则集准入_个人贷款（提示词库 A-17 业务示例） */
    public static final String ADMISSION_PERSONAL_LOAN = """
            # 准入规则集：个人经营/消费贷款（RETE 匹配，salience 降序冲突消解）
            ruleset ADMISSION_PERSONAL_LOAN name "个人贷款准入规则集" category ADMISSION version 2026.09.01 status ACTIVE
            requires age:number, monthlyIncome:number, loanAmount:number, term:number, creditScore:number

            rule R001 "准入_年龄上限" salience 100
            when age > 70
            then REJECT "年龄超限" ADM-01

            rule R002 "准入_年龄下限" salience 100
            when age < 18
            then REJECT "年龄未满18周岁" ADM-02

            rule R003 "准入_征信评分" salience 95
            when creditScore < 600
            then REJECT "征信评分不足" ADM-03

            rule R004 "准入_收入要求" salience 90
            when monthlyIncome < 5000
            then REJECT "月收入不达标" ADM-04

            rule R005 "准入_期限校验" salience 80
            when term > 60
            then REJECT "贷款期限超限" ADM-05

            rule R006 "准入_金额人工复核" salience 70
            when loanAmount > 1000000
            then REVIEW "贷款金额超限，转人工复核" ADM-06

            rule R007 "准入_年龄偏大提示" salience 30
            when age >= 60
            then WARN "年龄偏大，提示关注还款能力" ADM-07

            rule R008 "准入_综合通过" salience 10
            when creditScore >= 600 and monthlyIncome >= 5000
            then APPROVE "准入校验通过" ADM-08
            """;

    /** 规则集_贷后风险预警（预警类：全部输出并取最高级） */
    public static final String RISK_EARLY_WARNING = """
            # 预警规则集：贷后风险监测（预警结论全部输出，聚合取最高级）
            ruleset RISK_EARLY_WARNING name "贷后风险预警规则集" category RISK version 2026.09.01 status ACTIVE
            requires overdueDays:number, balance:number

            rule W001 "逾期二级预警" salience 100
            when overdueDays >= 90
            then WARN "逾期90天以上，五级分类下调次级" RSK-W02

            rule W002 "逾期一级预警" salience 90
            when overdueDays >= 30 and overdueDays < 90
            then WARN "逾期30天以上，加强贷后监测" RSK-W01

            rule W003 "大额余额预警" salience 60
            when balance > 5000000
            then WARN "单户余额超500万，转贷后复核" RSK-W03

            rule W004 "次级分类预警" salience 50
            when fiveClass == 'SUB_STANDARD'
            then WARN "五级分类次级，加强监控" RSK-W04
            """;

    /** 规则集_贷款五级分类认定（产出分类结论：以逾期天数等原始指标直接判定，供分类建议节点调用） */
    public static final String FIVE_CLASS_CLASSIFICATION = """
            # 五级分类认定规则集：以逾期天数产出分类建议（与制度 DOC202609001「逾期>90天下调次级」口径一致）
            ruleset FIVE_CLASS_CLASSIFICATION name "贷款五级分类认定规则集" category RISK version 2026.11.16 status ACTIVE
            requires overdueDays:number

            rule F001 "损失类认定" salience 100
            when overdueDays > 360
            then WARN "建议五级分类：损失 LOSS（逾期超360天）" FLC-05

            rule F002 "可疑类认定" salience 90
            when overdueDays > 180
            then WARN "建议五级分类：可疑 DOUBTFUL（逾期181-360天）" FLC-04

            rule F003 "次级类认定" salience 80
            when overdueDays > 90
            then WARN "建议五级分类：次级 SUB_STANDARD（逾期91-180天）" FLC-03

            rule F004 "关注类认定" salience 70
            when overdueDays > 0
            then WARN "建议五级分类：关注 SPECIAL_MENTION（逾期1-90天）" FLC-02

            rule F005 "正常类认定" salience 10
            when overdueDays <= 0
            then APPROVE "建议五级分类：正常 NORMAL（无逾期）" FLC-01
            """;

    /** 内置规则集源（rulesetId → DSL 文本；发布走 DRAFT→TEST→ACTIVE→DISABLED 状态机，随发布失效缓存） */
    public static final Map<String, String> SOURCES = Map.of(
            "ADMISSION_PERSONAL_LOAN", ADMISSION_PERSONAL_LOAN,
            "RISK_EARLY_WARNING", RISK_EARLY_WARNING,
            "FIVE_CLASS_CLASSIFICATION", FIVE_CLASS_CLASSIFICATION);

    /** 条件（field op literal；IN/NOT_IN 的 value 为字面量列表） */
    public record Condition(String field, String op, Object value) {

        @SuppressWarnings("unchecked")
        public boolean matches(Map<String, Object> facts) {
            Object raw = facts.get(field);
            if (raw == null) {
                return false;
            }
            Object actual = coerce(raw);
            return switch (op) {
                case ">" -> cmp(actual, value) > 0;
                case ">=" -> cmp(actual, value) >= 0;
                case "<" -> cmp(actual, value) < 0;
                case "<=" -> cmp(actual, value) <= 0;
                case "==" -> eq(actual, value);
                case "!=" -> !eq(actual, value);
                case "IN" -> inList(actual, (List<Object>) value);
                case "NOT_IN" -> !inList(actual, (List<Object>) value);
                default -> false;
            };
        }

        private static int cmp(Object a, Object b) {
            if (a instanceof Double x && b instanceof Double y) {
                return Double.compare(x, y);
            }
            return String.valueOf(a).compareTo(String.valueOf(b));
        }

        private static boolean eq(Object a, Object b) {
            if (a instanceof Double x && b instanceof Double y) {
                return x.doubleValue() == y.doubleValue();
            }
            return String.valueOf(a).equals(String.valueOf(b));
        }

        private static boolean inList(Object actual, List<Object> list) {
            return list != null && list.stream().anyMatch(v -> eq(actual, v));
        }

        private static Object coerce(Object raw) {
            if (raw instanceof Number) {
                return ((Number) raw).doubleValue();
            }
            if (raw instanceof Boolean) {
                return raw;
            }
            String s = String.valueOf(raw).trim();
            if (s.matches("-?\\d+(\\.\\d+)?")) {
                return Double.parseDouble(s);
            }
            if (s.equalsIgnoreCase("true")) {
                return Boolean.TRUE;
            }
            if (s.equalsIgnoreCase("false")) {
                return Boolean.FALSE;
            }
            return s;
        }
    }

    /** 编译后规则 */
    public record CompiledRule(String ruleId, String name, int salience, List<Condition> conditions,
                               String decision, String message, String code) {}

    /** 编译后规则集（AST，可加载为 RETE 网络） */
    public record CompiledRuleset(String rulesetId, String name, String category, String version, String status,
                                  Map<String, String> requiredFields, List<CompiledRule> rules) {}

    /** DSL 编译：失败抛 IllegalStateException 并给出行号 */
    public static CompiledRuleset compile(String dsl) {
        String rulesetId = null;
        String name = null;
        String category = null;
        String version = null;
        String status = null;
        Map<String, String> required = new LinkedHashMap<>();
        List<CompiledRule> rules = new ArrayList<>();

        String ruleId = null;
        String ruleName = null;
        int salience = 0;
        List<Condition> conditions = null;

        int lineNo = 0;
        for (String raw : dsl.split("\n")) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                if (line.startsWith("ruleset ")) {
                    rulesetId = token(line, 1);
                    name = quoted(line, "name");
                    category = token(line, "category");
                    version = token(line, "version");
                    status = token(line, "status");
                } else if (line.startsWith("requires ")) {
                    for (String pair : line.substring("requires ".length()).split(",")) {
                        String[] kv = pair.trim().split(":");
                        if (kv.length == 2 && !kv[0].isBlank()) {
                            required.put(kv[0].trim(), kv[1].trim());
                        }
                    }
                } else if (line.startsWith("rule ")) {
                    String[] parts = line.split("\\s+");
                    ruleId = parts[1];
                    ruleName = quoted(line, null);
                    salience = Integer.parseInt(token(line, "salience"));
                } else if (line.startsWith("when ")) {
                    conditions = parseConditions(line.substring("when ".length()));
                } else if (line.startsWith("then ")) {
                    String[] parts = line.split("\\s+", 2);
                    String decision = parts[1].split("\\s+")[0];
                    String message = quoted(line, null);
                    String code = trailingToken(line);
                    rules.add(new CompiledRule(ruleId, ruleName, salience, List.copyOf(conditions), decision, message, code));
                    conditions = null;
                } else {
                    throw new IllegalArgumentException("无法识别的 DSL 语句");
                }
            } catch (Exception e) {
                throw new IllegalStateException("DSL 编译失败（行 " + lineNo + "）：" + line, e);
            }
        }
        if (rulesetId == null) {
            throw new IllegalStateException("DSL 编译失败：缺少 ruleset 声明");
        }
        return new CompiledRuleset(rulesetId, name, category, version, status, Map.copyOf(required), List.copyOf(rules));
    }

    private static List<Condition> parseConditions(String expr) {
        List<Condition> out = new ArrayList<>();
        for (String part : expr.split("(?i)\\s+and\\s+")) {
            String c = part.trim();
            String upper = c.toUpperCase(Locale.ROOT);
            if (upper.contains(" NOT IN ")) {
                int i = upper.indexOf(" NOT IN ");
                out.add(new Condition(c.substring(0, i).trim(), "NOT_IN", parseList(c.substring(i + 8))));
            } else if (upper.contains(" IN ")) {
                int i = upper.indexOf(" IN ");
                out.add(new Condition(c.substring(0, i).trim(), "IN", parseList(c.substring(i + 4))));
            } else if (c.contains(">=")) {
                out.add(binary(c, ">="));
            } else if (c.contains("<=")) {
                out.add(binary(c, "<="));
            } else if (c.contains("!=")) {
                out.add(binary(c, "!="));
            } else if (c.contains("==")) {
                out.add(binary(c, "=="));
            } else if (c.contains(">")) {
                out.add(binary(c, ">"));
            } else if (c.contains("<")) {
                out.add(binary(c, "<"));
            } else {
                throw new IllegalArgumentException("无法解析条件：" + c);
            }
        }
        return out;
    }

    private static Condition binary(String c, String op) {
        int i = c.indexOf(op);
        return new Condition(c.substring(0, i).trim(), op, parseLiteral(c.substring(i + op.length()).trim()));
    }

    private static Object parseLiteral(String lit) {
        if ((lit.startsWith("'") && lit.endsWith("'")) || (lit.startsWith("\"") && lit.endsWith("\""))) {
            return lit.substring(1, lit.length() - 1);
        }
        if (lit.matches("-?\\d+(\\.\\d+)?")) {
            return Double.parseDouble(lit);
        }
        if (lit.equalsIgnoreCase("true") || lit.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(lit.toLowerCase(Locale.ROOT));
        }
        return lit;
    }

    private static List<Object> parseList(String s) {
        String body = s.trim();
        if (body.startsWith("(") && body.endsWith(")")) {
            body = body.substring(1, body.length() - 1);
        }
        List<Object> out = new ArrayList<>();
        for (String item : body.split(",")) {
            out.add(parseLiteral(item.trim()));
        }
        return out;
    }

    /** 取第 n 个空白分词 */
    private static String token(String line, int n) {
        return line.split("\\s+")[n];
    }

    /** 取 key 后的下一个分词（如 version 2026.09.01） */
    private static String token(String line, String key) {
        String[] parts = line.split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals(key)) {
                return parts[i + 1];
            }
        }
        return null;
    }

    /** 取行内第一处引号内容；quoteKey 为 null 时取第一对引号 */
    private static String quoted(String line, String quoteKey) {
        int start = 0;
        if (quoteKey != null) {
            int keyIdx = line.indexOf(quoteKey);
            if (keyIdx >= 0) {
                start = keyIdx + quoteKey.length();
            }
        }
        int q1 = line.indexOf('"', start);
        int q2 = q1 >= 0 ? line.indexOf('"', q1 + 1) : -1;
        return q2 > q1 ? line.substring(q1 + 1, q2) : null;
    }

    /** 行尾分词（业务码） */
    private static String trailingToken(String line) {
        String[] parts = line.trim().split("\\s+");
        return parts[parts.length - 1];
    }
}
