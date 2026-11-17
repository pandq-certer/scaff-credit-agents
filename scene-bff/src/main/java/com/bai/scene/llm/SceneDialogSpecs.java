package com.bai.scene.llm;

import com.bai.scene.core.SceneContextStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 场景对话配置注册表：已接入「全对话大模型化」的场景清单。
 * <p>新场景接入步骤：在 {@link #register} 处注册一份 {@link SceneDialogSpec} 即可，
 * 对话框架、Controller 分支、降级策略均自动生效，无需改动其他代码。
 * 未注册的场景继续走关键词路由+模板回复（LLM 仅作全局意图路由兜底），已验证链路不受影响。
 */
public final class SceneDialogSpecs {

    private static final Map<String, SceneDialogSpec> SPECS = new ConcurrentHashMap<>();

    static {
        register(s62());
        register(s61());
        register(s41());
        register(s81());
        // S1 营销获客族
        register(s11());
        register(s12());
        register(s13());
        register(s14());
        register(s15());
        register(s16());
        register(s17());
        register(s18());
        // S2 申请受理族
        register(s21());
        register(s22());
        register(s23());
        register(s24());
        register(s25());
        // S3 尽调评估族
        register(s31());
        register(s32());
        register(s33());
        register(s34());
        register(s35());
        register(s36());
        register(s37());
        register(s38());
        register(s39());
        register(s310());
        register(s311());
        // S4 审查审批族（S4-1 已接入）
        register(s42());
        register(s43());
        register(s44());
        register(s45());
        register(s46());
        register(s47());
        register(s48());
        register(s49());
        // S5 合同签约族
        register(s51());
        register(s52());
        register(s53());
        register(s54());
        register(s55());
        register(s56());
        // S6 放款用信族（S6-1/S6-2 已接入，此处 S6-3~S6-13）
        register(s63());
        register(s64());
        register(s65());
        register(s66());
        register(s67());
        register(s68());
        register(s69());
        register(s610());
        register(s611());
        register(s612());
        register(s613());
        // S7 贷后管理族
        register(s71());
        register(s72());
        register(s73());
        register(s74());
        register(s75());
        register(s76());
        register(s77());
        register(s78());
        register(s79());
        register(s710());
        // S8 还款催收族（S8-1 已接入，此处 S8-2~S8-11）
        register(s82());
        register(s83());
        register(s84());
        register(s85());
        register(s86());
        register(s87());
        register(s88());
        register(s89());
        register(s810());
        register(s811());

        // S9 核算报送族（S9-1~S9-7）
        register(s91());
        register(s92());
        register(s93());
        register(s94());
        register(s95());
        register(s96());
        register(s97());

        // S10 渠道与协同族（S10-1~S10-6）
        register(s101());
        register(s102());
        register(s103());
        register(s104());
        register(s105());
        register(s106());

        // S11 数据与AI底座族（S11-1~S11-6）
        register(s111());
        register(s112());
        register(s113());
        register(s114());
        register(s115());
        register(s116());

        // S12 合规风控族（S12-1~S12-6）
        register(s121());
        register(s122());
        register(s123());
        register(s124());
        register(s125());
        register(s126());
    }

    private SceneDialogSpecs() { }

    public static SceneDialogSpec get(String sceneId) { return SPECS.get(sceneId); }

    public static boolean has(String sceneId) { return SPECS.containsKey(sceneId); }

    /** 对话框架按注册顺序构建专用意图路由目录 */
    public static Map<String, SceneDialogSpec> all() { return Map.copyOf(SPECS); }

    private static void register(SceneDialogSpec spec) { SPECS.put(spec.sceneId, spec); }

    // ---------- 各场景配置通用小工具 ----------

    /** 字符串槽位：空值/缺失回填缺省 */
    private static String text(JsonNode s, String field, String dft) {
        if (s == null || s.isMissingNode()) return dft;
        String t = s.path(field).asText("").trim();
        return t.isEmpty() ? dft : t;
    }

    /** 整数槽位：支持「300」「300条」等写法，缺失回填缺省 */
    private static int intOr(JsonNode s, String field, int dft) {
        if (s == null || s.isMissingNode()) return dft;
        JsonNode n = s.path(field);
        if (n.isNumber()) return n.asInt(dft);
        String t = n.asText("").replaceAll("[^0-9]", "").trim();
        if (t.isEmpty()) return dft;
        try { return Integer.parseInt(t); } catch (NumberFormatException e) { return dft; }
    }

    /** 金额槽位（元）：宽松解析，非正数回填缺省 */
    private static long amountOr(JsonNode s, String field, long dft) {
        long v = parseAmount(s == null ? null : s.path(field));
        return v > 0 ? v : dft;
    }

    /** 按键值对构建有序槽位 Map */
    private static Map<String, Object> norm(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /**
     * 结果分块抽取：标题命中任一关键词的分块，其行合并为 facts 值（键=分块标题）。
     * 结论/不符项等完整业务口径必须经此进入 facts，防止 LLM 依据部分数据编造结论。
     */
    private static void addBlocks(SceneContextStore.SceneContext ctx, Map<String, Object> facts, String... keywords) {
        for (var b : ctx.blocks) {
            String title = String.valueOf(b.getOrDefault("title", ""));
            for (String k : keywords) {
                if (title.contains(k)) {
                    List<?> lines = (List<?>) b.getOrDefault("lines", List.of());
                    if (!lines.isEmpty()) {
                        facts.put(title, String.join("；", lines.stream().map(String::valueOf).toList()));
                    }
                    break;
                }
            }
        }
    }

    /** stepData 数值入 facts：字段缺失/非数值/负数（哨兵值）一律不入，避免污染解读 */
    private static void putNum(Map<String, Object> facts, String key, JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return;
        JsonNode f = field == null || field.isEmpty() ? node : node.path(field);
        if (f.isMissingNode() || f.isNull() || !f.isNumber()) return;
        double v = f.asDouble();
        if (v < 0) return;
        facts.put(key, v);
    }

    // ============ S6-2 还款计划生成（自 S62LlmDialogService 迁移，行为不变） ============

    /** Mock 借据库（模拟核心系统借据要素；LLM 从中取参，联调真实核心后替换） */
    private static final String LOANS_JSON = """
            [
              {"applyNo":"YS20261028013","customer":"恒达商贸有限公司","productId":"LN-FLOW-001","productName":"流动资金贷款",
               "amount":5000000,"term":12,"rate":0.0345,"method":"EQUAL_INSTALLMENT","startDate":"2026-11-02","payDay":"每月20日"},
              {"applyNo":"YS20261030022","customer":"恒达商贸有限公司","productId":"LN-FLOW-001","productName":"流动资金贷款",
               "amount":2000000,"term":6,"rate":0.0320,"method":"EQUAL_PRINCIPAL","startDate":"2026-11-05","payDay":"每月20日"},
              {"applyNo":"YS20261101007","customer":"宏远商贸有限公司","productId":"LN-FLOW-001","productName":"流动资金贷款",
               "amount":3000000,"term":24,"rate":0.0385,"method":"EQUAL_INSTALLMENT","startDate":"2026-11-10","payDay":"每月20日"}
            ]""";

    /** 借据库代码内索引（applyNo → 要素），与 LOANS_JSON 保持一致；LLM 槽位缺失时回填 */
    private static final Map<String, Map<String, Object>> LOAN_DB = buildLoanDb();

    private static final Set<String> METHODS =
            Set.of("EQUAL_INSTALLMENT", "EQUAL_PRINCIPAL", "PERIOD_INTEREST", "BULLET");

    private static Map<String, Map<String, Object>> buildLoanDb() {
        Map<String, Map<String, Object>> db = new LinkedHashMap<>();
        db.put("YS20261028013", loan("YS20261028013", "恒达商贸有限公司", "LN-FLOW-001", 5000000L, 12, 0.0345, "EQUAL_INSTALLMENT", "2026-11-02"));
        db.put("YS20261030022", loan("YS20261030022", "恒达商贸有限公司", "LN-FLOW-001", 2000000L, 6, 0.0320, "EQUAL_PRINCIPAL", "2026-11-05"));
        db.put("YS20261101007", loan("YS20261101007", "宏远商贸有限公司", "LN-FLOW-001", 3000000L, 24, 0.0385, "EQUAL_INSTALLMENT", "2026-11-10"));
        return db;
    }

    private static Map<String, Object> loan(String applyNo, String customer, String productId,
                                            long amount, int term, double rate, String method, String startDate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applyNo", applyNo);
        m.put("customer", customer);
        m.put("productId", productId);
        m.put("amount", amount);
        m.put("term", term);
        m.put("rate", rate);
        m.put("method", method);
        m.put("startDate", startDate);
        return m;
    }

    private static SceneDialogSpec s62() {
        return new SceneDialogSpec(
                "S6-2",
                """
                【S6-2 还款计划生成】——为某笔用信/借据生成逐期还款计划，典型表述：做还款计划、算月供、出逐期计划表、
                这笔贷款每月还多少钱、调整还款方式重新试算、提前还款后计划怎么变等。
                注意：生成合同/借据文本、开票、结息等文档与账务类诉求不属于本场景。""",
                """
                必需要素：借据编号，或（金额+期限）。还款方式缺省为 EQUAL_INSTALLMENT（等额本息），起息日缺省 2026-11-02。
                用户提到借据编号且在下方借据库中，直接采用该借据全部要素，无需重复询问。

                借据库（Mock，模拟核心系统）：
                %s""".formatted(LOANS_JSON),
                """
                "applyNo":"借据编号","customer":"客户名称","productId":"产品编号","amount":金额元数字,"term":期数数字,"rate":年利率小数,"method":"EQUAL_INSTALLMENT|EQUAL_PRINCIPAL","startDate":"YYYY-MM-DD\"""",
                "（如「提前还款划算吗」「月供压力大吗」）",
                SceneDialogSpecs::normalizeS62Slots,
                SceneDialogSpecs::s62Facts,
                """
                你是银行信贷运营助手。根据后台实算的还款计划数据生成 3 行中文解读，供客户经理参考：
                第1行 还款压力评估；第2行 利息成本提示；第3行 行动建议（如提前还款/缩期/保持现状）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得出现数据之外的编造数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    /** S6-2 槽位规范化：借据库回填 + 宽松解析（"500万"/含千分位等），要素仍不足返回 null */
    private static Map<String, Object> normalizeS62Slots(JsonNode s) {
        if (s == null || s.isMissingNode()) return null;
        String applyNo = s.path("applyNo").asText("").trim();
        long amount = parseAmount(s.path("amount"));
        int term = s.path("term").asInt(0);
        Map<String, Object> db = LOAN_DB.get(applyNo);
        if (db != null) {
            if (amount <= 0) amount = (Long) db.get("amount");
            if (term <= 0) term = (Integer) db.get("term");
        }
        if (amount <= 0 || term <= 0) return null;
        double rate = s.path("rate").asDouble(db != null ? (Double) db.get("rate") : 0.0345);
        if (rate >= 1) rate = rate / 100.0;
        String method = s.path("method").asText("");
        if (!METHODS.contains(method)) {
            method = db != null ? (String) db.get("method") : "EQUAL_INSTALLMENT";
        }
        Map<String, Object> slots = new HashMap<>();
        slots.put("applyNo", applyNo.isEmpty() ? "YS20261028013" : applyNo);
        slots.put("customer", s.path("customer").asText(db != null ? (String) db.get("customer") : "客户"));
        slots.put("productId", s.path("productId").asText(db != null ? (String) db.get("productId") : "LN-FLOW-001"));
        slots.put("amount", amount);
        slots.put("term", term);
        slots.put("rate", rate);
        slots.put("method", method);
        slots.put("startDate", s.path("startDate").asText(db != null ? (String) db.get("startDate") : "2026-11-02"));
        return slots;
    }

    /** 金额宽松解析：数字、"5000000"、"5,000,000"、"500万" */
    private static long parseAmount(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return 0;
        if (n.isNumber()) return n.asLong(0);
        String t = n.asText("").replace(",", "").replace("，", "").replace("元", "").trim();
        if (t.isEmpty()) return 0;
        try {
            if (t.endsWith("万") || t.endsWith("W") || t.endsWith("w")) {
                return Math.round(Double.parseDouble(t.substring(0, t.length() - 1)) * 10000);
            }
            return (long) Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, Object> s62Facts(SceneContextStore.SceneContext ctx) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("customer", ctx.slots.getOrDefault("customer", "客户"));
        facts.put("applyNo", ctx.slots.get("applyNo"));
        facts.put("amount", ctx.slots.get("amount"));
        facts.put("term", ctx.slots.get("term"));
        facts.put("rate", ctx.slots.get("rate"));
        facts.put("method", ctx.slots.get("method"));
        facts.put("startDate", ctx.slots.get("startDate"));
        JsonNode plan = ctx.stepData.get("st3");
        if (plan != null && !plan.isMissingNode()) {
            facts.put("monthlyPayment", plan.path("monthlyPayment").asDouble(-1));
            facts.put("totalInterest", plan.path("totalInterest").asDouble(-1));
            JsonNode schedule = plan.path("schedule");
            if (schedule.isArray() && schedule.size() > 0) {
                facts.put("firstPeriod", schedule.get(0).toString());
                facts.put("lastPeriod", schedule.get(schedule.size() - 1).toString());
                facts.put("periods", schedule.size());
            }
        }
        return facts;
    }

    // ============ S6-1 放款条件检查 ============

    private static SceneDialogSpec s61() {
        return new SceneDialogSpec(
                "S6-1",
                """
                【S6-1 放款条件检查】——放款前逐项核对审批结论、合同生效、押品落实、名单复查与账户合规，输出准放/不准放结论，
                典型表述：检查放款条件、这笔能不能放款、放款前核对一下、准放检查等。
                注意：实际放款记账（S6-3）、生成还款计划（S6-2）不属于本场景。""",
                """
                必需要素：客户名称与用信申请编号；二者至少收集到一项，另一项可缺省。
                缺省演示要素：客户「京南机电制造有限公司」、用信申请编号「YS20261028013」（金额 500 万元、期限 1 年、年化 3.45%、抵押担保）。
                用户提到其他客户或编号时按用户口径收集，不要编造编号。""",
                "\"customer\":\"客户名称\",\"applyNo\":\"用信申请编号\"",
                "（如「还有哪些不符项」「补正后怎么放款」）",
                s -> {
                    if (s == null || s.isMissingNode()) return null;
                    Map<String, Object> slots = new HashMap<>();
                    String customer = s.path("customer").asText("").trim();
                    String applyNo = s.path("applyNo").asText("").trim();
                    slots.put("customer", customer.isEmpty() ? "京南机电制造有限公司" : customer);
                    slots.put("applyNo", applyNo.isEmpty() ? "YS20261028013" : applyNo);
                    return slots;
                },
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "客户"));
                    facts.put("applyNo", ctx.slots.get("applyNo"));
                    JsonNode recon = ctx.stepData.get("st3");
                    if (recon != null && !recon.isMissingNode()) {
                        facts.put("三方一致性核对结果", recon.path("result").asText("-"));
                    }
                    // 不符项与结论直接取自结果分块（与场景业务口径一致，防止 LLM 据部分数据编造结论）
                    for (var b : ctx.blocks) {
                        String title = String.valueOf(b.getOrDefault("title", ""));
                        if (title.contains("不符项") || title.contains("结论")) {
                            List<?> lines = (List<?>) b.getOrDefault("lines", List.of());
                            if (!lines.isEmpty()) facts.put(title, String.join("；", lines.stream().map(String::valueOf).toList()));
                        }
                    }
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台放款条件核对结果生成 3 行中文解读，供客户经理参考：
                第1行 核对结论概述（须与给定的结论字段完全一致，不得改为「准放」除非结论明确为准放）；
                第2行 主要不符项及其影响（以给定的不符项数据为准）；第3行 放款前建议动作。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的核对项。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    // ============ S4-1 授信方案智能生成 ============

    private static SceneDialogSpec s41() {
        return new SceneDialogSpec(
                "S4-1",
                """
                【S4-1 授信方案智能生成】——基于尽调结论与产品政策生成授信方案草案（品种/额度/期限/利率/担保/用途/还款方式）
                并试算还款计划，典型表述：做授信方案、出授信意见、生成方案草案、给某客户做流贷授信等。
                注意：正式审批流转（S4 族其他场景）、额度三口径测算（S4-2）不属于本场景。""",
                """
                必需要素：客户名称；拟用品种缺省为「流动资金贷款」。
                缺省演示要素：客户「北京华辰机械制造有限公司」、品种「流动资金贷款」、拟授信 500 万元、期限 12 个月、
                厂房抵押担保、按期付息到期还本。
                客户名称齐备即输出 READY，金额、期限、担保等未提及的要素一律直接采用缺省演示要素补齐，不要追问。""",
                "\"customer\":\"客户名称\",\"product\":\"拟用品种\",\"amount\":金额元数字,\"term\":期数数字",
                "（如「利率为什么这么定」「额度还能调吗」）",
                s -> {
                    if (s == null || s.isMissingNode()) return null;
                    Map<String, Object> slots = new HashMap<>();
                    String customer = s.path("customer").asText("").trim();
                    slots.put("customer", customer.isEmpty() ? "北京华辰机械制造有限公司" : customer);
                    slots.put("product", s.path("product").asText("流动资金贷款"));
                    long amount = parseAmount(s.path("amount"));
                    if (amount > 0) slots.put("amount", amount);
                    int term = s.path("term").asInt(0);
                    if (term > 0) slots.put("term", term);
                    return slots;
                },
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "客户"));
                    facts.put("product", ctx.slots.getOrDefault("product", "流动资金贷款"));
                    JsonNode fin = ctx.stepData.get("st4");
                    if (fin != null && !fin.isMissingNode()) {
                        facts.put("财务解析编号", fin.path("analysisNo").asText("-"));
                        facts.put("造假评分", fin.path("fraudScore").asText("-"));
                    }
                    JsonNode plan = ctx.stepData.get("st6");
                    if (plan != null && !plan.isMissingNode()) {
                        JsonNode mp = plan.path("monthlyPayment");
                        if (!mp.isMissingNode() && !mp.isNull() && mp.asDouble(-1) >= 0) {
                            facts.put("试算月供", mp.asDouble());
                        }
                        JsonNode ti = plan.path("totalInterest");
                        if (!ti.isMissingNode() && !ti.isNull() && ti.asDouble(-1) >= 0) {
                            facts.put("利息合计", ti.asDouble());
                        }
                        facts.put("试算方式", "先息后本、500 万元、12 期、年化 4.05%");
                    }
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台生成的授信方案要素与试算结果生成 3 行中文解读，供客户经理参考：
                第1行 方案要点（品种/额度/期限/担保）；第2行 偿债能力与风险提示；第3行 审批流转建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    // ============ S8-1 正常还款（计划执行/扣款/入账） ============

    private static SceneDialogSpec s81() {
        return new SceneDialogSpec(
                "S8-1",
                """
                【S8-1 正常还款（扣款/入账）】——核对某借据当期应还并执行扣款入账给出回执，典型表述：扣一下某客户这期月供、
                正常还款、查今天的批扣结果、执行扣款等。
                注意：提前还款重算（S8-2）、还款异常处理（S8-8）、生成还款计划（S6-2）不属于本场景。""",
                """
                必需要素：借据号；期次可缺省（默认当期）。
                缺省演示借据：J20250318007（本金 30 万元、12 期等额本息、年化 4.05%、2026-05-18 起息、当前第 6 期未扣）。
                用户提到其他借据号时按用户口径收集，不要编造借据号。""",
                "\"contractId\":\"借据号\",\"period\":期数数字",
                "（如「下期什么时候扣」「这期金额怎么算的」）",
                s -> {
                    if (s == null || s.isMissingNode()) return null;
                    Map<String, Object> slots = new HashMap<>();
                    String contractId = s.path("contractId").asText("").trim();
                    slots.put("contractId", contractId.isEmpty() ? "J20250318007" : contractId);
                    int period = s.path("period").asInt(0);
                    slots.put("period", period > 0 ? period : 6);
                    return slots;
                },
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("contractId", ctx.slots.getOrDefault("contractId", "J20250318007"));
                    facts.put("period", ctx.slots.getOrDefault("period", 6));
                    JsonNode p = ctx.stepData.get("st1");
                    if (p != null && !p.isMissingNode()) {
                        facts.put("当期应还（实算）", p.path("monthlyPayment").asDouble(-1));
                        facts.put("全周期利息合计（实算）", p.path("totalInterest").asDouble(-1));
                    }
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台扣款入账的实算数据生成 3 行中文解读，供客户经理参考：
                第1行 本期扣款结果与金额；第2行 入账与贷款余额变化提示；第3行 后续关注点（如扣款失败转异常处理）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    // ============ S1 营销获客族 ============

    private static SceneDialogSpec s11() {
        return new SceneDialogSpec(
                "S1-1",
                """
                【S1-1 营销洞察与精准触达】——按客群条件圈选客户、匹配产品、生成触达话术并经人工确认后短信发送，
                典型表述：圈客筛名单、把房贷到期客户筛出来推经营贷发短信、营销触达一批客户、
                存量客户提额/复贷空间盘点、季度营销规划摸底、看哪些客户还有营销机会等。
                注意：营销活动策划排期（S1-2）、睡眠户唤醒（S1-4）、产品交叉推荐（S1-5）、线索分配（S1-6）不属于本场景。""",
                """
                必需要素：客群条件（圈客口径）。
                缺省演示要素：客群「名下房贷90天内到期」、产品「随借随还经营贷（年利率3.45%起）」、渠道「短信」、发送 300 条。
                客群条件齐备即输出 READY；产品、渠道、条数未提及的一律用缺省演示要素补齐，不要追问。""",
                "\"condition\":\"客群条件\",\"product\":\"产品名称\",\"channel\":\"渠道\",\"limit\":条数数字",
                "（如「发了多少条」「有没有意向客户」「换个客群再圈一次」）",
                s -> norm("condition", text(s, "condition", "名下房贷90天内到期"),
                        "product", text(s, "product", "随借随还经营贷"),
                        "channel", text(s, "channel", "短信"),
                        "limit", intOr(s, "limit", 300)),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("客群条件", ctx.slots.getOrDefault("condition", "名下房贷90天内到期"));
                    facts.put("产品", ctx.slots.getOrDefault("product", "随借随还经营贷"));
                    facts.put("渠道", ctx.slots.getOrDefault("channel", "短信"));
                    facts.put("发送条数", ctx.slots.getOrDefault("limit", 300));
                    putNum(facts, "客群资产测算值", ctx.stepData.get("st3"), "value");
                    JsonNode sent = ctx.stepData.get("st8");
                    if (sent != null && !sent.isMissingNode()) {
                        facts.put("发送回执", "messageId=" + sent.path("messageId").asText("-")
                                + "，状态=" + sent.path("status").asText("-"));
                    }
                    addBlocks(ctx, facts, "客群总览", "图谱洞察", "产品与话术");
                    return facts;
                },
                """
                你是银行信贷营销助手。根据后台圈客与触达执行结果生成 3 行中文解读，供客户经理参考：
                第1行 圈客与发送结果概述；第2行 图谱风险提示（以给定的图谱洞察数据为准，如担保圈/涉诉关联，不得省略）；
                第3行 后续跟进建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s12() {
        return new SceneDialogSpec(
                "S1-2",
                """
                【S1-2 智能营销活动策划与执行】——针对目标客群策划营销活动（权益规则、三阶段排期、话术）并经审定后推送执行，
                典型表述：策划一个活动、给代发客群做分期费率优惠活动、出活动排期方案等。
                注意：单次客群圈选与短信触达（S1-1）、睡眠户唤醒（S1-4）不属于本场景。""",
                """
                必需要素：活动主题或目标客群。
                缺省演示要素：主题「代发工资客群分期费率优惠」、客群 12.6 万户、权益「手续费 9 折」、周期「2026-10-08 至 2026-11-07」。
                活动主题或客群齐备即输出 READY；周期、权益等未提及的一律用缺省演示要素补齐，不要追问。""",
                "\"theme\":\"活动主题\",\"period\":\"活动周期\"",
                "（如「活动效果怎么样」「权益能不能再加大」）",
                s -> norm("theme", text(s, "theme", "代发工资客群分期费率优惠"),
                        "period", text(s, "period", "2026-10-08 至 2026-11-07")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("活动主题", ctx.slots.getOrDefault("theme", "代发工资客群分期费率优惠"));
                    facts.put("活动周期", ctx.slots.getOrDefault("period", "2026-10-08 至 2026-11-07"));
                    putNum(facts, "客群规模测算值", ctx.stepData.get("st2"), "value");
                    addBlocks(ctx, facts, "活动方案", "排期计划", "话术示例");
                    return facts;
                },
                """
                你是银行信贷营销助手。根据后台活动策划与执行结果生成 3 行中文解读，供客户经理参考：
                第1行 活动方案与客群规模概述；第2行 排期节奏提示；第3行 执行关注建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s13() {
        return new SceneDialogSpec(
                "S1-3",
                """
                【S1-3 客户分层与差异化经营】——按 AUM/贡献/活跃度等维度将管户客群分层并输出各层差异化经营策略，
                典型表述：客户分层、按 AUM 分五层、给分层经营建议、客群分群等。
                注意：单个客户的画像查询（S1-8）、圈客发短信触达（S1-1）不属于本场景。""",
                """
                必需要素：无（说明要做客户分层即可）。
                缺省演示要素：维度「AUM+贡献+活跃度综合」、五层分布、各层经营策略。
                用户说明诉求即输出 READY，分层维度未提及的直接用缺省口径，不要追问。""",
                "\"dimension\":\"分层维度\"",
                "（如「高价值层怎么提升」「导出分层名单」）",
                s -> norm("dimension", text(s, "dimension", "AUM+贡献+活跃度综合")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("分层维度", ctx.slots.getOrDefault("dimension", "AUM+贡献+活跃度综合"));
                    putNum(facts, "核心层资产测算值", ctx.stepData.get("st2"), "value");
                    putNum(facts, "风险修正因子", ctx.stepData.get("st4"), "value");
                    addBlocks(ctx, facts, "分层总览", "各层经营策略");
                    return facts;
                },
                """
                你是银行信贷营销助手。根据后台客户分层结果生成 3 行中文解读，供客户经理参考：
                第1行 分层结果概述；第2行 各层经营策略要点；第3行 分层经营落地建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s14() {
        return new SceneDialogSpec(
                "S1-4",
                """
                【S1-4 睡眠户唤醒与存量激活】——圈选长期无交易的睡眠户、匹配唤醒权益与话术并经确认后批量触达，
                典型表述：唤醒睡眠户、激活存量客户、把休眠户圈出来做唤醒等。
                仅针对长期无交易的休眠客户；泛指「存量客户」的提额/复贷空间盘点、营销机会摸底（S1-1）不属于本场景。
                注意：一般营销圈客（S1-1）、活动策划（S1-2）、产品交叉推荐（S1-5）不属于本场景。""",
                """
                必需要素：无（说明要做睡眠户唤醒即可）。
                缺省演示要素：范围「对私」、休眠阈值「12 个月」、权益「零钱产品（七日年化约1.8%、随存随取）」、发送 5000 条。
                用户说明诉求即输出 READY，范围、阈值、条数未提及的一律用缺省演示要素补齐，不要追问。""",
                "\"scope\":\"客户范围\",\"threshold\":\"休眠阈值\",\"limit\":条数数字",
                "（如「唤醒了多少户」「权益能不能换」）",
                s -> norm("scope", text(s, "scope", "对私"),
                        "threshold", text(s, "threshold", "12 个月"),
                        "limit", intOr(s, "limit", 5000)),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("客户范围", ctx.slots.getOrDefault("scope", "对私"));
                    facts.put("休眠阈值", ctx.slots.getOrDefault("threshold", "12 个月"));
                    facts.put("发送条数", ctx.slots.getOrDefault("limit", 5000));
                    putNum(facts, "睡眠户规模测算值", ctx.stepData.get("st2"), "value");
                    addBlocks(ctx, facts, "睡眠户总览", "唤醒方案");
                    return facts;
                },
                """
                你是银行信贷营销助手。根据后台睡眠户圈选与唤醒执行结果生成 3 行中文解读，供客户经理参考：
                第1行 睡眠户规模与分级概述；第2行 唤醒权益与话术要点；第3行 触达与回访建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s15() {
        return new SceneDialogSpec(
                "S1-5",
                """
                【S1-5 交叉销售与产品推荐】——分析客户持有产品缺口、匹配适配产品、生成含风险提示的推荐话术并经确认后推送，
                典型表述：交叉销售、给只有存款的客户推荐理财、找产品缺口推产品等。
                注意：一般营销圈客（S1-1）、睡眠户唤醒（S1-4）不属于本场景。""",
                """
                必需要素：无（说明要做交叉销售/产品推荐即可）。
                缺省演示要素：推荐产品「稳健型理财（R2）」、目标客群「有存款无理财」、推送 2000 条。
                用户说明诉求即输出 READY，产品、条数未提及的一律用缺省演示要素补齐，不要追问。""",
                "\"product\":\"推荐产品\",\"limit\":条数数字",
                "（如「适配了多少客户」「话术发我看看」）",
                s -> norm("product", text(s, "product", "稳健型理财（R2）"),
                        "limit", intOr(s, "limit", 2000)),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("推荐产品", ctx.slots.getOrDefault("product", "稳健型理财（R2）"));
                    facts.put("推送条数", ctx.slots.getOrDefault("limit", 2000));
                    putNum(facts, "缺口客群资产测算值", ctx.stepData.get("st4"), "value");
                    addBlocks(ctx, facts, "机会总览", "推荐清单", "话术示例");
                    return facts;
                },
                """
                你是银行信贷营销助手。根据后台产品缺口分析与推荐执行结果生成 3 行中文解读，供客户经理参考：
                第1行 产品缺口与推荐机会概述；第2行 适配产品与理由提示；第3行 推送与合规建议（话术须含风险提示）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s16() {
        return new SceneDialogSpec(
                "S1-6",
                """
                【S1-6 渠道线索管理与商机跟进】——汇聚各渠道线索、抽取要素、去重分配客户经理并生成跟进计划与督办提醒，
                典型表述：汇总今天的线索、App 留资线索分配、商机跟进计划等。
                注意：客户经理待办工作台（S1-7）、营销触达（S1-1）不属于本场景。""",
                """
                必需要素：无（说明要汇总/分配线索即可）。
                缺省演示要素：来源渠道「App 留资」、时间范围「当日」。
                用户说明诉求即输出 READY，来源渠道未提及的直接用缺省口径，不要追问。""",
                "\"source\":\"来源渠道\"",
                "（如「分给了哪些客户经理」「有多少意向」）",
                s -> norm("source", text(s, "source", "App 留资")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("来源渠道", ctx.slots.getOrDefault("source", "App 留资"));
                    addBlocks(ctx, facts, "线索总览", "跟进计划", "商机看板");
                    return facts;
                },
                """
                你是银行信贷营销助手。根据后台线索汇总与分配结果生成 3 行中文解读，供客户经理参考：
                第1行 线索规模与去重结果概述；第2行 分配与跟进安排；第3行 转化督办建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s17() {
        return new SceneDialogSpec(
                "S1-7",
                """
                【S1-7 客户经理数字员工工作台】——聚合今日待办、名下客户与商机进度、整理拜访纪要的客户经理个人工作台，
                典型表述：我今天有哪些待办、查客户业务进度、记一下拜访纪要、我的工作台等。
                注意：渠道线索分配（S1-6）、客户分层（S1-3）不属于本场景。""",
                """
                必需要素：无（说明要看待办/进度或记录纪要即可）。
                缺省演示要素：工号「rm-001」；记录纪要时若用户口述了纪要内容则按用户口径收集，
                否则用缺省演示纪要（拜访宏远商贸王总：拟将明年设备更新贷款转我行，预计需求500万，下周需要看到方案）。
                用户说明诉求即输出 READY，不要追问。""",
                "\"intent\":\"待办|进度|纪要\",\"memoText\":\"纪要原文（仅记录纪要时提供）\"",
                "（如「第一项待办怎么处理」「商机进度如何」）",
                s -> norm("userId", "rm-001",
                        "memoText", text(s, "memoText",
                                "拜访宏远商贸王总：拟将明年设备更新贷款转我行，预计需求500万，下周需要看到方案。")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    addBlocks(ctx, facts, "今日待办", "客户动态", "业务进度", "拜访纪要");
                    return facts;
                },
                """
                你是银行信贷客户经理的数字员工助手。根据工作台聚合结果生成 3 行中文解读：
                第1行 今日待办概况；第2行 客户与商机动态提示；第3行 今日工作建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s18() {
        return new SceneDialogSpec(
                "S1-8",
                """
                【S1-8 客户画像】——归并客户身份并聚合资产、产品、交易、关联图谱信息，输出单客户画像视图，
                典型表述：看看某客户的画像、某客户全貌、客户视图等。
                注意：客群分层（S1-3）、营销圈客（S1-1）不属于本场景；
                仅当用户泛指「画像」「客户视图」「客户全貌」时选本场景；用户说「完整画像」时选 S11-3，不属于本场景。""",
                """
                必需要素：客户名称。
                缺省演示要素：客户「宏远商贸」（制造业中型，2015 年成立）。
                客户名称齐备即输出 READY；用户未指明客户时直接采用缺省演示客户，不要追问。""",
                "\"customer\":\"客户名称\"",
                "（如「有什么风险」「推荐适配产品」）",
                s -> norm("customer", text(s, "customer", "宏远商贸")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "宏远商贸"));
                    addBlocks(ctx, facts, "基础信息", "资产负债与产品持有", "交易行为特征", "关联图谱");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台客户画像聚合结果生成 3 行中文解读，供客户经理参考：
                第1行 客户资产负债与产品持有概述；第2行 风险提示（以给定的关联图谱数据为准，不得改为无风险）；
                第3行 营销或管护建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    // ============ S2 申请受理族 ============

    /** 校验类原子（data-validate）响应 → 摘要文案 */
    private static String validateSummary(JsonNode d) {
        if (d == null || d.isMissingNode()) return null;
        return d.path("result").asText("-") + "（通过 " + d.path("summary").path("passed").asInt(0)
                + " 项，警告 " + d.path("summary").path("warned").asInt(0)
                + "，失败 " + d.path("summary").path("failed").asInt(0) + "）";
    }

    private static SceneDialogSpec s21() {
        return new SceneDialogSpec(
                "S2-1",
                """
                【S2-1 线上贷款申请受理与补件】——受理客户贷款申请、核验身份与要素齐套性、进件校验并生成进件编号与补件任务，
                典型表述：受理某客户的申请、进件、补件等。
                注意：线下纸质申请录入（S2-2）、影像齐套校验（S2-3）、对公受理（S2-4）不属于本场景。""",
                """
                必需要素：客户名称；申请要素（金额/期限/用途）可缺省。
                缺省演示要素：客户「李娜」、诉求「经营贷申请 100 万元 / 36 个月 / 经营周转」。
                客户名称齐备即输出 READY；金额、期限等未提及的一律用缺省演示要素补齐，不要追问。""",
                "\"customer\":\"客户名称\",\"message\":\"申请诉求原文\"",
                "（如「要素齐了吗」「进件编号多少」）",
                s -> norm("customer", text(s, "customer", "李娜"),
                        "message", text(s, "message", "受理客户李娜的经营贷申请，金额100万，期限36个月")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "李娜"));
                    facts.put("诉求", ctx.slots.getOrDefault("message", "经营贷申请 100 万元 / 36 个月"));
                    String v = validateSummary(ctx.stepData.get("st5"));
                    if (v != null) facts.put("T_SUBMIT 进件校验", v);
                    addBlocks(ctx, facts, "客户与诉求", "进件校验", "历史借贷");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台申请受理结果生成 3 行中文解读，供客户经理参考：
                第1行 受理与要素齐套结论；第2行 校验与历史借贷要点（含缺失材料，不得省略）；第3行 补件与后续建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s22() {
        return new SceneDialogSpec(
                "S2-2",
                """
                【S2-2 线下申请要素录入与确认】——把线下申请单要素抽取结构化、校验完整性并逐项确认后建档，
                典型表述：录入线下申请、纸质申请要素录入、帮我把申请单录进去等。
                注意：线上申请受理（S2-1）、影像齐套校验（S2-3）不属于本场景；
                「把贷款/业务数据录入核算系统」类诉求（核算系统记账入账）指向 S9-3，不属于本场景。""",
                """
                必需要素：客户名称；申请要素可缺省。
                缺省演示要素：客户「王总」、申请「经营贷 80 万元 / 24 个月 / 设备采购 / 抵押物厂房」。
                客户名称齐备即输出 READY；其余要素未提及的一律用缺省演示要素补齐，不要追问。""",
                "\"customer\":\"客户名称\",\"message\":\"申请要素原文\"",
                "（如「哪个要素有缺」「进件号出了吗」）",
                s -> norm("customer", text(s, "customer", "王总"),
                        "message", text(s, "message", "王总申请经营贷，金额80万，期限24个月，用途设备采购，抵押物为厂房")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "王总"));
                    facts.put("申请要素", ctx.slots.getOrDefault("message", "经营贷 80 万元 / 24 个月 / 设备采购 / 抵押物厂房"));
                    String v = validateSummary(ctx.stepData.get("st3"));
                    if (v != null) facts.put("要素完整性校验", v);
                    addBlocks(ctx, facts, "结构化要素", "校验结果");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台要素录入与校验结果生成 3 行中文解读，供客户经理参考：
                第1行 结构化要素概述；第2行 校验结论与缺失项（不得省略）；第3行 确认建档或补正建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s23() {
        return new SceneDialogSpec(
                "S2-3",
                """
                【S2-3 影像上传与齐套性校验】——接收贷款申请影像资料、逐张识别分类、质量校验并核对清单齐套性，
                典型表述：上传影像资料、校验影像齐套、扫描件核验等。
                注意：线下申请要素录入（S2-2）、对公受理（S2-4）不属于本场景。""",
                """
                必需要素：客户名称。
                缺省演示要素：客户「李娜」、影像「18 页（身份证明/经营证明/银行流水/抵押物权属）」。
                客户名称齐备即输出 READY；影像明细未提及的一律用缺省演示口径，不要追问。""",
                "\"customer\":\"客户名称\"",
                "（如「缺哪一页」「质量有问题吗」）",
                s -> norm("customer", text(s, "customer", "李娜")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "李娜"));
                    String v = validateSummary(ctx.stepData.get("st1"));
                    if (v != null) facts.put("T_DOC 齐套性校验", v);
                    addBlocks(ctx, facts, "影像清单", "齐套性校验", "一致性比对");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台影像校验结果生成 3 行中文解读，供录入人员参考：
                第1行 影像上传与识别分类结论；第2行 质量与齐套性问题（模糊页、地址差异等不得省略）；第3行 补传与归档建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s24() {
        return new SceneDialogSpec(
                "S2-4",
                """
                【S2-4 对公客户申请受理】——核验企业身份与实际控制人、校验对公进件要素并受理建档生成补件任务，
                典型表述：受理某公司的法人贷申请、对公进件、企业贷款受理等。
                注意：个人经营贷受理（S2-1）、征信授权（S2-5）不属于本场景。""",
                """
                必需要素：企业名称；申请要素可缺省。
                缺省演示要素：企业「宏远商贸」、申请「法人贷 500 万元 / 36 个月 / 流动资金」。
                企业名称齐备即输出 READY；金额、期限等未提及的一律用缺省演示要素补齐，不要追问。""",
                "\"customer\":\"企业名称\",\"message\":\"申请要素原文\"",
                "（如「实控人是谁」「要补什么材料」）",
                s -> norm("customer", text(s, "customer", "宏远商贸"),
                        "message", text(s, "message", "受理宏远商贸的法人贷申请，金额500万，期限36个月，用途流动资金")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "宏远商贸"));
                    facts.put("申请要素", ctx.slots.getOrDefault("message", "法人贷 500 万元 / 36 个月 / 流动资金"));
                    String v = validateSummary(ctx.stepData.get("st3"));
                    if (v != null) facts.put("对公进件校验", v);
                    addBlocks(ctx, facts, "企业核验", "进件要素", "存量授信");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台对公受理结果生成 3 行中文解读，供客户经理参考：
                第1行 企业与实控人核验结论；第2行 进件校验与存量授信要点（含对外担保，不得省略）；第3行 受理与补件建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s25() {
        return new SceneDialogSpec(
                "S2-5",
                """
                【S2-5 征信授权与授权书校验】——识别校验征信授权书、核对签署意愿、建立授权建档并获取征信报告，
                典型表述：校验授权书、做征信授权、查一下征信等。
                注意：贷中征信与第三方批量查询（S3-2）不属于本场景。""",
                """
                必需要素：客户名称。
                缺省演示要素：客户「李娜」、授权书「AUTH-2026-001（2026-10-30 签署，用途信贷审批）」。
                客户名称齐备即输出 READY；授权书明细未提及的一律用缺省演示口径，不要追问。""",
                "\"customer\":\"客户名称\"",
                "（如「报告出来了吗」「授权什么时候到期」）",
                s -> norm("customer", text(s, "customer", "李娜")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "李娜"));
                    JsonNode auth = ctx.stepData.get("st4");
                    if (auth != null && !auth.isMissingNode()) {
                        facts.put("授权建档", "authId=" + auth.path("authId").asText("-")
                                + "，状态=" + auth.path("authStatus").asText("-")
                                + "，用途合规=" + auth.path("purposeValid").asText("-"));
                    }
                    addBlocks(ctx, facts, "授权书校验", "授权建档", "征信报告");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台征信授权与报告获取结果生成 3 行中文解读，供客户经理参考：
                第1行 授权书校验与建档结论；第2行 征信报告要点（逾期/查询记录不得省略）；第3行 后续用途建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    // ============ S3 尽调评估族 ============

    private static SceneDialogSpec s31() {
        return new SceneDialogSpec(
                "S3-1",
                """
                【S3-1 客户信息统一视图】——归并客户身份并把行内外多源信息交叉核对成一页式客户视图（含关联图谱），
                典型表述：出某客户的一页式视图、客户360、统一视图、客户档案汇总等。
                注意：单客户画像查询（S1-8）、征信与第三方查询（S3-2）不属于本场景。""",
                """
                必需要素：客户名称。
                缺省演示要素：客户「北京恒润机械制造有限公司」（经营贷余额 180 万元、正常类）。
                客户名称齐备即输出 READY；用户未指明客户时直接采用缺省演示客户，不要追问。""",
                "\"customer\":\"客户名称\"",
                "（如「有什么矛盾点」「发起征信查询」）",
                s -> norm("customer", text(s, "customer", "北京恒润机械制造有限公司")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "北京恒润机械制造有限公司"));
                    String v = validateSummary(ctx.stepData.get("st4"));
                    if (v != null) facts.put("多源交叉核对", v);
                    addBlocks(ctx, facts, "基础概览", "业务画像", "风险提示", "关联图谱");
                    return facts;
                },
                """
                你是银行信贷尽调助手。根据后台统一视图聚合结果生成 3 行中文解读，供客户经理参考：
                第1行 客户概览（授信/分类）；第2行 矛盾点与风险提示（以给定的风险提示数据为准，不得省略或改为无风险）；
                第3行 后续尽调动作建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s32() {
        return new SceneDialogSpec(
                "S3-2",
                """
                【S3-2 征信与第三方信息查询】——授权核验后解析企业征信报告并调取司法、税务等第三方信息交叉核对，
                典型表述：查企业征信、拉一下司法和税务信息、第三方信息核查等。
                注意：申请环节的征信授权建档（S2-5）不属于本场景。""",
                """
                必需要素：客户名称。
                缺省演示要素：客户「北京恒润机械制造有限公司」（企业征信、授权 AUTH-2026-101）。
                客户名称齐备即输出 READY；用户未指明客户时直接采用缺省演示客户，不要追问。""",
                "\"customer\":\"客户名称\"",
                "（如「被执行记录怎么回事」「负债核对一致吗」）",
                s -> norm("customer", text(s, "customer", "北京恒润机械制造有限公司")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "北京恒润机械制造有限公司"));
                    JsonNode auth = ctx.stepData.get("st3");
                    if (auth != null && !auth.isMissingNode()) {
                        facts.put("授权建档", "authId=" + auth.path("authId").asText("-")
                                + "，用途合规=" + auth.path("purposeValid").asText("-"));
                    }
                    String v = validateSummary(ctx.stepData.get("st9"));
                    if (v != null) facts.put("交叉核对", v);
                    addBlocks(ctx, facts, "授权核验状态", "征信要点", "第三方信息");
                    return facts;
                },
                """
                你是银行信贷尽调助手。根据后台征信与第三方查询结果生成 3 行中文解读，供客户经理参考：
                第1行 授权有效性与查询执行结论；第2行 征信与第三方要点（被执行/处罚/对外担保不得省略或美化）；
                第3行 交叉核对与后续尽调建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s33() {
        return new SceneDialogSpec(
                "S3-3",
                """
                【S3-3 股权穿透】——逐层穿透企业股权链到最终受益人并标注壳公司/代持嫌疑节点，
                典型表述：做股权穿透、查最终受益人、受益所有人识别等。
                注意：关联担保圈挖掘（S3-4）、对公受理核验（S2-4）不属于本场景。""",
                """
                必需要素：企业名称。
                缺省演示要素：企业「北京华信科技有限公司」（默认穿透 5 层）。
                企业名称齐备即输出 READY；用户未指明企业时直接采用缺省演示企业，不要追问。""",
                "\"customer\":\"企业名称\"",
                "（如「股权链怎么回事」「受益人有哪些」）",
                s -> norm("customer", text(s, "customer", "北京华信科技有限公司")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "北京华信科技有限公司"));
                    JsonNode g = ctx.stepData.get("st3");
                    if (g != null && !g.isMissingNode()) {
                        facts.put("股权链构建", "jobId=" + g.path("jobId").asText("-"));
                    }
                    addBlocks(ctx, facts, "穿透总览", "股权结构图", "最终受益人清单", "风险提示");
                    return facts;
                },
                """
                你是银行信贷尽调助手。根据后台股权穿透结果生成 3 行中文解读，供客户经理参考：
                第1行 穿透层级与最终受益人概述（比例须与数据一致）；第2行 担保圈/循环持股等风险提示（以数据为准，不得编造）；
                第3行 核实与受益所有人申报建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s34() {
        return new SceneDialogSpec(
                "S3-4",
                """
                【S3-4 关联关系挖掘与知识图谱】——挖掘担保圈、互保与隐性关联并测算风险传染敞口，
                典型表述：挖一下担保圈、互保关系、关联网络挖掘等。
                注意：股权穿透（S3-3）、审批环节关联交易管控（S4-6/S4-7）不属于本场景。""",
                """
                必需要素：客户名称。
                缺省演示要素：客户「上海润泽实业有限公司」（默认穿透 3 层）。
                客户名称齐备即输出 READY；用户未指明客户时直接采用缺省演示客户，不要追问。""",
                "\"customer\":\"客户名称\"",
                "（如「担保圈多大」「要不要预警」）",
                s -> norm("customer", text(s, "customer", "上海润泽实业有限公司")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "上海润泽实业有限公司"));
                    addBlocks(ctx, facts, "关联总览", "股权与担保路径", "股东持股汇总", "风险提示");
                    return facts;
                },
                """
                你是银行信贷尽调助手。根据后台关联挖掘与穿透结果生成 3 行中文解读，供客户经理参考：
                第1行 关联网络概述（持股比例须与数据一致）；第2行 担保圈/循环持股风险提示（以数据为准，不得编造）；
                第3行 预警与管护建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s35() {
        return new SceneDialogSpec(
                "S3-5",
                """
                【S3-5 现场尽调与勘察管理】——接收现场影像并质检、定位核验、识别单据、汇总现场经营要素生成尽调记录草稿，
                典型表述：现场尽调、整理今天拍的现场照片、实地调查记录等。
                注意：非现场尽调报告生成（S3-11）、押品评估（S3-10）不属于本场景。""",
                """
                必需要素：无（说明要做现场尽调/整理现场影像即可）。
                缺省演示要素：客户「北京恒润机械制造有限公司」、现场影像「8 张照片 + 1 段视频（演示占位）」。
                用户说明诉求即输出 READY，不要追问。""",
                "\"customer\":\"客户名称\",\"note\":\"现场情况说明（可选）\"",
                "（如「哪张照片要重拍」「现场情况怎么样」）",
                s -> norm("customer", text(s, "customer", "北京恒润机械制造有限公司"),
                        "note", text(s, "note", "")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    addBlocks(ctx, facts, "影像质检报告", "现场要素摘要", "单据识别明细");
                    return facts;
                },
                """
                你是银行信贷尽调助手。根据后台现场尽调处理结果生成 3 行中文解读，供客户经理参考：
                第1行 影像质检与定位核验结论（退回重拍的影像不得省略）；第2行 现场经营要素提示；
                第3行 尽调记录与补充采集建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s36() {
        return new SceneDialogSpec(
                "S3-6",
                """
                【S3-6 名单筛查】——对客户及其关联方批量筛查制裁、行内黑名单、失信与涉诉名单并打标处置，
                典型表述：过一下名单、做名单筛查、制裁名单筛查等。
                注意：反欺诈评分（S3-7）、审批环节合规红线（S4-6）不属于本场景。""",
                """
                必需要素：客户名称（或说明按名单文件批量筛查）。
                缺省演示要素：客户「北京恒润机械制造有限公司」及法定代表人与主要股东共 12 户。
                客户名称齐备即输出 READY；用户未指明客户时直接采用缺省演示客户，不要追问。""",
                "\"customer\":\"客户名称\"",
                "（如「命中了什么」「禁入怎么处理」）",
                s -> norm("customer", text(s, "customer", "北京恒润机械制造有限公司")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "北京恒润机械制造有限公司"));
                    addBlocks(ctx, facts, "筛查总览", "命中明细", "关联传播", "处置建议");
                    return facts;
                },
                """
                你是银行信贷风控助手。根据后台名单筛查结果生成 3 行中文解读，供风控人员参考：
                第1行 筛查范围与命中结论；第2行 命中明细与关联波及（禁入/人工关注口径须与处置建议一致，不得淡化）；
                第3行 处置与流转建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s37() {
        return new SceneDialogSpec(
                "S3-7",
                """
                【S3-7 反欺诈识别】——跑欺诈规则与模型评分、流水异常归因、黑名单筛查与团伙网络核查并给出处置建议，
                典型表述：做反欺诈识别、查一下有没有欺诈风险、跑欺诈评分等。
                注意：名单筛查（S3-6）、审批规则融合（S4-4）不属于本场景。""",
                """
                必需要素：客户名称。
                缺省演示要素：客户「北京恒润机械制造有限公司」（演示评分 62 分中风险）。
                客户名称齐备即输出 READY；用户未指明客户时直接采用缺省演示客户，不要追问。""",
                "\"customer\":\"客户名称\"",
                "（如「疑点在哪」「要不要转人工」）",
                s -> norm("customer", text(s, "customer", "北京恒润机械制造有限公司")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "北京恒润机械制造有限公司"));
                    addBlocks(ctx, facts, "欺诈评分与等级", "疑点清单", "团伙网络", "处置建议");
                    return facts;
                },
                """
                你是银行信贷风控助手。根据后台反欺诈识别结果生成 3 行中文解读，供风控人员参考：
                第1行 评分与风险等级（分数与等级须与数据完全一致）；第2行 疑点清单要点（不得省略或美化）；
                第3行 人工核查与处置建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s38() {
        return new SceneDialogSpec(
                "S3-8",
                """
                【S3-8 项目融资评估】——项目财务解析、行业景气判断与 IRR/DSCR 等财务测算并给出评估结论，
                典型表述：项目融资评估、项目贷款可研测算、算一下项目 IRR 等。
                注意：流贷授信方案（S4-1）、押品评估（S3-10）不属于本场景。""",
                """
                必需要素：项目名称；总投资、拟贷金额与期限可缺省。
                缺省演示要素：项目「恒润机械新生产线」、总投资 3000 万元、拟贷 1800 万元、期限 60 个月。
                项目名称齐备即输出 READY；其余要素未提及的一律用缺省演示要素补齐，不要追问。""",
                "\"project\":\"项目名称\",\"amount\":拟贷金额元数字,\"term\":期数数字",
                "（如「DSCR 达标吗」「宽限期怎么设」）",
                s -> norm("project", text(s, "project", "恒润机械新生产线"),
                        "amount", amountOr(s, "amount", 1800000L),
                        "term", intOr(s, "term", 60)),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("project", ctx.slots.getOrDefault("project", "恒润机械新生产线"));
                    JsonNode fin = ctx.stepData.get("st1");
                    if (fin != null && !fin.isMissingNode()) {
                        facts.put("财务解析编号", fin.path("analysisNo").asText("-"));
                        facts.put("造假评分", fin.path("fraudScore").asText("-"));
                    }
                    addBlocks(ctx, facts, "项目概要", "行业与市场判断", "财务测算结果", "风险与建议");
                    return facts;
                },
                """
                你是银行信贷尽调助手。根据后台项目融资评估结果生成 3 行中文解读，供客户经理参考：
                第1行 项目与测算结论概述（IRR/DSCR/回收期须与数据一致，DSCR 低于警戒线须如实提示）；
                第2行 主要风险提示；第3行 担保与期限结构建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s39() {
        return new SceneDialogSpec(
                "S3-9",
                """
                【S3-9 供应链金融评估】——分析供应链交易网络与回款、核验单据与核心企业确权、测算单笔可融额度，
                典型表述：做供应链金融评估、应收账款融资额度、保理业务评估等。
                注意：票据贴现（S6-10）、额度三口径测算（S4-2）不属于本场景。""",
                """
                必需要素：融资主体名称；核心企业与业务类型可缺省。
                缺省演示要素：融资主体「北京恒润机械制造有限公司」、核心企业「华电重工」、业务「应收账款融资（发票 120 万元、折扣率 80%）」。
                融资主体齐备即输出 READY；其余要素未提及的一律用缺省演示要素补齐，不要追问。""",
                "\"customer\":\"融资主体名称\",\"coreCompany\":\"核心企业名称\"",
                "（如「额度算出来多少」「确权有问题吗」）",
                s -> norm("customer", text(s, "customer", "北京恒润机械制造有限公司"),
                        "coreCompany", text(s, "coreCompany", "华电重工")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "北京恒润机械制造有限公司"));
                    facts.put("coreCompany", ctx.slots.getOrDefault("coreCompany", "华电重工"));
                    JsonNode lc = ctx.stepData.get("st7");
                    if (lc != null && !lc.isMissingNode()) {
                        facts.put("额度测算", "可融=" + lc.path("limitAmount").asText("-")
                                + "，剩余可用=" + lc.path("remainAmount").asText("-")
                                + "，管控=" + lc.path("controlStatus").asText("-"));
                    }
                    addBlocks(ctx, facts, "供应链网络", "交易真实性核验", "回款历史与账期分析", "额度测算建议");
                    return facts;
                },
                """
                你是银行信贷尽调助手。根据后台供应链评估结果生成 3 行中文解读，供客户经理参考：
                第1行 供应链网络与交易真实性结论（确权核验结论不得省略）；第2行 回款与账期要点；
                第3行 额度与后续动作建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s310() {
        return new SceneDialogSpec(
                "S3-10",
                """
                【S3-10 抵质押物评估】——按审慎口径对押品估值、核验现场影像并测算折扣率与覆盖率，
                典型表述：评估一下押品、抵押物估值、算一下覆盖率等。
                注意：贷后押品重估（S7-4）、项目融资评估（S3-8）不属于本场景。""",
                """
                必需要素：押品类型；坐落、面积等基本情况可缺省。
                缺省演示要素：押品「住宅（昌平区、120 ㎡）」、市场比较法评估值约 312 万元、住宅折扣率 70%。
                押品类型齐备即输出 READY；其余要素未提及的一律用缺省演示要素补齐，不要追问。""",
                "\"collateralType\":\"押品类型\",\"area\":面积数字",
                "（如「评估值多少」「覆盖够不够」）",
                s -> norm("collateralType", text(s, "collateralType", "住宅"),
                        "area", intOr(s, "area", 120)),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode val = ctx.stepData.get("st4");
                    if (val != null && !val.isMissingNode()) {
                        facts.put("估值结果", "评估值=" + val.path("value").asText("-")
                                + "，结论=" + val.path("conclusion").asText("-"));
                    }
                    JsonNode lc = ctx.stepData.get("st6");
                    if (lc != null && !lc.isMissingNode()) {
                        facts.put("额度测算", "可贷=" + lc.path("limitAmount").asText("-")
                                + "，管控=" + lc.path("controlStatus").asText("-"));
                    }
                    addBlocks(ctx, facts, "押品概况", "估值方法与结果", "折扣率与覆盖率");
                    return facts;
                },
                """
                你是银行信贷尽调助手。根据后台押品评估结果生成 3 行中文解读，供客户经理参考：
                第1行 估值方法与评估值（数字须与数据一致）；第2行 折扣率与覆盖率结论（充足/不足须如实）；
                第3行 正式评估与抵押登记提示。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s311() {
        return new SceneDialogSpec(
                "S3-11",
                """
                【S3-11 尽调报告自动生成】——汇总已完成的尽调结论，按行内模板拼装报告初稿并校验要素后双签定稿，
                典型表述：生成尽调报告、出报告初稿、尽调结论汇总成报告等。
                注意：单一尽调模块执行（S3-2/S3-3/S3-5 等）、授信方案（S4-1）不属于本场景。""",
                """
                必需要素：客户名称。
                缺省演示要素：客户「北京恒润机械制造有限公司」、报告「7 章尽调报告初稿（PDF）」。
                客户名称齐备即输出 READY；用户未指明客户时直接采用缺省演示客户，不要追问。""",
                "\"customer\":\"客户名称\"",
                "（如「还缺什么材料」「报告下载」）",
                s -> norm("customer", text(s, "customer", "北京恒润机械制造有限公司")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "北京恒润机械制造有限公司"));
                    JsonNode r = ctx.stepData.get("st3");
                    if (r != null && !r.isMissingNode()) {
                        facts.put("报告生成", "reportId=" + r.path("reportId").asText("-")
                                + "，三校验=" + r.path("checks").path("caliber").asText("-") + "/"
                                + r.path("checks").path("required").asText("-") + "/"
                                + r.path("checks").path("balance").asText("-"));
                    }
                    addBlocks(ctx, facts, "报告目录与要素校验", "待补充材料清单", "报告文档下载");
                    return facts;
                },
                """
                你是银行信贷尽调助手。根据后台报告生成结果生成 3 行中文解读，供客户经理参考：
                第1行 报告生成与要素校验结论；第2行 待补充材料与缺口提示（不得省略）；第3行 双签定稿建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    // ============ S4 审查审批族（S4-1 已接入，此处 S4-2~S4-9） ============

    private static SceneDialogSpec s42() {
        return new SceneDialogSpec(
                "S4-2",
                """
                【S4-2 额度测算】——按收入倍数、押品折扣、风险限额三条口径分别测算并取最小值给出建议额度，
                典型表述：额度测算、能贷多少、三口径、测额度等。
                注意：授信方案设计（S4-1）、供应链融资额度（S3-9）、贷后重检（S7-3）不属于本场景；
                「额度占用扣减/释放查询」类表述指向 S6-4，「额度台账检查/台账核对」类表述指向 S6-6，均不属于本场景。""",
                """
                必需要素：客户名称。
                缺省演示要素：客户「北京华辰机械制造有限公司」（流贷，拟申请 600 万元，厂房抵押）。
                客户名称齐备即输出 READY；拟申请金额等未提及的一律用缺省演示要素补齐，不要追问。""",
                "\"customer\":\"客户名称\"",
                "（如「哪个口径约束」「额度还能提吗」）",
                s -> norm("customer", text(s, "customer", "北京华辰机械制造有限公司")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "北京华辰机械制造有限公司"));
                    JsonNode lc = ctx.stepData.get("st8");
                    if (lc != null && !lc.isMissingNode()) {
                        facts.put("建议额度", "limitAmount=" + lc.path("limitAmount").asText("-")
                                + "，剩余可用=" + lc.path("remainAmount").asText("-")
                                + "，管控=" + lc.path("controlStatus").asText("-"));
                    }
                    addBlocks(ctx, facts, "三口径测算明细", "建议额度", "测算后偿债指标预测");
                    return facts;
                },
                """
                你是银行信贷审批助手。根据后台三口径额度测算结果生成 3 行中文解读，供审批人员参考：
                第1行 三口径测算概述（各口径金额须与数据一致）；第2行 建议额度与约束口径（取小结果与管控状态不得改动）；
                第3行 确认或调整口径建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s43() {
        return new SceneDialogSpec(
                "S4-3",
                """
                【S4-3 利率定价】——按 FTP 基准叠加风险溢价、期限溢价与综合贡献调整测算建议利率并做政策区间校验，
                典型表述：利率定价、算个利率、FTP 加点、给这笔贷款定价等。
                注意：产品创设定价（S4-8）、投贷联动让价（S4-9）、还款计划试算（S6-2）不属于本场景。""",
                """
                必需要素：无（说明要定价即可）。
                缺省演示要素：客户「北京华辰机械制造有限公司」、流贷 600 万元 / 12 个月 / 抵押 / A 级 / 浮动利率。
                用户说明诉求即输出 READY，客户与要素未提及的一律用缺省演示口径，不要追问。""",
                "\"customer\":\"客户名称（可选）\",\"amount\":金额元数字（可选）,\"term\":期数（可选）",
                "（如「定价依据是什么」「能再让多少」）",
                s -> norm("customer", text(s, "customer", "北京华辰机械制造有限公司"),
                        "amount", amountOr(s, "amount", 6000000L),
                        "term", intOr(s, "term", 12)),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode q = ctx.stepData.get("st4");
                    if (q != null && !q.isMissingNode()) {
                        facts.put("定价测算", "基准=" + q.path("baseRate").asText("-")
                                + "，风险溢价=" + q.path("riskPremium").asText("-")
                                + "，综合利率=" + q.path("finalRate").asText("-"));
                    }
                    addBlocks(ctx, facts, "定价依据", "建议年利率", "定价瀑布");
                    return facts;
                },
                """
                你是银行信贷审批助手。根据后台利率定价测算结果生成 3 行中文解读，供审批人员参考：
                第1行 定价构成概述（各项加点须与数据一致）；第2行 建议利率与政策区间校验结论（突破底线必须如实提示）；
                第3行 让价空间与升级审批提示。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s44() {
        return new SceneDialogSpec(
                "S4-4",
                """
                【S4-4 智能审批（规则+模型融合）】——并行跑名单筛查、规则红线校验、模型评分与授权政策比对，融合形成审批建议并流转审批流，
                典型表述：智能审批、提交审批、审批建议、这笔能过吗等。
                注意：合规红线审查（S4-6）、审批授权路由（S4-5）不属于本场景。""",
                """
                必需要素：无（说明要提交审批即可；授信任务编号可一并说明）。
                缺省演示要素：授信 600 万元 / 利率 3.75% / 12 个月 / 厂房抵押（演示任务 SX20261102-018）。
                用户说明诉求即输出 READY，任务编号未提及的直接用缺省演示口径，不要追问。""",
                "\"taskNo\":\"授信任务编号（可选）\"",
                "（如「命中了什么规则」「流程到哪了」）",
                s -> norm("taskNo", text(s, "taskNo", "SX20261102-018")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode r = ctx.stepData.get("st2");
                    if (r != null && !r.isMissingNode()) {
                        facts.put("规则校验", "结论=" + r.path("conclusion").path("decision").asText("-")
                                + "，命中 " + r.path("hits").size() + " 条");
                    }
                    addBlocks(ctx, facts, "规则校验结果", "模型评分", "审批建议", "审批流状态");
                    return facts;
                },
                """
                你是银行信贷审批助手。根据后台智能审批结果生成 3 行中文解读，供审批人员参考：
                第1行 审批建议概述（结论与授信要素须与数据一致）；第2行 规则命中与模型评分要点（红线触发不得省略）；
                第3行 人工复核与流转提示（最终意见由审批人作出）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s45() {
        return new SceneDialogSpec(
                "S4-5",
                """
                【S4-5 审批授权路由】——按金额、风险等级与品种自动判定审批层级并路由到审批人，权限外业务自动升级，
                典型表述：审批层级、这笔谁批、授权路由、改派审批人等。
                注意：智能审批结论（S4-4）、审批任务督办（S9 族）不属于本场景。""",
                """
                必需要素：无（说明要做审批路由即可；授信任务编号可一并说明）。
                缺省演示要素：授信 600 万元落支行权限档（≤1000 万）、主审支行审批人张审、时限 1 个工作日。
                用户说明诉求即输出 READY，任务编号与审批人未提及的一律用缺省演示口径，不要追问。""",
                "\"taskNo\":\"授信任务编号（可选）\",\"approver\":\"审批人（可选）\"",
                "（如「为什么是这个层级」「怎么改派」）",
                s -> norm("taskNo", text(s, "taskNo", "SX20261102-018"),
                        "approver", text(s, "approver", "支行审批人张审")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode a = ctx.stepData.get("st2");
                    if (a != null && !a.isMissingNode()) {
                        facts.put("审批人核验", "authId=" + a.path("authId").asText("-")
                                + "，风险等级=" + a.path("riskLevel").asText("-"));
                    }
                    addBlocks(ctx, facts, "路由依据", "路由结果", "权限校验");
                    return facts;
                },
                """
                你是银行信贷审批助手。根据后台授权路由结果生成 3 行中文解读，供审批主管参考：
                第1行 层级判定与路由结果（层级、审批人、时限须与数据一致）；第2行 权限与回避校验要点；
                第3行 督办与改派提示。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s46() {
        return new SceneDialogSpec(
                "S4-6",
                """
                【S4-6 合规审查（红线管控）】——授信审查环节按红线规则逐项审查禁入行业、限贷、多头授信与用途合规，触发项列明条款出处，整改或拦截，
                典型表述：合规审查、合规检查、这笔合规吗等——凡用户说「合规审查」「合规检查」一律选本场景。
                注意：名单筛查（S3-6）、产品合规校验（S4-8）不属于本场景；
                仅当用户明确说「踩红线」「红线检查」「例外审批」等存续业务检查表述时才选 S12-2。""",
                """
                必需要素：无（说明要做合规审查即可；客户名称或任务编号可一并说明）。
                缺省演示要素：客户「北京华辰机械制造有限公司」（制造业、购原材料用途、他行授信 180 万）。
                用户说明诉求即输出 READY，客户未指明时直接用缺省演示口径，不要追问。""",
                "\"customer\":\"客户名称（可选）\",\"taskNo\":\"任务编号（可选）\"",
                "（如「红线在哪一条」「怎么整改」）",
                s -> norm("customer", text(s, "customer", "北京华辰机械制造有限公司"),
                        "taskNo", text(s, "taskNo", "SX20261102-018")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "北京华辰机械制造有限公司"));
                    JsonNode r = ctx.stepData.get("st3");
                    if (r != null && !r.isMissingNode()) {
                        facts.put("规则校验", "结论=" + r.path("conclusion").path("decision").asText("-")
                                + "，命中 " + r.path("hits").size() + " 条");
                    }
                    addBlocks(ctx, facts, "审查项清单", "审查结论", "条款依据");
                    return facts;
                },
                """
                你是银行信贷合规助手。根据后台合规审查结果生成 3 行中文解读，供合规人员参考：
                第1行 审查结论概述（通过/红线触发条数须与数据一致）；第2行 审查项要点与条款出处（触发项不得省略）；
                第3行 整改或流转建议。
                注意：「通过 0 条」与结论 REJECT 是同一事实的两种表述，不构成数据矛盾，不得提示「结论不一致」。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s47() {
        return new SceneDialogSpec(
                "S4-7",
                """
                【S4-7 关联交易识别与管控（图谱核心）】——从股权、任职、担保、资金往来挖掘关联网络，识别集团与担保圈并校验统一授信占用，
                典型表述：关联交易识别、集团统一授信、隐性关联、关联方管控等。
                注意：尽调环节关联挖掘（S3-4）、额度三口径测算（S4-2）不属于本场景。""",
                """
                必需要素：客户名称。
                缺省演示要素：客户「北京华辰机械制造有限公司」（实控人关联 3 户、担保圈 2 个、本次拟贷 600 万）。
                客户名称齐备即输出 READY；用户未指明客户时直接采用缺省演示客户，不要追问。""",
                "\"customer\":\"客户名称\"",
                "（如「占用率多少」「担保圈要紧吗」）",
                s -> norm("customer", text(s, "customer", "北京华辰机械制造有限公司")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "北京华辰机械制造有限公司"));
                    JsonNode lc = ctx.stepData.get("st5");
                    if (lc != null && !lc.isMissingNode()) {
                        facts.put("集团统一授信", "额度=" + lc.path("limitAmount").asText("-")
                                + "，在用=" + lc.path("usedAmount").asText("-")
                                + "，剩余=" + lc.path("remainAmount").asText("-")
                                + "，管控=" + lc.path("controlStatus").asText("-"));
                    }
                    addBlocks(ctx, facts, "关联网络", "集团统一授信占用", "管控建议");
                    return facts;
                },
                """
                你是银行信贷风控助手。根据后台关联交易识别结果生成 3 行中文解读，供风控人员参考：
                第1行 关联网络概述（户数与担保圈数须与数据一致）；第2行 统一授信占用与担保圈风险（占用率、圈内逾期须如实）；
                第3行 管控建议（隐性关联须人工确认后生效）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s48() {
        return new SceneDialogSpec(
                "S4-8",
                """
                【S4-8 贷款产品创设与参数配置】——检索制度与在架产品、设计产品要素草案、试算定价、做合规校验并提交授权发布，
                典型表述：创设新产品、设计产品要素、产品参数配置等。
                注意：存量产品定价（S4-3）、产品推荐营销（S1-5）不属于本场景。""",
                """
                必需要素：无（说明要创设产品即可；目标客群与核心诉求可一并说明）。
                缺省演示要素：产品「科创小微信用贷」（准入成立满 2 年＋纳税 B 级、额度 10～300 万、12 个月、先息后本、纯信用）。
                用户说明诉求即输出 READY，客群诉求未提及的直接用缺省演示口径，不要追问。""",
                "\"requirement\":\"目标客群与核心诉求（可选）\"",
                "（如「定价怎么算的」「要报备吗」）",
                s -> norm("requirement", text(s, "requirement", "面向科创小微企业纯信用线上产品")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode q = ctx.stepData.get("st4");
                    if (q != null && !q.isMissingNode()) {
                        facts.put("定价试算", "基准=" + q.path("baseRate").asText("-")
                                + "，风险溢价=" + q.path("riskPremium").asText("-")
                                + "，综合利率=" + q.path("finalRate").asText("-"));
                    }
                    addBlocks(ctx, facts, "产品要素草案", "定价试算", "合规校验结论");
                    return facts;
                },
                """
                你是银行信贷产品助手。根据后台产品创设与校验结果生成 3 行中文解读，供产品经理参考：
                第1行 要素草案概述（准入、额度、期限须与数据一致）；第2行 定价与合规校验结论（利率底线不得美化）；
                第3行 授权发布与报备提示（参数不直接生效）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s49() {
        return new SceneDialogSpec(
                "S4-9",
                """
                【S4-9 投贷联动与认股选择权管理】——为科创企业设计利率让价换认股选择权的安排，含对价定价、协议方案与投委会授权，
                典型表述：投贷联动、认股选择权、利率换期权、行权安排等。
                注意：普通利率定价（S4-3）、科创产品创设（S4-8）不属于本场景。""",
                """
                必需要素：无（说明要做投贷联动安排即可；科创企业名称可一并说明）。
                缺省演示要素：企业「华辰半导体设备（半导体设备零部件、成长期）」、让价 30BP 换未来 3 年按估值 1.2 亿认购不超 1% 股权。
                用户说明诉求即输出 READY，企业未指明时直接用缺省演示口径，不要追问。""",
                "\"customer\":\"科创企业名称（可选）\"",
                "（如「对价怎么定的」「行权条件是什么」）",
                s -> norm("customer", text(s, "customer", "华辰半导体设备")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode q = ctx.stepData.get("st2");
                    if (q != null && !q.isMissingNode()) {
                        facts.put("让价后定价", "基准=" + q.path("baseRate").asText("-")
                                + "，综合利率=" + q.path("finalRate").asText("-"));
                    }
                    JsonNode mc = ctx.stepData.get("st5");
                    if (mc != null && !mc.isMissingNode()) {
                        facts.put("组合统计", "测算值=" + mc.path("value").asText("-")
                                + mc.path("unit").asText(""));
                    }
                    addBlocks(ctx, facts, "行业评估", "选择权方案", "合规与组合结论");
                    return facts;
                },
                """
                你是银行投贷联动助手。根据后台选择权方案与校验结果生成 3 行中文解读，供投委会参考：
                第1行 行业评估与让价方案概述（让价幅度、对价、集中度须与数据一致）；第2行 合规与风险隔离要点；
                第3行 投委会授权与客户签署提示。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    // ============ S5 合同签约族 ============

    private static SceneDialogSpec s51() {
        return new SceneDialogSpec(
                "S5-1",
                """
                【S5-1 合同/借据智能生成】——审批通过后按产品模板自动生成借款合同、借据与担保合同并校验要素完整性，
                典型表述：生成借款合同、出借据、起草担保合同等。
                注意：条款合规检查（S5-2）、要素一致性校验（S5-4）、签约执行（S5-3）不属于本场景。""",
                """
                必需要素：无（说明要生成合同即可；客户名称或审批任务编号可一并说明）。
                缺省演示要素：客户「华辰机械」、流贷 600 万元 / 利率 3.75% / 12 个月 / 先息后本 / 厂房抵押（任务 SX20261102-018）。
                用户说明诉求即输出 READY，客户未指明时直接用缺省演示口径，不要追问。""",
                "\"customer\":\"客户名称（可选）\",\"taskNo\":\"审批任务编号（可选）\"",
                "（如「有哪些缺项」「改一下利率条款」）",
                s -> norm("customer", text(s, "customer", "华辰机械"),
                        "taskNo", text(s, "taskNo", "SX20261102-018")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "华辰机械"));
                    facts.put("taskNo", ctx.slots.getOrDefault("taskNo", "SX20261102-018"));
                    addBlocks(ctx, facts, "生成清单", "要素套入核对表", "缺项提示");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台合同生成结果生成 3 行中文解读，供客户经理参考：
                第1行 生成清单概述；第2行 要素与审批结果一致性及缺项提示（缺项不得省略）；
                第3行 后续提示（文本须经条款合规检查与要素一致性校验后方可签署）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s52() {
        return new SceneDialogSpec(
                "S5-2",
                """
                【S5-2 合同条款合规检查】——逐条审查合同必备条款、利率费用表述、违约责任与格式条款公平性并出具修改意见，
                典型表述：审合同、条款审查、检查一下合同条款、看有没有法律风险等。
                注意：合同生成（S5-1）、要素一致性校验（S5-4）不属于本场景。""",
                """
                必需要素：无（说明要审查合同即可；合同编号可一并说明）。
                缺省演示要素：合同「HT20261102-018（借款合同，12 条待审）」。
                用户说明诉求即输出 READY，合同编号未提及的直接用缺省演示口径，不要追问。""",
                "\"contractNo\":\"合同编号（可选）\"",
                "（如「哪几条要改」「为什么要改」）",
                s -> norm("contractNo", text(s, "contractNo", "HT20261102-018")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("contractNo", ctx.slots.getOrDefault("contractNo", "HT20261102-018"));
                    JsonNode r = ctx.stepData.get("st3");
                    if (r != null && !r.isMissingNode()) {
                        facts.put("法规命中", r.path("total").asInt(0) + " 条");
                    }
                    addBlocks(ctx, facts, "审查概览", "问题条款清单", "修改建议对照");
                    return facts;
                },
                """
                你是银行信贷法务助手。根据后台合同条款审查结果生成 3 行中文解读，供法律/客户经理参考：
                第1行 审查概览（条数与通过/建议修改/不合规数须与数据一致）；第2行 问题条款要点（逐条不得省略或淡化）；
                第3行 修改与终审提示（不替代法律部门终审）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s53() {
        return new SceneDialogSpec(
                "S5-3",
                """
                【S5-3 电子签约与意愿核验】——依次完成签署人人证核验、人脸活体、短信意愿确认后电子签章并留存证据，
                典型表述：发起签约、电子签约、人脸核验、意愿核验等。
                注意：合同生成（S5-1）、归档登记（S5-5）不属于本场景。""",
                """
                必需要素：无（说明要发起签约即可；签署人可一并说明）。
                缺省演示要素：签署人「陈志强」（演示手机号 138****5555），借款合同与担保合同两份。
                用户说明诉求即输出 READY，签署人未指明时直接用缺省演示口径，不要追问。""",
                "\"signer\":\"签署人姓名（可选）\"",
                "（如「核验没过怎么办」「证书编号多少」）",
                s -> norm("signer", text(s, "signer", "陈志强")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("signer", ctx.slots.getOrDefault("signer", "陈志强"));
                    JsonNode ocr = ctx.stepData.get("st1");
                    if (ocr != null && !ocr.isMissingNode()) {
                        facts.put("人证核验", "一致=" + ocr.path("match").asText("-"));
                    }
                    addBlocks(ctx, facts, "核验结果", "签署结果", "证据留存清单");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台电子签约执行结果生成 3 行中文解读，供客户经理参考：
                第1行 双因素意愿核验结论（人证/活体/验证码须与数据一致，未通过不得说已签署）；
                第2行 签章结果与证书编号；第3行 证据留存与后续归档提示。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s54() {
        return new SceneDialogSpec(
                "S5-4",
                """
                【S5-4 合同要素一致性校验】——签署前把合同文本、审批结果、产品模板三方要素逐项核对，不一致即拦截并定位差异，
                典型表述：一致性校验、签前核对、合同和审批对一遍等。
                注意：条款合规检查（S5-2）、合同生成（S5-1）不属于本场景。""",
                """
                必需要素：无（说明要校验即可；合同编号可一并说明）。
                缺省演示要素：合同「HT20261102-018」（600 万 / 3.75% / 12 个月 / 先息后本 / 厂房抵押）。
                用户说明诉求即输出 READY，合同编号未提及的直接用缺省演示口径，不要追问。""",
                "\"contractNo\":\"合同编号（可选）\"",
                "（如「差在哪」「能签了吗」）",
                s -> norm("contractNo", text(s, "contractNo", "HT20261102-018")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("contractNo", ctx.slots.getOrDefault("contractNo", "HT20261102-018"));
                    String v = validateSummary(ctx.stepData.get("st4"));
                    if (v != null) facts.put("三方交叉核对", v);
                    addBlocks(ctx, facts, "三方核对总表", "借据核对", "结论");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台要素一致性校验结果生成 3 行中文解读，供客户经理参考：
                第1行 三方核对结论（一致/差异须与数据完全一致，差异不得说可签）；第2行 差异项与拦截说明；
                第3行 处理路径提示（差异回退重新生成，无差异方可签署）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s55() {
        return new SceneDialogSpec(
                "S5-5",
                """
                【S5-5 合同归档与登记】——归集已签合同电子件与纸质扫描件、校验完整性并登记合同台账，
                典型表述：合同归档、登记合同台账、归档登记等。
                注意：全流程贷款档案立卷（S5-6）、放款条件检查（S6-1）不属于本场景。""",
                """
                必需要素：无（说明要归档即可；合同编号或资料可直接说明）。
                缺省演示要素：合同「HT20261102-018」（华辰机械 / 600 万 / 12 个月 / 2026-11-02 签署）。
                用户说明诉求即输出 READY，合同未指明时直接用缺省演示口径，不要追问。""",
                "\"contractNo\":\"合同编号（可选）\"",
                "（如「担保登记齐了吗」「台账登了吗」）",
                s -> norm("contractNo", text(s, "contractNo", "HT20261102-018")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("contractNo", ctx.slots.getOrDefault("contractNo", "HT20261102-018"));
                    addBlocks(ctx, facts, "归档清单", "台账登记", "完整性提示");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台归档登记结果生成 3 行中文解读，供客户经理参考：
                第1行 归档与台账登记结论；第2行 完整性提示（担保登记待办等缺口不得省略）；
                第3行 后续衔接提示（放款由放款条件检查环节承接）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s56() {
        return new SceneDialogSpec(
                "S5-6",
                """
                【S5-6 贷款档案全流程归档】——把申请、尽调、审批、合同、放款各环节资料自动归集立卷、校验缺件并督办补齐，
                典型表述：贷款档案、立卷归档、档案卷、查缺件等。
                注意：单笔合同归档登记（S5-5）、贷后资料调阅（S7 族）不属于本场景。""",
                """
                必需要素：无（说明要立卷归档即可；客户名称或任务编号可一并说明）。
                缺省演示要素：档案「SX20261102-018 全流程档案卷（12 项归档、1 项缺件）」。
                用户说明诉求即输出 READY，客户未指明时直接用缺省演示口径，不要追问。""",
                "\"customer\":\"客户名称（可选）\",\"taskNo\":\"任务编号（可选）\"",
                "（如「缺什么件」「卷号多少」）",
                s -> norm("customer", text(s, "customer", "华辰机械"),
                        "taskNo", text(s, "taskNo", "SX20261102-018")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "华辰机械"));
                    facts.put("taskNo", ctx.slots.getOrDefault("taskNo", "SX20261102-018"));
                    addBlocks(ctx, facts, "立卷清单", "缺件清单", "立卷结果");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台档案立卷结果生成 3 行中文解读，供档案管理员参考：
                第1行 立卷结果与卷号（数字须与数据一致）；第2行 缺件清单与督办时限（缺件不得省略，超期限制须提示）；
                第3行 补件与调阅建议。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    // ============ S6 放款用信族（S6-1/S6-2 已接入，此处 S6-3~S6-13） ============

    private static SceneDialogSpec s63() {
        return new SceneDialogSpec(
                "S6-3",
                """
                【S6-3 放款记账与凭证】——放款条件与还款计划确认后，授权校验并调用核心系统放款记账，生成借据号与凭证号，
                典型表述：执行放款、放款记账、出放款凭证等。
                注意：放款条件检查（S6-1）、还款计划（S6-2）、额度占用（S6-4）不属于本场景。""",
                """
                必需要素：用信申请编号（或客户名称）。
                缺省演示要素：用信申请「YS20261028013」（京南机电 500 万元 / 年化 3.45% / 12 个月 / 放款账户合同约定账户）。
                编号齐备即输出 READY；用户未给出编号时直接用缺省演示口径，不要追问。
                记账一经确认不可撤回，确认引导中须提示核对要素。""",
                "\"applyNo\":\"用信申请编号\",\"customer\":\"客户名称\"",
                "（如「凭证号多少」「资金到了吗」）",
                s -> norm("applyNo", text(s, "applyNo", "YS20261028013"),
                        "customer", text(s, "customer", "京南机电制造有限公司")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("applyNo", ctx.slots.getOrDefault("applyNo", "YS20261028013"));
                    facts.put("customer", ctx.slots.getOrDefault("customer", "京南机电制造有限公司"));
                    JsonNode auth = ctx.stepData.get("st1");
                    if (auth != null && !auth.isMissingNode()) {
                        facts.put("授权校验", "authId=" + auth.path("authId").asText("-")
                                + "，风险等级=" + auth.path("riskLevel").asText("-"));
                    }
                    addBlocks(ctx, facts, "放款要素核对表", "记账结果", "异常提示");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台放款记账结果生成 3 行中文解读，供放款岗参考：
                第1行 记账结果概述（借据号、凭证号、到账状态须与数据一致）；第2行 要素与已确认计划一致性提示；
                第3行 风控提示（记账不可撤回、失败走冲正补偿禁止重复放款）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s64() {
        return new SceneDialogSpec(
                "S6-4",
                """
                【S6-4 额度占用联动】——放款扣减与回款释放联动核心额度台账，可查询占用情况并双人复核变更，
                典型表述：额度占用、额度扣减、额度释放、额度使用情况等。
                注意：台账检查、台账核对、额度使用排查类诉求不属于本场景（本场景处理占用/释放变动），应路由 S6-6；
                三口径额度测算（S4-2）、集团统一授信占用（S4-7）也不属于本场景。""",
                """
                必需要素：无（说明要查/扣/释放额度即可；客户名称或授信合同编号可一并说明）。
                缺省演示要素：客户「京南机电制造有限公司」（授信 1000 万、本次放款扣减 500 万、可用 400 万）。
                用户说明诉求即输出 READY，客户未指明时直接用缺省演示口径，不要追问。""",
                "\"customer\":\"客户名称（可选）\",\"amount\":金额元数字（可选）",
                "（如「还剩多少额度」「回款释放了吗」）",
                s -> norm("customer", text(s, "customer", "京南机电制造有限公司"),
                        "amount", amountOr(s, "amount", 5000000L)),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "京南机电制造有限公司"));
                    JsonNode lc = ctx.stepData.get("st1");
                    if (lc != null && !lc.isMissingNode()) {
                        facts.put("额度测算", "占用后余额=" + lc.path("remainAmount").asText("-")
                                + "，控制状态=" + lc.path("controlStatus").asText("-"));
                    }
                    addBlocks(ctx, facts, "额度台账", "变动明细", "预警提示");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台额度联动结果生成 3 行中文解读，供客户经理参考：
                第1行 额度台账概述（总额/已用/可用数字须与数据一致）；第2行 本次变动明细；
                第3行 预警与复核提示（超限预警须如实提示）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s65() {
        return new SceneDialogSpec(
                "S6-5",
                """
                【S6-5 放款通知与回执】——放款完成后生成通知文案、人工确认后按渠道发送并跟踪回执、生成咨询应答口径，
                典型表述：放款通知、通知客户、贷款到账通知等。
                注意：放款记账（S6-3）、还款计划（S6-2）不属于本场景。""",
                """
                必需要素：无（说明要发放款通知即可；客户名称或借据号可一并说明）。
                缺省演示要素：客户「京南机电制造有限公司」、贷款 500 万元到账（年利率 3.45%，首期还款日 2026-12-20）。
                用户说明诉求即输出 READY，客户未指明时直接用缺省演示口径，不要追问。""",
                "\"customer\":\"客户名称（可选）\",\"applyNo\":\"借据号或申请编号（可选）\"",
                "（如「回执确认了吗」「客户咨询怎么答」）",
                s -> norm("customer", text(s, "customer", "京南机电制造有限公司"),
                        "applyNo", text(s, "applyNo", "YS20261028013")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "京南机电制造有限公司"));
                    JsonNode rag = ctx.stepData.get("st5");
                    if (rag != null && !rag.isMissingNode()) {
                        facts.put("咨询应答口径", "answerId=" + rag.path("answerId").asText("-")
                                + "，忠实度=" + rag.path("quality").path("faithfulness").asText("-"));
                    }
                    addBlocks(ctx, facts, "通知文案预览", "发送结果", "客户咨询应答口径");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台放款通知执行结果生成 3 行中文解读，供客户经理参考：
                第1行 通知发送结论（渠道与回执须与数据一致）；第2行 文案要点（不得出现利率以外的承诺性表述）；
                第3行 客户咨询应答提示（到期还款与提前还款口径须与数据一致）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s66() {
        return new SceneDialogSpec(
                "S6-6",
                """
                【S6-6 到期用信排查与额度使用监控】——排查到期未用信客户、统计额度使用率并生成到期提醒任务，
                典型表述：额度台账检查、台账检查、台账核对、额度台账排查、到期未用信、用信排查、额度使用率、用信监控等。
                注意：额度占用扣减/释放等变动联动（S6-4）、贷后检查（S7-1）不属于本场景。""",
                """
                必需要素：无（说明要排查即可；排查范围可一并说明，如「我们支行未来 30 天到期」）。
                缺省演示要素：范围「本支行未来 30 天」（使用率 65.6%，到期未用信 2 户：宏达建材 200 万、恒通物流 150 万）。
                用户说明诉求即输出 READY，范围未提及的直接用缺省演示口径，不要追问。""",
                "\"scope\":\"排查范围（可选）\"",
                "（如「哪几户没提款」「导出名单」）",
                s -> norm("scope", text(s, "scope", "本支行未来 30 天到期")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("scope", ctx.slots.getOrDefault("scope", "本支行未来 30 天到期"));
                    putNum(facts, "贷款余额指标值", ctx.stepData.get("st2"), "value");
                    addBlocks(ctx, facts, "排查总览", "到期未用信名单", "预警提示");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台用信排查结果生成 3 行中文解读，供客户经理参考：
                第1行 排查总览（使用率与到期笔数须与数据一致）；第2行 到期未用信名单（逐户金额与剩余天数不得省略）；
                第3行 提醒与处置建议（处置由客户经理人工跟进）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s67() {
        return new SceneDialogSpec(
                "S6-7",
                """
                【S6-7 限时抵押落实督办】——跟踪押品登记落实进度、生成督办工单与到期提醒、复核押品覆盖率，
                典型表述：抵押督办、抵押落实、限时抵押、落实中押品等。
                注意：押品评估（S3-10）、贷后押品重估（S7-4）不属于本场景。""",
                """
                必需要素：无（说明要督办即可；机构范围或客户名称可一并说明）。
                缺省演示要素：在途押品 8 笔（即将到期 2 笔：宏达建材厂房剩余 8 天、恒通物流车辆剩余 6 天）。
                用户说明诉求即输出 READY，范围未提及的直接用缺省演示口径，不要追问。""",
                "\"scope\":\"机构范围或客户名称（可选）\"",
                "（如「覆盖率够吗」「还有几笔在途」）",
                s -> norm("scope", text(s, "scope", "本机构在途押品")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("scope", ctx.slots.getOrDefault("scope", "本机构在途押品"));
                    JsonNode val = ctx.stepData.get("st2");
                    if (val != null && !val.isMissingNode()) {
                        facts.put("押品估值复核", "估值=" + val.path("value").asText("-")
                                + "，结论=" + val.path("conclusion").asText("-"));
                    }
                    addBlocks(ctx, facts, "督办总览", "督办清单", "覆盖率复核结果");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台抵押督办结果生成 3 行中文解读，供客户经理参考：
                第1行 督办总览（在途/即将到期/逾期笔数须与数据一致）；第2行 督办清单与时限（剩余天数不得改动）；
                第3行 覆盖率结论与升级提示（逾期处置由信贷管理部门人工决策）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s68() {
        return new SceneDialogSpec(
                "S6-8",
                """
                【S6-8 受托支付审核与资金用途管控】——判断受托支付门槛、识别交易背景、核对资金用途并执行直付交易对手，
                典型表述：受托支付、支付对象审核、资金用途管控、直付等。
                注意：放款记账（S6-3）、贸易融资（S6-9）不属于本场景。""",
                """
                必需要素：客户名称与支付金额。
                缺省演示要素：客户「京南机电制造有限公司」、支付 480 万元（交易对手恒信钢材有限公司、热轧钢板 480 万购销合同）。
                客户名称与金额齐备即输出 READY；其余要素未提及的一律用缺省演示要素补齐，不要追问。
                审核疑点须提示转人工，不得自动放款到借款人账户。""",
                "\"customer\":\"客户名称\",\"amount\":支付金额元数字",
                "（如「资料齐了吗」「直付了吗」）",
                s -> norm("customer", text(s, "customer", "京南机电制造有限公司"),
                        "amount", amountOr(s, "amount", 4800000L)),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "京南机电制造有限公司"));
                    facts.put("amount", ctx.slots.getOrDefault("amount", 4800000L));
                    addBlocks(ctx, facts, "门槛判断结果", "交易背景识别", "用途核对结论");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台受托支付审核结果生成 3 行中文解读，供审批人员参考：
                第1行 门槛判断结论（强制门槛与合同约定须与数据一致）；第2行 交易背景与用途核对结论（疑点不得省略）；
                第3行 直付要求提示（直付交易对手账户，不得过借款人自有账户）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s69() {
        return new SceneDialogSpec(
                "S6-9",
                """
                【S6-9 贸易融资与应收账款池融资】——审核贸易背景、核查行业账期与关联关系并按折扣率测算融资额度，
                典型表述：应收账款融资、池融资、仓单融资、贸易融资等。
                注意：供应链金融评估（S3-9）、票据贴现（S6-10）不属于本场景。""",
                """
                必需要素：客户名称（融资主体）；融资品种可缺省。
                缺省演示要素：融资主体「京南机电制造有限公司」、品种「应收账款融资」（付款方恒通集团、600 万元、账期 90 天、折扣率 0.8）。
                客户名称齐备即输出 READY；其余要素未提及的一律用缺省演示要素补齐，不要追问。""",
                "\"customer\":\"融资主体名称\",\"bizType\":\"融资品种（可选）\"",
                "（如「额度算出来多少」「账期正常吗」）",
                s -> norm("customer", text(s, "customer", "京南机电制造有限公司"),
                        "bizType", text(s, "bizType", "应收账款融资")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "京南机电制造有限公司"));
                    facts.put("bizType", ctx.slots.getOrDefault("bizType", "应收账款融资"));
                    JsonNode lc = ctx.stepData.get("st6");
                    if (lc != null && !lc.isMissingNode()) {
                        facts.put("额度测算", "可融=" + lc.path("limitAmount").asText("-")
                                + "，控制状态=" + lc.path("controlStatus").asText("-"));
                    }
                    addBlocks(ctx, facts, "贸易背景识别", "关联核查结论", "额度测算结果");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台贸易融资审核结果生成 3 行中文解读，供客户经理参考：
                第1行 贸易背景与账期结论（账期与行业惯例数字须与数据一致）；第2行 关联核查结论（非关联/关联须如实）；
                第3行 额度测算与确权材料提示。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s610() {
        return new SceneDialogSpec(
                "S6-10",
                """
                【S6-10 票据承兑与贴现业务】——识别票据要素、筛查承兑人名单、查询市场利率并测算贴现利率与贴现息，
                典型表述：票据贴现、承兑汇票、贴现利率等。
                注意：贸易融资（S6-9）、利率定价（S4-3）不属于本场景。""",
                """
                必需要素：无（说明要贴现即可；客户名称可一并说明）。
                缺省演示要素：银行承兑汇票「BILL20261102001」（票面 100 万元、剩余 60 天、国股大行承兑）。
                用户说明诉求即输出 READY，票据要素未提及的直接用缺省演示口径，不要追问。""",
                "\"customer\":\"客户名称（可选）\",\"billNo\":\"票号（可选）\"",
                "（如「贴现息多少」「名单筛查过了吗」）",
                s -> norm("customer", text(s, "customer", "京南机电制造有限公司"),
                        "billNo", text(s, "billNo", "BILL20261102001")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "京南机电制造有限公司"));
                    String v = validateSummary(ctx.stepData.get("st3"));
                    if (v != null) facts.put("票据要素核对", v);
                    JsonNode q = ctx.stepData.get("st6");
                    if (q != null && !q.isMissingNode()) {
                        facts.put("贴现定价", "执行利率=" + q.path("finalRate").asText("-"));
                    }
                    addBlocks(ctx, facts, "票据要素识别结果", "名单筛查结论", "定价测算");
                    return facts;
                },
                """
                你是银行票据业务助手。根据后台票据审核与定价结果生成 3 行中文解读，供客户经理参考：
                第1行 票据要素概述（票号、金额、剩余期限须与数据一致）；第2行 名单筛查与承兑敞口提示；
                第3行 定价结果（执行利率、贴现息、实付金额须与数据一致，不得自行重算）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s611() {
        return new SceneDialogSpec(
                "S6-11",
                """
                【S6-11 保函与表外承诺业务】——测算保函金额与保证金、生成保函文本、校验合规并走授权签章开立流程，
                典型表述：开保函、履约保函、投标保函、表外承诺等。
                注意：贷款产品定价（S4-3）、跨境业务（S6-13）不属于本场景。""",
                """
                必需要素：保函品种与金额。
                缺省演示要素：品种「履约保函」、金额 200 万元（受益人城建集团、有效期 12 个月、保证金 20%、见索即付）。
                保函品种与金额齐备即输出 READY；受益人等未提及的一律用缺省演示要素补齐，不要追问。""",
                "\"guaranteeType\":\"保函品种\",\"amount\":金额元数字",
                "（如「保证金多少」「文本能改吗」）",
                s -> norm("guaranteeType", text(s, "guaranteeType", "履约保函"),
                        "amount", amountOr(s, "amount", 2000000L)),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("guaranteeType", ctx.slots.getOrDefault("guaranteeType", "履约保函"));
                    facts.put("amount", ctx.slots.getOrDefault("amount", 2000000L));
                    JsonNode lc = ctx.stepData.get("st1");
                    if (lc != null && !lc.isMissingNode()) {
                        facts.put("表外额度测算", "占用后余额=" + lc.path("remainAmount").asText("-")
                                + "，控制状态=" + lc.path("controlStatus").asText("-"));
                    }
                    addBlocks(ctx, facts, "保函要素", "保函文本预览", "授权校验结果");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台保函开立结果生成 3 行中文解读，供客户经理参考：
                第1行 保函要素概述（品种、金额、保证金比例须与数据一致）；第2行 文本与授权校验要点；
                第3行 表外管理提示（不占贷款额度、纳入表外授信、到期前提醒注销）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s612() {
        return new SceneDialogSpec(
                "S6-12",
                """
                【S6-12 委托贷款与同业专项贷】——审查委托资金来源合规性、核对额度、生成三方协议并执行划款放款，
                典型表述：委托贷款、同业专项贷、三方协议等。
                注意：自营贷款放款（S6-3）、跨境业务（S6-13）不属于本场景。""",
                """
                必需要素：委托人名称与借款人名称；金额可缺省。
                缺省演示要素：委托人「京南集团」、借款人「恒通物流」、金额 300 万元（1 年 / 4.2%、自有资金）。
                委托人与借款人齐备即输出 READY；金额等未提及的一律用缺省演示要素补齐，不要追问。
                资金来源合规存疑时须提示转人工，不执行划款。""",
                "\"principal\":\"委托人名称\",\"borrower\":\"借款人名称\",\"amount\":金额元数字（可选）",
                "（如「资金来源查了吗」「协议发了没」）",
                s -> norm("principal", text(s, "principal", "京南集团"),
                        "borrower", text(s, "borrower", "恒通物流"),
                        "amount", amountOr(s, "amount", 3000000L)),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("principal", ctx.slots.getOrDefault("principal", "京南集团"));
                    facts.put("borrower", ctx.slots.getOrDefault("borrower", "恒通物流"));
                    facts.put("amount", ctx.slots.getOrDefault("amount", 3000000L));
                    JsonNode lc = ctx.stepData.get("st3");
                    if (lc != null && !lc.isMissingNode()) {
                        facts.put("额度核对", "控制状态=" + lc.path("controlStatus").asText("-")
                                + "，余额=" + lc.path("remainAmount").asText("-"));
                    }
                    addBlocks(ctx, facts, "合规审查结论", "协议文本预览", "划款要素");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台委托贷款办理结果生成 3 行中文解读，供客户经理参考：
                第1行 资金来源合规结论（依据《商业银行委托贷款管理办法》须与数据一致）；第2行 协议要素概述；
                第3行 受托行职责提示（不垫付资金、不承担信用风险）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s613() {
        return new SceneDialogSpec(
                "S6-13",
                """
                【S6-13 跨境与国际信贷业务】——办理跨境贸易融资放款辅助，含外汇合规校验、反洗钱监测、汇率查询与结售汇测算，
                典型表述：跨境融资、外汇放款、结售汇、国际信贷等。
                注意：贸易融资（S6-9）、委托贷款（S6-12）不属于本场景。""",
                """
                必需要素：客户名称与币种；金额可缺省。
                缺省演示要素：客户「京南机电制造有限公司」、币种「美元」、金额 80 万美元（出口报关、外汇登记已办理、牌价 7.12）。
                客户名称与币种齐备即输出 READY；金额等未提及的一律用缺省演示要素补齐，不要追问。
                汇率以给定牌价快照为准，不得自行估算；反洗钱预警命中须提示转人工。""",
                "\"customer\":\"客户名称\",\"currency\":\"币种\",\"amount\":金额（外币数字，可选）",
                "（如「汇率多少」「合规过了吗」）",
                s -> norm("customer", text(s, "customer", "京南机电制造有限公司"),
                        "currency", text(s, "currency", "美元"),
                        "amount", amountOr(s, "amount", 800000L)),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "京南机电制造有限公司"));
                    facts.put("currency", ctx.slots.getOrDefault("currency", "美元"));
                    facts.put("amount", ctx.slots.getOrDefault("amount", 800000L));
                    addBlocks(ctx, facts, "外汇合规校验结论", "跨境单据识别结果", "汇率与结售汇测算");
                    return facts;
                },
                """
                你是银行国际业务助手。根据后台跨境业务办理结果生成 3 行中文解读，供客户经理参考：
                第1行 外汇合规与单据识别结论（外汇登记须提示人工办理）；第2行 汇率与结售汇测算（牌价与折算金额须与数据一致，不得自行重算）；
                第3行 风控提示（反洗钱预警命中转人工，禁止自动放款）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    // ============ S7 贷后管理族 ============

    private static SceneDialogSpec s71() {
        return new SceneDialogSpec(
                "S7-1",
                """
                【S7-1 贷后检查任务生成与执行】——按风险等级排定检查计划、派发工单、督办逾期并生成检查报告，
                典型表述：排检查、贷后检查、检查任务、检查报告等。
                注意：风险预警（S7-2）、五级分类（S7-3）、材料收集（S7-7）不属于本场景。""",
                """
                必需要素：无（说明要排检查即可；客户名称或检查周期可一并说明）。
                缺省演示要素：客户「恒达商贸有限公司」、本月到期检查 12 笔（已完成 9、逾期未检 3）。
                用户说明诉求即输出 READY，客户未指明时直接用缺省演示口径，不要追问。""",
                "\"customer\":\"客户名称（可选）\",\"period\":\"检查周期（可选）\"",
                "（如「还有几笔没检」「发起分类复评」）",
                s -> norm("customer", text(s, "customer", "恒达商贸有限公司"),
                        "period", text(s, "period", "本月")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "恒达商贸有限公司"));
                    addBlocks(ctx, facts, "检查任务清单", "执行进度", "检查报告摘要", "预警联动提示");
                    return facts;
                },
                """
                你是银行贷后管理助手。根据后台贷后检查结果生成 3 行中文解读，供客户经理参考：
                第1行 检查任务与进度概述（完成/逾期笔数须与数据一致）；第2行 检查报告要点（挪用/分类初判须如实）；
                第3行 督办与联动提示（发现异常转风险预警跟踪）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s72() {
        return new SceneDialogSpec(
                "S7-2",
                """
                【S7-2 风险信号识别与预警】——从流水、舆情、司法、征信、名单五渠道核查风险信号并分级预警、生成处置任务，
                典型表述：风险信号、预警、查一下风险、异常流水等。
                注意：贷后检查（S7-1）、反欺诈评分（S3-7）、全域预警汇总（S7-9）不属于本场景。""",
                """
                必需要素：客户名称。
                缺省演示要素：客户「恒达商贸有限公司」、近 3 个月（演示命中 4 条信号、橙色预警）。
                客户名称齐备即输出 READY；周期未提及的直接用缺省演示口径，不要追问。""",
                "\"customer\":\"客户名称\",\"period\":\"查询周期（可选）\"",
                "（如「什么信号」「要不要上报」）",
                s -> norm("customer", text(s, "customer", "恒达商贸有限公司"),
                        "period", text(s, "period", "近 3 个月")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "恒达商贸有限公司"));
                    facts.put("period", ctx.slots.getOrDefault("period", "近 3 个月"));
                    addBlocks(ctx, facts, "信号总览", "明细信号", "关联传导图谱数据形态", "预警任务与处置选项");
                    return facts;
                },
                """
                你是银行风控助手。根据后台风险信号核查结果生成 3 行中文解读，供管户经理参考：
                第1行 信号总览（命中条数与预警级别须与数据完全一致）；第2行 明细信号要点（流水/舆情/涉诉/担保不得省略）；
                第3行 处置提示（预警确认后可触发分类复评）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的内容。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s73() {
        return new SceneDialogSpec(
                "S7-3",
                """
                【S7-3 五级分类与风险评级】——按《贷款风险分类指引》口径复评分类，给出依据链与迁徙轨迹，
                典型表述：五级分类、分类复评、归哪一类、风险评级、迁徙等。
                注意：反欺诈评分（S3-7）、授信年检（S7-10）、拨备计提（S9 族）不属于本场景。""",
                """
                必需要素：无（说明要做分类复评即可；客户名称或贷款账号可一并说明）。
                缺省演示要素：客户「恒达商贸有限公司」（当前关注类、建议次级类、PD 3.2%）。
                用户说明诉求即输出 READY，客户未指明时直接用缺省演示口径，不要追问。
                分类建议为辅助口径，须提示最终以有权人工认定为准。""",
                "\"customer\":\"客户名称（可选）\",\"loanNo\":\"贷款账号（可选）\"",
                "（如「为什么降级」「迁徙轨迹如何」）",
                s -> norm("customer", text(s, "customer", "恒达商贸有限公司"),
                        "loanNo", text(s, "loanNo", "LN20260901002")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "恒达商贸有限公司"));
                    JsonNode fin = ctx.stepData.get("st4");
                    if (fin != null && !fin.isMissingNode()) {
                        facts.put("财报分析", "analysisNo=" + fin.path("analysisNo").asText("-")
                                + "，舞弊分=" + fin.path("fraudScore").asText("-"));
                    }
                    addBlocks(ctx, facts, "分类结论", "依据链", "迁徙轨迹");
                    return facts;
                },
                """
                你是银行风控助手。根据后台五级分类复评结果生成 3 行中文解读，供风控人员参考：
                第1行 分类结论（当前分类与建议分类须与数据完全一致，不得改向）；第2行 依据链要点（资产负债率、PD 等数字一致）；
                第3行 认定提示（下调须有权人审批，跨级下调加签风险总监）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s74() {
        return new SceneDialogSpec(
                "S7-4",
                """
                【S7-4 押品管理与重估】——按价格指数重估押品、质检影像、测算覆盖率并触发补押预警工单，
                典型表述：押品重估、押品管理、覆盖率、补押等。
                注意：押品评估（S3-10）、抵押落实督办（S6-7）不属于本场景。""",
                """
                必需要素：无（说明要重估即可；客户名称或押品编号可一并说明）。
                缺省演示要素：押品「城东工业园厂房」（重估 132 万元、下降 12%、覆盖率 107% 低于 130% 阈值）。
                用户说明诉求即输出 READY，客户未指明时直接用缺省演示口径，不要追问。""",
                "\"customer\":\"客户名称（可选）\",\"collateral\":\"押品名称（可选）\"",
                "（如「覆盖率够吗」「要不要补押」）",
                s -> norm("customer", text(s, "customer", "恒达商贸有限公司"),
                        "collateral", text(s, "collateral", "城东工业园厂房")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "恒达商贸有限公司"));
                    facts.put("collateral", ctx.slots.getOrDefault("collateral", "城东工业园厂房"));
                    JsonNode val = ctx.stepData.get("st2");
                    if (val != null && !val.isMissingNode()) {
                        facts.put("重估估值", "估值=" + val.path("value").asText("-")
                                + "，结论=" + val.path("conclusion").asText("-"));
                    }
                    JsonNode lc = ctx.stepData.get("st4");
                    if (lc != null && !lc.isMissingNode()) {
                        facts.put("覆盖率测算", "折扣后可贷=" + lc.path("limitAmount").asText("-")
                                + "，控制状态=" + lc.path("controlStatus").asText("-"));
                    }
                    addBlocks(ctx, facts, "押品台账总览", "重估结果", "覆盖率测算", "状态核查与建议");
                    return facts;
                },
                """
                你是银行押品管理助手。根据后台押品重估结果生成 3 行中文解读，供管户经理参考：
                第1行 重估结果（新估值与降幅须与数据一致）；第2行 覆盖率结论（低于阈值必须如实提示，不得淡化）；
                第3行 补押与流程提示（大额差异须评估机构报告，重估双人复核）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s75() {
        return new SceneDialogSpec(
                "S7-5",
                """
                【S7-5 贷后评价（客户/产品/机构）】——按后评价制度算不良率、迁徙率、综合收益率等指标并出评价报告，
                典型表述：贷后评价、后评价、产品表现、资产质量等。
                注意：模型监控（S7-6）、监管报送（S9 族）不属于本场景。""",
                """
                必需要素：无（说明要做后评价即可；评价对象与周期可一并说明，如「做二季度经营贷产品后评价」）。
                缺省演示要素：对象「经营贷产品」、周期「2026 年二季度」（综合评级 B+）。
                用户说明诉求即输出 READY，对象与周期未提及的直接用缺省演示口径，不要追问。""",
                "\"object\":\"评价对象（可选）\",\"period\":\"评价周期（可选）\"",
                "（如「指标怎么样」「改进建议是什么」）",
                s -> norm("object", text(s, "object", "经营贷产品"),
                        "period", text(s, "period", "2026 年二季度")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("object", ctx.slots.getOrDefault("object", "经营贷产品"));
                    facts.put("period", ctx.slots.getOrDefault("period", "2026 年二季度"));
                    addBlocks(ctx, facts, "评价总览", "指标明细", "趋势图表", "结论与建议");
                    return facts;
                },
                """
                你是银行贷后评价助手。根据后台后评价结果生成 3 行中文解读，供管理岗参考：
                第1行 评价总览（对象、周期、评级须与数据一致）；第2行 指标明细（不良率、迁徙率、收益率数字一致）；
                第3行 结论建议（结果为管理参考，不直接决定续贷或奖罚）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s76() {
        return new SceneDialogSpec(
                "S7-6",
                """
                【S7-6 智能体进化（模型监控与迭代）】——监控评分模型 PSI/KS/bad rate 漂移、归因客群结构变化并生成重训建议，
                典型表述：模型监控、模型漂移、评分卡、重训、PSI 等。
                注意：批量评分卡建模（S12 族）、贷后评价（S7-5）不属于本场景。""",
                """
                必需要素：无（说明要看模型监控即可；模型名称与周期可一并说明）。
                缺省演示要素：模型「经营贷评分卡 v3.2」、周期「近 3 月」（PSI 0.28 超 0.25 阈值、显著漂移）。
                用户说明诉求即输出 READY，模型与周期未提及的直接用缺省演示口径，不要追问。
                本场景只监控与建议，不直接下线或切换线上模型，须在回复中提示。""",
                "\"model\":\"模型名称（可选）\",\"period\":\"监控周期（可选）\"",
                "（如「漂移原因是什么」「要重训吗」）",
                s -> norm("model", text(s, "model", "经营贷评分卡 v3.2"),
                        "period", text(s, "period", "近 3 月")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("model", ctx.slots.getOrDefault("model", "经营贷评分卡 v3.2"));
                    facts.put("period", ctx.slots.getOrDefault("period", "近 3 月"));
                    addBlocks(ctx, facts, "监控总览", "指标明细", "漂移归因", "迭代建议");
                    return facts;
                },
                """
                你是银行模型管理助手。根据后台模型监控结果生成 3 行中文解读，供模型管理岗参考：
                第1行 监控总览（PSI 与阈值判定须与数据完全一致）；第2行 指标与归因要点（客群迁移归因不得省略）；
                第3行 迭代提示（重训须经审批，本场景不直接切换线上模型）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s77() {
        return new SceneDialogSpec(
                "S7-7",
                """
                【S7-7 贷后资料自动收集（15 日用信材料）】——接收放款后 15 日内补交的用途证明材料，识别抽取要素并与放款要素交叉校验核销，
                典型表述：用信材料、材料收集、催材料、用途证明、发票等。
                注意：受托支付审核（S6-8）、风险预警（S7-2）不属于本场景。""",
                """
                必需要素：无（说明要收集/核验材料即可；客户名称或借据号可一并说明）。
                缺省演示要素：客户「恒达建材商行」（受托支付 96 万元，发票 2 张＋合同 1 张，演示已收齐）。
                用户说明诉求即输出 READY，客户未指明时直接用缺省演示口径，不要追问。
                用户在对话中发送发票/合同图片或文件时按其口径收集。""",
                "\"customer\":\"客户名称（可选）\",\"loanNo\":\"借据号（可选）\"",
                "（如「材料齐了吗」「哪户超期了」）",
                s -> norm("customer", text(s, "customer", "恒达建材商行"),
                        "loanNo", text(s, "loanNo", "LN20260901002")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "恒达建材商行"));
                    String v = validateSummary(ctx.stepData.get("st7"));
                    if (v != null) facts.put("用途交叉校验", v);
                    addBlocks(ctx, facts, "材料清单核销状态", "识别结果", "用途校验结论", "后续引导");
                    return facts;
                },
                """
                你是银行贷后管理助手。根据后台用信材料核验结果生成 3 行中文解读，供客户经理参考：
                第1行 材料收集与核销状态（应交/已交/缺项须与数据一致）；第2行 识别与用途校验结论（金额与支付对象一致性须如实）；
                第3行 缺项催收或转预警提示。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s78() {
        return new SceneDialogSpec(
                "S7-8",
                """
                【S7-8 到期转贷代办】——识别到期贷款、判定无还本续贷资格、测算续贷方案并双向确认后执行转贷，
                典型表述：无还本续贷、续贷、转贷、到期提醒等。
                注意：还款计划（S6-2）、正常还款（S8-1）、授信年检（S7-10）不属于本场景。""",
                """
                必需要素：无（说明要办理续贷即可；贷款账号或续贷意向可一并说明）。
                缺省演示要素：客户「万科供应链公司」（余额 86 万元、2026-12-02 到期、年利率 3.45%、月供利息约 2470 元）。
                用户说明诉求即输出 READY，客户未指明时直接用缺省演示口径，不要追问。
                不符合条件的客户只提示正常还款安排，不得给出续贷方案。""",
                "\"customer\":\"客户名称（可选）\",\"loanNo\":\"贷款账号（可选）\"",
                "（如「能续贷吗」「月供多少」）",
                s -> norm("customer", text(s, "customer", "万科供应链公司"),
                        "loanNo", text(s, "loanNo", "LN-S7-8-001")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "万科供应链公司"));
                    JsonNode q = ctx.stepData.get("st5");
                    if (q != null && !q.isMissingNode()) {
                        facts.put("续贷定价", "执行利率=" + q.path("finalRate").asText("-"));
                    }
                    addBlocks(ctx, facts, "到期信息", "续贷资格初判", "转贷方案");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台续贷测算结果生成 3 行中文解读，供客户经理参考：
                第1行 到期信息概述（余额、到期日、剩余天数须与数据一致）；第2行 资格初判与方案要素（利率、月供须与数据一致）；
                第3行 办理提示（客户与管户经理双向确认，超时视为放弃）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s79() {
        return new SceneDialogSpec(
                "S7-9",
                """
                【S7-9 全域风险预警与统一处置】——汇聚全域预警信号、挖掘关联传导、统一生成分级处置工单并按授权派发，
                典型表述：全域风险、全行预警、风险汇总、统一处置等。
                注意：单客户风险预警（S7-2）、模型监控（S7-6）不属于本场景。""",
                """
                必需要素：无（说明要汇总处置即可；范围与周期可一并说明，如「本周全行橙色以上」）。
                缺省演示要素：范围「全行」、周期「本周」（橙红信号 37 条、处置工单 12 张）。
                用户说明诉求即输出 READY，范围与周期未提及的直接用缺省演示口径，不要追问。""",
                "\"scope\":\"风险范围（可选）\",\"period\":\"统计周期（可选）\"",
                "（如「处置到哪一步」「担保圈多大」）",
                s -> norm("scope", text(s, "scope", "全行"),
                        "period", text(s, "period", "本周")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("scope", ctx.slots.getOrDefault("scope", "全行"));
                    facts.put("period", ctx.slots.getOrDefault("period", "本周"));
                    addBlocks(ctx, facts, "全域风险总览", "关联传导图谱数据形态", "处置工单清单", "处置确认");
                    return facts;
                },
                """
                你是银行风险管理助手。根据后台全域风险处置结果生成 3 行中文解读，供风险总监参考：
                第1行 全域总览（信号条数、机构分布须与数据一致）；第2行 传导与担保圈要点（敞口金额不得省略）；
                第3行 处置派发提示（按授权层级派发，越权拦截）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s710() {
        return new SceneDialogSpec(
                "S7-10",
                """
                【S7-10 授信年检与额度重检】——年检时重新查征信、复评信用等级、引用押品重估并重算额度，给出年检结论并流转审批，
                典型表述：授信年检、年检、额度重检、额度调整等。
                注意：续贷办理（S7-8）、额度测算（S4-2）不属于本场景。""",
                """
                必需要素：客户名称。
                缺省演示要素：客户「恒达商贸有限公司」（原额度 200 万元、年检结论维持原额度）。
                客户名称齐备即输出 READY；用户未指明客户时直接采用缺省演示客户，不要追问。
                年检超期未完成会自动暂停新增用信权限，须在确认引导中提示。""",
                "\"customer\":\"客户名称\"",
                "（如「年检过了吗」「额度调了吗」）",
                s -> norm("customer", text(s, "customer", "恒达商贸有限公司")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "恒达商贸有限公司"));
                    JsonNode lc = ctx.stepData.get("st6");
                    if (lc != null && !lc.isMissingNode()) {
                        facts.put("额度重算", "建议额度=" + lc.path("limitAmount").asText("-")
                                + "，控制状态=" + lc.path("controlStatus").asText("-"));
                    }
                    addBlocks(ctx, facts, "年检总览", "复评依据", "额度重测算");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台授信年检结果生成 3 行中文解读，供客户经理参考：
                第1行 年检结论（维持/调增/调减须与数据一致）；第2行 复评依据要点（征信、等级、押品降幅如实）；
                第3行 审批与超期提示（超期未检暂停新增用信）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    // ============ S8 还款催收族（S8-1 已接入，此处 S8-2~S8-11） ============

    private static SceneDialogSpec s82() {
        return new SceneDialogSpec(
                "S8-2",
                """
                【S8-2 提前还款重算】——按日计息重算、校验最低提前还款额、重算新还款计划并核算违约金，审批确认后办理，
                典型表述：提前还款、部分还款、一次性结清、提前结清、违约金等。
                注意：正常还款入账（S8-1）、利率调整重算（S8-3）、续贷（S7-8）不属于本场景。""",
                """
                必需要素：借据号，或（剩余本金+提前还款金额）。
                缺省演示要素：借据「J20250612023」（剩余本金 35 万元、提前还 10 万元、减少月供版、违约金按提前还本金 1%）。
                用户说明诉求即输出 READY；借据与金额未提及的一律用缺省演示要素补齐，不要追问。
                重算结果须经有权人审批后才允许实际扣款，确认引导中须提示。""",
                "\"loanNo\":\"借据号（可选）\",\"prepayAmount\":提前还款金额元数字（可选）,\"mode\":\"减少月供|缩短期限（可选）\"",
                "（如「违约金多少」「新月供多少」）",
                s -> norm("loanNo", text(s, "loanNo", "J20250612023"),
                        "prepayAmount", amountOr(s, "prepayAmount", 100000L),
                        "mode", text(s, "mode", "减少月供")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode pr = ctx.stepData.get("st1");
                    putNum(facts, "执行利率", pr, "finalRate");
                    JsonNode pl = ctx.stepData.get("st3");
                    putNum(facts, "新月供", pl, "monthlyPayment");
                    addBlocks(ctx, facts, "提前还款测算单", "还款后计划");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台提前还款重算结果生成 3 行中文解读，供客户经理参考：
                第1行 测算概述（提前还款金额、占用利息、违约金须与数据一致）；第2行 新计划要点（新月供或新期限须与数据一致）；
                第3行 办理提示（审批通过后衔接扣款入账）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造或重算数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s83() {
        return new SceneDialogSpec(
                "S8-3",
                """
                【S8-3 利率调整重算】——查询最新 LPR、校验新利率合规、批量重算存量浮动利率贷款计划并抽样勾稽后发布通知，
                典型表述：利率调整、LPR 重定价、存量房贷重算、浮动利率重算等。
                注意：单笔定价（S4-3）、提前还款（S8-2）不属于本场景。""",
                """
                必需要素：无（说明要重定价即可；调整依据与适用范围可一并说明）。
                缺省演示要素：依据「2026 年 12 月 LPR（1 年期 3.0%、5 年期以上 3.85%）」、范围「全部存量浮动利率房贷」、重定价日 2027-01-01。
                用户说明诉求即输出 READY，依据与范围未提及的直接用缺省演示口径，不要追问。""",
                "\"adjustBasis\":\"调整依据（可选）\",\"scope\":\"适用范围（可选）\"",
                "（如「新月供多少」「抽样结果如何」）",
                s -> norm("adjustBasis", text(s, "adjustBasis", "2026 年 12 月 LPR 调整"),
                        "scope", text(s, "scope", "全部存量浮动利率房贷")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode p = ctx.stepData.get("st2");
                    putNum(facts, "新执行利率", p, "finalRate");
                    JsonNode pl = ctx.stepData.get("st3");
                    putNum(facts, "样本借据新月供", pl, "monthlyPayment");
                    addBlocks(ctx, facts, "重算总览", "抽样明细", "通知预览");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台利率重算结果生成 3 行中文解读，供计财人员参考：
                第1行 重算总览（新利率口径与重定价日须与数据一致）；第2行 抽样与勾稽结论（勾稽不平借据须提示挂起）；
                第3行 通知发布提示（展示新旧月供对照，固定利率已剔除）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造或重算数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s84() {
        return new SceneDialogSpec(
                "S8-4",
                """
                【S8-4 展期/转贷】——生成展期或无还本续贷方案、重算计划、走审批流并生成协议走电子签约，
                典型表述：贷款展期、展期、无还本续贷方案、转贷方案等。
                注意：到期续贷办理（S7-8）、风险处置（S8-9）不属于本场景。""",
                """
                必需要素：借据号；展期期限与困难原因可缺省。
                缺省演示要素：借据「J20250610015」（1000 万元、展期 6 个月至 2027-06-10、利率维持 3.85%、期内按期付息到期还本）。
                用户说明诉求即输出 READY；借据与期限未提及的一律用缺省演示要素补齐，不要追问。
                已逾期或分类次级以下客户不受理展期，须提示先转风险处置。""",
                "\"loanNo\":\"借据号（可选）\",\"extendMonths\":展期月数（可选）,\"reason\":\"困难原因（可选）\"",
                "（如「月息多少」「审批到哪了」）",
                s -> norm("loanNo", text(s, "loanNo", "J20250610015"),
                        "extendMonths", intOr(s, "extendMonths", 6),
                        "reason", text(s, "reason", "流动资金周转困难")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode p = ctx.stepData.get("st2");
                    if (p != null && !p.isMissingNode()) {
                        facts.put("展期后计划", "按期付息月息=" + p.path("monthlyPayment").asText("-")
                                + "，利息合计=" + p.path("totalInterest").asText("-"));
                    }
                    addBlocks(ctx, facts, "方案摘要", "审批进度", "协议预览");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台展期方案与审批结果生成 3 行中文解读，供客户经理参考：
                第1行 方案概述（展期期限、利率、到期日须与数据一致）；第2行 审批进度提示（终审通过才生效）；
                第3行 协议与签约提示（电子签约意愿核验衔接）。不得受理口径外的客户。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s85() {
        return new SceneDialogSpec(
                "S8-5",
                """
                【S8-5 逾期识别与智能催收】——逾期客户身份归并、按 M1/M2/M3 分级匹配策略、检索合规话术、经催收主管确认后触达并留痕，
                典型表述：智能催收、逾期催收、催收短信、催收函、逾期名单、逾期分级等。
                注意：还款入账（S8-1）、风险处置清收（S8-9）、减免（S8-11）不属于本场景。""",
                """
                必需要素：无（说明要看逾期名单或催收即可；客户名称或逾期天数可一并说明）。
                缺省演示要素：客户「赵敏」（逾期 45 天、M2、当日批后逾期 47 户合计 386 万元）。
                用户说明诉求即输出 READY；客户与天数未提及的一律用缺省演示口径，不要追问。
                合规红线：22:00 至次日 8:00 不外呼、每日电话不超 3 次、严禁骚扰无关第三人；客户已投诉或异议立即转人工。""",
                "\"customer\":\"逾期客户名称（可选）\",\"overdueDays\":逾期天数（可选）",
                "（如「M3 有几户」「话术合规吗」）",
                s -> norm("customer", text(s, "customer", "赵敏"),
                        "overdueDays", intOr(s, "overdueDays", 45)),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("customer", ctx.slots.getOrDefault("customer", "赵敏"));
                    JsonNode rep = ctx.stepData.get("st5");
                    if (rep != null && !rep.isMissingNode()) {
                        facts.put("征信参考", "reportNo=" + rep.path("report").path("reportNo").asText("-"));
                    }
                    addBlocks(ctx, facts, "逾期总览", "分级任务清单", "话术预览");
                    return facts;
                },
                """
                你是银行催收助手。根据后台催收分级与策略结果生成 3 行中文解读，供催收主管参考：
                第1行 逾期总览（户数、分级分布、本金合计须与数据一致）；第2行 分级策略与触达时间窗（合规要点不得省略）；
                第3行 处置提示（M3 转风险处置、已投诉客户立即停止自动触达）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s86() {
        return new SceneDialogSpec(
                "S8-6",
                """
                【S8-6 罚息复利计算】——按合同口径按日计罚息与复利、更新应还总额、批量勾稽后出账并通知客户，
                典型表述：罚息复利、罚息、复利、计息等。
                注意：逾期催收（S8-5）、减免（S8-11）、核销（S8-7）不属于本场景。""",
                """
                必需要素：无（说明要算罚息即可；借据号可一并说明）。
                缺省演示要素：借据「J20250820009」（逾期本金 52,000 元、逾期 18 天、罚息按合同上浮口径、未付利息复利基数 1,670 元）。
                用户说明诉求即输出 READY；借据未提及的直接用缺省演示口径，不要追问。
                勾稽差异超阈值禁止出账；客户对罚息有异议的暂停该户出账。""",
                "\"loanNo\":\"借据号（可选）\"",
                "（如「罚息多少」「批量出账了吗」）",
                s -> norm("loanNo", text(s, "loanNo", "J20250820009")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode pr = ctx.stepData.get("st1");
                    putNum(facts, "罚息口径执行利率", pr, "finalRate");
                    addBlocks(ctx, facts, "计息明细", "批量汇总", "通知预览");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台罚息复利计息结果生成 3 行中文解读，供运营岗参考：
                第1行 计息明细（罚息、复利金额须与数据一致，不得自行重算）；第2行 批量与勾稽结论（差异笔数须如实）；
                第3行 提示（已核销借据剔除、有异议户暂停出账转减免评估）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造或重算数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s87() {
        return new SceneDialogSpec(
                "S8-7",
                """
                【S8-7 核销/呆账处理】——呆账认定统计、合规校验、生成核销方案、逐级审批通过后执行账务核销并建账销案存台账，
                典型表述：核销、呆账、账销案存、核销方案等。
                注意：清收处置（S8-9）、抵债（S8-10）、减免（S8-11）不属于本场景。""",
                """
                必需要素：借据号；呆账认定依据可缺省。
                缺省演示要素：借据「J20240310004」（本金 28 万元次级、利息 3.6 万元、依据法院终本裁定 2026-08）。
                用户说明诉求即输出 READY；借据与依据未提及的一律用缺省演示要素补齐，不要追问。
                核销不代表债务免除，终审通过才执行账务处理，须在回复中提示。""",
                "\"loanNo\":\"借据号（可选）\",\"basis\":\"认定依据（可选）\"",
                "（如「审批到哪了」「还要追索吗」）",
                s -> norm("loanNo", text(s, "loanNo", "J20240310004"),
                        "basis", text(s, "basis", "法院终本裁定")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    putNum(facts, "拟核销口径统计值", ctx.stepData.get("st1"), "value");
                    addBlocks(ctx, facts, "核销清单", "合规校验结果", "审批进度");
                    return facts;
                },
                """
                你是银行资产保全助手。根据后台核销方案与审批结果生成 3 行中文解读，供保全人员参考：
                第1行 核销清单概述（本息金额、认定依据须与数据一致）；第2行 合规校验结论（材料不齐须列缺项退回）；
                第3行 提示（核销不免除债务，账销案存继续追索）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s88() {
        return new SceneDialogSpec(
                "S8-8",
                """
                【S8-8 还款异常处理】——解析批扣失败流水、分类异常、与核心对账后按类型处置（补扣/提醒/转人工换卡）并通知客户，
                典型表述：扣款失败、还款异常、补扣、换卡、还款卡变更等。
                注意：催收（S8-5）、还款入账（S8-1）不属于本场景。""",
                """
                必需要素：无（说明要处理扣款失败即可；借据号或异常类型可一并说明）。
                缺省演示要素：借据「J20250318007」（应扣 23,375 元、原因码 BAL_NO_ENOUGH 余额不足、当日失败 12 笔）。
                用户说明诉求即输出 READY；借据未提及的直接用缺省演示口径，不要追问。
                对账不平（疑似已入账）禁止补扣；账户冻结/销户等司法异常立即转人工。""",
                "\"loanNo\":\"借据号（可选）\",\"action\":\"批量补扣|逐笔人工（可选）\"",
                "（如「失败原因是什么」「补扣成功了吗」）",
                s -> norm("loanNo", text(s, "loanNo", "J20250318007"),
                        "action", text(s, "action", "批量补扣")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    String v = validateSummary(ctx.stepData.get("st3"));
                    if (v != null) facts.put("核心对账校验", v);
                    addBlocks(ctx, facts, "异常清单", "处置结果", "客户通知预览");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台还款异常处置结果生成 3 行中文解读，供运营岗参考：
                第1行 异常清单概述（笔数与原因分布须与数据一致）；第2行 处置结果（补扣成功/转人工须如实）；
                第3行 提示（连续未存足转催收、客户投诉暂停自动处置）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s89() {
        return new SceneDialogSpec(
                "S8-9",
                """
                【S8-9 风险处置归集与清收管理】——生成清收处置方案、排定任务、监测新增风险信号、归集进度并生成清收报告走审批，
                典型表述：清收、风险处置、不良处置、处置方案、清收报告等。
                注意：催收（S8-5）、核销（S8-7）、抵债（S8-10）不属于本场景。""",
                """
                必需要素：借据号或不良组合名称。
                缺省演示要素：借据组合「恒顺机械」（次级、本金 420 万元、抵押厂房存续、预计回收率 85%-90%）。
                用户说明诉求即输出 READY；借据与组合未提及的一律用缺省演示口径，不要追问。
                重大处置方案须经资产保委会审批，未经审批不得执行处置动作。""",
                "\"loanNo\":\"借据号或组合名称（可选）\"",
                "（如「回收率多少」「进度如何」）",
                s -> norm("loanNo", text(s, "loanNo", "恒顺机械 420 万元次级贷款")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode sm = ctx.stepData.get("st5");
                    if (sm != null && !sm.isMissingNode()) {
                        facts.put("清收摘要", "summaryId=" + sm.path("summaryId").asText("-")
                                + "，压缩比=" + sm.path("stats").path("ratio").asText("-"));
                    }
                    JsonNode rp = ctx.stepData.get("st6");
                    if (rp != null && !rp.isMissingNode()) {
                        facts.put("清收报告", "reportId=" + rp.path("reportId").asText("-")
                                + "，fileId=" + rp.path("fileId").asText("-"));
                    }
                    addBlocks(ctx, facts, "风险组合概览", "处置方案", "进度看板");
                    return facts;
                },
                """
                你是银行资产保全助手。根据后台清收方案与进度生成 3 行中文解读，供保全人员参考：
                第1行 组合概览（本金、分类、预计回收率须与数据一致）；第2行 处置路径与进度（滞后节点须如实）；
                第3行 提示（重大方案须保委会审批；涉抵债转 S8-10、核销转 S8-7、减免转 S8-11）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s810() {
        return new SceneDialogSpec(
                "S8-10",
                """
                【S8-10 抵债资产接收与管理】——对抵债资产估值、生成接收方案与作价、保管风险监测、审批通过后签协议入账，
                典型表述：抵债资产、以物抵债、抵债接收等。
                注意：押品重估（S7-4）、核销（S8-7）、清收（S8-9）不属于本场景。""",
                """
                必需要素：借据号与抵债资产类型。
                缺省演示要素：资产「工业园区厂房」（市场法评估、债权余额 680 万元按作价接收、11-20 前完成过户）。
                用户说明诉求即输出 READY；资产未提及的一律用缺省演示口径，不要追问。
                作价高于债权余额倒挂须说明差额处理；权属不清或在先查封的暂停接收。""",
                "\"asset\":\"抵债资产（可选）\",\"loanNo\":\"借据号（可选）\"",
                "（如「估值多少」「过户安排」）",
                s -> norm("asset", text(s, "asset", "工业园区厂房"),
                        "loanNo", text(s, "loanNo", "J20240310004")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    putNum(facts, "抵债资产估值", ctx.stepData.get("st1"), "value");
                    JsonNode rp = ctx.stepData.get("st6");
                    if (rp != null && !rp.isMissingNode()) {
                        facts.put("接收报告", "reportId=" + rp.path("reportId").asText("-")
                                + "，fileId=" + rp.path("fileId").asText("-"));
                    }
                    addBlocks(ctx, facts, "抵债资产概览", "接收方案", "协议预览");
                    return facts;
                },
                """
                你是银行资产保全助手。根据后台抵债接收方案生成 3 行中文解读，供保全人员参考：
                第1行 资产估值与作价（数字须与数据一致，余值差额处理须如实）；第2行 接收方案与过户安排；
                第3行 提示（权属不清暂停接收；持有期间定期重估、超期未处置预警督办）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s811() {
        return new SceneDialogSpec(
                "S8-11",
                """
                【S8-11 利息本金豁免管理】——校验减免政策适用性、测算拟减免金额、试算减免后结清金额并逐级审批后账务处理，
                典型表述：罚息减免、利息减免、本金减免、减免、豁免等。
                注意：罚息计息（S8-6）、催收（S8-5）、核销（S8-7）不属于本场景。""",
                """
                必需要素：借据号与减免类型（罚息/利息/部分本金）。
                缺省演示要素：借据「J20250408019」（客户刘芳、应还本金 86,000 元、拟减免罚息复利 612 元、政策口径罚息复利全免）。
                用户说明诉求即输出 READY；借据与减免类型未提及的一律用缺省演示要素补齐，不要追问。
                不在政策清单内不生成方案；本金减免须上报有权人终审，不得擅自审批。""",
                "\"loanNo\":\"借据号（可选）\",\"exemptType\":\"减免类型（可选）\",\"reason\":\"困难依据（可选）\"",
                "（如「能免多少」「审批到哪了」）",
                s -> norm("loanNo", text(s, "loanNo", "J20250408019"),
                        "exemptType", text(s, "exemptType", "罚息复利"),
                        "reason", text(s, "reason", "特殊困难客户")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode pr = ctx.stepData.get("st2");
                    putNum(facts, "罚息口径执行利率", pr, "finalRate");
                    JsonNode p = ctx.stepData.get("st3");
                    putNum(facts, "正常利息合计", p, "totalInterest");
                    addBlocks(ctx, facts, "减免测算", "政策依据与材料校验", "审批进度", "协议/结清证明预览");
                    return facts;
                },
                """
                你是银行信贷运营助手。根据后台减免测算与审批结果生成 3 行中文解读，供审批人员参考：
                第1行 减免测算概述（应还、拟减免、减免后实付须与数据一致，不得自行重算）；第2行 政策依据与材料校验结论；
                第3行 审批提示（本金减免上收终审；减免后未按约结清恢复全额计息）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    // ============ S9 核算报送族 ============

    private static SceneDialogSpec s91() {
        return new SceneDialogSpec(
                "S9-1",
                """
                【S9-1 计提处理】——抽取计提区间贷款数据、校验计提基数、按 ECL 三阶段试算利息与减值计提、生成分录并经会计主管终审过账，
                典型表述：利息计提、减值计提、月末计提、ECL 计提、计提等。
                注意：结息处理（S9-2）、账务过账对账（S9-3）不属于本场景。""",
                """
                必需要素：无（说明要做计提即可；期间与计提类型可一并说明）。
                缺省演示要素：2026 年 10 月末全行贷款利息计提＋减值计提（含 ECL 三阶段）。
                用户说明诉求即输出 READY；期间与类型未提及的直接用缺省演示口径，不要追问。
                减值阶段跨级调整须双人复核；利率缺失、分类矛盾等异常户不静默跳过。""",
                "\"period\":\"计提期间（可选）\",\"accrualType\":\"利息|减值（可选）\"",
                "（如「计提多少」「异常户有几笔」）",
                s -> norm("period", text(s, "period", "2026 年 10 月末"),
                        "accrualType", text(s, "accrualType", "利息计提＋减值计提")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode pr = ctx.stepData.get("st3");
                    putNum(facts, "计提口径利率", pr, "finalRate");
                    addBlocks(ctx, facts, "计提总览", "异常户清单", "会计分录预览");
                    return facts;
                },
                """
                你是银行核算助手。根据后台计提试算结果生成 3 行中文解读，供会计主管参考：
                第1行 计提总览（利息、减值各阶段金额须与数据一致，不得自行重算）；第2行 异常户与挂起处理；
                第3行 过账提示（借贷平衡勾稽通过后经会计主管终审过账）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s92() {
        return new SceneDialogSpec(
                "S9-2",
                """
                【S9-2 结息处理】——接入结息户积数、按积数×利率试算结息、批量入账、转实收过账并重排分期计划，
                典型表述：对公贷款结息、季末结息、结息、转实收、周期结息等。
                注意：计提处理（S9-1）、利息发票（S9-6）不属于本场景。""",
                """
                必需要素：无（说明要结息即可；结息日与客户范围可一并说明）。
                缺省演示要素：对公贷款 2026-09-20 季末结息（结息 8,642 户、应付利息 1.26 亿元、失败 11 户）。
                用户说明诉求即输出 READY；结息日与范围未提及的直接用缺省演示口径，不要追问。
                与计提勾稽差异超 0.1% 须核算主管确认方可入账。""",
                "\"settleDate\":\"结息日（可选）\",\"scope\":\"结息范围（可选）\"",
                "（如「失败几户」「对账平吗」）",
                s -> norm("settleDate", text(s, "settleDate", "2026-09-20 季末"),
                        "scope", text(s, "scope", "全部对公贷款")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode pr = ctx.stepData.get("st2");
                    putNum(facts, "结息计息口径利率", pr, "finalRate");
                    JsonNode pl = ctx.stepData.get("st7");
                    putNum(facts, "分期户下期月供", pl, "monthlyPayment");
                    addBlocks(ctx, facts, "结息总览", "勾稽表", "失败户清单");
                    return facts;
                },
                """
                你是银行核算助手。根据后台结息处理结果生成 3 行中文解读，供核算员参考：
                第1行 结息总览（户数、利息总额、成功失败笔数须与数据一致）；第2行 勾稽结论（差异率与容差须如实）；
                第3行 提示（失败户可重跑、转实收已过账、分期计划已重排）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造或重算数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s93() {
        return new SceneDialogSpec(
                "S9-3",
                """
                【S9-3 账务记账与总账】——接入各业务批次分录、校验借贷平衡、批量过账总账、汇总发生额并做总分账对账，
                典型表述：过账、总账、总分账对账、冲正、贷款分录入账、把贷款录入核算系统、核算系统记账等。
                注意：计提处理（S9-1）、结息处理（S9-2）不属于本场景。""",
                """
                必需要素：无（说明要过账或对账即可；批次范围可一并说明）。
                缺省演示要素：2026-11-02 全业务批次过账（待过账 1,286 笔、成功 1,279 笔、挂起 7 笔）。
                用户说明诉求即输出 READY；批次未提及的直接用缺省演示口径，不要追问。
                冲正、跨日调整、科目互转必须会计主管确认双人复核；对账不平不自动冲正。""",
                "\"bizDate\":\"过账日期（可选）\",\"batch\":\"批次范围（可选）\"",
                "（如「差异在哪」「挂起几笔」）",
                s -> norm("bizDate", text(s, "bizDate", "2026-11-02"),
                        "batch", text(s, "batch", "放款/还款/计提/结息全批次")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    String v = validateSummary(ctx.stepData.get("st2"));
                    if (v != null) facts.put("分录校验", v);
                    JsonNode m = ctx.stepData.get("st4");
                    if (m != null && !m.isMissingNode() && !m.path("value").isMissingNode()) {
                        facts.put("总账汇总", m.path("value").asText("-") + m.path("unit").asText(""));
                    }
                    addBlocks(ctx, facts, "过账总览", "对账结果", "挂起凭证清单");
                    return facts;
                },
                """
                你是银行核算助手。根据后台过账与对账结果生成 3 行中文解读，供会计主管参考：
                第1行 过账总览（凭证笔数、成功挂起须与数据一致）；第2行 总分对账结论（差异户与容差须如实）；
                第3行 提示（挂起凭证补附件后重过；冲正须人工确认不自动执行）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s94() {
        return new SceneDialogSpec(
                "S9-4",
                """
                【S9-4 监管报送】——按 EAST/1104/金数/大额风险暴露口径取数、校验、脱敏、生成报送文件并经确认后正式提交，
                典型表述：监管报送、EAST 报送、EAST、1104、金数、大额风险暴露等。
                注意：绿色信贷专项报送（S9-7）、报表分析（S9-5）不属于本场景。""",
                """
                必需要素：无（说明要报送即可；报送种类与期间可一并说明）。
                缺省演示要素：2026 年 10 月 EAST（1.2 万条）＋1104（23 张表），校验异常 EAST 4 条/1104 1 条。
                用户说明诉求即输出 READY；种类与期间未提及的直接用缺省演示口径，不要追问。
                正式提交前须报送专员确认；同一报送期同种类重复提交幂等拦截。""",
                "\"reportType\":\"报送种类（可选）\",\"period\":\"报送期间（可选）\"",
                "（如「异常几条」「提交了吗」）",
                s -> norm("reportType", text(s, "reportType", "EAST＋1104"),
                        "period", text(s, "period", "2026 年 10 月")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    addBlocks(ctx, facts, "报送总览", "校验报告", "新旧口径并存说明");
                    return facts;
                },
                """
                你是银行监管报送助手。根据后台报送准备结果生成 3 行中文解读，供报送专员参考：
                第1行 报送总览（记录数、报表张数、待确认状态须与数据一致）；第2行 校验结论（异常明细须如实）；
                第3行 提示（格式类异常可自动修复、口径冲突挂起人工裁定，确认后才正式提交）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s95() {
        return new SceneDialogSpec(
                "S9-5",
                """
                【S9-5 报表生成与指标分析】——按机构与期间取数、计算余额/不良率/收益率等指标、做同环比分析并生成报表，
                典型表述：对公贷款经营报表、经营报表、报表分析、指标分析、经营分析、同环比等。
                注意：监管报送（S9-4）、绿色信贷统计（S9-7）不属于本场景。""",
                """
                必需要素：无（说明要看报表即可；机构与期间可一并说明）。
                缺省演示要素：城东支行 2026 年 10 月对公贷款（余额 86.5 亿元、环比 +2.1%、不良率 1.42%）。
                用户说明诉求即输出 READY；机构与期间未提及的直接用缺省演示口径，不要追问。""",
                "\"org\":\"机构/支行（可选）\",\"period\":\"统计期间（可选）\"",
                "（如「不良率多少」「能下钻吗」）",
                s -> norm("org", text(s, "org", "城东支行"),
                        "period", text(s, "period", "2026 年 10 月")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode m = ctx.stepData.get("st2");
                    if (m != null && !m.isMissingNode() && !m.path("value").isMissingNode()) {
                        facts.put("核心指标测算值", m.path("value").asText("-") + m.path("unit").asText(""));
                    }
                    addBlocks(ctx, facts, "指标总览表", "分析结论");
                    return facts;
                },
                """
                你是银行报表分析助手。根据后台指标与分析结果生成 3 行中文解读，供经营管理人员参考：
                第1行 指标总览（余额、不良率、收益率及同环比须与数据一致，不得自行重算）；
                第2行 异常点提示（如不良率上升的行业与户数据实）；第3行 后续引导（可下钻明细或导出报表）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s96() {
        return new SceneDialogSpec(
                "S9-6",
                """
                【S9-6 税务与利息发票处理】——核对增值税率、价税拆分、生成开票信息并经税务专员确认后开票通知，
                典型表述：利息发票、开票、红冲、增值税、发票等。
                注意：结息处理（S9-2）、报表分析（S9-5）不属于本场景。""",
                """
                必需要素：客户名称，或（含税利息金额+期间）。
                缺省演示要素：东城商贸有限公司 2026 年 7—9 月含税利息 212,000 元（贷款服务 6%、无免税）。
                用户说明诉求即输出 READY；客户与金额未提及的一律用缺省演示要素补齐，不要追问。
                红冲必须关联原发票号且原发票状态为已开具；单笔 50 万元以上开票双人复核。""",
                "\"customer\":\"客户名称（可选）\",\"grossAmount\":含税金额元数字（可选）,\"period\":\"开票期间（可选）\"",
                "（如「税额多少」「开票了吗」）",
                s -> norm("customer", text(s, "customer", "东城商贸有限公司"),
                        "grossAmount", amountOr(s, "grossAmount", 212000L),
                        "period", text(s, "period", "2026 年 7—9 月")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode pr = ctx.stepData.get("st3");
                    putNum(facts, "应税口径利率", pr, "finalRate");
                    addBlocks(ctx, facts, "开票信息预览", "税务核对说明", "历史记录");
                    return facts;
                },
                """
                你是银行税务助手。根据后台开票信息生成 3 行中文解读，供税务专员参考：
                第1行 开票预览（抬头、含税金额、税额、不含税金额须与数据一致，不得自行重算）；
                第2行 税务核对结论（税率、免税情形、台账核对须如实）；第3行 提示（红冲须关联原发票，确认后开票并回写台账）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s97() {
        return new SceneDialogSpec(
                "S9-7",
                """
                【S9-7 绿色信贷统计与专项报送】——按绿色产业指导目录匹配客户与项目、校验标识、统计结构并生成专项报表，
                典型表述：绿色信贷、绿色贷款、绿色标识、绿色金融等。
                注意：监管报送（S9-4）、经营报表分析（S9-5）不属于本场景。""",
                """
                必需要素：无（说明要统计绿色信贷即可；期间可一并说明）。
                缺省演示要素：2026 年三季度绿色贷款（余额 186.5 亿元、占比 8.2%、存疑标识 48 户）。
                用户说明诉求即输出 READY；期间未提及的直接用缺省演示口径，不要追问。
                目录代码无法匹配的不自动打标；标识由非绿色改绿色须双人复核。""",
                "\"period\":\"统计期间（可选）\"",
                "（如「余额多少」「存疑几户」）",
                s -> norm("period", text(s, "period", "2026 年三季度")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode m = ctx.stepData.get("st4");
                    if (m != null && !m.isMissingNode() && !m.path("value").isMissingNode()) {
                        facts.put("结构统计测算值", m.path("value").asText("-") + m.path("unit").asText(""));
                    }
                    addBlocks(ctx, facts, "统计总览", "存疑标识清单", "结构图表");
                    return facts;
                },
                """
                你是银行绿色信贷助手。根据后台统计结果生成 3 行中文解读，供绿色信贷专员参考：
                第1行 统计总览（余额、占比、同比变动须与数据一致）；第2行 存疑标识与人工判定口径；
                第3行 报送提示（复核定稿后生成专项报表，标识变更回写客户标签）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    // ============ S10 渠道与协同族 ============

    private static SceneDialogSpec s101() {
        return new SceneDialogSpec(
                "S10-1",
                """
                【S10-1 全渠道统一入口】——一句话识别多个意图、跨轮指代补全、编排并行推进、分块聚合响应并留痕，
                典型表述：统一对话入口、全渠道统一入口、一句话办多件事、多件事、多诉求等。
                注意：多智能体任务编排调度（S11-6）、单一还款计划（S6-2）不属于本场景。""",
                """
                必需要素：无（一句话说清多件事即可）。
                缺省演示要素：查贷款余额＋查投诉进度＋申请 30 万经营贷（名下 2 笔在贷 56.2 万元、工单已办结）。
                用户说明诉求即输出 READY；具体任务未提全的直接用缺省演示口径，不要追问。
                涉及贷款申请等敏感操作须用户显式确认后才派发。""",
                "\"tasks\":\"一次要办的多件事（可选）\"",
                "（如「办到哪一步了」「看某个分块」）",
                s -> norm("tasks", text(s, "tasks", "查贷款余额＋查投诉进度＋申请 30 万经营贷")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode sm = ctx.stepData.get("st5");
                    if (sm != null && !sm.isMissingNode()) {
                        facts.put("聚合摘要", "summaryId=" + sm.path("summaryId").asText("-"));
                    }
                    addBlocks(ctx, facts, "贷款余额", "投诉进度", "经营贷申请");
                    return facts;
                },
                """
                你是银行智能助手。根据后台多任务执行结果生成 3 行中文解读，供用户参考：
                第1行 任务清单推进情况（各任务状态须与数据一致）；第2行 关键结果（余额、工单进度、申请环节须如实）；
                第3行 后续引导（说明哪些任务需用户补充资料或确认）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s102() {
        return new SceneDialogSpec(
                "S10-2",
                """
                【S10-2 数字员工】——语音指令转写、解析操作动作与参数、校验柜员授权后执行导出/通知等任务并回执，
                典型表述：数字员工指令、语音指令、语音办理等。
                注意：PC 页面联动（S10-3）、全渠道多任务入口（S10-1）不属于本场景。""",
                """
                必需要素：一句话操作指令（如导出某清单并通知某批客户）。
                缺省演示要素：导出昨日放款清单（86 笔）并对其中 3 户发送放款成功短信。
                用户说明诉求即输出 READY；指令要素未提全的一律用缺省演示口径补齐，不要追问。
                执行前复述关键参数（清单范围、短信模板、接收人）并确认，不可逆操作须明确提示。""",
                "\"instruction\":\"语音/文字操作指令（可选）\"",
                "（如「执行成功了吗」「清单在哪」）",
                s -> norm("instruction", text(s, "instruction", "导出昨日放款清单并给 3 户发放款成功短信")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    addBlocks(ctx, facts, "执行回执", "放款清单", "短信发送");
                    return facts;
                },
                """
                你是银行数字员工助手。根据后台任务执行回执生成 3 行中文解读，供柜员参考：
                第1行 执行回执（任务数、成败、耗时须与数据一致）；第2行 各任务结果（清单笔数、短信发送须如实）；
                第3行 提示（失败自动重试仍失败转人工，全程留痕可查询）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s103() {
        return new SceneDialogSpec(
                "S10-3",
                """
                【S10-3 PC 联动】——识别操作对象与上下文、校验授权后在客户 PC 页面定位并执行联动操作，失败自动降级接口通道，
                典型表述：PC 联动、页面联动、联动补录等。
                注意：数字员工语音指令（S10-2）、前后台一致性核查（S10-4）不属于本场景。""",
                """
                必需要素：目标客户与要执行的操作。
                缺省演示要素：为客户张伟在档案页补录抵押信息（和平路 3 号厂房，评估 120 万元，并生成重检任务）。
                用户说明诉求即输出 READY；客户与操作未提及的一律用缺省演示口径补齐，不要追问。""",
                "\"customer\":\"目标客户（可选）\",\"op\":\"要执行的操作（可选）\"",
                "（如「补录成功了吗」「重检任务呢」）",
                s -> norm("customer", text(s, "customer", "张伟"),
                        "op", text(s, "op", "档案页补录抵押信息")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    addBlocks(ctx, facts, "联动回执", "补录明细");
                    return facts;
                },
                """
                你是银行渠道联动助手。根据后台联动操作回执生成 3 行中文解读，供经办柜员参考：
                第1行 联动结果（页面路径与操作成败须与数据一致）；第2行 补录明细要点（内容、生成的后续任务须如实）；
                第3行 提示（页面无响应自动降级接口、全程留痕）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s104() {
        return new SceneDialogSpec(
                "S10-4",
                """
                【S10-4 前后台一致性】——比对渠道、核心、客户三方交易状态，定位不一致后走补偿交易并双人复核，
                典型表述：交易一致性、前后台一致性、回滚、补偿交易、对账探查、扣没扣等。
                注意：还款扣款失败处置（S8-8）、总账对账（S9-3）不属于本场景。""",
                """
                必需要素：无（说明要查交易状态即可；交易号可一并说明）。
                缺省演示要素：某扣款交易渠道「处理中」、核心「成功」、客户账已扣 8,642.00 元。
                用户说明诉求即输出 READY；交易号未提及的直接用缺省演示口径，不要追问。
                交易状态不一致须人工判定不自动回滚；涉及客户资金的补偿须双人复核。""",
                "\"txnNo\":\"交易号（可选）\"",
                "（如「到底扣没扣」「补偿了吗」）",
                s -> norm("txnNo", text(s, "txnNo", "近端渠道扣款交易")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    addBlocks(ctx, facts, "三方状态比对表", "处置结果", "说明");
                    return facts;
                },
                """
                你是银行交易核查助手。根据后台三方状态比对结果生成 3 行中文解读，供运营岗参考：
                第1行 三方状态（渠道、核心、客户账状态与金额须与数据一致，不得自行推断）；
                第2行 一致性结论与处置（补偿、通知重发须如实）；第3行 提示（不一致转人工判定、补偿双人复核留痕）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s105() {
        return new SceneDialogSpec(
                "S10-5",
                """
                【S10-5 接口变更适配】——检索接口文档、做合规与契约校验、评估调用点影响面、灰度验证后经确认切流，
                典型表述：接口变更、接口升级、灰度验证、切流、接口适配等。
                注意：税务发票开具（S9-6）、渠道联动（S10-3）不属于本场景。""",
                """
                必需要素：接口名称（变更内容可一并说明）。
                缺省演示要素：税务开票接口 v2.1（契约校验 32/34 通过、10% 流量灰度抽样 100 笔全成功）。
                用户说明诉求即输出 READY；接口未提及的一律用缺省演示口径补齐，不要追问。
                切流须技术负责人确认灰度报告；失败率超 1% 自动回滚旧版本。""",
                "\"apiName\":\"接口名称（可选）\",\"change\":\"变更内容（可选）\"",
                "（如「灰度结果如何」「切流了吗」）",
                s -> norm("apiName", text(s, "apiName", "税务开票接口 v2.1"),
                        "change", text(s, "change", "新增红字原因码与购买方备注字段")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode kr = ctx.stepData.get("st1");
                    if (kr != null && !kr.isMissingNode() && !kr.path("total").isMissingNode()) {
                        facts.put("接口文档命中", kr.path("total").asInt(0) + " 条");
                    }
                    addBlocks(ctx, facts, "接入总览", "合规校验", "灰度报告");
                    return facts;
                },
                """
                你是银行技术适配助手。根据后台接口适配与灰度结果生成 3 行中文解读，供技术负责人参考：
                第1行 接入与契约校验（通过率、待映射字段须与数据一致）；第2行 合规与灰度结论（成功率、耗时须如实）；
                第3行 切流提示（确认后切流、观察期 24 小时、异常自动回滚）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s106() {
        return new SceneDialogSpec(
                "S10-6",
                """
                【S10-6 客服与投诉处理】——投诉意图与业务分类、情绪分级、检索标准应答、生成工单与答复函并回访留痕，
                典型表述：投诉、我要投诉、投诉处理、智能客服等。
                注意：适当性管理与消保审查（S12-6）、法规解读（S12-1）不属于本场景。""",
                """
                必需要素：无（客户表达不满或提问即可；投诉内容可一并说明）。
                缺省演示要素：客户对经营贷本期扣款 8,642 元提出异议并咨询利率计算（工单已派发、3 个工作日答复）。
                用户说明诉求即输出 READY；投诉细节未提及的直接用缺省演示口径，不要追问。
                情绪升级投诉自动转人工专员；答复函发出前须承办人确认，不自动承诺赔付。""",
                "\"complaint\":\"投诉或咨询内容（可选）\"",
                "（如「工单进度」「怎么计算的」）",
                s -> norm("complaint", text(s, "complaint", "经营贷本期扣款金额异议")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode sm = ctx.stepData.get("st7");
                    if (sm != null && !sm.isMissingNode()) {
                        facts.put("对话摘要", "summaryId=" + sm.path("summaryId").asText("-"));
                    }
                    addBlocks(ctx, facts, "咨询解答", "投诉受理", "答复函预览");
                    return facts;
                },
                """
                你是银行智能客服助手。根据后台处理结果生成 3 行中文解读，供客服专员参考：
                第1行 解答与受理（计算口径、工单号与派发去向须与数据一致，不得自行重算）；
                第2行 情绪分级与处理时效（转人工、答复时限须如实）；第3行 提示（答复函经确认后发出、办结后回访）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    // ============ S11 数据与AI底座族 ============

    private static SceneDialogSpec s111() {
        return new SceneDialogSpec(
                "S11-1",
                """
                【S11-1 结构化/非结构化数据接入】——连接数据源拉取结构化数据、解析扫描件、抽取要素、入库前质检并出接入回执，
                典型表述：数据接入、接数据、解析入库、拉数、数据源接入等。
                注意：数据质量治理（S11-2）、客户画像（S11-3）不属于本场景。""",
                """
                必需要素：数据源与范围（如某类外部数据、某段时间），或直接上传文件。
                缺省演示要素：2026 年三季度工商变更数据（结构化 12,486 条＋两份尽调报告扫描件、要素 37 项）。
                用户说明诉求即输出 READY；数据源未提及的一律用缺省演示口径补齐，不要追问。
                付费数据源超常规批次取数前须数据主管确认授权范围。""",
                "\"source\":\"数据源（可选）\",\"range\":\"数据范围/期间（可选）\"",
                "（如「入了多少条」「质检过了吗」）",
                s -> norm("source", text(s, "source", "工商公示数据"),
                        "range", text(s, "range", "2026 年 7—9 月")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode sm = ctx.stepData.get("st7");
                    if (sm != null && !sm.isMissingNode()) {
                        facts.put("接入摘要", "summaryId=" + sm.path("summaryId").asText("-"));
                    }
                    addBlocks(ctx, facts, "接入概览", "解析明细", "质检回执", "落地信息");
                    return facts;
                },
                """
                你是银行数据接入助手。根据后台接入结果生成 3 行中文解读，供数据人员参考：
                第1行 接入概览（数据源、记录数、解析要素数须与数据一致）；第2行 质检结论（缺失率与阈值对比须如实）；
                第3行 落地提示（入库目标表与下游可用场景）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s112() {
        return new SceneDialogSpec(
                "S11-2",
                """
                【S11-2 数据质量治理】——按完整性/准确性/时效性扫描目标表、与外部数据交叉核对、统计问题分布并出整改建议，
                典型表述：数据质量、质检、数据治理、整改通知、数据不准等。
                注意：数据接入（S11-1）、监管报送校验（S9-4）不属于本场景。""",
                """
                必需要素：目标表名或数据范围。
                缺省演示要素：客户基本信息表最近一个月（86.4 万条、完整性 96.2 分、手机号缺失 9,217 户略超阈值）。
                用户说明诉求即输出 READY；表名未提及的一律用缺省演示口径补齐，不要追问。
                问题记录数超阈值自动生成修复任务；整改通知须经责任机构确认后发送。""",
                "\"table\":\"目标表（可选）\",\"period\":\"检查期间（可选）\"",
                "（如「问题在哪」「怎么整改」）",
                s -> norm("table", text(s, "table", "客户基本信息表"),
                        "period", text(s, "period", "最近一个月")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    String v = validateSummary(ctx.stepData.get("st2"));
                    if (v != null) facts.put("交叉核对校验", v);
                    addBlocks(ctx, facts, "质检总评", "问题清单", "交叉核对结果", "整改建议");
                    return facts;
                },
                """
                你是银行数据治理助手。根据后台质检结果生成 3 行中文解读，供数据人员参考：
                第1行 质检总评（各维度得分与环比变动须与数据一致）；第2行 问题分布（缺失、越界、不一致笔数须如实）；
                第3行 整改建议（责任机构与时限、修复任务安排）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s113() {
        return new SceneDialogSpec(
                "S11-3",
                """
                【S11-3 指标计算与客户画像】——归并客户身份、计算核心指标、归集资产负债分布并挖掘关联图谱后输出完整画像，
                典型表述：完整画像、画像生成、指标计算等——凡用户说「完整画像」一律选本场景，即使只给了客户名称；
                用户仅泛指「画像」「客户视图」「客户全貌」时选 S1-8，不属于本场景。
                注意：多智能体任务编排（S11-6）不属于本场景。""",
                """
                必需要素：客户名称或企业名。
                缺省演示要素：恒通商贸有限公司（归并 2 个客户号、在贷 300 万元、对外担保 450 万元含正和物流 300 万元）。
                用户说明诉求即输出 READY；客户未提及的一律用缺省演示要素补齐，不要追问。
                高风险关联（如对外担保对象逾期）须在画像中显著提示。""",
                "\"customer\":\"客户名称/企业名（可选）\"",
                "（如「资产负债率多少」「关联风险呢」）",
                s -> norm("customer", text(s, "customer", "恒通商贸有限公司")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode er = ctx.stepData.get("st1");
                    if (er != null && !er.isMissingNode() && !er.path("goldenId").isMissingNode()) {
                        facts.put("goldenId", er.path("goldenId").asText("-"));
                    }
                    JsonNode m = ctx.stepData.get("st2");
                    if (m != null && !m.isMissingNode() && !m.path("value").isMissingNode()) {
                        facts.put("在贷余额测算值", m.path("value").asText("-") + m.path("unit").asText(""));
                    }
                    addBlocks(ctx, facts, "基本信息与身份归并", "核心指标", "资产负债与行为偏好", "关联图谱");
                    return facts;
                },
                """
                你是银行客户画像助手。根据后台画像结果生成 3 行中文解读，供客户经理参考：
                第1行 身份与核心指标（归并户数、资产负债率等须与数据一致，不得自行重算）；
                第2行 资产负债与行为偏好（存款、在贷须如实）；第3行 关联风险提示（担保、逾期等高风险关联不得省略）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s114() {
        return new SceneDialogSpec(
                "S11-4",
                """
                【S11-4 模型推理服务（统一推理）】——按模型版本路由、批量执行推理、跟踪进度、统计评分分布并做衰减监控提示，
                典型表述：模型推理、批量评分、批量打分、跑模型、统一推理等。
                注意：贷后评分卡调优与预警（S7-4）、客户完整画像（S11-3）不属于本场景。""",
                """
                必需要素：模型名称与打分对象范围。
                缺省演示要素：小微评分卡 V2.1 给 500 户批量打分（成功 496 户、拒绝率 7.2%、4 户缺流水转补数）。
                用户说明诉求即输出 READY；模型与对象未提及的一律用缺省演示口径补齐，不要追问。
                推理结果仅供决策参考，审批结论归审批族；灰度版本替换须模型管理员确认。""",
                "\"model\":\"模型名称（可选）\",\"count\":打分对象数（可选）",
                "（如「分布如何」「失败几户」）",
                s -> norm("model", text(s, "model", "小微评分卡 V2.1"),
                        "count", intOr(s, "count", 500)),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode mc = ctx.stepData.get("st5");
                    if (mc != null && !mc.isMissingNode() && !mc.path("total").isMissingNode()) {
                        facts.put("统计总量", mc.path("total").asInt(0));
                        if (!mc.path("rejected").isMissingNode()) facts.put("拒绝数", mc.path("rejected").asInt(0));
                    }
                    addBlocks(ctx, facts, "推理概况", "结果分布", "异常清单", "监控提示");
                    return facts;
                },
                """
                你是银行模型推理助手。根据后台批量推理结果生成 3 行中文解读，供模型管理员参考：
                第1行 推理概况（模型版本、户数、成功率须与数据一致）；第2行 结果分布（分级户数、拒绝率须如实）；
                第3行 监控提示（分布偏差与衰减预警线、失败户补数安排）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s115() {
        return new SceneDialogSpec(
                "S11-5",
                """
                【S11-5 知识引擎（RAG/制度问答）】——抽取问题要素、向量检索制度库与监管条款、生成带引用的解答并留痕，
                典型表述：制度问答、知识问答、查制度、规定是什么、条款是什么等。
                注意：法规解读与影响分析（S12-1）、客服投诉应答（S10-6）不属于本场景。""",
                """
                必需要素：要问的制度问题。
                缺省演示要素：个人经营贷受托支付起点金额（原则上受托支付、自主支付单笔超 30 万元须受托）。
                用户说明诉求即输出 READY；问题未提及的一律用缺省演示口径补齐，不要追问。
                解答须标注制度出处并注明以本行最新制度口径为准，不替代合规审查结论。""",
                "\"question\":\"制度问题（可选）\"",
                "（如「出处是哪条」「还有别的口径吗」）",
                s -> norm("question", text(s, "question", "个人经营贷受托支付起点金额是多少")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode ra = ctx.stepData.get("st4");
                    if (ra != null && !ra.isMissingNode() && !ra.path("quality").isMissingNode()) {
                        facts.put("解答忠实度", ra.path("quality").path("faithfulness").asText("-"));
                    }
                    addBlocks(ctx, facts, "直接答案", "制度原文", "监管对照", "相关延伸");
                    return facts;
                },
                """
                你是银行制度问答助手。根据后台检索与解答结果生成 3 行中文解读：
                第1行 直接答案（口径数字须与数据一致，不得自行改写）；第2行 制度与监管出处（条款须如实）；
                第3行 提示（以本行最新制度为准、具体业务以合规审查结论为准）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s116() {
        return new SceneDialogSpec(
                "S11-6",
                """
                【S11-6 多智能体编排与协同】——一句话拆解多意图、生成编排计划、并行调度画像/筛查等子任务并聚合呈现，
                典型表述：多意图任务、任务编排、多智能体、总调度、智能体编排等。
                注意：单一还款计划测算（S6-2）、全渠道统一入口（S10-1）不属于本场景。""",
                """
                必需要素：无（一句话说清要并行办的几件事即可）。
                缺省演示要素：给恒通商贸出完整画像＋查涉诉风险＋按 300 万元/3.45%/36 期算还款计划（三项全部完成）。
                用户说明诉求即输出 READY；任务未提全的一律用缺省演示口径补齐，不要追问。
                编排只做调度，数值由原子服务实算；子任务失败自动重试一次后降级标注。""",
                "\"taskText\":\"要并行办理的多件事（可选）\"",
                "（如「跑到哪一步了」「再看个结果」）",
                s -> norm("taskText", text(s, "taskText", "画像＋涉诉筛查＋还款计划试算")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode pl = ctx.stepData.get("st5");
                    putNum(facts, "还款计划月供", pl, "monthlyPayment");
                    putNum(facts, "还款计划总利息", pl, "totalInterest");
                    addBlocks(ctx, facts, "任务清单总览", "画像结果", "筛查结果", "还款计划");
                    return facts;
                },
                """
                你是银行任务编排助手。根据后台多任务执行结果生成 3 行中文解读，供用户参考：
                第1行 任务清单完成情况（各子任务状态须与数据一致）；第2行 各任务关键结果（画像、筛查、试算须如实）；
                第3行 提示（数值由原子服务实算可验算，失败子任务已标注降级）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造或重算数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    // ============ S12 合规风控族 ============

    private static SceneDialogSpec s121() {
        return new SceneDialogSpec(
                "S12-1",
                """
                【S12-1 法规解读与制度支持】——抽取法规条款要素、定位原文、对照本行制度、生成白话解读与解读报告，
                典型表述：法规解读、解读一下、新规影响、条款解读等。
                注意：制度条款问答（S11-5）、合规检查（S12-2）不属于本场景。""",
                """
                必需要素：法规名称或主题（条款号可一并说明）。
                缺省演示要素：《征信业管理条例》第二十五条异议处理（20 日内核查答复，本行 15 日严于监管）。
                用户说明诉求即输出 READY；法规未提及的一律用缺省演示要素补齐，不要追问。
                解读报告对外使用前须合规负责人审定；解读为参考性质不构成正式法律意见。""",
                "\"regulation\":\"法规名称/主题（可选）\",\"clause\":\"条款号（可选）\"",
                "（如「对行里什么影响」「报告好了吗」）",
                s -> norm("regulation", text(s, "regulation", "《征信业管理条例》"),
                        "clause", text(s, "clause", "第二十五条")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode rg = ctx.stepData.get("st5");
                    if (rg != null && !rg.isMissingNode()) {
                        facts.put("解读报告", "reportId=" + rg.path("reportId").asText("-")
                                + "，校验结论=" + rg.path("checks").path("overall").asText("-"));
                    }
                    addBlocks(ctx, facts, "条款原文", "白话解读", "本行制度对照", "落地建议");
                    return facts;
                },
                """
                你是银行法规解读助手。根据后台解读结果生成 3 行中文解读，供合规人员参考：
                第1行 条款核心要求（时限、义务等数字须与数据一致）；第2行 本行制度对照结论（严于/符合须如实）；
                第3行 落地建议（系统硬控、报告待审定等）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s122() {
        return new SceneDialogSpec(
                "S12-2",
                """
                【S12-2 合规检查与红线管控】——对存续业务逐条校验红线规则、定位条款依据、筛查禁入名单并输出通过/拦截/例外审批三档结论，
                典型表述：踩红线、红线检查、例外审批等——凡用户说「踩红线」「红线检查」「例外审批」一律选本场景，即使业务是授信或贷款申请；
                但用户说「合规审查」「合规检查」「这笔合规吗」等授信审查表述时选 S4-6，不属于本场景。
                注意：反洗钱监测（S12-3）不属于本场景。""",
                """
                必需要素：要检查的业务（客户与要素可一并说明）。
                缺省演示要素：恒通商贸 500 万元信用方式经营贷（评级 BBB 上限 300 万元，结论「例外审批」）。
                用户说明诉求即输出 READY；业务要素未提及的一律用缺省演示口径补齐，不要追问。
                例外审批须经有权人按金额分级签批，本场景不自行放行；拦截类结论可直接阻断业务。""",
                "\"biz\":\"业务描述（可选）\",\"amount\":金额元数字（可选）",
                "（如「触发哪条」「怎么处置」）",
                s -> norm("biz", text(s, "biz", "恒通商贸 500 万元信用方式经营贷"),
                        "amount", amountOr(s, "amount", 5000000L)),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode kr = ctx.stepData.get("st2");
                    if (kr != null && !kr.isMissingNode() && !kr.path("total").isMissingNode()) {
                        facts.put("条款依据命中", kr.path("total").asInt(0) + " 条");
                    }
                    addBlocks(ctx, facts, "总结论", "逐条校验结果", "触发项说明", "处置建议");
                    return facts;
                },
                """
                你是银行合规检查助手。根据后台红线校验结果生成 3 行中文解读，供合规人员参考：
                第1行 总结论（通过/拦截/例外审批须与数据一致）；第2行 触发项说明（触发原因与依据须如实）；
                第3行 处置提示（例外审批须有权人签批、拦截项调整后可重查）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s123() {
        return new SceneDialogSpec(
                "S12-3",
                """
                【S12-3 反洗钱监测（大额/可疑交易）】——归并客户身份、按监管口径筛查大额与可疑交易、构建资金图谱、输出风险评级，
                典型表述：反洗钱、大额交易、可疑交易、洗钱风险等。
                注意：红线合规检查（S12-2）、制裁名单筛查场景（S4）不属于本场景。""",
                """
                必需要素：客户名称（查清单时可说明时间范围）。
                缺省演示要素：恒通商贸近 30 天（大额 3 笔当日累计最高 386 万元、快进快出 1 笔、评级「中」）。
                用户说明诉求即输出 READY；客户未提及的一律用缺省演示口径补齐，不要追问。
                可疑交易报告须反洗钱专员人工审定后才生成上报底稿，本场景不自动上报。""",
                "\"customer\":\"客户名称（可选）\",\"days\":查询天数（可选）",
                "（如「可疑在哪」「怎么上报」）",
                s -> norm("customer", text(s, "customer", "恒通商贸有限公司"),
                        "days", intOr(s, "days", 30)),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode er = ctx.stepData.get("st1");
                    if (er != null && !er.isMissingNode() && !er.path("goldenId").isMissingNode()) {
                        facts.put("goldenId", er.path("goldenId").asText("-"));
                    }
                    JsonNode gb = ctx.stepData.get("st3");
                    if (gb != null && !gb.isMissingNode() && !gb.path("jobId").isMissingNode()) {
                        facts.put("资金图谱", "jobId=" + gb.path("jobId").asText("-"));
                    }
                    addBlocks(ctx, facts, "身份归并", "大额/可疑筛查", "资金关联图谱", "风险评级与处置");
                    return facts;
                },
                """
                你是银行反洗钱监测助手。根据后台筛查与评级结果生成 3 行中文解读，供反洗钱专员参考：
                第1行 身份归并与筛查结果（笔数、金额、特征须与数据一致）；第2行 资金关联要点（可疑对手须如实）；
                第3行 处置提示（上报底稿须专员审定、不自动上报）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s124() {
        return new SceneDialogSpec(
                "S12-4",
                """
                【S12-4 授权与隐私合规（征信授权/个人信息保护）】——核对征信与个人信息授权状态、核验经办人、缺失时引导补签并脱敏展示，
                典型表述：个人信息保护、隐私合规、授权状态、授权合规等。
                注意：授信审批环节的合规检查（S4-6）、征信异议处理（S12-5）不属于本场景。""",
                """
                必需要素：客户名称（业务用途可一并说明）。
                缺省演示要素：恒通商贸经营贷授信审查（企业授权已签、法定代表人个人征信授权缺失已发起补签）。
                用户说明诉求即输出 READY；客户未提及的一律用缺省演示口径补齐，不要追问。
                无授权查询一律拦截不放行；补签完成后自动放行，全程留痕。""",
                "\"customer\":\"客户名称（可选）\",\"purpose\":\"业务用途（可选）\"",
                "（如「授权齐了吗」「补签了吗」）",
                s -> norm("customer", text(s, "customer", "恒通商贸有限公司"),
                        "purpose", text(s, "purpose", "经营贷授信审查")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode nt = ctx.stepData.get("st5");
                    if (nt != null && !nt.isMissingNode() && !nt.path("messageId").isMissingNode()) {
                        facts.put("客户经理提醒", "messageId=" + nt.path("messageId").asText("-")
                                + "，状态=" + nt.path("status").asText("-"));
                    }
                    addBlocks(ctx, facts, "授权状态总览", "授权要素核对", "处置建议", "脱敏展示说明");
                    return facts;
                },
                """
                你是银行授权合规助手。根据后台核对结果生成 3 行中文解读，供经办人员参考：
                第1行 授权状态总览（各项授权状态须与数据一致）；第2行 缺失项与处置（补签安排须如实）；
                第3行 提示（未授权不读取征信、越权查询拦截、敏感字段脱敏展示）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s125() {
        return new SceneDialogSpec(
                "S12-5",
                """
                【S12-5 征信异议处理】——受理客户异议、解析征信记录、回源本行数据核查、确认差错并生成答复函，全程 20 日时限管控，
                典型表述：征信异议、异议受理、异议申请、更正征信等。
                注意：征信授权状态核对（S12-4）、法规条款解读（S12-1）不属于本场景。""",
                """
                必要素：客户名称或异议编号。
                缺省演示要素：张伟异议（征信显示逾期 2 期、本行 2022-01-15 已结清，确属报送差错，20 日时限剩 19 天）。
                用户说明诉求即输出 READY；客户未提及的一律用缺省演示口径补齐，不要追问。
                答复函须合规岗审定后发出；确属差错由数据主管发起更正，本场景不越权改数。""",
                "\"customer\":\"客户名称（可选）\",\"disputeNo\":\"异议编号（可选）\"",
                "（如「查出来了吗」「多久答复」）",
                s -> norm("customer", text(s, "customer", "张伟"),
                        "disputeNo", text(s, "disputeNo", "DY20261104001")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode au = ctx.stepData.get("st2");
                    if (au != null && !au.isMissingNode() && !au.path("authId").isMissingNode()) {
                        facts.put("授权建档", "authId=" + au.path("authId").asText("-"));
                    }
                    JsonNode cr = ctx.stepData.get("st3");
                    if (cr != null && !cr.isMissingNode()) {
                        String rno = cr.path("report").path("reportNo").asText("");
                        if (rno.isEmpty()) rno = cr.path("reportNo").asText("");
                        if (rno.isEmpty()) rno = cr.path("taskId").asText("");
                        if (!rno.isEmpty()) facts.put("征信报告编号", rno);
                    }
                    addBlocks(ctx, facts, "工单信息", "比对结果", "核查结论", "答复函草稿");
                    return facts;
                },
                """
                你是银行征信异议助手。根据后台核查结果生成 3 行中文解读，供合规岗参考：
                第1行 受理与时限（异议编号、剩余天数须与数据一致）；第2行 比对与核查结论（是否报送差错须如实）；
                第3行 处置提示（差错走更正流程、答复函审定后发出）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }

    private static SceneDialogSpec s126() {
        return new SceneDialogSpec(
                "S12-6",
                """
                【S12-6 适当性管理与消保审查】——对产品销售做适当性合规校验、投诉情绪分级、定位消保法规、生成审查意见并流转消保部审定，
                典型表述：适当性、消保、产品能不能卖、消保审查等。
                注意：一般投诉受理与客服应答（S10-6）、法规解读（S12-1）不属于本场景。""",
                """
                必需要素：无（说明要做适当性或消保审查即可；产品与客群可一并说明）。
                缺省演示要素：老年客户销售经营贷未做还款压力测试（结论「需整改」，C2 客户承担 C3 产品）。
                用户说明诉求即输出 READY；产品未提及的一律用缺省演示口径补齐，不要追问。
                审查意见与答复函须消保部审定；答复函承认告知不充分但不自动承诺赔付。""",
                "\"product\":\"产品名称（可选）\",\"crowd\":\"目标客群（可选）\"",
                "（如「哪几项要整改」「怎么回访」）",
                s -> norm("product", text(s, "product", "经营贷"),
                        "crowd", text(s, "crowd", "老年客户")),
                ctx -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    JsonNode kr = ctx.stepData.get("st3");
                    if (kr != null && !kr.isMissingNode() && !kr.path("total").isMissingNode()) {
                        facts.put("消保依据命中", kr.path("total").asInt(0) + " 条");
                    }
                    addBlocks(ctx, facts, "审查结论", "逐条校验结果", "投诉分级", "处置建议");
                    return facts;
                },
                """
                你是银行消保审查助手。根据后台审查结果生成 3 行中文解读，供消保人员参考：
                第1行 审查结论（需整改/通过及触发项须与数据一致）；第2行 投诉分级与回访安排（时限须如实）；
                第3行 处置提示（整改清单、答复函经消保部审定后发出、不自动承诺赔付）。
                要求：只依据给定数据，客观陈述不夸大；每行不超过 45 字；不得编造数据之外的数字。
                只输出 JSON：{"lines":["...","...","..."]}""");
    }
}
