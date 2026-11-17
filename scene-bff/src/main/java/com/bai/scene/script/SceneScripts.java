package com.bai.scene.script;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 场景剧本注册表：营销获客族 S1-1~S1-8、申请受理族 S2-1~S2-5、尽调评估族 S3-1~S3-11、授信审批族 S4-1~S4-9、合同签约族 S5-1~S5-6、放款用信族 S6-1~S6-13、贷后管理族 S7-1~S7-10、还款催收族 S8-1~S8-11、核算报送族 S9-1~S9-7、平台能力族 S10-1~S10-6、数据与AI底座族 S11-1~S11-6、合规风控族 S12-1~S12-6（累计 98/98）
 * <p>步骤与《提示词库》场景规格一一对应：98 个场景全部接线真实原子调用（确定性仿真实现，400 处占位已替换），
 * 仅 S4-4 st5 主智能体融合计算在本地执行；外部系统联调项（核心账务、电子签章等）以确定性仿真＋人工确认运行并标注。
 * 人工确认节点（⚑）由编排引擎挂起等待自然语言确认。
 */
public final class SceneScripts {

    private SceneScripts() {}

    /** 全部剧本（sceneId → script），LinkedHashMap 保持注册顺序 */
    private static final Map<String, SceneScript> REGISTRY = new LinkedHashMap<>();

    public static Map<String, SceneScript> all() { return REGISTRY; }

    public static SceneScript get(String sceneId) { return REGISTRY.get(sceneId); }

    /** 意图路由：按关键词匹配场景（命中第一个），未命中返回 null */
    public static SceneScript match(String message) {
        if (message == null || message.isBlank()) return null;
        SceneScript best = null;
        int bestLen = 0;
        for (SceneScript s : REGISTRY.values()) {
            for (String kw : s.keywords()) {
                // 最长命中优先：避免泛词（如“受理”）抢占更具体的场景关键词（如“法人贷”）；同长保持注册序
                if (message.contains(kw) && kw.length() > bestLen) {
                    best = s;
                    bestLen = kw.length();
                }
            }
        }
        return best;
    }

    /** 从消息提取发送条数上限（“N 条”），缺省 -1 表示用剧本缺省值 */
    public static int extractLimit(String message) {
        if (message == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*条").matcher(message);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    // ---------- 工具 ----------

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static List<String> deps(String... ids) { return List.of(ids); }

    private static SceneStep real(String id, String name, String agentId, List<String> dependsOn, String url,
                                  SceneStep.PayloadFn payload,
                                  SceneStep.BiFn summary) {
        return new SceneStep(id, name, agentId, dependsOn, false, false, url, payload, summary);
    }

    private static SceneStep mock(String id, String name, String agentId, List<String> dependsOn) {
        return SceneStep.of(id, name, agentId, dependsOn, false, true);
    }

    private static SceneStep human(String id, String name, List<String> dependsOn) {
        return SceneStep.of(id, name, "human-confirm", dependsOn, true, false);
    }

    /** 数值智能格式化：整数不带小数，否则保留2位，带千分位 */
    private static String fmtSmart(double v) {
        return v == Math.rint(v) ? String.format("%,.0f", v) : String.format("%,.2f", v);
    }

    private static double d(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(v));
    }

    /** 通用结构化分块：{title, type, data, lines}——lines 为等义文字版，保证 LLM facts 抽取兼容 */
    private static Map<String, Object> typedBlock(String title, String type, Object data, List<String> lines) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("type", type);
        m.put("data", data);
        m.put("lines", lines);
        return m;
    }

    /** 向结构化分块追加附加行（如动态步骤摘要）：前端按 type 渲染忽略 lines，LLM facts 仍可完整读取 */
    private static Map<String, Object> withExtraLines(Map<String, Object> typed, String... extra) {
        if (extra.length > 0) {
            @SuppressWarnings("unchecked")
            List<String> lines = (List<String>) typed.get("lines");
            List<String> merged = new java.util.ArrayList<>(lines == null ? List.of() : lines);
            merged.addAll(List.of(extra));
            typed.put("lines", merged);
        }
        return typed;
    }

    /** 分块构造：{title, lines:[]}；全部行均为「K：V」形态时自动升级为 kv 结构化分块 */
    private static Map<String, Object> block(String title, String... lines) {
        java.util.regex.Pattern KV = java.util.regex.Pattern.compile("^([^：]{2,10})：([^：]{1,22})$");
        boolean allKv = lines.length > 0;
        for (String l : lines) {
            java.util.regex.Matcher mm = KV.matcher(l);
            if (!(mm.matches() && !mm.group(2).matches(".*[，。；？！].*"))) { allKv = false; break; }
        }
        if (allKv) {
            List<Object> data = new java.util.ArrayList<>();
            for (String l : lines) {
                java.util.regex.Matcher mm = KV.matcher(l);
                mm.matches();
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("k", mm.group(1));
                it.put("v", mm.group(2));
                data.add(it);
            }
            return typedBlock(title, "kv", data, List.of(lines));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("lines", List.of(lines));
        return m;
    }

    /** KV 键值分块：kvBlock("标题", "键", "值", ...) */
    private static Map<String, Object> kvBlock(String title, Object... kv) {
        List<Object> data = new java.util.ArrayList<>();
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = String.valueOf(kv[i]), v = String.valueOf(kv[i + 1]);
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("k", k);
            it.put("v", v);
            data.add(it);
            lines.add(k + "：" + v);
        }
        return typedBlock(title, "kv", data, lines);
    }

    /** 条形图分块：barsBlock("标题", "单位", "标签", 数值, ...)；负值自动红色变体 */
    private static Map<String, Object> barsBlock(String title, String unit, Object... labelValue) {
        List<Object> data = new java.util.ArrayList<>();
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < labelValue.length; i += 2) {
            String label = String.valueOf(labelValue[i]);
            double v = d(labelValue[i + 1]);
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("label", label);
            it.put("value", v);
            it.put("unit", unit);
            it.put("display", fmtSmart(v) + unit);
            data.add(it);
            lines.add(label + "：" + fmtSmart(v) + unit);
        }
        return typedBlock(title, "bars", data, lines);
    }

    /** 环形图分块：donutBlock("标题", "单位", "标签", 数值, ...)；中心展示合计 */
    private static Map<String, Object> donutBlock(String title, String unit, Object... labelValue) {
        List<Object> data = new java.util.ArrayList<>();
        List<String> lines = new java.util.ArrayList<>();
        double total = 0;
        for (int i = 0; i + 1 < labelValue.length; i += 2) {
            String label = String.valueOf(labelValue[i]);
            double v = d(labelValue[i + 1]);
            total += v;
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("label", label);
            it.put("value", v);
            it.put("display", fmtSmart(v));
            data.add(it);
            lines.add(label + "：" + fmtSmart(v) + unit);
        }
        Map<String, Object> m = typedBlock(title, "donut", data, lines);
        m.put("unit", unit);
        m.put("totalText", fmtSmart(total) + unit);
        return m;
    }

    /** 进度条分块：progressBlock("标题", "标签", 0~100数值, "展示文本", ...)；display 传 "" 自动取整百分比 */
    private static Map<String, Object> progressBlock(String title, Object... lvd) {
        List<Object> data = new java.util.ArrayList<>();
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i + 2 < lvd.length + 1; i += 3) {
            String label = String.valueOf(lvd[i]);
            double v = d(lvd[i + 1]);
            String disp = String.valueOf(lvd[i + 2]);
            if (disp.isBlank()) disp = (int) Math.round(v) + "%";
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("label", label);
            it.put("value", v);
            it.put("display", disp);
            data.add(it);
            lines.add(label + "：" + disp);
        }
        return typedBlock(title, "progress", data, lines);
    }

    /** 穿透路径 → 图谱数据：nodes（首节点=穿透实体，person/company 名称启发式）+
     *  links（持股方向边 source=持股方、target=被持股方，ratio=真实边持股比例 hopRatios，去重取首值） */
    private static Map<String, Object> graphData(JsonNode p) {
        List<Map<String, Object>> nodes = new java.util.ArrayList<>();
        List<Map<String, Object>> links = new java.util.ArrayList<>();
        java.util.Set<String> seenNodes = new java.util.LinkedHashSet<>();
        java.util.Set<String> seenEdges = new java.util.LinkedHashSet<>();
        for (JsonNode path : p.path("paths")) {
            String raw = path.path("path").asText("");
            boolean upstream = raw.contains("←");
            String[] parts = raw.split(upstream ? "←" : "→");
            List<Double> hopRatios = new java.util.ArrayList<>();
            path.path("hopRatios").forEach(x -> hopRatios.add(x.asDouble()));
            for (int i = 0; i < parts.length; i++) {
                String name = parts[i].trim();
                if (name.isEmpty()) {
                    continue;
                }
                if (seenNodes.add(name)) {
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("id", name);
                    n.put("name", name);
                    n.put("kind", seenNodes.size() == 1 ? "entity"
                            : (name.matches(".*(公司|企业|合伙|集团|中心|厂|所|银行).*") ? "company" : "person"));
                    nodes.add(n);
                }
                if (i > 0) {
                    String source = upstream ? parts[i].trim() : parts[i - 1].trim();
                    String target = upstream ? parts[i - 1].trim() : parts[i].trim();
                    String key = source + "|" + target;
                    if (seenEdges.add(key)) {
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("source", source);
                        e.put("target", target);
                        if (i - 1 < hopRatios.size()) {
                            e.put("ratio", Math.round(hopRatios.get(i - 1) * 1000) / 10.0);
                        }
                        links.add(e);
                    }
                }
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodes", nodes);
        data.put("links", links);
        return data;
    }

    /**
     * 穿透结果动态分块（st4 graph-penetrate 实算结果 → 4 个结果分块，演示口径与实算一致）：
     * 总览（路径数/最深层级/股东数/担保圈检出）、路径图、股东持股汇总（progress）、风险提示。
     * p 为空时全保护降级为「详见滚动日志」占位。
     */
    private static List<Map<String, Object>> penetrateBlocks(JsonNode p, String tOverview, String tPaths,
                                                             String tHolders, String tRisk,
                                                             String overviewExtra, String... riskTail) {
        List<Map<String, Object>> blocks = new java.util.ArrayList<>();
        if (p == null || p.isMissingNode() || !p.has("paths")) {
            blocks.add(block(tOverview, "穿透结果：详见滚动日志"));
            blocks.add(block(tRisk, "穿透结果异常，请查看滚动日志核实"));
            return blocks;
        }
        int maxHops = 0;
        for (JsonNode path : p.path("paths")) {
            maxHops = Math.max(maxHops, path.path("hops").asInt(0));
        }
        boolean cycle = p.path("cycleDetected").asBoolean(false);
        blocks.add(block(tOverview,
                "穿透路径 " + p.path("paths").size() + " 条｜最深 " + maxHops + " 层｜股东 "
                        + p.path("equitySummary").size() + " 户｜担保圈/循环持股：" + (cycle ? "检出" : "未检出"),
                overviewExtra));
        List<String> pathLines = new java.util.ArrayList<>();
        for (JsonNode path : p.path("paths")) {
            pathLines.add(path.path("path").asText("-").replace("←", " ← ")
                    + "（" + path.path("hops").asInt(0) + " 层，持股 "
                    + Math.round(path.path("ratio").asDouble(0) * 1000) / 10.0 + "%）");
        }
        blocks.add(typedBlock(tPaths, "graph", graphData(p), pathLines));
        List<Map<String, Object>> holderItems = new java.util.ArrayList<>();
        List<String> holderLines = new java.util.ArrayList<>();
        for (JsonNode e : p.path("equitySummary")) {
            double w = Math.min(100.0, e.path("weighted").asDouble(0) * 100);
            String label = e.path("holder").asText("-") + " 合计";
            String disp = String.format("%.1f%%（直接 %.0f%%＋间接 %.1f%%）", w,
                    e.path("direct").asDouble(0) * 100, e.path("indirect").asDouble(0) * 100);
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("label", label);
            it.put("value", w);
            it.put("display", disp);
            it.put("level", "ok");   // 持股比例为中性语义，固定蓝色，不按 <50% 自动标红
            holderItems.add(it);
            holderLines.add(label + "：" + disp);
        }
        blocks.add(typedBlock(tHolders, "progress", holderItems, holderLines));
        List<String> riskLines = new java.util.ArrayList<>();
        riskLines.add(cycle ? "检出担保圈/循环持股，建议核查互保链条敞口与风险传染路径" : "未检出担保圈与循环持股风险");
        riskLines.addAll(java.util.Arrays.asList(riskTail));
        blocks.add(block(tRisk, riskLines.toArray(new String[0])));
        return blocks;
    }

    /** 状态清单分块：statusBlock("标题", "ok|warn|fail", "条目", "备注", ...)；备注可为 "" */
    private static Map<String, Object> statusBlock(String title, Object... sln) {
        List<Object> data = new java.util.ArrayList<>();
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i + 2 < sln.length + 1; i += 3) {
            String state = String.valueOf(sln[i]);
            String label = String.valueOf(sln[i + 1]);
            String note = String.valueOf(sln[i + 2]);
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("state", state);
            it.put("label", label);
            if (!note.isBlank()) it.put("note", note);
            data.add(it);
            String mark = "ok".equals(state) ? "✓" : "fail".equals(state) ? "×" : "!";
            lines.add(mark + " " + label + (note.isBlank() ? "" : "（" + note + "）"));
        }
        return typedBlock(title, "status", data, lines);
    }

    /** 表格分块：tableBlock("标题", new String[]{"列1","列2"}, new String[]{...}, ...)；lines 保留管道表供 LLM 读取 */
    private static Map<String, Object> tableBlock(String title, String[] headers, String[]... rows) {
        List<List<String>> rs = new java.util.ArrayList<>();
        List<String> lines = new java.util.ArrayList<>();
        lines.add("| " + String.join(" | ", headers) + " |");
        for (String[] row : rows) {
            rs.add(List.of(row));
            lines.add("| " + String.join(" | ", row) + " |");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("type", "table");
        m.put("headers", List.of(headers));
        m.put("rows", rs);
        m.put("lines", lines);
        return m;
    }

    private static String s(Object v, String dft) { return v == null ? dft : String.valueOf(v); }

    /**
     * 演示主体证件号派生：按主体名/槽位确定性生成，前缀按主体组隔离。
     * 同名同码 → 复现同一归并记录（MATCH）；异名异码 → 走 NEW，规避多场景共用固定证件号
     * 被不同主体抢先绑定后的 ENT_1006 同人不同证幂等冲突。
     */
    private static String demoCode(String prefix, String seed) {
        return String.format(prefix + "%04dX", Math.abs(seed.hashCode()) % 10000);
    }

    /** 归并结果主标识展示：MATCH 时取顶层 goldenId，NEW/REVIEW 等无既存黄金记录时展示归并决策 */
    private static String goldenIdOf(JsonNode d) {
        String gid = d.path("goldenId").asText("");
        if (!gid.isBlank()) return gid;
        String dec = d.path("match").path("decision").asText("");
        return dec.isBlank() ? "-" : "无（决策 " + dec + "）";
    }

    /** 数值槽位兜底：LLM 传参优先，否则用剧本默认值（关键词路径不带 LLM 槽位） */
    private static Object numOr(Object v, Object dft) {
        if (v instanceof Number n) return n;
        if (v instanceof String t && t.matches("\\d+(\\.\\d+)?")) return Double.valueOf(t);
        return dft;
    }

    /** 字符串槽位兜底 */
    private static String strOr(Object v, String dft) { return v == null || String.valueOf(v).isBlank() ? dft : String.valueOf(v); }

    /** 还款方式中文名 */
    private static String methodCn(Object v) {
        return switch (s(v, "EQUAL_INSTALLMENT")) {
            case "EQUAL_PRINCIPAL" -> "等额本金";
            case "PERIOD_INTEREST" -> "按期付息到期还本";
            case "BULLET" -> "利随本清";
            default -> "等额本息";
        };
    }

    /** 金额（元）→ 万元展示 */
    private static String wan(Object v) {
        double d = v instanceof Number n ? n.doubleValue() : 0;
        return d % 10000 == 0 ? String.valueOf((long) (d / 10000)) : String.format("%.2f", d / 10000);
    }

    /** 从 repayment-plan 响应提取末期结清日；无数据时返回通用文案 */
    private static String lastPayoff(Map<String, JsonNode> sd) {
        JsonNode schedule = planSchedule(sd);
        if (schedule != null && schedule.isArray() && schedule.size() > 0) {
            String date = schedule.get(schedule.size() - 1).path("date").asText("");
            if (!date.isBlank()) {
                return "末月 " + date + " 结清，完整 " + schedule.size() + " 期计划表见滚动日志";
            }
        }
        return "完整计划表见滚动日志";
    }

    /** 取 S6-2 st3 还款计划 schedule */
    private static JsonNode planSchedule(Map<String, JsonNode> sd) {
        JsonNode plan = sd == null ? null : sd.get("st3");
        return plan == null ? null : plan.path("schedule");
    }

    /** 金额千分位（保留2位小数） */
    private static String fmtNum(Object v) {
        double d = v instanceof Number n ? n.doubleValue() : 0;
        return String.format("%,.2f", d);
    }

    /** 逐期还款表（markdown 管道表格，前端渲染为真表格）；无数据时返回兜底文案行 */
    private static List<String> scheduleTable(Map<String, JsonNode> sd) {
        JsonNode schedule = planSchedule(sd);
        if (schedule == null || !schedule.isArray() || schedule.isEmpty()) {
            return List.of(lastPayoff(sd));
        }
        JsonNode plan = sd.get("st3");
        double principal0 = 0;
        for (JsonNode it : schedule) {
            principal0 += it.path("principal").asDouble(0);
        }
        List<String> lines = new java.util.ArrayList<>();
        lines.add("| 期次 | 还款日 | 还款额(元) | 其中本金(元) | 其中利息(元) | 剩余本金(元) |");
        double paid = 0;
        for (JsonNode it : schedule) {
            double p = it.path("principal").asDouble(0);
            paid += p;
            lines.add("| " + it.path("period").asInt() + " | " + it.path("date").asText("-")
                    + " | " + fmtNum(it.path("payment").asDouble())
                    + " | " + fmtNum(p) + " | " + fmtNum(it.path("interest").asDouble())
                    + " | " + fmtNum(Math.max(principal0 - paid, 0)) + " |");
        }
        return lines;
    }

    // ---------- 真实原子步骤快捷构造（原占位步骤升级：确定性仿真原子已上线） ----------

    /** 审计留痕 audit-log-agent（:8140 /api/bai/audit-log/write，who/what/when/where/result 五元组留痕） */
    private static SceneStep realAudit(String id, String name, List<String> dependsOn) {
        return real(id, name, "audit-log", dependsOn, ":8140/api/bai/audit-log/write",
                (sl, sm, sd) -> map("events", List.of(map(
                        "who", map("userId", "scene-bff", "role", "AGENT", "orgId", "ORG-DEMO"),
                        "what", map("action", "SCENE_STEP_DONE", "objectType", "SCENE_STEP", "objectId", name),
                        "when", java.time.OffsetDateTime.now().toString(),
                        "where", map("channel", "SCENE_BFF", "ip", "127.0.0.1", "traceId", "scene-bff-demo"),
                        "result", map("code", "0", "message", "ok"),
                        "sensitive", false, "masked", false))),
                (sl, d) -> "审计受理 " + d.path("accepted").asInt(0) + " 条，logId=" + d.path("logIds").path(0).asText("-"));
    }

    /** 文本生成 text-gen-agent（:8141 /api/bai/text-gen/generate，FREE 模式按风格模板确定性成文） */
    private static SceneStep realTextGen(String id, String name, List<String> dependsOn) {
        String topic = name.contains("text-gen-agent ") ? name.substring(name.indexOf("text-gen-agent ") + 15) : name;
        return real(id, name, "text-gen", dependsOn, ":8141/api/bai/text-gen/generate",
                (sl, sm, sd) -> map("mode", "FREE", "points", List.of(topic), "style", "FORMAL", "maxLength", 200),
                (sl, d) -> "已生成文本（" + d.path("length").asInt(0) + " 字，合规"
                        + (d.path("compliance").path("passed").asBoolean(false) ? "通过" : "拦截") + "）：" + d.path("text").asText("-"));
    }

    /** 任务调度 task-schedule-agent（:8142 /api/bai/task/schedule，注册定时调度并返回下次触发时间） */
    private static SceneStep realTask(String id, String name, List<String> dependsOn, String cron) {
        return real(id, name, "task-schedule", dependsOn, ":8142/api/bai/task/schedule",
                (sl, sm, sd) -> map("name", name, "cron", cron,
                        "target", map("agent", "scene-bff", "action", "SCENE_STEP", "params", map()),
                        "policy", map("priority", "P2")),
                (sl, d) -> "调度已注册 " + d.path("scheduleId").asText("-") + "（" + d.path("cron").asText("-")
                        + "），下次触发 " + d.path("nextFireTime").asText("-"));
    }

    /** 任务调度缺省 cron：每日 09:00 触发 */
    private static SceneStep realTask(String id, String name, List<String> dependsOn) {
        return realTask(id, name, dependsOn, "0 0 9 * * ?");
    }

    // ---------- 批次2：已有模块接线（ocr / ner / summary / quality / sentiment / semantic / orchestrate / notify / metric / report） ----------

    /** OCR 识别 ocr-agent（:8101 /api/bai/ocr/recognize JSON 联调通道，imageRef 为演示影像标识） */
    private static SceneStep realOcr(String id, String name, List<String> dependsOn, String docType, String imageRef) {
        return real(id, name, "ocr", dependsOn, ":8101/api/bai/ocr/recognize",
                (sl, sm, sd) -> map("imageRef", imageRef, "docType", docType, "mask", true),
                (sl, d) -> "识别完成 docType=" + d.path("docType").asText("-") + "，字段 " + d.path("fields").size()
                        + " 个，平均置信度 " + String.format("%.2f", d.path("confidence").asDouble(0)));
    }

    /** NER 实体抽取 ner-agent（:8111 /api/bai/ner/extract） */
    private static SceneStep realNer(String id, String name, List<String> dependsOn, String text, String scene) {
        return real(id, name, "ner", dependsOn, ":8111/api/bai/ner/extract",
                (sl, sm, sd) -> map("text", text, "scene", scene),
                (sl, d) -> "抽取实体 " + d.path("entities").size() + " 个（scene=" + d.path("scene").asText("-") + "）");
    }

    /** 摘要生成 summary-agent（:8115 /api/bai/summary/generate，要点式，≤120 字） */
    private static SceneStep realSummary(String id, String name, List<String> dependsOn, String text) {
        return real(id, name, "summary", dependsOn, ":8115/api/bai/summary/generate",
                (sl, sm, sd) -> map("text", text, "mode", "bullet", "maxChars", 120, "extractKeyInfo", true),
                (sl, d) -> "摘要：" + truncate(d, 60));
    }

    /** 数据质量检查 data-quality-agent（:8130 /api/bai/quality/check，assetId 须在质量白名单） */
    private static SceneStep realQuality(String id, String name, List<String> dependsOn, String assetId) {
        return real(id, name, "data-quality", dependsOn, ":8130/api/bai/quality/check",
                (sl, sm, sd) -> map("assetId", assetId, "asOf", "2026-11-04"),
                (sl, d) -> "质量分 " + String.format("%.1f", d.path("qualityScore").asDouble(0))
                        + "，问题 " + d.path("issues").size() + " 项（checkId=" + d.path("checkId").asText("-") + "）");
    }

    /** 情绪分析 sentiment-agent（:8113 /api/bai/sentiment/analyze） */
    private static SceneStep realSentiment(String id, String name, List<String> dependsOn, String text, String scene) {
        return real(id, name, "sentiment", dependsOn, ":8113/api/bai/sentiment/analyze",
                (sl, sm, sd) -> map("text", text, "scene", scene),
                (sl, d) -> "情绪=" + d.path("emotion").path("category").asText("-")
                        + "，强度 L" + d.path("intensity").path("level").asInt(0));
    }

    /** 语义匹配 semantic-match-agent（:8114 /api/bai/semantic/match） */
    private static SceneStep realSemantic(String id, String name, List<String> dependsOn, String query, String candidate, String scene) {
        return real(id, name, "semantic-match", dependsOn, ":8114/api/bai/semantic/match",
                (sl, sm, sd) -> map("queryText", query, "candidateText", candidate, "scene", scene),
                (sl, d) -> "相似度 " + String.format("%.2f", d.path("score").asDouble(0))
                        + "，等级=" + d.path("level").asText("-"));
    }

    /** 编排计划 agent-orchestrate-agent（:8108 /api/bai/agent-orchestrate/plan，goal 描述编排目标） */
    private static SceneStep realOrchestrate(String id, String name, List<String> dependsOn, String goal) {
        return real(id, name, "agent-orchestrate", dependsOn, ":8108/api/bai/agent-orchestrate/plan",
                (sl, sm, sd) -> map("goal", goal, "constraints", map("时限", "当日", "人工确认点", "无")),
                (sl, d) -> "编排计划 planId=" + d.path("planId").asText("-")
                        + "，步骤 " + d.path("estimatedSteps").asInt(d.path("steps").size()) + " 个");
    }

    /** 通知发送 notify-agent（:8106 /api/bai/notify/send，templateCode 须已注册模板） */
    private static SceneStep realNotify(String id, String name, List<String> dependsOn,
                                        String templateCode, String custName, String phone, java.util.Map<String, Object> params) {
        return real(id, name, "notify", dependsOn, ":8106/api/bai/notify/send",
                (sl, sm, sd) -> {
                    java.util.Map<String, Object> ps = new java.util.LinkedHashMap<>(params);
                    ps.putIfAbsent("customerName", custName);
                    return map("templateCode", templateCode,
                            "receiver", map("customerName", custName, "phone", phone),
                            "params", ps, "channels", List.of("SMS"), "priority", "P1");
                },
                (sl, d) -> "受理回执 messageId=" + d.path("messageId").asText("-") + "，状态=" + d.path("status").asText("-"));
    }

    /** 指标计算 metric-calc-agent（:8128 /api/bai/metric/calc，复合指标 value 为键值组） */
    private static SceneStep realMetric(String id, String name, List<String> dependsOn, String metricId, java.util.Map<String, Object> params) {
        return real(id, name, "metric-calc", dependsOn, ":8128/api/bai/metric/calc",
                (sl, sm, sd) -> map("metricId", metricId, "params", params),
                (sl, d) -> "指标 " + d.path("metricId").asText(metricId) + "：" + truncate(d.path("value"), 70));
    }

    /** 报告生成 report-gen-agent（:8131 /api/bai/report/generate，templateId 须已注册） */
    private static SceneStep realReport(String id, String name, List<String> dependsOn, String templateId, java.util.Map<String, Object> params) {
        return real(id, name, "report-gen", dependsOn, ":8131/api/bai/report/generate",
                (sl, sm, sd) -> map("templateId", templateId, "params", params, "format", "PDF"),
                (sl, d) -> "报告 reportId=" + d.path("reportId").asText("-") + "（PDF）已生成");
    }

    /** 从步骤名称中提取 marker 之后的关键描述文本，用于构造原子入参文本 */
    private static String topic(String name, String marker) {
        int i = name.indexOf(marker);
        String t = i >= 0 ? name.substring(i + marker.length()) : name;
        return t.replaceAll("^[：:\\s]+", "").replaceAll("[？?。]+$", "").trim();
    }

    /** 文档解析 doc-parse-agent（:8143 /api/bai/docparse/parse JSON 通道，fileName 须含 pdf/jpg 等合法扩展名） */
    private static SceneStep realDocParse(String id, String name, List<String> dependsOn, String fileName) {
        return realDocParse(id, name, dependsOn, fileName, null);
    }

    private static SceneStep realDocParse(String id, String name, List<String> dependsOn,
                                          String fileName, String docType) {
        return real(id, name, "doc-parse-agent", dependsOn, ":8143/api/bai/docparse/parse",
                (sl, sm, sd) -> {
                    java.util.Map<String, Object> m = map("fileName", fileName);
                    if (docType != null) {
                        m.put("docType", docType);
                    }
                    return m;
                },
                (sl, d) -> "解析 docId=" + d.path("docId").asText("-") + "，类型=" + d.path("docType").asText("-")
                        + "，KV 字段 " + d.path("kv").size() + " 个" + (d.path("validation").path("needReview").asBoolean(false) ? "（含低置信待复核）" : ""));
    }

    /** 结构化生成 structure-gen-agent（:8144 /api/bai/structure-gen/generate，schemaId 须为内置 Schema） */
    private static SceneStep realStructure(String id, String name, List<String> dependsOn,
                                           String schemaId, String inputType, String input, String outputType) {
        return real(id, name, "structure-gen-agent", dependsOn, ":8144/api/bai/structure-gen/generate",
                (sl, sm, sd) -> map("schemaId", schemaId, "inputType", inputType, "input", input,
                        "outputType", outputType, "bizKey", "SB-" + id),
                (sl, d) -> "生成 genId=" + d.path("genId").asText("-") + "，outputType=" + d.path("outputType").asText(outputType)
                        + "，实体映射 " + d.path("entityMapped").asInt(0) + " 个，校验"
                        + (d.path("validation").path("passed").asBoolean(false) ? "通过" : "未通过"));
    }

    /** 合规检查 compliance-check-agent（:8145 /api/bai/compliance/check，scene ∈ ADMISSION/CONTRACT/RED_LINE/PRODUCT） */
    private static SceneStep realCompliance(String id, String name, List<String> dependsOn,
                                            String scene, java.util.Map<String, Object> business) {
        return real(id, name, "compliance-check", dependsOn, ":8145/api/bai/compliance/check",
                (sl, sm, sd) -> map("scene", scene, "business", business),
                (sl, d) -> "合规 checkId=" + d.path("checkId").asText("-") + "，结论=" + d.path("conclusion").asText("-")
                        + "，风险等级=" + d.path("riskLevel").asText("-") + "，命中规则 " + d.path("hits").size() + " 条");
    }

    /** 敏感信息脱敏 sensitive-mask-agent（:8146 /api/bai/sensitive-mask/mask，dataType ∈ FIELD/TEXT/BATCH，scenario 须为 6 类之一） */
    private static SceneStep realMask(String id, String name, List<String> dependsOn,
                                      String dataType, String text, String scenario) {
        return real(id, name, "sensitive-mask", dependsOn, ":8146/api/bai/sensitive-mask/mask",
                (sl, sm, sd) -> map("dataType", dataType, "text", text, "scenario", scenario),
                (sl, d) -> "脱敏 maskId=" + d.path("maskId").asText("-") + "，识别敏感字段 " + d.path("detectedCount").asInt(0) + " 处");
    }

    /** 名单筛查 screening-agent（:8147 /api/bai/screening/screen，scene ∈ ADMISSION/AML/DUE_DILIGENCE/RE_SCREEN） */
    private static SceneStep realScreening(String id, String name, List<String> dependsOn,
                                           String scene, String subjectName) {
        return realScreening(id, name, dependsOn, scene, subjectName, null);
    }

    private static SceneStep realScreening(String id, String name, List<String> dependsOn,
                                           String scene, String subjectName, String idNo) {
        return real(id, name, "screening-agent", dependsOn, ":8147/api/bai/screening/screen",
                (sl, sm, sd) -> map("scene", scene,
                        "subject", idNo == null ? map("name", subjectName) : map("name", subjectName, "idNo", idNo)),
                (sl, d) -> "筛查 screenId=" + d.path("screenId").asText("-") + "，结论=" + d.path("conclusion").asText("-")
                        + "，命中 " + d.path("hits").size() + " 项" + (d.path("escalated").asBoolean(false) ? "（已升级处置）" : ""));
    }

    /** 风险信号 risk-signal-agent（:8148 /api/bai/risk-signal/detect，sources ⊆ FLOW/JUDICIAL/INDUSTRY/TAX/PUBLIC，events 1-100 条） */
    private static SceneStep realRiskSignal(String id, String name, List<String> dependsOn,
                                            String entityId, List<String> sources, List<String> events) {
        return real(id, name, "risk-signal", dependsOn, ":8148/api/bai/risk-signal/detect",
                (sl, sm, sd) -> map("entity", map("entityType", "ORG", "entityId", entityId),
                        "sources", sources, "events", events),
                (sl, d) -> "信号 signalId=" + d.path("signalId").asText("-") + "，等级=" + d.path("aggregated").path("aggLevel").asText("-")
                        + "，综合分=" + d.path("aggregated").path("aggScore").asDouble(0) + (d.path("alertTriggered").asBoolean(false) ? "，已触发预警" : ""));
    }

    /** 模型推理 model-infer-agent（:8149 /api/bai/model/infer/score，modelId 须为已注册模型且 features 满足必填，RATING 场景须带 customerId） */
    private static SceneStep realModelInfer(String id, String name, List<String> dependsOn, String scene,
                                            String modelType, String modelId, String customerId,
                                            java.util.Map<String, Object> features) {
        return real(id, name, "model-infer", dependsOn, ":8149/api/bai/model/infer/score",
                (sl, sm, sd) -> {
                    java.util.Map<String, Object> m = map("scene", scene, "modelType", modelType,
                            "modelId", modelId, "features", features);
                    if (customerId != null) {
                        m.put("customerId", customerId);
                    }
                    return m;
                },
                (sl, d) -> "评分=" + d.path("score").asText("-") + "，等级=" + d.path("grade").asText("-")
                        + "，概率=" + d.path("probability").asDouble(0) + (d.path("needReview").asBoolean(false) ? "（需人工复核）" : ""));
    }

    /** 图谱穿透 graph-penetrate-agent（:8150 /api/bai/graph-penetrate/penetrate，direction ∈ UPSTREAM/DOWNSTREAM，maxDepth ≤10） */
    private static SceneStep realGraphPenetrate(String id, String name, List<String> dependsOn,
                                                String entityId, String direction, int maxDepth) {
        return real(id, name, "graph-penetrate", dependsOn, ":8150/api/bai/graph-penetrate/penetrate",
                (sl, sm, sd) -> map("entity", map("entityType", "ORG", "entityId", entityId),
                        "direction", direction, "maxDepth", maxDepth),
                (sl, d) -> "穿透 penetrateId=" + d.path("penetrateId").asText("-") + "，路径 " + d.path("paths").size() + " 条，股东 "
                        + d.path("equitySummary").size() + " 户"
                        + (d.path("cycleDetected").asBoolean(false) ? "，检测到循环持股" : ""));
    }

    /** 待办跟踪 todo-agent（:8151 /api/bai/todo/create，source ∈ APPROVAL/INSPECT/COLLECT/DISBURSE，assignee 须含 userId/roleId/orgId） */
    private static SceneStep realTodo(String id, String name, List<String> dependsOn,
                                      String source, String bizId, String title, String assigneeId) {
        return real(id, name, "todo", dependsOn, ":8151/api/bai/todo/create",
                (sl, sm, sd) -> map("source", source, "bizId", bizId, "title", title,
                        "assignee", map("type", "USER", "id", assigneeId)),
                (sl, d) -> "待办 todoId=" + d.path("todoId").asText("-") + "，状态=" + d.path("status").asText("PENDING"));
    }

    /** 事件订阅 event-subscribe-agent（:8152 /api/bai/event-subscribe/publish，eventType 须为预置类型且 payload 含必填字段） */
    private static SceneStep realEvent(String id, String name, List<String> dependsOn,
                                       String eventType, String bizKey, java.util.Map<String, Object> payload) {
        return real(id, name, "event-subscribe", dependsOn, ":8152/api/bai/event-subscribe/publish",
                (sl, sm, sd) -> map("eventType", eventType, "bizKey", bizKey, "payload", payload),
                (sl, d) -> "事件 eventId=" + d.path("eventId").asText("-") + "，状态=" + d.path("status").asText("-")
                        + "，命中订阅 " + d.path("matchedSubscriptions").asInt(0) + " 个、分发 " + d.path("dispatched").asInt(0) + " 路");
    }

    /** 审批路由 approval-route-agent（:8153 /api/bai/approval-route/route，按金额/风险等级确定性路由） */
    private static SceneStep realApproval(String id, String name, List<String> dependsOn,
                                          String bizType, double amount, String riskLevel) {
        return real(id, name, "approval-route", dependsOn, ":8153/api/bai/approval-route/route",
                (sl, sm, sd) -> map("bizType", bizType,
                        "context", map("amount", amount, "riskLevel", riskLevel, "org", "普惠金融部", "product", "流动资金贷款")),
                (sl, d) -> "路由 routeId=" + d.path("routeId").asText("-") + " → 节点=" + d.path("nodeName").asText("-")
                        + "，审批人 " + d.path("approvers").size() + " 名，时限 " + d.path("timeLimit").path("hours").asInt(24) + " 小时");
    }

    /** 反洗钱监测 aml-agent（:8154 /api/bai/aml/monitor，window ∈ T1H/T1D/T7D/T30D，transactions 每笔须含 amount） */
    private static SceneStep realAml(String id, String name, List<String> dependsOn, String window,
                                     java.util.List<java.util.Map<String, Object>> txns) {
        return real(id, name, "aml", dependsOn, ":8154/api/bai/aml/monitor",
                (sl, sm, sd) -> map("window", window, "scene", "ALL", "transactions", txns),
                (sl, d) -> "监测 monitorId=" + d.path("monitorId").asText("-") + "，风险等级=" + d.path("riskLevel").asText("-")
                        + "，触发规则 " + d.path("triggers").size() + " 条，可疑=" + d.path("suspicious").asBoolean(false));
    }

    /** 图像处理 image-agent（:8155 /api/bai/image/process JSON 通道，fileName 须带 jpg/png 等合法扩展名） */
    private static SceneStep realImage(String id, String name, List<String> dependsOn,
                                       String fileName, String scene, String stepsJson) {
        return real(id, name, "image-agent", dependsOn, ":8155/api/bai/image/process",
                (sl, sm, sd) -> map("fileName", fileName, "scene", scene, "steps", stepsJson),
                (sl, d) -> "处理完成 imageId=" + d.path("imageId").asText("-") + "，页数 " + d.path("pages").size()
                        + "，耗时 " + d.path("elapsedMs").asInt(0) + "ms");
    }

    /** 影像质量评估 image-agent（:8155 /api/bai/image/quality JSON 通道，返回五维评分与 ACCEPT/RETRY/REJECT 建议） */
    private static SceneStep realImageQ(String id, String name, List<String> dependsOn,
                                        String fileName, String scene) {
        return real(id, name, "image-agent", dependsOn, ":8155/api/bai/image/quality",
                (sl, sm, sd) -> map("fileName", fileName, "scene", scene),
                (sl, d) -> "质检 imageId=" + d.path("imageId").asText("-") + "，综合分 "
                        + d.path("quality").path("score").asDouble(0) + "，建议=" + d.path("quality").path("suggestion").asText("-"));
    }

    /** 欺诈检测 fraud-agent（:8156 /api/bai/fraud/check，scene ∈ PRE_LOAN/TRANS_MONITOR/APPLY_GUARD，交易监控须带 txnRef） */
    private static SceneStep realFraud(String id, String name, List<String> dependsOn,
                                       String scene, String entityType, String entityId, String txnRef) {
        return real(id, name, "fraud-agent", dependsOn, ":8156/api/bai/fraud/check",
                (sl, sm, sd) -> {
                    java.util.Map<String, Object> m = map("scene", scene,
                            "entity", map("entityType", entityType, "entityId", entityId));
                    if (txnRef != null) {
                        m.put("txnRef", txnRef);
                    }
                    return m;
                },
                (sl, d) -> "案例 caseId=" + d.path("caseId").asText("-") + "，决策=" + d.path("decision").asText("-")
                        + "，欺诈评分=" + d.path("fraudScore").asInt(0));
    }

    /** 界面操作 ui-ops-agent（:8157 /api/bai/ui-ops/operate，action ∈ FILL/SELECT/CLICK/SUBMIT/UPLOAD，FILL/SELECT 须带 value） */
    private static SceneStep realUiOps(String id, String name, List<String> dependsOn,
                                       String sessionId, String element, String action, String value) {
        return real(id, name, "ui-ops", dependsOn, ":8157/api/bai/ui-ops/operate",
                (sl, sm, sd) -> {
                    java.util.Map<String, Object> m = map("sessionId", sessionId, "element", element, "action", action);
                    if (value != null) {
                        m.put("value", value);
                    }
                    return m;
                },
                (sl, d) -> "操作 opId=" + d.path("opId").asText("-") + "，结果=" + d.path("result").asText("-")
                        + "，页面快照 hash=" + d.path("pageSnapshot").path("hash").asText("-"));
    }

    /** 联合操作执行 call-exec-agent（:8158 /api/bai/call-exec/execute，definitionId 须为已发布定义且 params 满足必填契约） */
    private static SceneStep realCallExec(String id, String name, List<String> dependsOn,
                                          String definitionId, java.util.Map<String, Object> params) {
        return real(id, name, "call-exec-agent", dependsOn, ":8158/api/bai/call-exec/execute",
                (sl, sm, sd) -> map("definitionId", definitionId, "params", params),
                (sl, d) -> "调用 callId=" + d.path("callId").asText("-") + "，状态=" + d.path("status").asText("-")
                        + "，回执=" + truncate(d.path("result"), 60));
    }

    /** 语音转写 asr-agent（:8159 /api/bai/asr/transcribe JSON 通道，scene ∈ collection/customer-service/voice-command/recording/general） */
    private static SceneStep realAsr(String id, String name, List<String> dependsOn,
                                     String audioRef, String scene) {
        return real(id, name, "asr-agent", dependsOn, ":8159/api/bai/asr/transcribe",
                (sl, sm, sd) -> map("audioRef", audioRef, "scene", scene, "lang", "zh"),
                (sl, d) -> "转写 asrId=" + d.path("asrId").asText("-") + "，状态=" + d.path("status").asText("-")
                        + "，文本=" + truncate(d.path("fullText"), 60));
    }

    static {
        // ============ S1-1 营销洞察与精准触达 ============
        REGISTRY.put("S1-1", new SceneScript(
                "S1-1", "marketing-insight-agent", "营销洞察与精准触达",
                List.of("圈客", "筛出", "营销名单", "发短信", "触达", "经营贷营销", "提额空间", "复贷空间", "存量客户盘点", "营销机会"),
                "您好，我是营销洞察助手。您可以直接一句话告诉我要做什么，例如“把名下房贷即将到期的客户筛出来，推荐经营贷，发短信触达”。我会自动圈客、写话术并在您确认后发送。",
                "本次将为您完成：1.客群圈选 2.产品准入核对 3.图谱洞察 4.话术生成 5.触达发送确认",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并客户身份，合并同人多户", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> {
                                    String cond = s(sl.get("condition"), "名下房贷90天内到期");
                                    // 演示主体证件号按客群条件确定性派生（前缀 91310001 与其他场景固定演示码隔离）：
                                    // 同条件复现同一归并记录（MATCH），不同条件走 NEW，规避多场景共用固定证件号被
                                    // 不同主体抢先绑定后的 ENT_1006 同人不同证幂等冲突降级
                                    String code = String.format("91310001MA1FL%04dX", Math.abs(cond.hashCode()) % 10000);
                                    return map("record", map("name", "宏远商贸", "idCard", code),
                                            "source", "core", "entityType", "COMPANY");
                                },
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d) + "，指纹=" + d.path("fingerprint").asText("-")),
                        real("st2", "② 调用 knowledge-process-agent 核对推荐产品的目标客群准入口径", "knowledge-process", deps("st1"),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "DOC-S11-001", "text", "随借随还经营贷目标客群准入：名下存量房贷客户且到期前90天，还款记录正常、无当前逾期；经营实体成立满2年；额度上限300万，年利率3.45%起。", "source", "policy"),
                                null),
                        real("st3", "③ 调用 metric-calc-agent 按客群条件圈选并统计分布", "metric-calc", deps("st1"),
                                ":8128/api/bai/metric/calc",
                                (sl, sm, sd) -> map("metricId", "LOAN_BALANCE", "params", map("orgId", "ORG-DEMO", "date", "2026-10-31",
                                        "condition", s(sl.get("condition"), "名下房贷90天内到期"))),
                                (sl, d) -> "客群资产规模测算值=" + d.path("value").asText("-") + s(d.path("unit").asText(""), "") + "（确定性仿真，随客群条件变化）"),
                        real("st4", "④ 调用 data-query-agent 查询客群资产与到期分布", "data-query", deps("st1"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_core", "table", "loan_balance_daily",
                                        "fields", List.of("branch_code", "loan_balance")),
                                null),
                        realStructure("st5", "⑤ 调用 structure-gen-agent 生成客群分布与图谱数据形态", deps("st3", "st4"), "SCHEMA_REPORT_OUTLINE_V1", "TEXT", "客群分布与图谱数据形态", "JSON"),
                        realTextGen("st6", "⑥ 调用 text-gen-agent 按客群标签生成 2～3 版触达话术", deps("st3", "st4")),
                        human("st7", "⚑ 人工确认节点：名单、话术、条数与渠道推送营销人员终审", deps("st2", "st5", "st6")),
                        real("st8", "⑦ 调用 notify-agent 批量发送触达消息", "notify", deps("st7"),
                                ":8106/api/bai/notify/send",
                                (sl, sm, sd) -> map("templateCode", "MARKETING_PROMO",
                                        "receiver", map("customerName", "演示客户", "phone", "13800001110"),
                                        "params", map("customerName", "演示客户"),
                                        "channels", List.of("SMS"), "priority", "P1"),
                                (sl, d) -> "受理回执 messageId=" + d.path("messageId").asText("-") + "，状态=" + d.path("status").asText("-")),
                        realAudit("st9", "⑧ 调用 audit-log-agent 记录圈选条件、名单、话术与发送回执", deps("st8"))),
                "回复 1 确认发送，回复 2 修改方案，回复 3 暂不发送",
                (sl, sm, sd) -> {
                    String cond = s(sl.get("condition"), "名下房贷90天内到期");
                    // 圈选户数随客群条件确定性派生（同条件复现一致）；默认条件保持 486 户与提示词库示例口径一致
                    int hh = "名下房贷90天内到期".equals(cond) ? 486 : 300 + Math.abs(cond.hashCode()) % 400;
                    String prod = s(sl.get("product"), "随借随还经营贷");
                    return List.of(
                        block("客群总览",
                                "客群条件：" + cond,
                                "圈选户数：" + hh + " 户（确定性演示，随客群条件变化），按到期月份分布已生成",
                                "数据来源：客户数据中心 + entity-resolution 归并"),
                        block("图谱洞察",
                                "发现担保圈 2 个、关联企业 3 家",
                                "高风险关联客户 1 户（关联企业涉诉），建议人工关注"),
                        block("产品与话术",
                                "产品：" + prod + "，年利率 3.45% 起",
                                "话术 v1：客群条件已命中，" + prod + "可随时支取，年利率3.45%起，名额有限。",
                                "话术 v2：专属客群方案，" + prod + "额度最高300万，随借随还按日计息。"),
                        block("确认与执行",
                                "渠道：" + s(sl.get("channel"), "短信") + "，发送条数：" + s(sl.get("limit"), "300") + " 条",
                                "回复 1 确认发送，回复 2 修改方案，回复 3 暂不发送"));
                }));

        // ============ S1-2 智能营销活动策划与执行 ============
        REGISTRY.put("S1-2", new SceneScript(
                "S1-2", "marketing-campaign-agent", "智能营销活动策划与执行",
                List.of("活动", "营销季", "优惠活动", "策划"),
                "您好，我是营销活动助手。您可以直接一句话说想做什么活动，例如“三季度针对代发工资客群做信用卡分期费率优惠活动，为期一个月”，我会自动出方案、排期和话术。",
                "本次将为您完成：1.活动方案 2.客群规模测算 3.三阶段排期 4.话术生成 5.推送执行确认",
                List.of(
                        real("st1", "① 调用 knowledge-process-agent 核对产品可做权益的政策边界", "knowledge-process", deps(),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "DOC-S12-001", "text", "信用卡分期费率优惠活动政策边界：手续费折扣下限8折，不得与合同条款冲突，权益表述须经营销管理部门审定。", "source", "policy"),
                                null),
                        real("st2", "② 调用 metric-calc-agent 测算目标客群规模与分层", "metric-calc", deps(),
                                ":8128/api/bai/metric/calc",
                                (sl, sm, sd) -> map("metricId", "LOAN_BALANCE", "params", map("orgId", "ORG-DEMO", "date", "2026-10-31")),
                                (sl, d) -> "客群规模测算值=" + d.path("value").asText("-") + "（确定性仿真）"),
                        realTextGen("st3", "③ 调用 text-gen-agent 生成活动方案（权益规则、节奏、渠道、话术要点）", deps("st1", "st2")),
                        human("st4", "⚑ 人工确认节点：权益规则与预算推送营销管理部门审定", deps("st3")),
                        realTask("st5", "④ 调用 task-schedule-agent 生成活动排期（预热/引爆/收尾三阶段日程）", deps("st4")),
                        realTextGen("st6", "⑤ 调用 text-gen-agent 按阶段生成活动通知话术", deps("st4")),
                        real("st7", "⑥ 调用 notify-agent 按排期推送活动通知", "notify", deps("st5", "st6"),
                                ":8106/api/bai/notify/send",
                                (sl, sm, sd) -> map("templateCode", "MARKETING_PROMO",
                                        "receiver", map("customerName", "演示客户", "phone", "13800001112"),
                                        "params", map("customerName", "演示客户"),
                                        "channels", List.of("SMS"), "priority", "P1"),
                                (sl, d) -> "受理回执 messageId=" + d.path("messageId").asText("-")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录活动方案、确认记录与执行数据", deps("st7"))),
                "回复 1 确认方案进入排期执行，回复 2 修改权益或客群，回复 3 暂停",
                (sl, sm, sd) -> List.of(
                        block("活动方案",
                                "主题：" + s(sl.get("theme"), "代发工资客群分期费率优惠"),
                                "目标客群：12.6 万户（确定性演示），权益：手续费 9 折",
                                "周期：" + s(sl.get("period"), "2026-10-08 至 2026-11-07")),
                        block("排期计划",
                                "预热 10-08 ｜ 引爆 10-15 ｜ 收尾 11-01（三阶段日程）"),
                        block("话术示例",
                                "预热版：三季度专属福利，代发客户分期手续费限时9折。",
                                "引爆版：最后3天，分期费率优惠即将结束，速来办理。"),
                        block("执行状态",
                                "回复 1 确认方案进入排期执行，回复 2 修改权益，回复 3 暂停"))));

        // ============ S1-3 客户分层与差异化经营 ============
        REGISTRY.put("S1-3", new SceneScript(
                "S1-3", "customer-segmentation-agent", "客户分层与差异化经营",
                List.of("分层", "分群", "分级", "差异化经营", "AUM", "五层"),
                "您好，我是客户分层助手。您可以直接一句话说“把我名下客户按 AUM 分五层并给经营建议”，我会自动完成分层并输出每层的经营动作。",
                "本次将为您完成：1.客户身份归并 2.分层指标计算 3.五层分布统计 4.各层经营策略",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并客户身份，合并多账户同一客户", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", "演示管户客群", "idCard", "ORG-DEMO"),
                                        "source", "core", "entityType", "COMPANY"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        real("st2", "② 调用 metric-calc-agent 计算 AUM、日均存款、中收、产品持有数等分层指标", "metric-calc", deps("st1"),
                                ":8128/api/bai/metric/calc",
                                (sl, sm, sd) -> map("metricId", "LOAN_BALANCE", "params", map("orgId", "ORG-DEMO", "date", "2026-10-31")),
                                (sl, d) -> "核心层资产测算值=" + d.path("value").asText("-") + "（确定性仿真）"),
                        realModelInfer("st3", "③ 调用 model-infer-agent 输出风险评分作为分层修正因子", deps("st1"), "RATING", "SCORECARD", "CRB-SC-2025-V3", "C-S1-3", map("annualIncome", 1200000, "assetLiabRatio", 0.35, "netCashRatio", 0.42)),
                        real("st4", "④ 调用 metric-calc-agent 统计各层客户数与资产占比", "metric-calc", deps("st2", "st3"),
                                ":8128/api/bai/metric/calc",
                                (sl, sm, sd) -> map("metricId", "BAD_LOAN_RATE", "params", map("orgId", "ORG-DEMO", "date", "2026-10-31")),
                                (sl, d) -> "风险修正因子=" + d.path("value").asText("-") + "%（确定性仿真）"),
                        realTextGen("st5", "⑤ 调用 text-gen-agent 生成各层差异化经营策略（维护/提升/激活/降级预警）", deps("st4")),
                        realStructure("st6", "⑥ 调用 structure-gen-agent 生成分层分布可视化", deps("st4"), "SCHEMA_REPORT_OUTLINE_V1", "TEXT", "分层分布可视化", "JSON"),
                        realMask("st7", "⑦ 调用 sensitive-mask-agent 对客户名单脱敏展示", deps("st4"), "TEXT", "客户名单：张伟 13800001111、王芳 13800002222、李娜 310101199001011234", "DISPLAY")),
                "回复 1 按分层结果生成各层触达方案，回复 2 只看分层结果，回复 3 调整分层口径重算",
                (sl, sm, sd) -> List.of(
                        block("分层总览",
                                "分层维度：" + s(sl.get("dimension"), "AUM+贡献+活跃度综合") + "，层级数：5",
                                "核心层 480 户占 AUM 61%｜高价值层 2100 户占 22%｜大众层 2.1 万户占 14%（确定性演示）"),
                        block("各层经营策略",
                                "核心层：专属客户经理 + 季度回访",
                                "高价值层：产品提升 + 月度触达",
                                "大众层：标准化权益 + 季度活动"),
                        block("名单明细",
                                "客户明细已脱敏展示（sensitive-mask 占位）",
                                "回复 1 按核心层/高价值层生成触达方案，回复 2 导出分层名单，回复 3 结束"))));

        // ============ S1-4 睡眠户唤醒与存量激活 ============
        REGISTRY.put("S1-4", new SceneScript(
                "S1-4", "dormant-activation-agent", "睡眠户唤醒与存量激活",
                List.of("睡眠", "休眠", "沉睡", "唤醒", "激活存量"),
                "您好，我是睡眠户唤醒助手。您可以直接一句话说“把对私 12 个月无交易的睡眠户圈出来，配零钱产品权益唤醒”，我会自动圈客、写话术并在您确认后发送。",
                "本次将为您完成：1.睡眠户圈选分级 2.产品权益匹配 3.三版唤醒话术 4.触达发送确认",
                List.of(
                        real("st1", "① 调用 data-query-agent 接入账户状态与余额数据", "data-query", deps(),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "status")),
                                null),
                        real("st2", "② 调用 metric-calc-agent 圈选睡眠户并按休眠时长分级", "metric-calc", deps("st1"),
                                ":8128/api/bai/metric/calc",
                                (sl, sm, sd) -> map("metricId", "LOAN_BALANCE", "params", map("orgId", "ORG-DEMO", "date", "2026-10-31")),
                                (sl, d) -> "睡眠户规模测算值=" + d.path("value").asText("-") + "（确定性仿真）"),
                        real("st3", "③ 调用 knowledge-process-agent 匹配适配产品与权益口径", "knowledge-process", deps("st1"),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "DOC-S14-001", "text", "零钱产品唤醒权益：七日年化约1.8%、随存随取；唤醒权益不得与产品既有收费规则冲突。", "source", "policy"),
                                null),
                        realTextGen("st4", "④ 调用 text-gen-agent 按休眠分级生成唤醒话术 3 版", deps("st2", "st3")),
                        human("st5", "⚑ 人工确认节点：唤醒名单、权益表述与条数推送营销人员确认", deps("st4")),
                        real("st6", "⑤ 调用 notify-agent 批量发送唤醒消息", "notify", deps("st5"),
                                ":8106/api/bai/notify/send",
                                (sl, sm, sd) -> map("templateCode", "MARKETING_PROMO",
                                        "receiver", map("customerName", "演示客户", "phone", "13800001113"),
                                        "params", map("customerName", "演示客户"),
                                        "channels", List.of("SMS"), "priority", "P1"),
                                (sl, d) -> "受理回执 messageId=" + d.path("messageId").asText("-")),
                        realNotify("st7", "⑥ 调用 notify-agent 对已回复客户生成 7 日内回访督办任务", deps("st6"), "TASK_URGE", "李娜", "13800001111", map("taskContent", "意向客户 7 日内回访", "deadline", "2026-11-11")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录圈选、话术、发送与回访记录", deps("st7"))),
                "回复 1 确认发送，回复 2 调整权益或名单，回复 3 取消",
                (sl, sm, sd) -> List.of(
                        block("睡眠户总览",
                                "客户范围：" + s(sl.get("scope"), "对私") + "，休眠阈值：" + s(sl.get("threshold"), "12 个月"),
                                "轻度休眠 8600 户｜中度 1.2 万户｜重度 2400 户（确定性演示）"),
                        block("唤醒方案",
                                "适配产品：零钱产品（七日年化 1.8% 左右、随存随取）",
                                "话术 v1：您的账户有专属零钱权益待领取，随存随取。",
                                "话术 v2：闲置资金也能有收益，零钱产品七日年化1.8%左右。"),
                        block("确认与执行",
                                "回复 1 确认发送 " + s(sl.get("limit"), "5000") + " 条，回复 2 修改，回复 3 取消"))));

        // ============ S1-5 交叉销售与产品推荐 ============
        REGISTRY.put("S1-5", new SceneScript(
                "S1-5", "cross-sell-agent", "交叉销售与产品推荐",
                List.of("交叉销售", "推荐产品", "产品缺口", "推荐理财", "理财", "推荐"),
                "您好，我是交叉销售助手。您可以直接一句话说“给名下只有存款的客户推荐稳健理财”，我会自动分析产品缺口、匹配产品并生成推荐话术。",
                "本次将为您完成：1.持有产品缺口分析 2.产品匹配 3.推荐话术 4.触达推送确认",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并客户身份，合并多账户持有产品视图", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", "演示管户客群", "idCard", "ORG-DEMO"),
                                        "source", "core", "entityType", "COMPANY"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        real("st2", "② 调用 knowledge-process-agent 核对产品风险等级与准入条件", "knowledge-process", deps("st1"),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "DOC-S15-001", "text", "稳健型理财风险等级 R2，准入：风险测评未过期（C3 及以上）客户；话术须含完整风险提示语，不得承诺收益。", "source", "policy"),
                                null),
                        realModelInfer("st3", "③ 调用 model-infer-agent 评分客户风险承受与产品适配度", deps("st1"), "RATING", "SCORECARD", "CRB-SC-2025-V3", "C-S1-5", map("annualIncome", 1200000, "assetLiabRatio", 0.35, "netCashRatio", 0.42)),
                        real("st4", "④ 调用 metric-calc-agent 统计产品缺口分布（有存款无理财客户数等）", "metric-calc", deps("st1"),
                                ":8128/api/bai/metric/calc",
                                (sl, sm, sd) -> map("metricId", "LOAN_BALANCE", "params", map("orgId", "ORG-DEMO", "date", "2026-10-31")),
                                (sl, d) -> "缺口客群资产测算值=" + d.path("value").asText("-") + "（确定性仿真）"),
                        realTextGen("st5", "⑤ 调用 text-gen-agent 生成分版本推荐话术（含风险提示语）", deps("st2", "st3", "st4")),
                        human("st6", "⚑ 人工确认节点：推荐名单、话术与风险提示语推送营销人员确认", deps("st5")),
                        real("st7", "⑥ 调用 notify-agent 推送推荐消息", "notify", deps("st6"),
                                ":8106/api/bai/notify/send",
                                (sl, sm, sd) -> map("templateCode", "MARKETING_PROMO",
                                        "receiver", map("customerName", "演示客户", "phone", "13800001114"),
                                        "params", map("customerName", "演示客户"),
                                        "channels", List.of("SMS"), "priority", "P1"),
                                (sl, d) -> "受理回执 messageId=" + d.path("messageId").asText("-")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录推荐依据、话术与触达记录", deps("st7"))),
                "回复 1 确认按名单触达，回复 2 调整推荐产品，回复 3 取消",
                (sl, sm, sd) -> List.of(
                        barsBlock("机会总览", "户",
                                "有存款无理财", 1840,
                                "其中 R2 适配", 1620,
                                "其中测评过期降级", 86),
                        block("推荐清单",
                                "推荐方向：" + s(sl.get("product"), "稳健型理财（R2）"),
                                "适配理由：持有产品单一、AUM 达标、风险测评匹配"),
                        block("话术示例",
                                "您名下活期存款可转投 R2 稳健理财，业绩比较基准 2.8% 左右。产品有风险，投资需谨慎。"),
                        block("确认与执行",
                                "回复 1 确认推送 " + s(sl.get("limit"), "2000") + " 条，回复 2 修改，回复 3 取消"))));

        // ============ S1-6 渠道线索管理与商机跟进 ============
        REGISTRY.put("S1-6", new SceneScript(
                "S1-6", "channel-lead-agent", "渠道线索管理与商机跟进",
                List.of("线索", "商机", "跟进", "留资"),
                "您好，我是渠道线索助手。您可以直接一句话说“汇总今天 App 留资的线索并分配给对应客户经理”，我会自动去重、分配、生成跟进计划。",
                "本次将为您完成：1.线索要素抽取 2.去重合并与分配 3.三日跟进计划 4.督办提醒",
                List.of(
                        real("st1", "① 调用 ner-agent 抽取线索要素（客户、产品方向、来源、意向程度）", "ner", deps(),
                                ":8111/api/bai/ner/extract",
                                (sl, sm, sd) -> map("text", "客户经理张伟报备：客户李娜（13800001111）咨询随借随还经营贷，意向度高，预计需求100万，来源 App 留资。", "scene", "LEAD"),
                                null),
                        realTask("st2", "② 调用 task-schedule-agent 按规则去重合并并分配客户经理", deps("st1")),
                        realTask("st3", "③ 调用 task-schedule-agent 生成 3 日内跟进计划与回访日程", deps("st2")),
                        realNotify("st4", "④ 调用 notify-agent 到期前提醒客户经理跟进", deps("st3"), "DUE_REMIND", "王建国", "13800002222", map("dueDate", "2026-11-15")),
                        realTodo("st5", "⑤ 调用 todo-agent 跟踪商机阶段（新线索/跟进中/意向明确/已申请/已关闭）", deps("st3"), "COLLECT", "BIZ-S1-6", "S1-6 st5 待办跟踪", "RM001"),
                        realEvent("st6", "？条件分支：跟进超 7 日未转化 → 调用 event-subscribe-agent 回收线索入公共池并提醒", deps("st5"), "risk.alert", "S1-6-st6", map("alertId", "LEAD-S1-6", "level", "ORANGE")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录分配、跟进与转化记录", deps("st6"))),
                "回复 1 确认分配并生成跟进计划，回复 2 调整分配对象，回复 3 暂存线索池",
                (sl, sm, sd) -> List.of(
                        block("线索总览",
                                "来源渠道：" + s(sl.get("source"), "App 留资") + "，时间范围：当日",
                                "抽取线索 46 条，去重合并 39 条，经营贷方向 28 条（确定性演示）"),
                        block("跟进计划",
                                "每条线索首访时间已排定，3 日内完成首轮回访"),
                        barsBlock("商机看板", "",
                                "新线索", 39, "跟进中", 0, "意向明确", 0),
                        block("确认与执行",
                                "回复 1 确认分配并启动提醒，回复 2 调整分配对象，回复 3 查看超时线索"))));

        // ============ S1-7 客户经理数字员工工作台 ============
        REGISTRY.put("S1-7", new SceneScript(
                "S1-7", "rm-workbench-agent", "客户经理数字员工工作台",
                List.of("待办", "工作台", "我的客户", "纪要", "数字员工"),
                "您好，我是您的数字员工。您可以直接说“我今天有哪些待办”“查一下张三客户的业务进度”“记一下刚才的拜访纪要”，我会帮您聚合处理。",
                "本次将为您完成：1.今日待办汇总 2.客户与商机进度 3.拜访纪要整理",
                List.of(
                        real("st1", "① 调用 dialog-agent 维持多轮会话状态", "dialog", deps(),
                                ":8107/api/bai/dialog/session",
                                (sl, sm, sd) -> map("action", "CREATE",
                                        "userId", s(sl.get("userId"), "rm-001"), "scene", "DIGITAL_STAFF"),
                                (sl, d) -> "会话状态就绪，dialogSession=" + d.path("sessionId").asText("-")),
                        realNotify("st2", "② 调用 notify-agent 汇总今日待办与到期提醒", deps("st1"), "TASK_URGE", "客户经理团队", "13800003333", map("taskContent", "今日待办 6 项、到期提醒 3 项", "deadline", "2026-11-04 18:00")),
                        realTodo("st3", "③ 调用 todo-agent 查询名下客户与商机进度", deps("st1"), "INSPECT", "BIZ-S1-7", "S1-7 st3 待办跟踪", "RM001"),
                        real("st4", "④ 调用 summary-agent 整理拜访纪要", "summary", deps("st1"),
                                ":8115/api/bai/summary/generate",
                                (sl, sm, sd) -> map("text", s(sl.get("memoText"), "拜访宏远商贸王总：拟将明年设备更新贷款转我行，预计需求500万，下周需要看到方案。"),
                                        "mode", "bullet", "maxChars", 100, "extractKeyInfo", true),
                                (sl, d) -> "纪要摘要：" + truncate(d, 60)),
                        realOrchestrate("st5", "⑤ 按用户诉求调度 S1-1/S1-3/S1-6 等子场景并聚合结果", deps("st2", "st3", "st4"), "调度 S1-1 客户圈选、S1-3 名单评估、S1-6 到期提醒三个子场景并聚合结果"),
                        realAudit("st6", "⑥ 调用 audit-log-agent 记录工作台操作与纪要", deps("st5"))),
                "回复 1 处理第一项待办，回复 2 记录下一次拜访，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("今日待办",
                                "经营贷申请回访 3 户｜到期提醒 2 户｜待确认营销发送 1 项",
                                "回复 1 处理第一项待办"),
                        block("客户动态",
                                "宏远商贸 AUM 较上月提升 12%（确定性演示）"),
                        block("业务进度",
                                "进行中商机 2 个：设备更新转贷（意向明确）、经营贷增额（跟进中）"),
                        block("拜访纪要",
                                s(sm.get("st4"), "纪要已整理（详见滚动日志）"),
                                "回复 2 加入下周跟进提醒，回复 3 结束"))));

        // ============ S1-8 客户画像 ============
        REGISTRY.put("S1-8", new SceneScript(
                "S1-8", "customer-profile-agent", "客户画像",
                List.of("画像", "客户视图", "客户全貌", "看看.*客户"),
                "您好，我是客户画像助手。您可以直接说客户名称或证件号，例如“看看宏远商贸的客户画像”，我会聚合资产、产品、关联关系等信息并输出画像视图。",
                "本次将为您完成：1.身份归并 2.关联关系挖掘 3.多源统计 4.画像视图输出",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并客户身份，合并多账户与历史身份关联", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", s(sl.get("customer"), "宏远商贸"), "idCard", demoCode("91310002MA1FL", s(sl.get("customer"), "宏远商贸"))),
                                        "source", "core", "entityType", "COMPANY"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        real("st2", "② 调用 graph-build-agent 挖掘股权、任职、担保、交易关联", "graph-build", deps("st1"),
                                ":8134/api/bai/graph/build",
                                (sl, sm, sd) -> map("mode", "FULL", "scope", map("goldenId", s(sl.get("goldenId"), "G-DEMO"))),
                                null),
                        real("st3", "③ 调用 metric-calc-agent 统计资产、负债、产品持有与交易行为", "metric-calc", deps("st1"),
                                ":8128/api/bai/metric/calc",
                                (sl, sm, sd) -> map("metricId", "LOAN_BALANCE", "params", map("orgId", "ORG-DEMO", "date", "2026-10-31")),
                                (sl, d) -> "资产/负债测算值=" + d.path("value").asText("-") + "（确定性仿真）"),
                        real("st4", "④ 调用 data-query-agent 查询客户关联与资产子图", "data-query", deps("st1"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_core", "table", "loan_balance_daily",
                                        "fields", List.of("branch_code", "loan_balance")),
                                null),
                        realStructure("st5", "⑤ 调用 structure-gen-agent 生成画像结构与图谱数据形态", deps("st2", "st3", "st4"), "SCHEMA_DD_CARD_V2", "TEXT", "客户画像结构与图谱形态", "CARD"),
                        realMask("st6", "⑥ 调用 sensitive-mask-agent 对敏感字段脱敏", deps("st5"), "TEXT", "客户张伟，手机 13800001111，身份证 310101199001011234，卡号 6222020200112345678", "DISPLAY"),
                        realAudit("st7", "⑦ 调用 audit-log-agent 记录查询人、查询时间与用途", deps("st6"))),
                "回复 1 基于该画像推荐适配产品，回复 2 圈出同类客户，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("基础信息",
                                "客户：" + s(sl.get("customer"), "宏远商贸") + "（制造业中型，成立 2015 年，敏感字段已脱敏）"),
                        block("资产负债与产品持有",
                                "AUM 860 万｜现有贷款 300 万（正常类）｜持有产品 3 个（确定性演示）"),
                        block("交易行为特征",
                                "近 6 月流水稳定，上下游交易集中度中等"),
                        block("关联图谱",
                                "关联企业 3 家｜担保圈 1 个｜实际控制人已识别",
                                "风险提示：1 户关联企业在他行有逾期记录，建议人工关注"))));

        // ============ S2-1 线上贷款申请受理与补件 ============
        REGISTRY.put("S2-1", new SceneScript(
                "S2-1", "application-filing-agent", "线上贷款申请受理与补件",
                List.of("受理", "申请受理", "补件", "进件"),
                "您好，我是申请受理助手。您可以直接一句话说“受理客户李娜的经营贷申请”，我会自动核验客户与要素齐套性，并生成进件编号。",
                "本次将为您完成：1.诉求解析 2.客户核验 3.要素齐套校验 4.受理确认 5.进件建档",
                List.of(
                        real("st1", "① 调用 intent-agent 解析一句话诉求并归一化", "intent", deps(),
                                ":8103/api/bai/intent/classify",
                                (sl, sm, sd) -> map("text", s(sl.get("message"), "受理客户李娜的经营贷申请，金额100万，期限36个月"),
                                        "scene", "credit", "enableOod", true, "topK", 2),
                                (sl, d) -> "意图=" + d.path("intents").path(0).path("level1").asText("-")
                                        + "/" + d.path("intents").path(0).path("level2").asText("-")
                                        + "，置信度=" + d.path("intents").path(0).path("confidence").asText("-")),
                        real("st2", "② 调用 entity-resolution-agent 核验客户身份与历史身份关联", "entity-resolution", deps("st1"),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", s(sl.get("customer"), "李娜"), "idCard", demoCode("31011019900101", s(sl.get("customer"), "李娜"))),
                                        "source", "core", "entityType", "PERSON"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        realDocParse("st3", "③ 调用 doc-parse-agent 核验申请材料齐套性（身份证明/经营证明/流水）", deps("st2"), "apply-S2-1.pdf"),
                        human("st4", "⚑ 人工确认节点：要素齐全确认受理建档，缺失项生成补件任务", deps("st2", "st3")),
                        real("st5", "④ 调用 data-validate-agent 按 T_SUBMIT 模板校验进件数据", "data-validate", deps("st4"),
                                ":8129/api/bai/validate/validate",
                                (sl, sm, sd) -> map("templateId", "T_SUBMIT",
                                        "data", map("name", s(sl.get("customer"), "李娜"),
                                                "idCard", demoCode("31011019900101", s(sl.get("customer"), "李娜")), "phone", "13800001111",
                                                "applyDate", "2026-11-03", "applyAmount", 1000000, "term", 36,
                                                "startDate", "2026-12-01", "endDate", "2029-12-01",
                                                "purpose", "经营周转")),
                                (sl, d) -> "T_SUBMIT 校验：" + d.path("result").asText("-")
                                        + "（通过 " + d.path("summary").path("passed").asInt(0) + " 项，警告 "
                                        + d.path("summary").path("warned").asInt(0) + "，失败 "
                                        + d.path("summary").path("failed").asInt(0) + "）"),
                        real("st6", "⑤ 调用 data-query-agent 查询客户历史借贷记录", "data-query", deps("st2"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        realTask("st7", "⑥ 调用 task-schedule-agent 生成进件编号与补件任务（如有缺失）", deps("st5", "st6")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录受理过程与补件留痕", deps("st7"))),
                "回复 1 确认受理建档，回复 2 补充或修改要素，回复 3 暂缓受理",
                (sl, sm, sd) -> List.of(
                        block("客户与诉求",
                                "客户：" + s(sl.get("customer"), "李娜") + "（身份已归并，历史无不良）",
                                "诉求：" + s(sl.get("message"), "经营贷申请 100 万 / 36 个月"),
                                "要素齐套性：身份证明 ✔｜经营证明 ✔｜银行流水 ✘（缺近6个月）"),
                        block("进件校验",
                                s(sm.get("st5"), "T_SUBMIT 模板校验：通过（详见滚动日志）")),
                        block("历史借贷",
                                "名下存量贷款 1 笔（正常类），无逾期记录"),
                        block("受理确认",
                                "回复 1 确认受理并生成进件编号，回复 2 补件后重试，回复 3 暂缓"))));

        // ============ S2-2 线下申请要素录入与确认 ============
        REGISTRY.put("S2-2", new SceneScript(
                "S2-2", "application-filing-agent", "线下申请要素录入与确认",
                List.of("录入", "线下申请", "要素录入", "纸质申请"),
                "您好，我是要素录入助手。您可以直接把线下申请单的要素念给我或拍照上传，我会逐项结构化并与您逐项确认。",
                "本次将为您完成：1.要素抽取 2.结构化校验 3.逐项确认 4.建档落库",
                List.of(
                        real("st1", "① 调用 ner-agent 从申请描述中抽取要素（客户、产品、金额、期限、用途）", "ner", deps(),
                                ":8111/api/bai/ner/extract",
                                (sl, sm, sd) -> map("text", s(sl.get("message"), "王总申请经营贷，金额80万，期限24个月，用途设备采购，抵押物为厂房"),
                                        "scene", "FILING"),
                                (sl, d) -> "抽取要素 " + d.path("entities").size() + " 项"),
                        real("st2", "② 调用 entity-resolution-agent 归并客户身份", "entity-resolution", deps("st1"),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", s(sl.get("customer"), "王总"), "idCard", demoCode("31011019850505", s(sl.get("customer"), "王总"))),
                                        "source", "core", "entityType", "PERSON"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        real("st3", "③ 调用 data-validate-agent 校验结构化要素完整性", "data-validate", deps("st2"),
                                ":8129/api/bai/validate/validate",
                                (sl, sm, sd) -> map("templateId", "T_SUBMIT",
                                        "data", map("name", s(sl.get("customer"), "王总"),
                                                "idCard", demoCode("31011019850505", s(sl.get("customer"), "王总")), "phone", "13900002222",
                                                "applyDate", "2026-11-03", "applyAmount", 800000, "term", 24,
                                                "startDate", "2026-12-01", "endDate", "2028-12-01",
                                                "purpose", "设备采购", "collateral", "厂房")),
                                (sl, d) -> "要素完整性校验：" + d.path("result").asText("-")
                                        + "（通过 " + d.path("summary").path("passed").asInt(0) + " 项，警告 "
                                        + d.path("summary").path("warned").asInt(0) + "，失败 "
                                        + d.path("summary").path("failed").asInt(0) + "）"),
                        human("st4", "⚑ 人工确认节点：要素逐项与客户确认（一次问全，缺项生成补件任务）", deps("st3")),
                        realTodo("st5", "⑤ 调用 todo-agent 生成待客户确认事项与补件任务", deps("st4"), "COLLECT", "APPLY-S2-2", "S2-2 st5 待办跟踪", "RM001"),
                        realAudit("st6", "⑥ 调用 audit-log-agent 记录录入人与确认留痕", deps("st5"))),
                "回复 1 确认要素无误并建档，回复 2 修正要素，回复 3 转补件流程",
                (sl, sm, sd) -> List.of(
                        block("结构化要素",
                                "客户：" + s(sl.get("customer"), "王总") + "｜产品：经营贷｜金额：80 万｜期限：24 个月",
                                "用途：设备采购｜抵押物：厂房（评估待定）"),
                        block("校验结果",
                                s(sm.get("st3"), "要素完整性校验：通过（详见滚动日志）")),
                        block("确认与建档",
                                "回复 1 确认要素无误并生成进件编号，回复 2 逐项修正，回复 3 转补件"))));

        // ============ S2-3 影像上传与齐套性校验 ============
        REGISTRY.put("S2-3", new SceneScript(
                "S2-3", "application-filing-agent", "影像上传与齐套性校验",
                List.of("影像", "上传", "齐套", "扫描件"),
                "您好，我是影像校验助手。您可以直接说“上传李娜的贷款申请影像资料并校验齐套性”，我会逐张识别分类并核对清单。",
                "本次将为您完成：1.影像上传 2.逐张识别分类 3.质量校验 4.清单核对",
                List.of(
                        real("st1", "① 调用 data-validate-agent 按 T_DOC 模板校验影像清单齐套性", "data-validate", deps(),
                                ":8129/api/bai/validate/validate",
                                (sl, sm, sd) -> map("templateId", "T_DOC",
                                        "data", map("custName", s(sl.get("customer"), "李娜"),
                                                "docType", "CONTRACT", "fileKey", "oss://demo/S23/loan-apply-18p.pdf",
                                                "docs", List.of("身份证明", "经营证明", "银行流水", "抵押物权属"),
                                                "pages", 18)),
                                (sl, d) -> "T_DOC 齐套性校验：" + d.path("result").asText("-")
                                        + "（通过 " + d.path("summary").path("passed").asInt(0) + " 项，警告 "
                                        + d.path("summary").path("warned").asInt(0) + "，失败 "
                                        + d.path("summary").path("failed").asInt(0) + "）"),
                        realOcr("st2", "② 调用 ocr-agent 逐张识别影像要素", deps(), "MIXED_DOC", "IMG-S23-01"),
                        realImageQ("st3", "③ 调用 image-agent 校验影像质量（清晰度/完整性/防篡改）", deps("st2"), "contract-apply-S2-3.jpg", "contract"),
                        realDocParse("st4", "④ 调用 doc-parse-agent 比对识别要素与申请单一致性", deps("st2"), "apply-form-S2-3.pdf"),
                        human("st5", "⚑ 人工确认节点：缺件清单与重传统计推送录入人员确认", deps("st1", "st3", "st4")),
                        realTask("st6", "⑤ 调用 task-schedule-agent 生成缺件重传任务", deps("st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录上传与校验留痕", deps("st6"))),
                "回复 1 确认影像齐套并归档，回复 2 补传缺失影像，回复 3 转人工处理",
                (sl, sm, sd) -> List.of(
                        block("影像清单",
                                "共上传 18 页：身份证明 2｜经营证明 4｜银行流水 6｜抵押物权属 6",
                                "识别分类 18/18 成功，质量校验 17 通过 1 模糊（第 7 页流水）"),
                        block("齐套性校验",
                                s(sm.get("st1"), "T_DOC 模板校验：详见滚动日志")),
                        statusBlock("一致性比对",
                                "ok", "金额一致", "",
                                "ok", "期限一致", "",
                                "warn", "抵押物地址差异 1 处", "待确认"),
                        block("确认与处理",
                                "回复 1 确认归档，回复 2 补传第 7 页流水并确认地址，回复 3 转人工"))));

        // ============ S2-4 对公客户申请受理 ============
        REGISTRY.put("S2-4", new SceneScript(
                "S2-4", "application-filing-agent", "对公客户申请受理",
                List.of("法人贷", "法人", "对公申请", "对公贷款", "企业申请", "公司贷款"),
                "您好，我是对公受理助手。您可以直接说“受理宏远商贸的法人贷申请”，我会自动核验企业与实际控制人，并完成受理建档。",
                "本次将为您完成：1.企业身份核验 2.实际控制人识别 3.要素校验 4.受理确认",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 核验企业身份并归并关联记录", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", s(sl.get("customer"), "宏远商贸"), "idCard", demoCode("91310003MA1FL", s(sl.get("customer"), "宏远商贸"))),
                                        "source", "core", "entityType", "COMPANY"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        real("st2", "② 调用 graph-build-agent 挖掘股权与实际控制人", "graph-build", deps("st1"),
                                ":8134/api/bai/graph/build",
                                (sl, sm, sd) -> map("mode", "FULL",
                                        "scope", map("goldenId", s(sd.containsKey("st1") ? sd.get("st1").path("goldenId").asText(null) : null, "G-DEMO"))),
                                null),
                        real("st3", "③ 调用 data-validate-agent 校验对公进件要素", "data-validate", deps("st1"),
                                ":8129/api/bai/validate/validate",
                                (sl, sm, sd) -> map("templateId", "T_SUBMIT",
                                        "data", map("name", s(sl.get("customer"), "宏远商贸"),
                                                "idCard", demoCode("91310003MA1FL", s(sl.get("customer"), "宏远商贸")), "phone", "13700003333",
                                                "applyDate", "2026-11-03", "applyAmount", 5000000, "term", 36,
                                                "startDate", "2026-12-01", "endDate", "2029-12-01",
                                                "purpose", "流动资金")),
                                (sl, d) -> "对公进件校验：" + d.path("result").asText("-")
                                        + "（通过 " + d.path("summary").path("passed").asInt(0) + " 项，警告 "
                                        + d.path("summary").path("warned").asInt(0) + "，失败 "
                                        + d.path("summary").path("failed").asInt(0) + "）"),
                        human("st4", "⚑ 人工确认节点：企业与实控人核验结果推送客户经理确认受理", deps("st2", "st3")),
                        real("st5", "⑤ 调用 data-query-agent 查询企业存量授信与担保情况", "data-query", deps("st1"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        realTask("st6", "⑥ 调用 task-schedule-agent 生成对公补件任务（章程/财报/决议）", deps("st4", "st5")),
                        realAudit("st7", "⑦ 调用 audit-log-agent 记录受理与核验留痕", deps("st6"))),
                "回复 1 确认受理建档，回复 2 补充企业材料，回复 3 暂缓",
                (sl, sm, sd) -> List.of(
                        block("企业核验",
                                "企业：" + s(sl.get("customer"), "宏远商贸") + "（统一社会信用代码已核验）",
                                "实际控制人：王总（持股 68%，穿透识别完成）"),
                        block("进件要素",
                                "产品：法人贷｜金额：500 万｜期限：36 个月｜用途：流动资金",
                                s(sm.get("st3"), "T_SUBMIT 校验：详见滚动日志")),
                        block("存量授信",
                                "名下存量贷款 1 笔 300 万（正常类），对外担保 1 笔"),
                        block("受理确认",
                                "回复 1 确认受理，回复 2 补充章程/财报/股东决议，回复 3 暂缓"))));

        // ============ S2-5 征信授权与授权书校验 ============
        REGISTRY.put("S2-5", new SceneScript(
                "S2-5", "application-filing-agent", "征信授权与授权书校验",
                List.of("授权书", "征信授权", "征信查询", "授权"),
                "您好，我是征信授权助手。您可以直接说“校验李娜的征信授权书并生成授权”，我会完成授权书一致性校验、授权建档与报告获取。",
                "本次将为您完成：1.授权书识别校验 2.授权建档 3.征信报告获取",
                List.of(
                        realOcr("st1", "① 调用 ocr-agent 识别授权书要素", deps(), "AUTH_LETTER", "IMG-S25-01"),
                        real("st2", "② 调用 entity-resolution-agent 归并客户身份", "entity-resolution", deps("st1"),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", s(sl.get("customer"), "李娜"), "idCard", demoCode("31011019900101", s(sl.get("customer"), "李娜"))),
                                        "source", "core", "entityType", "PERSON"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        human("st3", "⚑ 人工确认节点：授权书要素与本人核对，确认签署意愿", deps("st2")),
                        real("st4", "③ 调用 credit-report-agent 建立征信授权（CREATE）", "credit-report", deps("st3"),
                                ":8110/api/bai/credit-report/authorize",
                                (sl, sm, sd) -> map("action", "CREATE",
                                        "customer", map("certNo", "310110199001011234", "name", s(sl.get("customer"), "李娜")),
                                        "authDoc", map("docNo", "AUTH-2026-001", "certNo", "310110199001011234",
                                                "name", s(sl.get("customer"), "李娜"), "signedAt", "2026-10-30"),
                                        "purpose", "信贷审批", "validDays", 365),
                                (sl, d) -> "授权建档 authId=" + d.path("authId").asText("-")
                                        + "，状态=" + d.path("authStatus").asText("-")
                                        + "，用途合规=" + d.path("purposeValid").asText("-")),
                        real("st5", "④ 调用 credit-report-agent 获取征信报告", "credit-report", deps("st4"),
                                ":8110/api/bai/credit-report/report",
                                (sl, sm, sd) -> map("authId", s(sd.containsKey("st4") ? sd.get("st4").path("authId").asText(null) : null, "AUTH-DEMO-001"),
                                        "reportType", "PERSON"),
                                (sl, d) -> "报告编号=" + s(d.path("report").path("reportNo").asText(null),
                                        s(d.path("reportNo").asText(null), d.path("taskId").asText("-"))) + "，要点已提取"),
                        realAudit("st6", "⑤ 调用 audit-log-agent 记录授权书影像、核对与授权留痕", deps("st5"))),
                "回复 1 确认授权并获取征信报告，回复 2 授权书有误需重新签署，回复 3 暂不授权",
                (sl, sm, sd) -> List.of(
                        block("授权书校验",
                                "客户：" + s(sl.get("customer"), "李娜") + "（授权书要素与本人一致，签署日期 2026-10-30）",
                                "用途：信贷审批（白名单内）"),
                        block("授权建档",
                                s(sm.get("st4"), "授权建档：详见滚动日志")),
                        block("征信报告",
                                s(sm.get("st5"), "报告获取：详见滚动日志"),
                                "要点：当前无逾期｜近 2 年逾期 0 次｜查询记录近 1 月 2 次"),
                        block("确认与后续",
                                "回复 1 确认授权生效，回复 2 重新签署，回复 3 暂不授权"))));

        // ============ S3-1 客户信息统一视图（图谱核心） ============
        REGISTRY.put("S3-1", new SceneScript(
                "S3-1", "customer-360-agent", "客户信息统一视图",
                List.of("统一视图", "客户档案", "客户360", "一页式视图"),
                "您好，我是客户统一视图助手。直接告诉我客户姓名或企业名称，我会把行内各系统与外部渠道的信息归并成一页式视图，大约半分钟。",
                "本次将为您完成：1.身份归并 2.多源数据接入 3.交叉核对 4.关联图谱 5.脱敏视图生成",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并客户身份，合并一人多户/一户多证", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", s(sl.get("customer"), "北京恒润机械制造有限公司"),
                                                "idCard", demoCode("91310004MA1FL", s(sl.get("customer"), "北京恒润机械制造有限公司"))),
                                        "source", "core", "entityType", "COMPANY"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d) + "，指纹=" + d.path("fingerprint").asText("-")),
                        human("st2", "⚑ 人工确认节点：同名多主体歧义，请客户经理选定唯一客户（超时 30 分钟挂起提醒）", deps("st1")),
                        real("st3", "② 调用 data-query-agent 并行接入核心系统、信贷管理与客户数据中心", "data-query", deps("st2"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        real("st4", "③ 调用 data-validate-agent 交叉核对多源信息矛盾点", "data-validate", deps("st3"),
                                ":8129/api/bai/validate/validate",
                                (sl, sm, sd) -> map("templateId", "T_RECON",
                                        "data", map("orgId", "ORG-DEMO", "applyDate", "2026-11-03",
                                                "loanAmount", 1800000, "repayAmount", 1200000)),
                                (sl, d) -> "多源核对：" + d.path("result").asText("-")
                                        + "（通过 " + d.path("summary").path("passed").asInt(0) + " 项，警告 "
                                        + d.path("summary").path("warned").asInt(0) + "，失败 "
                                        + d.path("summary").path("failed").asInt(0) + "）"),
                        real("st5", "④ 调用 graph-build-agent 挖掘股权、任职、担保、资金往来关联", "graph-build", deps("st2"),
                                ":8134/api/bai/graph/build",
                                (sl, sm, sd) -> map("mode", "FULL",
                                        "scope", map("goldenId", s(sd.containsKey("st1") ? sd.get("st1").path("goldenId").asText(null) : null, "G-DEMO"))),
                                (sl, d) -> "关联子图构建 jobId=" + d.path("jobId").asText("-") + "，状态=" + d.path("status").asText("ACCEPTED")),
                        realMask("st6", "⑤ 调用 sensitive-mask-agent 按岗位权限对敏感字段脱敏", deps("st3", "st4", "st5"), "TEXT", "客户张伟，手机 13800001111，身份证 310101199001011234", "DISPLAY"),
                        realStructure("st6b", "⑥ 调用 structure-gen-agent 生成一页式视图与图谱数据形态", deps("st6"), "SCHEMA_DD_CARD_V2", "TEXT", "信贷调查一页式视图", "CARD"),
                        realAudit("st7", "⑦ 调用 audit-log-agent 记录本次查询留痕", deps("st6b"))),
                "回复 1 选定该主体并生成视图，回复 2 补充证件信息区分主体，回复 3 终止查询",
                (sl, sm, sd) -> List.of(
                        block("基础概览",
                                "客户：" + s(sl.get("customer"), "北京恒润机械制造有限公司") + "｜客户号 C-2026-0301｜所属机构：昌平支行",
                                "在办授信：经营贷 1 笔，余额 180 万元｜五级分类：正常类",
                                s(sm.get("st3"), "多源数据接入：详见滚动日志")),
                        block("业务画像",
                                "存款：近 6 个月日均结算约 45 万元｜贷款：180 万（随借随还经营贷）",
                                "客户标签：制造业上游｜纳税 B 级｜开票连续 24 个月"),
                        block("风险提示",
                                s(sm.get("st4"), "交叉核对：详见滚动日志"),
                                "矛盾点：注册资本行内登记 1000 万元 ≠ 工商公示 2000 万元，请核实最近一次工商变更"),
                        block("关联图谱",
                                "关联企业 6 家，其中法定代表人另持有 2 家企业，1 家存在被执行记录（建议人工关注）",
                                s(sm.get("st5"), "关联子图：详见滚动日志"),
                                "回复 1 发起征信与第三方查询，回复 2 做股权穿透，回复 3 把视图并入尽调报告素材，回复 4 结束"))));

        // ============ S3-2 征信与第三方信息查询 ============
        REGISTRY.put("S3-2", new SceneScript(
                "S3-2", "credit-info-query-agent", "征信与第三方信息查询",
                List.of("征信报告", "查征信", "企业征信", "第三方信息"),
                "您好，我是征信与第三方查询助手。查询前请先确认客户已签署征信授权书。直接告诉我客户名称或证件号即可，授权书可拍照发送给我核验。",
                "本次将为您完成：1.身份归并 2.授权书识别 3.授权建档 4.征信与第三方查询 5.要素抽取与交叉核对",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并客户身份", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", s(sl.get("customer"), "北京恒润机械制造有限公司"),
                                                "idCard", demoCode("91310004MA1FL", s(sl.get("customer"), "北京恒润机械制造有限公司"))),
                                        "source", "core", "entityType", "COMPANY"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        realOcr("st2", "② 调用 ocr-agent 识别授权书照片，提取授权编号、用途、有效期", deps(), "AUTH_LETTER", "IMG-S32-01"),
                        real("st3", "③ 调用 credit-report-agent 建立征信授权（CREATE）", "credit-report", deps("st1"),
                                ":8110/api/bai/credit-report/authorize",
                                (sl, sm, sd) -> map("action", "CREATE",
                                        "customer", map("certNo", "91310000123456789X",
                                                "name", s(sl.get("customer"), "北京恒润机械制造有限公司")),
                                        "authDoc", map("docNo", "AUTH-2026-101", "certNo", "91310000123456789X",
                                                "name", s(sl.get("customer"), "北京恒润机械制造有限公司"), "signedAt", "2026-11-02"),
                                        "purpose", "信贷审批", "validDays", 365),
                                (sl, d) -> "授权建档 authId=" + d.path("authId").asText("-")
                                        + "，用途合规=" + d.path("purposeValid").asText("-")),
                        human("st4", "⚑ 人工确认节点：授权有效性核验（有效期内、用途匹配、主体一致），确认授权依据真实", deps("st2", "st3")),
                        real("st5", "④ 调用 credit-report-agent 解析征信报告（逾期、负债、对外担保、查询记录）", "credit-report", deps("st4"),
                                ":8110/api/bai/credit-report/report",
                                (sl, sm, sd) -> map("authId", s(sd.containsKey("st3") ? sd.get("st3").path("authId").asText(null) : null, "AUTH-DEMO-101"),
                                        "reportType", "COMPANY"),
                                (sl, d) -> "报告编号=" + s(d.path("report").path("reportNo").asText(null),
                                        s(d.path("reportNo").asText(null), d.path("taskId").asText("-")))),
                        real("st6", "⑤ 调用 external-data-agent 并行调取司法涉诉信息（P06）", "external-data", deps("st4"),
                                ":8105/api/bai/external/fetch",
                                (sl, sm, sd) -> map("sourceType", "JUDICIAL",
                                        "query", map("company", s(sl.get("customer"), "北京恒润机械制造有限公司")),
                                        "scenario", "尽调"),
                                (sl, d) -> "司法数据 callId=" + d.path("callId").asText("-") + "，状态=" + d.path("status").asText("-")),
                        real("st7", "⑥ 调用 external-data-agent 并行调取税务欠税信息（P07）", "external-data", deps("st4"),
                                ":8105/api/bai/external/fetch",
                                (sl, sm, sd) -> map("sourceType", "TAX",
                                        "query", map("company", s(sl.get("customer"), "北京恒润机械制造有限公司"),
                                                "creditCode", "91310000123456789X"),
                                        "scenario", "尽调"),
                                (sl, d) -> "税务数据 callId=" + d.path("callId").asText("-") + "，状态=" + d.path("status").asText("-")),
                        real("st8", "⑦ 调用 ner-agent 抽取第三方信息要素（欠税金额、被执行记录）", "ner", deps("st6", "st7"),
                                ":8111/api/bai/ner/extract",
                                (sl, sm, sd) -> map("text", "第三方信息核查：北京恒润机械制造有限公司于 2026-05-20 存在 2 条被执行记录（均已结案），涉及金额 45 万元；无欠税公告；2025-08-01 环保行政处罚 1 条，已整改完毕。", "scene", "FILING"),
                                (sl, d) -> "抽取要素 " + d.path("entities").size() + " 项"),
                        real("st9", "⑧ 调用 data-validate-agent 征信负债与行内申报数据交叉核对", "data-validate", deps("st5"),
                                ":8129/api/bai/validate/validate",
                                (sl, sm, sd) -> map("templateId", "T_RECON",
                                        "data", map("orgId", "ORG-DEMO", "applyDate", "2026-11-03",
                                                "loanAmount", 1800000, "repayAmount", 1200000)),
                                (sl, d) -> "交叉核对：" + d.path("result").asText("-")
                                        + "（通过 " + d.path("summary").path("passed").asInt(0) + " 项，警告 "
                                        + d.path("summary").path("warned").asInt(0) + "，失败 "
                                        + d.path("summary").path("failed").asInt(0) + "）"),
                        realMask("st10", "⑨ 调用 sensitive-mask-agent 按岗位权限脱敏", deps("st5", "st8", "st9"), "TEXT", "客户张伟 310101199001011234 授信 500 万元", "DISPLAY"),
                        realAudit("st11", "⑩ 调用 audit-log-agent 记录查询全程留痕（查询原因、授权编号、时间）", deps("st10"))),
                "回复 1 确认授权有效并发起查询，回复 2 补充授权文件，回复 3 终止查询",
                (sl, sm, sd) -> List.of(
                        block("授权核验状态",
                                "授权编号 AUTH-2026-101｜用途：信贷审批（白名单内）｜有效期：签署日 2026-11-02 起 365 天",
                                s(sm.get("st3"), "授权建档：详见滚动日志")),
                        block("征信要点",
                                s(sm.get("st5"), "征信解析：详见滚动日志"),
                                "企业当前无逾期｜对外担保余额 300 万元｜近半年硬查询 4 次"),
                        block("第三方信息",
                                s(sm.get("st8"), "要素抽取：详见滚动日志"),
                                "司法：2 条被执行记录均已结案，无失信记录｜税务：无欠税公告｜行政处罚 1 条（已整改）"),
                        block("交叉核对与后续",
                                s(sm.get("st9"), "交叉核对：详见滚动日志"),
                                "回复 1 把结论并入尽调报告（衔接 S3-11），回复 2 导出脱敏摘要，回复 3 结束"))));

        // ============ S3-3 股权穿透（图谱核心） ============
        REGISTRY.put("S3-3", new SceneScript(
                "S3-3", "equity-penetration-agent", "股权穿透",
                List.of("股权穿透", "穿透", "最终受益人", "受益所有人"),
                "您好，我是股权穿透助手。告诉我企业名称，我逐层穿透到最终受益人，默认穿透 5 层，约半分钟出图。",
                "本次将为您完成：1.主体归并 2.工商数据接入 3.股权链构建 4.穿透计算 5.代持嫌疑标注",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并目标企业主体", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", s(sl.get("customer"), "北京华信科技有限公司"),
                                                "idCard", demoCode("91310004MA1FL", s(sl.get("customer"), "北京华信科技有限公司"))),
                                        "source", "core", "entityType", "COMPANY"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        real("st2", "② 调用 data-query-agent 接入工商登记与企业公示数据（P05 未联调，暂以行内担保底料演示）", "data-query", deps("st1"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        real("st3", "③ 调用 graph-build-agent 逐层构建股权链，标记壳公司与代持嫌疑节点", "graph-build", deps("st2"),
                                ":8134/api/bai/graph/build",
                                (sl, sm, sd) -> map("mode", "FULL",
                                        "scope", map("goldenId", s(sd.containsKey("st1") ? sd.get("st1").path("goldenId").asText(null) : null, "G-DEMO"))),
                                (sl, d) -> "股权链构建 jobId=" + d.path("jobId").asText("-")),
                        realGraphPenetrate("st4", "④ 调用 graph-penetrate-agent 计算穿透路径，加总各最终受益人持股比例", deps("st3"), "N00001", "UPSTREAM", 5),
                        human("st5", "⚑ 人工确认节点：请对穿透结果与最终受益人认定标注核实结论（超时 24 小时挂起）", deps("st4")),
                        realStructure("st6", "⑤ 调用 structure-gen-agent 输出股权结构图（图谱数据形态）", deps("st5"), "SCHEMA_REPORT_OUTLINE_V1", "TEXT", "股权结构图", "JSON"),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录穿透依据与留痕", deps("st6"))),
                "回复 1 标注核实结论并出图，回复 2 补充工商信息，回复 3 结束",
                (sl, sm, sd) -> penetrateBlocks(sd.containsKey("st4") ? sd.get("st4") : null,
                        "穿透总览", "股权结构图", "最终受益人清单", "风险提示",
                        s(sm.get("st3"), "股权链构建：详见滚动日志"),
                        "回复 1 继续做关联关系挖掘（衔接 S3-4），回复 2 把结构图并入尽调报告，回复 3 结束")));

        // ============ S3-4 关联关系挖掘与知识图谱（图谱核心） ============
        REGISTRY.put("S3-4", new SceneScript(
                "S3-4", "affiliation-graph-agent", "关联关系挖掘与知识图谱",
                List.of("担保圈", "互保", "关联挖掘", "关联网络"),
                "您好，我是关联关系挖掘助手。告诉我客户名称，我挖担保圈、互保和关联交易网络，大约 1 分钟。",
                "本次将为您完成：1.主体归并 2.关联挖掘 3.担保圈与风险传染计算 4.名单筛查 5.高风险圈人工标注",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并客户主体及其关联方候选", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", s(sl.get("customer"), "上海润泽实业有限公司"),
                                                "idCard", demoCode("91310004MA1FL", s(sl.get("customer"), "上海润泽实业有限公司"))),
                                        "source", "core", "entityType", "COMPANY"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        real("st2", "② 调用 graph-build-agent 挖掘担保、任职、共同联系方式、资金往来关联", "graph-build", deps("st1"),
                                ":8134/api/bai/graph/build",
                                (sl, sm, sd) -> map("mode", "FULL",
                                        "scope", map("goldenId", s(sd.containsKey("st1") ? sd.get("st1").path("goldenId").asText(null) : null, "G-DEMO"))),
                                (sl, d) -> "关联挖掘 jobId=" + d.path("jobId").asText("-")),
                        real("st3", "③ 调用 data-query-agent 接入行内互保敞口数据（含行外公示担保口径）", "data-query", deps("st1"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        realGraphPenetrate("st4", "④ 调用 graph-penetrate-agent 计算担保圈规模与风险传染路径", deps("st2", "st3"), "N00002", "UPSTREAM", 3),
                        realScreening("st5", "⑤ 调用 screening-agent 对圈内主体做制裁/黑名单筛查", deps("st2"), "ADMISSION", "上海润泽实业有限公司"),
                        realStructure("st6", "⑥ 调用 structure-gen-agent 输出关联网络图谱数据形态", deps("st4", "st5"), "SCHEMA_REPORT_OUTLINE_V1", "TEXT", "关联网络图谱", "JSON"),
                        human("st7", "⚑ 人工确认节点：担保圈风险核查完毕，请标注是否纳入贷后预警（超时 24 小时挂起提醒）", deps("st6")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录挖掘依据与留痕", deps("st7"))),
                "回复 1 纳入贷后预警台账，回复 2 仅存档不预警，回复 3 结束",
                (sl, sm, sd) -> penetrateBlocks(sd.containsKey("st4") ? sd.get("st4") : null,
                        "关联总览", "股权与担保路径", "股东持股汇总", "风险提示",
                        s(sm.get("st2"), "关联挖掘：详见滚动日志"),
                        "回复 1 把高风险圈纳入贷后预警（衔接 S7-2），回复 2 导出图谱数据，回复 3 结束")));

        // ============ S3-5 现场尽调与勘察管理 ============
        REGISTRY.put("S3-5", new SceneScript(
                "S3-5", "onsite-survey-agent", "现场尽调与勘察管理",
                List.of("现场尽调", "勘察", "实地调查", "现场记录"),
                "您好，我是现场尽调助手。到客户现场后直接拍照片、录视频发给我，我自动质检、抽要素，GPS 定位会随照片一起留证。",
                "本次将为您完成：1.影像接收 2.定位核验 3.影像质检与抽帧 4.单据识别 5.现场要素汇总",
                List.of(
                        realDocParse("st1", "① 调用 doc-parse-agent 接收 8 张照片与 1 段视频，校验定位水印与拍摄时间戳（影像为演示占位）", deps(), "photos-S3-5.jpg"),
                        human("st2", "⚑ 人工确认节点：无定位水印或定位与注册地址偏差超 5 公里时，请说明实地情况", deps("st1")),
                        realImageQ("st3", "② 调用 image-agent 照片质检（模糊、篡改痕迹、场景匹配度）", deps("st2"), "site-photo-S3-5.jpg", "site"),
                        realImage("st4", "③ 调用 image-agent 视频抽帧（生产设备、库存、门头招牌）", deps("st2"), "site-video-S3-5.jpg", "site", "[{\"name\":\"crop\",\"enabled\":true},{\"name\":\"resize\",\"enabled\":true}]"),
                        realOcr("st5", "④ 调用 ocr-agent 识别现场笔录与水电费票据", deps("st2"), "MIXED_DOC", "IMG-S35-02"),
                        realOcr("st6", "⑤ 调用 ocr-agent 识别购销合同与发票要素", deps("st2"), "PURCHASE_CONTRACT", "IMG-S35-03"),
                        real("st7", "⑥ 调用 ner-agent 汇总现场要素（员工人数、设备运转、库存水位、合同金额）", "ner", deps("st3", "st4", "st5", "st6"),
                                ":8111/api/bai/ner/extract",
                                (sl, sm, sd) -> map("text", "现场笔录 2026-11-03：北京恒润机械制造有限公司厂房员工约 15 人，生产设备运转正常；库存钢材约 80 吨，货值约 300 万元；本月购销合同 2 份合计 260 万元，交易对手华电重工，账期 90 天。", "scene", "FILING"),
                                (sl, d) -> "抽取要素 " + d.path("entities").size() + " 项"),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录影像上传与处理全程留痕（含定位、时间）", deps("st7"))),
                "回复 1 确认定位与现场情况无误，回复 2 补充说明或重拍，回复 3 暂停处理",
                (sl, sm, sd) -> List.of(
                        statusBlock("影像质检报告",
                                "ok", "8 张照片", "7 张合格",
                                "fail", "第 5 张", "模糊退回重拍",
                                "ok", "视频", "2 分钟：抽帧 12 帧分析完成",
                                "ok", "定位", "距昌平区注册地址 1.2 公里，偏差在容许范围内"),
                        block("现场要素摘要",
                                s(sm.get("st7"), "要素抽取：详见滚动日志"),
                                "生产正常｜员工约 15 人｜库存钢材约 80 吨（与申报产能匹配）｜门头招牌与注册名称一致"),
                        block("单据识别明细",
                                "购销合同 2 份合计 260 万元｜交易对手华电重工｜账期 90 天｜水电费票据与产能匹配"),
                        block("现场尽调记录草稿",
                                "已按行内尽调记录模板生成草稿，可直接编辑",
                                "回复 1 补充上传照片或单据，回复 2 生成正式尽调记录并入报告（衔接 S3-11），回复 3 结束"))));

        // ============ S3-6 名单筛查 ============
        REGISTRY.put("S3-6", new SceneScript(
                "S3-6", "list-screening-agent", "名单筛查",
                List.of("名单筛查", "过名单", "制裁名单", "黑名单", "失信名单"),
                "您好，我是名单筛查助手。您说客户名称，或直接发送待筛查名单文件（Excel/CSV），我批量过制裁名单、行内黑名单、失信与涉诉名单。",
                "本次将为您完成：1.主体归并 2.名单筛查 3.命中打标 4.关联传播 5.处置建议",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并筛查主体（含法定代表人与主要股东）", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", s(sl.get("customer"), "北京恒润机械制造有限公司"),
                                                "idCard", demoCode("91310004MA1FL", s(sl.get("customer"), "北京恒润机械制造有限公司"))),
                                        "source", "core", "entityType", "COMPANY"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        realScreening("st2", "② 调用 screening-agent 逐户筛查制裁/黑名单/失信/涉诉/不良名单（P08 未联调，名单源为演示占位）", deps("st1"), "ADMISSION", "演示客户"),
                        real("st3", "③ 调用 text-classify-agent 对命中打标（直接命中/历史命中/近似命中）", "text-classify", deps("st2"),
                                ":8112/api/bai/textclass/classify",
                                (sl, sm, sd) -> map("text", "恒润机械关联企业恒泰商贸：存在失信被执行人记录（直接命中）；B 企业法定代表人曾担任某失信企业高管（历史命中）。", "task", "compliance_text", "topK", 3),
                                (sl, d) -> "打标结论=" + (d.path("labels").isArray() && d.path("labels").size() > 0
                                        ? d.path("labels").get(0).path("level1").asText("-") + "/" + d.path("labels").get(0).path("level2").asText("-")
                                        : "-")),
                        realGraphPenetrate("st4", "④ 调用 graph-penetrate-agent 对命中主体查关联传播（条件分支：仅命中时触发）", deps("st2"), "C-S3-6", "DOWNSTREAM", 3),
                        human("st5", "⚑ 人工确认节点：近似命中转人工比对，请风控人员确认命中性质后归档", deps("st3", "st4")),
                        realAudit("st6", "⑤ 调用 audit-log-agent 记录筛查与命中留痕", deps("st5"))),
                "回复 1 确认命中比对结论，回复 2 重新筛查其他名单源，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("筛查总览",
                                "筛查 12 户（含法定代表人与主要股东）｜命中 2 户｜命中类型：失信 1、历史命中 1",
                                s(sm.get("st2"), "名单筛查：详见滚动日志")),
                        block("命中明细",
                                s(sm.get("st3"), "命中打标：详见滚动日志"),
                                "A 企业：失信被执行人（直接命中，相似度 100%）｜B 企业：法定代表人历史任职（历史命中）"),
                        block("关联传播",
                                "A 企业担保圈波及我行 2 笔在贷业务｜命中主体与本次申请主体无直接持股关系"),
                        block("处置建议",
                                "A 户：禁入线索，转合规审查（衔接 S4-6）｜B 户：人工关注｜其余 10 户：通过",
                                "回复 1 命中户转合规审查，回复 2 导出筛查记录，回复 3 结束"))));

        // ============ S3-7 反欺诈识别 ============
        REGISTRY.put("S3-7", new SceneScript(
                "S3-7", "anti-fraud-review-agent", "反欺诈识别",
                List.of("反欺诈", "欺诈", "骗贷", "团伙"),
                "您好，我是反欺诈识别助手。输入客户名称后我跑欺诈规则与模型，大约 20 秒出评分与疑点。",
                "本次将为您完成：1.主体归并 2.欺诈评分 3.黑名单与流水核查 4.团伙网络 5.疑点人工核查",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并客户主体", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", s(sl.get("customer"), "北京恒润机械制造有限公司"),
                                                "idCard", demoCode("91310004MA1FL", s(sl.get("customer"), "北京恒润机械制造有限公司"))),
                                        "source", "core", "entityType", "COMPANY"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        realFraud("st2", "② 调用 fraud-agent 执行欺诈规则与模型评分", deps("st1"), "PRE_LOAN", "ORG", "C-S3-7", null),
                        real("st3", "③ 调用 data-query-agent 流水异常归因（过路流水、快进快出）", "data-query", deps("st1"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_core", "table", "loan_balance_daily",
                                        "fields", List.of("branch_code", "loan_balance")),
                                null),
                        realScreening("st4", "④ 调用 screening-agent 欺诈黑名单筛查", deps("st1"), "ADMISSION", "演示客户"),
                        realImageQ("st5", "⑤ 调用 image-agent 申请影像篡改识别", deps("st1"), "apply-identity-S3-7.jpg", "cert"),
                        realGraphPenetrate("st6", "⑥ 调用 graph-penetrate-agent 团伙欺诈关联网络查询（条件分支：评分 ≥60 分触发）", deps("st2"), "C-S3-7", "DOWNSTREAM", 3),
                        human("st7", "⚑ 人工确认节点：评分 ≥80 分（高风险）强制转人工核查工单，请录入核查结论（超时自动升级提醒风控经理）", deps("st3", "st4", "st5", "st6")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录评分与疑点留痕", deps("st7"))),
                "回复 1 录入人工核查结论（通过/拒贷线索），回复 2 补充核查材料，回复 3 转风控经理",
                (sl, sm, sd) -> List.of(
                        block("欺诈评分与等级",
                                "评分 62 分（中风险）｜主要得分因子：流水异常、影像存疑、黑名单近似命中",
                                s(sm.get("st2"), "欺诈评分：详见滚动日志")),
                        block("疑点清单",
                                "近 3 个月月末入账、次月初转出，过路流水特征｜经营流水与申报年营收偏差约 40%，建议补充纳税证明",
                                s(sm.get("st3"), "流水归因：详见滚动日志")),
                        block("团伙网络",
                                "共用设备、地址、联系人核查：未发现关联组团申请",
                                s(sm.get("st6"), "团伙网络：详见滚动日志")),
                        block("处置建议",
                                "人工核查（评分未达 80 分强制线，但存在疑点）",
                                "回复 1 疑点转人工核查工单（衔接 S4-4），回复 2 导出核查记录，回复 3 结束"))));

        // ============ S3-8 项目融资评估 ============
        REGISTRY.put("S3-8", new SceneScript(
                "S3-8", "project-finance-agent", "项目融资评估",
                List.of("项目融资", "项目贷款", "可研", "IRR", "现金流测算"),
                "您好，我是项目融资评估助手。告诉我项目名称、总投资、拟贷金额与期限，我完成行业判断与财务测算。",
                "本次将为您完成：1.项目财务解析 2.行业与市场分析 3.财务测算（IRR/DSCR） 4.假设确认 5.评估结论",
                List.of(
                        real("st1", "① 调用 financial-statement-agent 解析项目财务数据与投资估算", "financial-statement", deps(),
                                ":8139/api/bai/financial-statement/analyze",
                                (sl, sm, sd) -> map("entityId", "E-HENGRUN", "period", "2026-Q3", "scenario", "DUE_DILIGENCE",
                                        "statements", List.of(
                                                map("type", "BALANCE", "items", map("totalAssets", 52000000, "totalLiab", 31000000, "totalEquity", 21000000)),
                                                map("type", "INCOME", "items", map("revenue", 18000000, "netProfit", 1260000)),
                                                map("type", "CASHFLOW", "items", map("operatingCashFlow", 2100000)))),
                                (sl, d) -> "财务解析 analysisNo=" + d.path("analysisNo").asText("-")
                                        + "，造假评分=" + d.path("fraudScore").asText("-")),
                        real("st2", "② 调用 knowledge-process-agent 查询行业景气与基准参数（LPR 3.45%、同业收益率基准）", "knowledge-process", deps(),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "DOC-S38-001", "source", "policy",
                                        "text", "机床工具行业 2026 年景气判断：需求平稳偏弱，竞争充分。项目融资基准参数：LPR 3.45%，同业项目贷款收益率基准 4.2%，DSCR 警戒线 1.3，关键假设偏离行业基准超 20% 须人工确认。"),
                                null),
                        real("st3", "③ 调用 trend-analyze-agent 分析市场规模与区域条件", "trend-analyze", deps(),
                                ":8132/api/bai/trend/analyze",
                                (sl, sm, sd) -> map("seriesId", "MACHINE_TOOL_MARKET", "window", "M6",
                                        "methods", List.of("trend", "mom", "yoy"),
                                        "points", List.of(
                                                map("date", "2026-04", "value", 98.2), map("date", "2026-05", "value", 97.6),
                                                map("date", "2026-06", "value", 99.1), map("date", "2026-07", "value", 100.3),
                                                map("date", "2026-08", "value", 99.8), map("date", "2026-09", "value", 101.2))),
                                (sl, d) -> "行业趋势 analyzeId=" + d.path("analyzeId").asText("-")
                                        + "，方向=" + d.path("trend").path("direction").asText("-")),
                        realMetric("st4", "④ 调用 metric-calc-agent 计算 IRR、DSCR、投资回收期、盈亏平衡点", deps("st1", "st2", "st3"), "PROJECT_EVAL", map("initialInvestment", 12000000, "annualCashflows", List.of(2600000.0, 2800000.0, 3000000.0, 3200000.0, 3500000.0), "annualRevenue", 98000000, "annualFixedCost", 3600000, "variableCostRate", 0.88)),
                        realGraphPenetrate("st5", "⑤ 调用 graph-penetrate-agent 查询项目股东与主要交易对手关联（条件分支：发现关联交易疑点时触发）", deps("st1"), "C-S3-8", "UPSTREAM", 3),
                        human("st6", "⚑ 人工确认节点：关键假设（售价、达产率）偏离行业基准超过 20%，请确认假设口径（DSCR 低于 1.3 亦须说明）", deps("st4", "st5")),
                        realStructure("st7", "⑥ 调用 structure-gen-agent 输出现金流测算图表", deps("st6"), "SCHEMA_LOAN_TABLE_V1", "TEXT", "现金流测算", "TABLE"),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录评估依据与留痕", deps("st7"))),
                "回复 1 确认假设口径并出具结论，回复 2 调整假设重新测算，回复 3 暂缓",
                (sl, sm, sd) -> List.of(
                        block("项目概要",
                                "项目：恒润机械新生产线｜总投资 3000 万元，资本金 1200 万元（40%）",
                                "拟贷 1800 万元｜期限 60 个月｜还款来源：生产线投产后的经营现金流"),
                        block("行业与市场判断",
                                s(sm.get("st3"), "行业趋势：详见滚动日志"),
                                "机床工具行业景气：中性偏弱｜需求平稳、竞争充分｜区域配套条件良好"),
                        block("财务测算结果",
                                "IRR 7.6%｜达产年 DSCR 1.45（达产首年 1.12，低于 1.3 警戒线，建议宽限期 1 年）｜投资回收期 6.2 年｜盈亏平衡点 72%",
                                s(sm.get("st1"), "财务解析：详见滚动日志")),
                        block("风险与建议",
                                "主要风险：达产不及预期｜建议追加设备抵押并设置宽限期",
                                "回复 1 把评估结论并入尽调报告（衔接 S3-11），回复 2 调整假设重新测算，回复 3 结束"))));

        // ============ S3-9 供应链金融评估（图谱核心） ============
        REGISTRY.put("S3-9", new SceneScript(
                "S3-9", "supply-chain-eval-agent", "供应链金融评估",
                List.of("供应链金融", "应收账款", "订单融资", "保理"),
                "您好，我是供应链金融评估助手。告诉我核心企业、融资主体和业务类型（应收账款/订单/保理），交易单据请直接拍照发送。",
                "本次将为您完成：1.主体归并 2.供应链网络与回款分析 3.单据识别 4.确权核验 5.额度测算",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并核心企业（华电重工）与融资主体（恒润机械）", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", s(sl.get("customer"), "北京恒润机械制造有限公司"),
                                                "idCard", demoCode("91310004MA1FL", s(sl.get("customer"), "北京恒润机械制造有限公司"))),
                                        "source", "core", "entityType", "COMPANY"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        real("st2", "② 调用 data-query-agent 分析历史回款流水与账期", "data-query", deps("st1"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_core", "table", "loan_balance_daily",
                                        "fields", List.of("branch_code", "loan_balance")),
                                null),
                        real("st3", "③ 调用 graph-build-agent 挖掘供应链历史交易关系", "graph-build", deps("st1"),
                                ":8134/api/bai/graph/build",
                                (sl, sm, sd) -> map("mode", "FULL",
                                        "scope", map("goldenId", s(sd.containsKey("st1") ? sd.get("st1").path("goldenId").asText(null) : null, "G-DEMO"))),
                                (sl, d) -> "交易关系构建 jobId=" + d.path("jobId").asText("-")),
                        realOcr("st4", "④ 调用 ocr-agent 识别发票、购销合同与核心企业确权通知要素", deps(), "MIXED_DOC", "IMG-S39-01"),
                        real("st5", "⑤ 调用 financial-statement-agent 分析融资主体财务", "financial-statement", deps(),
                                ":8139/api/bai/financial-statement/analyze",
                                (sl, sm, sd) -> map("entityId", "E-HENGRUN", "period", "2026-Q3", "scenario", "DUE_DILIGENCE",
                                        "statements", List.of(
                                                map("type", "BALANCE", "items", map("totalAssets", 52000000, "totalLiab", 31000000, "totalEquity", 21000000)),
                                                map("type", "INCOME", "items", map("revenue", 18000000, "netProfit", 1260000)))),
                                (sl, d) -> "财务解析 analysisNo=" + d.path("analysisNo").asText("-")),
                        human("st6", "⚑ 人工确认节点：核验核心企业确权文件（确权通知/应付确认）真实有效；无确权则中止额度测算", deps("st2", "st3", "st4", "st5")),
                        real("st7", "⑥ 调用 limit-calc-agent 按折扣率 80% 与账期测算单笔可融额度（应收折扣率指标未在 metric-calc 指标库，按 A-56 额度测算执行）", "limit-calc", deps("st6"),
                                ":8123/api/bai/limit/calc",
                                (sl, sm, sd) -> map("customerId", "C-HENGRUN", "limitType", "SINGLE",
                                        "incomeBased", 800000, "collateralBased", 960000,
                                        "requestedAmount", 1200000,
                                        "ratingFactor", 0.85, "availableLimit", 5000000),
                                (sl, d) -> "可融额度=" + d.path("limitAmount").asText("-")
                                        + "，剩余可用=" + d.path("remainAmount").asText("-")
                                        + "，管控=" + d.path("controlStatus").asText("-")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录评估全程留痕", deps("st7"))),
                "回复 1 确认确权有效并测算额度，回复 2 补充单据重新核验，回复 3 暂缓",
                (sl, sm, sd) -> List.of(
                        block("供应链网络",
                                "核心企业华电重工 ← 恒润机械：连续交易 2 年｜上下游节点 5 个",
                                s(sm.get("st3"), "交易关系：详见滚动日志")),
                        block("交易真实性核验",
                                "发票与购销合同要素一致（金额、对手、账期）｜确权通知：华电重工应付确认已出具",
                                "回复 1 确认核验通过，回复 2 补充单据"),
                        block("回款历史与账期分析",
                                "近 6 笔回款均按时，回款路径闭环（华电重工对公账户 → 恒润机械结算户）",
                                s(sm.get("st2"), "回款流水：详见滚动日志")),
                        block("额度测算建议",
                                s(sm.get("st7"), "额度测算：详见滚动日志"),
                                "发票 120 万元 × 折扣率 80% = 单笔可融 96 万元｜账期 90 天",
                                "回复 1 转额度测算（衔接 S4-2），回复 2 补充单据重新测算，回复 3 结束"))));

        // ============ S3-10 抵质押物评估 ============
        REGISTRY.put("S3-10", new SceneScript(
                "S3-10", "collateral-eval-agent", "抵质押物评估",
                List.of("押品评估", "押品", "抵押物评估", "抵押评估"),
                "您好，我是押品评估助手。告诉我押品类型（房产/土地/设备/存货）和基本情况，现场照片视频可以直接发送。",
                "本次将为您完成：1.行情与口径接入 2.现场影像核验 3.估值测算 4.双人复核 5.折扣率与覆盖率",
                List.of(
                        real("st1", "① 调用 knowledge-process-agent 查询不动产估价口径与市场行情基准（行情源 P09 未联调，行情随政策文本带入）", "knowledge-process", deps(),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "DOC-S310-001", "source", "policy",
                                        "text", "押品审慎估值口径：住宅市场比较法取近 3 个月同小区可比成交案例；昌平区近期住宅成交均价 2.6 万元/㎡；住宅折扣率上限 70%，商用房 50%，设备 40%；估值与申报值偏差超 20% 须双人复核。"),
                                null),
                        realImageQ("st2", "② 调用 image-agent 现场照片质检（影像为演示占位）", deps(), "site-photo-S3-10.jpg", "site"),
                        realImage("st3", "③ 调用 image-agent 押品现场视频抽帧", deps(), "collateral-video-S3-10.jpg", "collateral", "[{\"name\":\"crop\",\"enabled\":true},{\"name\":\"resize\",\"enabled\":true}]"),
                        real("st4", "④ 调用 valuation-agent 市场比较法估值", "valuation", deps("st1"),
                                ":8124/api/bai/valuation/value",
                                (sl, sm, sd) -> map("assetType", "REAL_ESTATE", "method", "MARKET",
                                        "assetRef", "京昌平住宅-120㎡-2026",
                                        "params", map("area", 120, "unitPrice", 26000, "comparables", 3),
                                        "valueDate", "2026-11-03"),
                                (sl, d) -> "评估值=" + d.path("value").asText("-")
                                        + "，区间=" + d.path("range").path("low").asText("-") + "~" + d.path("range").path("high").asText("-")
                                        + "，结论=" + d.path("conclusion").asText("-")),
                        human("st5", "⚑ 人工确认节点：估值与申报值偏差超过 20% 或未实勘时，双人复核确认估值结论（超时挂起）", deps("st2", "st3", "st4")),
                        real("st6", "⑤ 调用 limit-calc-agent 测算建议折扣率与押品覆盖率（住宅 70%）", "limit-calc", deps("st5"),
                                ":8123/api/bai/limit/calc",
                                (sl, sm, sd) -> map("customerId", "C-DEMO", "limitType", "SINGLE",
                                        "incomeBased", 600000, "collateralBased", 2184000,
                                        "requestedAmount", 2000000,
                                        "ratingFactor", 0.9, "availableLimit", 3000000),
                                (sl, d) -> "可贷额度=" + d.path("limitAmount").asText("-")
                                        + "，剩余可用=" + d.path("remainAmount").asText("-")
                                        + "，管控=" + d.path("controlStatus").asText("-")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录估值依据与留痕", deps("st6"))),
                "回复 1 确认估值结论并测算覆盖，回复 2 安排正式评估机构上门，回复 3 暂缓",
                (sl, sm, sd) -> List.of(
                        block("押品概况",
                                "押品类型：住宅｜坐落：昌平区｜面积 120 ㎡｜权属：清晰无查封",
                                "拟担保金额：200 万元经营贷"),
                        block("估值方法与结果",
                                s(sm.get("st4"), "估值结果：详见滚动日志"),
                                "市场比较法：参考 3 套近期同小区成交案例（均价 2.6 万元/㎡），评估值约 312 万元"),
                        block("折扣率与覆盖率",
                                s(sm.get("st6"), "额度测算：详见滚动日志"),
                                "住宅折扣率 70% → 可贷约 218 万元，对 200 万元授信覆盖率 1.56（充足）"),
                        block("现场影像核验备注",
                                "房屋现状整洁，无明显瑕疵｜正式抵押登记价值以具备资质的评估机构报告为准",
                                "回复 1 转额度测算（衔接 S4-2），回复 2 安排正式评估机构，回复 3 结束"))));

        // ============ S3-11 尽调报告自动生成 ============
        REGISTRY.put("S3-11", new SceneScript(
                "S3-11", "dd-report-agent", "尽调报告自动生成",
                List.of("尽调报告", "报告初稿", "尽调结论汇总"),
                "您好，我是尽调报告生成助手。本次尽调已完成的结论我会自动汇总，按行内模板生成报告初稿。",
                "本次将为您完成：1.分模块摘要 2.制度口径检索 3.报告拼装 4.要素校验 5.双签定稿",
                List.of(
                        real("st1", "① 调用 summary-agent 分模块摘要（主体/征信/股权/现场/风险/财务/押品 7 章）", "summary", deps(),
                                ":8115/api/bai/summary/generate",
                                (sl, sm, sd) -> map("text", "恒润机械尽调结论汇总：主体为制造业小微企业，经营贷余额 180 万元，五级分类正常；企业征信无逾期，对外担保 300 万元；股权穿透 4 层，最终受益人张某某合计 42%；现场尽调生产正常、库存与产能匹配；名单筛查命中 2 户关联企业；反欺诈评分 62 分中风险；押品住宅评估值 312 万元，覆盖率 1.56。",
                                        "mode", "bullet", "maxChars", 200, "extractKeyInfo", true),
                                (sl, d) -> "摘要 summaryId=" + d.path("summaryId").asText("-")
                                        + "，压缩比=" + d.path("stats").path("ratio").asText("-")),
                        real("st2", "② 调用 knowledge-retrieve-agent 检索报告制度口径与引用条款", "knowledge-retrieve", deps(),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "授信尽调报告制度 调查意见页 风险提示页 要素完整性", "topK", 3),
                                (sl, d) -> "制度依据命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        real("st3", "③ 调用 report-gen-agent 按行内模板拼装报告初稿（当前模板库为监管报表模板，尽调模板待管理员配置）", "report-gen", deps("st1", "st2"),
                                ":8131/api/bai/report/generate",
                                (sl, sm, sd) -> map("templateId", "T_MGMT_MONTHLY",
                                        "params", map("orgId", "ORG-DEMO", "date", "2026-10-31",
                                                "granularity", "ORG",
                                                "customer", s(sl.get("customer"), "北京恒润机械制造有限公司")),
                                        "format", "PDF"),
                                (sl, d) -> "报告=" + d.path("reportId").asText("-")
                                        + "，文件=" + d.path("fileId").asText("-")
                                        + "，三校验=" + d.path("checks").path("caliber").asText("-") + "/"
                                        + d.path("checks").path("required").asText("-") + "/"
                                        + d.path("checks").path("balance").asText("-")),
                        realDocParse("st4", "④ 调用 doc-parse-agent 版式还原（标题层级、表格、页眉）", deps("st3"), "investigation-report-S3-11.pdf", "report"),
                        realCompliance("st5", "⑤ 调用 compliance-check-agent 校验报告要素完整性（调查意见页、风险提示页、结论页）", deps("st4"), "ADMISSION", map("docType", "调查报告", "sections", "调查意见页,风险提示页,结论页")),
                        human("st6", "⚑ 人工确认节点：报告要素校验通过后，调查人＋复核人双签定稿（超时提醒）", deps("st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录报告生成与修改留痕", deps("st6"))),
                "回复 1 双签定稿并归档，回复 2 补充缺口材料后重新生成，回复 3 暂不定稿",
                (sl, sm, sd) -> List.of(
                        block("报告目录与要素校验",
                                "章节：借款人主体/征信分析/股权结构/现场尽调/风险揭示/押品情况/调查意见",
                                s(sm.get("st3"), "报告拼装：详见滚动日志")),
                        block("报告正文分章预览",
                                s(sm.get("st1"), "分章摘要：详见滚动日志"),
                                "每章数据来源已标注（如：征信数据来自 2026-11-02 人行查询）"),
                        block("待补充材料清单",
                                "本笔为流动资金贷款，项目融资评估不适用｜名单筛查命中 2 户待合规结论｜其余无缺口",
                                s(sm.get("st2"), "制度口径：详见滚动日志")),
                        block("报告文档下载",
                                "报告 PDF 已生成，可在对话内直接下载保存（草稿回写尽调档案库）",
                                "回复 1 双签定稿并归档（衔接 S5-5），回复 2 补充材料重新生成，回复 3 结束"))));

        // ============ S4-1 授信方案智能生成 ============
        REGISTRY.put("S4-1", new SceneScript(
                "S4-1", "credit-plan-agent", "授信方案智能生成",
                List.of("授信方案", "授信意见", "方案草案", "流贷授信"),
                "您好，我是授信方案助手。您直接告诉我客户名称和拟用产品，例如“为华辰机械做一份流贷授信方案”，我会基于尽调结果自动生成方案草案并试算还款计划，关键参数由您和审批人终审。",
                "本次将为您完成：1.客户归并与尽调结论复用 2.产品政策与制度检索 3.偿债指标复核 4.方案草案与还款计划试算 5.关键参数终审",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并客户身份与关联客户号（尽调报告 DJ-2026-102 已复用）", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", s(sl.get("customer"), "北京华辰机械制造有限公司"),
                                                "idCard", demoCode("91110001MA1FL", s(sl.get("customer"), "北京华辰机械制造有限公司"))),
                                        "source", "core", "entityType", "COMPANY"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        real("st2", "② 调用 knowledge-process-agent 调取流贷产品准入条件与要素政策", "knowledge-process", deps("st1"),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "DOC-S41-001", "source", "policy",
                                        "text", "流动资金贷款产品要素与准入：制造业小微企业成立满 2 年、纳税 B 级以上；额度上限 1000 万元，期限最长 12 个月；担保方式信用/保证/抵押/质押；利率区间 LPR+60BP 至 LPR+120BP；还款方式按期付息到期还本或等额本息。"),
                                null),
                        real("st3", "③ 调用 knowledge-retrieve-agent 检索本行授信制度与授权比例要求", "knowledge-retrieve", deps("st1"),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "授信审批授权制度 支行权限档位 审贷分离 授信方案要素要求", "topK", 3),
                                (sl, d) -> "制度依据命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        real("st4", "④ 调用 financial-statement-agent 复核资产负债率等偿债指标（metric-calc 指标库无对应偿债指标，按 A-65 财务分析执行）", "financial-statement", deps("st1"),
                                ":8139/api/bai/financial-statement/analyze",
                                (sl, sm, sd) -> map("entityId", "E-HUACHEN", "period", "2026-Q3", "scenario", "DUE_DILIGENCE",
                                        "statements", List.of(
                                                map("type", "BALANCE", "items", map("totalAssets", 52000000, "totalLiab", 30160000, "totalEquity", 21840000)),
                                                map("type", "INCOME", "items", map("revenue", 42000000, "netProfit", 2940000)),
                                                map("type", "CASHFLOW", "items", map("operatingCashFlow", 3600000)))),
                                (sl, d) -> "财务解析 analysisNo=" + d.path("analysisNo").asText("-")
                                        + "，造假评分=" + d.path("fraudScore").asText("-")),
                        realTextGen("st5", "⑤ 调用 text-gen-agent 生成授信方案草案（品种/额度/期限/利率区间/担保/用途/还款方式，逐项标注依据）", deps("st2", "st3", "st4")),
                        real("st6", "⑥ 调用 repayment-plan-agent 按方案要素试算 12 期还款计划（先息后本，LPR3.45%+60BP）", "repayment-plan", deps("st5"),
                                ":8127/api/bai/repayment-plan/plan",
                                (sl, sm, sd) -> map("contractId", "HT-S41-DEMO", "principal", 5000000, "rate", 0.0405,
                                        "terms", 12, "method", "PERIOD_INTEREST", "startDate", "2026-12-01"),
                                (sl, d) -> "月供=" + d.path("monthlyPayment").asText("-")
                                        + "，利息合计=" + d.path("totalInterest").asText("-")
                                        + "，期数=12（先息后本）"),
                        human("st7", "⚑ 人工确认节点：授信额度、利率水平、担保条件三项关键参数提交客户经理与审批人终审（48 小时未确认自动挂起并提醒）", deps("st2", "st3", "st4", "st5", "st6")),
                        realStructure("st8", "⑦ 调用 structure-gen-agent 生成方案要点图表", deps("st7"), "SCHEMA_LOAN_TABLE_V1", "TEXT", "授信方案要点", "TABLE"),
                        realAudit("st9", "⑧ 调用 audit-log-agent 记录方案生成与人工修改全程留痕", deps("st8"))),
                "回复 1 确认提交审批，回复 2 调整参数，回复 3 暂存草稿",
                (sl, sm, sd) -> List.of(
                        block("客户与尽调结论摘要",
                                "客户：" + s(sl.get("customer"), "北京华辰机械制造有限公司") + "（制造业小微企业，成立 8 年）",
                                "尽调结论：近三年流水年均 4200 万，征信无逾期，资产负债率 58%（数据来源 S3 族报告）",
                                s(sm.get("st1"), "身份归并：详见滚动日志")),
                        block("授信方案草案",
                                "品种：流动资金贷款｜建议额度 500 万元｜期限 12 个月",
                                "利率区间：LPR 3.45%+60～120BP｜担保：厂房抵押（评估值 980 万元）",
                                "用途：原材料采购与经营周转｜还款方式：按期付息到期还本（逐项依据已标注）"),
                        block("还款计划试算",
                                s(sm.get("st6"), "还款计划：详见滚动日志"),
                                "先息后本：月息约 1.69 万元，到期一次性归还本金 500 万元"),
                        block("确认区",
                                "方案关键参数（额度/利率/担保）须人工终审后方可流转，系统不自动生效",
                                "回复 1 确认提交审批，回复 2 调整参数，回复 3 暂存草稿"))));

        // ============ S4-2 额度测算 ============
        REGISTRY.put("S4-2", new SceneScript(
                "S4-2", "credit-limit-agent", "额度测算",
                List.of("额度测算", "能贷多少", "三口径", "测额度"),
                "您好，我是额度测算助手。告诉我客户名称和拟用品种，我会按收入倍数、押品折扣、风险限额三条口径各算一遍，取最小值给出建议额度，计算过程全部透明可查。",
                "本次将为您完成：1.征信负债汇总 2.经营收入核定 3.押品折扣测算 4.风险限额校验 5.三口径取小",
                List.of(
                        real("st1", "① 调用 credit-report-agent 建立征信查询授权（用途：信贷审批）", "credit-report", deps(),
                                ":8110/api/bai/credit-report/authorize",
                                (sl, sm, sd) -> map("action", "CREATE",
                                        "customer", map("certNo", "91110000123456789X", "name", s(sl.get("customer"), "北京华辰机械制造有限公司")),
                                        "authDoc", map("docNo", "AUTH-S42-001", "certNo", "91110000123456789X",
                                                "name", s(sl.get("customer"), "北京华辰机械制造有限公司"), "signedAt", "2026-11-03"),
                                        "purpose", "信贷审批", "validDays", 365),
                                (sl, d) -> "授权建档 authId=" + d.path("authId").asText("-")
                                        + "，用途合规=" + d.path("purposeValid").asText("-")),
                        real("st2", "② 调用 credit-report-agent 汇总客户现有授信与对外担保余额（他行余额 180 万）", "credit-report", deps("st1"),
                                ":8110/api/bai/credit-report/report",
                                (sl, sm, sd) -> map("authId", s(sd.containsKey("st1") ? sd.get("st1").path("authId").asText(null) : null, "CRR-DEMO-001"),
                                        "reportType", "COMPANY"),
                                (sl, d) -> "征信报告=" + s(d.path("report").path("reportNo").asText(null),
                                        s(d.path("reportNo").asText(null), d.path("taskId").asText("-"))) + "，现有授信与担保余额已汇总"),
                        real("st3", "③ 调用 data-query-agent 分析经营用信流水，核定有效经营收入（流水明细表待接入，以核心余额日表近似演示）", "data-query", deps("st1"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_core", "table", "loan_balance_daily",
                                        "fields", List.of("branch_code", "loan_balance")),
                                null),
                        real("st4", "④ 调用 financial-statement-agent 提取近一年营业收入与偿债指标", "financial-statement", deps("st1"),
                                ":8139/api/bai/financial-statement/analyze",
                                (sl, sm, sd) -> map("entityId", "E-HUACHEN", "period", "2026-Q3", "scenario", "DUE_DILIGENCE",
                                        "statements", List.of(
                                                map("type", "BALANCE", "items", map("totalAssets", 52000000, "totalLiab", 30160000, "totalEquity", 21840000)),
                                                map("type", "INCOME", "items", map("revenue", 42000000, "netProfit", 2940000)),
                                                map("type", "CASHFLOW", "items", map("operatingCashFlow", 3600000)))),
                                (sl, d) -> "财务解析 analysisNo=" + d.path("analysisNo").asText("-")
                                        + "，营业收入 4200 万已提取"),
                        real("st5", "⑤ 调用 valuation-agent 按押品类型计算可抵质押额度（厂房评估值 980 万×70% 折扣=686 万）", "valuation", deps("st1"),
                                ":8124/api/bai/valuation/value",
                                (sl, sm, sd) -> map("assetType", "REAL_ESTATE", "method", "MARKET",
                                        "assetRef", "京昌平厂房-5000㎡-2026",
                                        "params", map("area", 5000, "unitPrice", 1960, "comparables", 3),
                                        "valueDate", "2026-11-03"),
                                (sl, d) -> "评估值=" + d.path("value").asText("-")
                                        + "，区间=" + d.path("range").path("low").asText("-") + "~" + d.path("range").path("high").asText("-")
                                        + "，结论=" + d.path("conclusion").asText("-")),
                        realModelInfer("st6", "⑥ 调用 model-infer-agent 输出客户风险等级 A，映射单户限额倍数", deps("st4"), "RATING", "SCORECARD", "CRB-SC-2025-V3", "C-S4-2", map("annualIncome", 1200000, "assetLiabRatio", 0.35, "netCashRatio", 0.42)),
                        real("st7", "⑦ 调用 data-query-agent 查询客户所属集团与关联方统一授信占用（关联企业在用 600 万）", "data-query", deps("st1"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        real("st8", "⑧ 调用 limit-calc-agent 三口径取小（收入口径 1050 万/押品口径 686 万/集团集中度限额 600 万），输出建议额度与计算明细", "limit-calc", deps("st2", "st3", "st4", "st5", "st6", "st7"),
                                ":8123/api/bai/limit/calc",
                                (sl, sm, sd) -> map("customerId", "C-HUACHEN", "limitType", "SINGLE",
                                        "incomeBased", 10500000, "collateralBased", 6860000,
                                        "requestedAmount", 6000000,
                                        "ratingFactor", 0.9, "availableLimit", 6000000),
                                (sl, d) -> "建议额度=" + d.path("limitAmount").asText("-")
                                        + "，剩余可用=" + d.path("remainAmount").asText("-")
                                        + "，管控=" + d.path("controlStatus").asText("-")),
                        human("st9", "⚑ 人工确认节点：建议额度与客户经理预期差异超 20% 时确认口径取舍（留痕可审计）", deps("st8")),
                        realAudit("st10", "⑨ 调用 audit-log-agent 记录测算口径、取小过程与全程留痕", deps("st9"))),
                "回复 1 确认建议额度并回填授信方案，回复 2 调整测算口径重算，回复 3 导出测算说明",
                (sl, sm, sd) -> List.of(
                        barsBlock("三口径测算明细", "万",
                                "收入倍数口径＝有效经营收入 4200 万×25%", 1050,
                                "押品折扣口径＝厂房 980 万评估值×70%", 686,
                                "风险限额口径＝集团集中度上限", 600),
                        block("建议额度",
                                "三者取小＝600 万元，约束口径：集团集中度",
                                s(sm.get("st8"), "额度测算：详见滚动日志")),
                        block("测算后偿债指标预测",
                                "资产负债率测算值约 61%｜利息保障倍数 2.4｜征信现有授信他行余额 180 万已纳入"),
                        block("操作区",
                                "回复 1 确认建议额度并回填授信方案（衔接 S4-1），回复 2 调整测算口径重算，回复 3 导出测算说明"))));

        // ============ S4-3 利率定价 ============
        REGISTRY.put("S4-3", new SceneScript(
                "S4-3", "loan-pricing-agent", "利率定价",
                List.of("利率定价", "定价", "FTP", "加点"),
                "您好，我是利率定价助手。告诉我客户和额度期限，我按 FTP 加点模型给出建议利率，每一项加点都写清楚依据，突破底线时会提示升级审批。",
                "本次将为您完成：1.FTP 与 LPR 取数 2.风险溢价档位 3.政策区间校验 4.综合利率测算 5.定价确认",
                List.of(
                        real("st1", "① 调用 knowledge-process-agent 取当期 FTP 基准与 LPR 报价", "knowledge-process", deps(),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "DOC-S43-001", "source", "policy",
                                        "text", "当期资金定价基准：FTP 基准 2.85%，1 年期 LPR 3.45%；期限溢价 1 年期 20BP；综合贡献让价上限 20BP；定价以 FTP 为基准叠加信用风险溢价与期限溢价。"),
                                null),
                        realModelInfer("st2", "② 调用 model-infer-agent 取客户风险等级与对应风险溢价档位（A 级客户 → 90BP）", deps(), "RATING", "SCORECARD", "CRB-SC-2025-V3", "C-S4-3", map("annualIncome", 1200000, "assetLiabRatio", 0.35, "netCashRatio", 0.42)),
                        real("st3", "③ 调用 knowledge-process-agent 校验品种定价政策上下限（流贷 3.45%～4.50%）", "knowledge-process", deps(),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "DOC-S43-002", "source", "policy",
                                        "text", "流动资金贷款定价政策：下限 3.45%（LPR），上限 4.50%；抵押方式可适度下浮，信用方式上浮；利率下限不得违反变相高息揽存与低价倾销禁止性规定；突破底线须有权审批人审批。"),
                                null),
                        real("st4", "④ 调用 pricing-calc-agent 按 FTP+风险溢价+期限溢价+综合贡献调整测算综合利率", "pricing-calc", deps("st1", "st2", "st3"),
                                ":8122/api/bai/pricing/quote",
                                (sl, sm, sd) -> map("productId", "LN-FLOW-001", "amount", 6000000, "term", 12,
                                        "rateType", "FLOATING", "collateral", "抵押", "customerRating", "A"),
                                (sl, d) -> "基准利率=" + d.path("baseRate").asText("-")
                                        + "，风险溢价=" + d.path("riskPremium").asText("-")
                                        + "，成本加点=" + d.path("costAddOn").asText("-")
                                        + "，综合利率=" + d.path("finalRate").asText("-")),
                        realStructure("st5", "⑤ 调用 structure-gen-agent 生成定价瀑布图数据", deps("st4"), "SCHEMA_LOAN_TABLE_V1", "TEXT", "定价分解", "TABLE"),
                        human("st6", "⚑ 人工确认节点：建议利率低于政策底线或下浮超 50BP 时提交有权审批人终审", deps("st4", "st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录定价依据与审批留痕", deps("st6"))),
                "回复 1 确认定价并回填授信方案，回复 2 调整综合贡献让价重算，回复 3 导出定价说明",
                (sl, sm, sd) -> List.of(
                        block("定价依据",
                                "FTP 基准 2.85%＋风险溢价 90BP（A 级客户）＋期限溢价 20BP－综合贡献调整 20BP",
                                s(sm.get("st4"), "定价试算：详见滚动日志")),
                        block("建议年利率",
                                "约 3.75%（即 LPR 3.45%+30BP），落在政策区间 3.45%～4.50% 内",
                                "未突破底线利率，无需升级审批"),
                        barsBlock("定价瀑布", "%",
                                "FTP", 2.85,
                                "+风险溢价", 0.90,
                                "+期限溢价", 0.20,
                                "-综合贡献", -0.20,
                                "建议", 3.75),
                        block("操作区",
                                "回复 1 确认定价并回填授信方案，回复 2 调整综合贡献让价重算，回复 3 导出定价说明"))));

        // ============ S4-4 智能审批（规则+模型融合） ============
        REGISTRY.put("S4-4", new SceneScript(
                "S4-4", "credit-approval-agent", "智能审批（规则+模型融合）",
                List.of("智能审批", "提交审批", "审批建议", "能过吗", "审批流程"),
                "您好，我是智能审批助手。提交授信任务编号即可，我会并行跑规则校验、模型评分与名单筛查，给出审批建议并自动流转审批流，最终意见由审批人作出。",
                "本次将为您完成：1.名单筛查 2.规则红线校验 3.模型评分 4.授权政策比对 5.审批建议与流转",
                List.of(
                        realScreening("st1", "① 调用 screening-agent 筛查制裁名单、黑名单、不良名单", deps(), "ADMISSION", "恒通商贸有限公司"),
                        real("st2", "② 调用 rule-engine-agent 执行准入红线规则校验", "rule-engine", deps(),
                                ":8120/api/bai/rule/execute",
                                (sl, sm, sd) -> map("rulesetId", "ADMISSION_PERSONAL_LOAN", "rulesetVersion", "2026.09.01", "explain", true,
                                        "facts", map("age", 8, "monthlyIncome", 350000, "loanAmount", 6000000,
                                                "term", 12, "creditScore", 762)),
                                (sl, d) -> "规则结论=" + d.path("conclusion").path("decision").asText("-")
                                        + "，命中 " + d.path("hits").size() + " 条（execId=" + d.path("execId").asText("-") + "）"),
                        realModelInfer("st3", "③ 调用 model-infer-agent 运行评分卡：评分 762 分、违约概率 0.8%、风险等级 A", deps(), "RATING", "SCORECARD", "CRB-SC-2025-V3", "C-S4-4", map("annualIncome", 1200000, "assetLiabRatio", 0.35, "netCashRatio", 0.42)),
                        real("st4", "④ 调用 knowledge-retrieve-agent 比对授权政策与审批权限", "knowledge-retrieve", deps(),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "审批授权政策 审贷分离 支行审批权限 贷审会会签", "topK", 3),
                                (sl, d) -> "授权政策命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        mock("st5", "⑤ 融合规则与模型结果，形成审批建议结论：建议同意（大额转人工复核已提示）（主智能体确定性仿真）", "credit-approval-agent", deps("st1", "st2", "st3", "st4")),
                        human("st6", "⚑ 人工确认节点：审批人作出最终审批意见（同意/有条件同意/退回/拒绝），审贷分离、分级授权", deps("st5")),
                        realApproval("st7", "⑥ 调用 approval-route-agent 按审批意见流转下一节点（放款条件确认或退回整改）", deps("st6"), "CREDIT_APPLICATION", 5000000, "MEDIUM"),
                        realTodo("st8", "⑦ 调用 todo-agent 更新审批进度与督办", deps("st7"), "APPROVAL", "APP-S4-4", "S4-4 st8 待办跟踪", "APPR001"),
                        realAudit("st9", "⑧ 调用 audit-log-agent 记录审批全程留痕", deps("st8"))),
                "回复 1 确认审批建议并流转，回复 2 补充说明材料，回复 3 退回整改",
                (sl, sm, sd) -> List.of(
                        block("规则校验结果",
                                "红线 0 条触发、禁入条件 0 条、多头授信 2 家已纳入测算",
                                s(sm.get("st2"), "规则引擎：详见滚动日志")),
                        block("模型评分",
                                "评分 762 分｜违约概率 0.8%｜风险等级 A（评分仅作参考权重，不替代人工判断）",
                                s(sm.get("st3"), "模型评分：详见滚动日志")),
                        block("审批建议",
                                "建议同意：授信 600 万元、利率 3.75%、期限 12 个月、厂房抵押",
                                "大额复核提示：金额超 100 万档触发转人工复核规则，已并入审批建议"),
                        block("审批流状态",
                                "当前节点：支行审批人（任务编号 SX20261102-018）",
                                "回复 1 确认审批建议并流转，回复 2 补充说明材料，回复 3 退回整改"))));

        // ============ S4-5 审批授权路由 ============
        REGISTRY.put("S4-5", new SceneScript(
                "S4-5", "approval-routing-agent", "审批授权路由",
                List.of("审批层级", "谁批", "授权路由", "改派", "路由审批"),
                "您好，我是审批授权路由助手。给我授信任务编号，我按金额、风险等级和品种自动判定审批层级并路由到审批人，权限外业务自动升级。",
                "本次将为您完成：1.权限矩阵调取 2.审批人身份核验 3.层级判定与回避校验 4.路由确认 5.督办登记",
                List.of(
                        real("st1", "① 调用 data-query-agent 调取机构权限档位与授信台账数据（权限矩阵表待配置，以授信账户表近似演示）", "data-query", deps(),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        real("st2", "② 调用 auth-agent 核验审批人身份与在岗状态（授权层级判定矩阵待权限中心扩展，身份核验先行）", "auth", deps("st1"),
                                ":8109/api/bai/auth/login",
                                (sl, sm, sd) -> map("identity", map("userId", "APPROVER-BR01", "name", "支行审批人张审"),
                                        "factor", map("type", "PASSWORD", "credential", "******"),
                                        "context", map("deviceId", "WEB-SCENE", "deviceFp", "FP-S45", "ip", "10.0.0.21",
                                                "userAgent", "credit-web", "geo", "北京"),
                                        "scene", "BACKOFFICE"),
                                (sl, d) -> "审批人身份核验 authId=" + d.path("authId").asText("-")
                                        + "，authenticated=" + d.path("authenticated").asText("-")
                                        + "，风险等级=" + d.path("riskLevel").asText("-")),
                        realApproval("st3", "③ 校验审批人回避与会签要求（集团客户加签、投贷联动加签投委会；无回避情形）", deps("st2"), "CREDIT_APPLICATION", 3000000, "MEDIUM"),
                        human("st4", "⚑ 人工确认节点：路由结果确认——600 万落支行权限档（≤1000 万），主审支行审批人；与预期不符可申请改派（改派须审批主管确认并留痕）", deps("st2", "st3")),
                        realApproval("st5", "④ 调用 approval-route-agent 路由任务到审批人节点（主审：支行审批人；会签：无）", deps("st4"), "CREDIT_APPLICATION", 3000000, "LOW"),
                        realTodo("st6", "⑤ 调用 todo-agent 登记路由状态与预计时限（1 个工作日）", deps("st5"), "APPROVAL", "APP-S4-5", "S4-5 st6 待办跟踪", "APPR001"),
                        realNotify("st7", "⑥ 调用 notify-agent 配置超时督办", deps("st6"), "TASK_URGE", "张审批", "13800004444", map("taskContent", "授信审批超时督办", "deadline", "2026-11-05")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录路由依据与全程留痕", deps("st7"))),
                "回复 1 确认路由，回复 2 申请改派审批人，回复 3 暂缓路由",
                (sl, sm, sd) -> List.of(
                        block("路由依据",
                                "建议额度 600 万落支行权限档（≤1000 万）｜风险等级 A｜品种流贷｜担保抵押",
                                s(sm.get("st1"), "权限数据：详见滚动日志")),
                        block("路由结果",
                                "主审：支行审批人张审｜会签：无｜预计时限 1 个工作日",
                                s(sm.get("st2"), "审批人身份核验：详见滚动日志")),
                        statusBlock("权限校验",
                                "ok", "该审批人权限上限 1500 万", "满足",
                                "ok", "无回避情形", "",
                                "ok", "集团客户/投贷联动加签规则", "已校验"),
                        block("操作区",
                                "回复 1 确认路由，回复 2 申请改派审批人，回复 3 暂缓路由"))));

        // ============ S4-6 合规审查（红线管控） ============
        REGISTRY.put("S4-6", new SceneScript(
                "S4-6", "compliance-review-agent", "合规审查（红线管控）",
                List.of("合规审查", "红线", "合规吗", "合规检查"),
                "您好，我是合规审查助手。给我客户名称或任务编号，我按红线规则逐项审查，触发项会列明条款出处，能整改的出整改意见，不能整改的直接拦截。",
                "本次将为您完成：1.名单复核 2.条款定位 3.红线逐项校验 4.结论分级 5.整改或拦截",
                List.of(
                        realScreening("st1", "① 调用 screening-agent 复核制裁名单、黑名单、失信被执行人", deps(), "RE_SCREEN", "恒通商贸有限公司"),
                        real("st2", "② 调用 knowledge-retrieve-agent 定位本行红线制度条款与监管规定", "knowledge-retrieve", deps(),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "流动资金贷款管理办法 授信尽职指引 红线条款 禁入行业 多头授信 受托支付", "topK", 3),
                                (sl, d) -> "条款命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        real("st3", "③ 调用 rule-engine-agent 逐项校验禁入行业、限贷行业、多头授信、用途合规（以内置准入规则集近似演示，红线规则库待合规部门配置）", "rule-engine", deps("st2"),
                                ":8120/api/bai/rule/execute",
                                (sl, sm, sd) -> map("rulesetId", "ADMISSION_PERSONAL_LOAN", "rulesetVersion", "2026.09.01", "explain", true,
                                        "facts", map("age", 8, "monthlyIncome", 350000, "loanAmount", 6000000,
                                                "term", 12, "creditScore", 735)),
                                (sl, d) -> "校验结论=" + d.path("conclusion").path("decision").asText("-")
                                        + "，命中 " + d.path("hits").size() + " 条（execId=" + d.path("execId").asText("-") + "）"),
                        realRiskSignal("st4", "④ 调用 risk-signal-agent 输出审查结论分级：通过（0 条红线触发）", deps("st1", "st3"), "C-S4-6", List.of("PUBLIC"), List.of("无红线触发记录")),
                        human("st5", "⚑ 人工确认节点：拦截类结论提交合规部门确认；整改类由客户经理整改后复审", deps("st4")),
                        realTextGen("st6", "⑤ 调用 text-gen-agent 生成整改通知或拦截告知（本次审查通过，通知模板备用）", deps("st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录审查全程留痕", deps("st6"))),
                "回复 1 通过审查并进入审批，回复 2 提交补充用途证明复审，回复 3 导出审查单",
                (sl, sm, sd) -> List.of(
                        block("审查项清单",
                                "行业分类制造业未禁入｜用途购原材料合规｜多头授信他行 180 万未超比例｜名单未命中",
                                s(sm.get("st2"), "条款依据：详见滚动日志")),
                        block("审查结论",
                                "通过，0 条红线触发",
                                s(sm.get("st3"), "规则校验：详见滚动日志")),
                        block("条款依据",
                                "《商业银行授信工作尽职指引》《流动资金贷款管理暂行办法》及本行红线制度（每项已标注出处）"),
                        block("操作区",
                                "回复 1 通过审查并进入智能审批（衔接 S4-4），回复 2 提交补充用途证明复审，回复 3 导出审查单"))));

        // ============ S4-7 关联交易识别与管控（图谱核心） ============
        REGISTRY.put("S4-7", new SceneScript(
                "S4-7", "related-party-agent", "关联交易识别与管控（图谱核心）",
                List.of("关联交易", "关联方", "集团统一授信", "统一授信", "隐性关联"),
                "您好，我是关联交易识别助手。给我客户名称，我从股权、任职、担保、资金往来数据挖关联网络，识别集团与担保圈，并校验统一授信占用，隐性关联会请您人工确认。",
                "本次将为您完成：1.身份归并 2.关联关系挖掘 3.图谱子图查询 4.统一授信占用校验 5.管控建议确认",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并客户多来源身份（同一实控人名下 3 户）", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", s(sl.get("customer"), "北京华辰机械制造有限公司"),
                                                "idCard", demoCode("91110001MA1FL", s(sl.get("customer"), "北京华辰机械制造有限公司"))),
                                        "source", "core", "entityType", "COMPANY"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        real("st2", "② 调用 graph-build-agent 从股权/任职/担保/资金往来挖掘关联关系", "graph-build", deps("st1"),
                                ":8134/api/bai/graph/build",
                                (sl, sm, sd) -> map("mode", "FULL",
                                        "scope", map("goldenId", s(sd.containsKey("st1") ? sd.get("st1").path("goldenId").asText(null) : null, "G-DEMO"))),
                                (sl, d) -> "关联关系构建 jobId=" + d.path("jobId").asText("-")
                                        + "，状态=" + d.path("status").asText("-")),
                        realGraphPenetrate("st3", "③ 调用 graph-penetrate-agent 查询关联网络子图与风险传播路径", deps("st2"), "C-S4-7", "DOWNSTREAM", 4),
                        realCompliance("st4", "④ 调用 compliance-check-agent 校验集团统一授信规则与关联交易比例", deps("st3"), "RED_LINE", map("rule", "GROUP_CREDIT", "relatedTxnRatio", "18%")),
                        real("st5", "⑤ 调用 limit-calc-agent 汇总关联方在用授信，计算集团统一授信占用率（在用 600 万＋本次 600 万，占用率 68%）", "limit-calc", deps("st4"),
                                ":8123/api/bai/limit/calc",
                                (sl, sm, sd) -> map("customerId", "GRP-HUACHEN", "limitType", "SINGLE",
                                        "incomeBased", 12000000, "collateralBased", 9800000,
                                        "requestedAmount", 6000000,
                                        "ratingFactor", 0.85, "availableLimit", 17640000),
                                (sl, d) -> "集团统一授信额度=" + d.path("limitAmount").asText("-")
                                        + "，在用=" + d.path("usedAmount").asText("-")
                                        + "，剩余=" + d.path("remainAmount").asText("-")
                                        + "，管控=" + d.path("controlStatus").asText("-")),
                        realRiskSignal("st6", "⑥ 调用 risk-signal-agent 输出担保圈与过度授信预警（担保圈 2 个，圈内 B 企业存在他行逾期，建议关注）", deps("st5"), "C-S4-7", List.of("FLOW", "INDUSTRY"), List.of("担保圈 2 个", "圈内 B 企业存在他行逾期")),
                        human("st7", "⚑ 人工确认节点：隐性关联关系与纳入集团管控范围由客户经理确认（回复 1 确认纳入集团统一授信，回复 2 否认并说明理由）", deps("st6")),
                        realMask("st8", "⑦ 调用 sensitive-mask-agent 对关联信息脱敏展示", deps("st7"), "TEXT", "关联方：北京鑫源商贸有限公司 统一代码 91310000MA1FL1234X", "DISPLAY"),
                        realAudit("st9", "⑧ 调用 audit-log-agent 记录识别与管控全程留痕", deps("st8"))),
                "回复 1 确认纳入集团统一授信，回复 2 否认隐性关联并说明理由，回复 3 导出关联清单",
                (sl, sm, sd) -> List.of(
                        block("关联网络（图谱数据形态）",
                                "核心企业华辰机械｜实控人关联 3 户｜担保圈 2 个｜资金往来关联 5 户（前端可展示图谱）",
                                s(sm.get("st2"), "关系挖掘：详见滚动日志")),
                        block("集团统一授信占用",
                                "华辰系集团在用授信合计 600 万，本次申请 600 万后集团占用率 68%，未超 100% 限额",
                                s(sm.get("st5"), "占用测算：详见滚动日志")),
                        block("管控建议",
                                "建议将本次授信纳入华辰集团统一授信管理",
                                "担保圈内 B 企业存在他行逾期，建议关注（隐性关联须经人工确认后生效）"),
                        block("操作区",
                                "回复 1 确认纳入集团统一授信（结论回填额度测算与审批任务），回复 2 否认隐性关联并说明理由，回复 3 导出关联清单"))));

        // ============ S4-8 贷款产品创设与参数配置 ============
        REGISTRY.put("S4-8", new SceneScript(
                "S4-8", "product-config-agent", "贷款产品创设与参数配置",
                List.of("产品创设", "创设", "新产品", "产品参数", "要素设计"),
                "您好，我是产品创设助手。一句话告诉我目标客群与核心诉求，我帮您设计产品要素、试算定价、做合规校验并生成产品方案，发布需产品管理委员会授权。",
                "本次将为您完成：1.制度与在架产品检索 2.要素草案 3.定价试算 4.合规校验 5.授权发布",
                List.of(
                        real("st1", "① 调用 knowledge-retrieve-agent 检索产品创设制度与监管要求（互联网贷款管理办法）", "knowledge-retrieve", deps(),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "互联网贷款管理暂行办法 产品创设监管要求 准入适当性 利率底线", "topK", 3),
                                (sl, d) -> "监管要求命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        real("st2", "② 调用 knowledge-process-agent 比对在架产品，避免重复创设", "knowledge-process", deps(),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "DOC-S48-001", "source", "policy",
                                        "text", "在架产品清单：随借随还经营贷（房贷到期客群，上限 300 万）、法人快贷（对公，上限 500 万）、税易贷（纳税客群，上限 200 万）；科创小微信用贷客群空白，无重复创设；互联网贷款须符合《商业银行互联网贷款管理暂行办法》并完成报备。"),
                                null),
                        realTextGen("st3", "③ 调用 text-gen-agent 生成产品要素草案（准入：成立满 2 年＋纳税 B 级以上；额度 10～300 万；期限 12 个月；先息后本；纯信用）", deps("st1", "st2")),
                        real("st4", "④ 调用 pricing-calc-agent 按 FTP 与客群风险试算定价区间（科创小微信用贷）", "pricing-calc", deps("st3"),
                                ":8122/api/bai/pricing/quote",
                                (sl, sm, sd) -> map("productId", "LN-SCIINNO-001", "amount", 3000000, "term", 12,
                                        "rateType", "FIXED", "collateral", "信用", "customerRating", "B"),
                                (sl, d) -> "基准利率=" + d.path("baseRate").asText("-")
                                        + "，风险溢价=" + d.path("riskPremium").asText("-")
                                        + "，综合利率=" + d.path("finalRate").asText("-")),
                        realCompliance("st5", "⑤ 调用 compliance-check-agent 校验产品合规（准入歧视、利率底线、适当性）", deps("st3", "st4"), "PRODUCT", map("check", "准入歧视,利率底线,适当性")),
                        human("st6", "⚑ 人工确认节点：产品方案提交产品管理委员会授权发布；涉监管报备产品须附合规部门意见", deps("st5")),
                        real("st7", "⑥ 调用 auth-agent 校验发布操作人身份与权限后写入产品库（授权矩阵待权限中心扩展，身份核验先行）", "auth", deps("st6"),
                                ":8109/api/bai/auth/login",
                                (sl, sm, sd) -> map("identity", map("userId", "PM-GRM-01", "name", "产品管理员"),
                                        "factor", map("type", "PASSWORD", "credential", "******"),
                                        "context", map("deviceId", "WEB-SCENE", "deviceFp", "FP-S48", "ip", "10.0.0.31",
                                                "userAgent", "credit-web", "geo", "北京"),
                                        "scene", "BACKOFFICE"),
                                (sl, d) -> "发布操作人核验 authId=" + d.path("authId").asText("-")
                                        + "，authenticated=" + d.path("authenticated").asText("-")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录创设与授权发布全程留痕", deps("st7"))),
                "回复 1 提交产品委员会授权发布，回复 2 调整要素重算，回复 3 起草配套合同模板",
                (sl, sm, sd) -> List.of(
                        block("产品要素草案",
                                "科创小微信用贷：准入成立满 2 年＋纳税 B 级以上｜额度 10～300 万｜期限 12 个月",
                                "利率区间 3.45%～5.40%｜先息后本｜纯信用、纯线上",
                                s(sm.get("st3"), "要素草案：详见滚动日志")),
                        block("定价试算",
                                s(sm.get("st4"), "定价试算：详见滚动日志"),
                                "FTP＋客群风险溢价区间测算，综合收益已测算"),
                        block("合规校验结论",
                                "通过，0 条违规（准入无歧视性条款、利率未破底线、适当性匹配）",
                                s(sm.get("st2"), "在架产品比对：无重复创设")),
                        block("操作区",
                                "发布须经产品管理委员会授权，产品参数不直接生效",
                                "回复 1 提交产品委员会授权发布，回复 2 调整要素重算，回复 3 起草配套合同模板（衔接 S5-1）"))));

        // ============ S4-9 投贷联动与认股选择权管理 ============
        REGISTRY.put("S4-9", new SceneScript(
                "S4-9", "equity-option-agent", "投贷联动与认股选择权管理",
                List.of("投贷联动", "认股选择权", "选择权", "利率换期权", "行权"),
                "您好，我是投贷联动助手。给我科创企业客户名称和授信方案，我帮您设计认股选择权安排：利率让价换未来认购权，对价定价透明，最终由投委会授权。",
                "本次将为您完成：1.行业评估 2.让价与对价定价 3.协议方案 4.合规与组合校验 5.投委会授权",
                List.of(
                        real("st1", "① 调用 trend-analyze-agent 评估科创企业行业景气与成长阶段（半导体设备零部件，成长期）", "trend-analyze", deps(),
                                ":8132/api/bai/trend/analyze",
                                (sl, sm, sd) -> map("seriesId", "SEMI_PARTS_INDEX", "window", "M6",
                                        "methods", List.of("trend", "mom"),
                                        "points", List.of(
                                                map("date", "2026-04", "value", 102.5), map("date", "2026-05", "value", 103.8),
                                                map("date", "2026-06", "value", 105.1), map("date", "2026-07", "value", 106.4),
                                                map("date", "2026-08", "value", 107.9), map("date", "2026-09", "value", 109.2))),
                                (sl, d) -> "行业趋势 analyzeId=" + d.path("analyzeId").asText("-")
                                        + "，方向=" + d.path("trend").path("direction").asText("-")),
                        real("st2", "② 调用 pricing-calc-agent 测算利率让价幅度与选择权对价（让价 30BP 换未来 3 年按估值 1.2 亿认购不超 1% 股权；期权定价模型未上线，先以利率试算演示）", "pricing-calc", deps("st1"),
                                ":8122/api/bai/pricing/quote",
                                (sl, sm, sd) -> map("productId", "LN-SCIINNO-001", "amount", 5000000, "term", 12,
                                        "rateType", "FLOATING", "collateral", "信用", "customerRating", "A"),
                                (sl, d) -> "基准利率=" + d.path("baseRate").asText("-")
                                        + "，风险溢价=" + d.path("riskPremium").asText("-")
                                        + "，让价后综合利率=" + d.path("finalRate").asText("-")),
                        realTextGen("st3", "③ 调用 text-gen-agent 生成认股选择权协议方案（行权条件、期限、比例、退出安排）", deps("st2")),
                        realCompliance("st4", "④ 调用 compliance-check-agent 校验投贷联动监管合规（银行投资限制、风险隔离）", deps("st3"), "RED_LINE", map("rule", "INVEST_LOAN")),
                        real("st5", "⑤ 调用 metric-calc-agent 统计投贷联动组合集中度（以贷款余额监管指标近似演示，组合统计口径待配置）", "metric-calc", deps(),
                                ":8128/api/bai/metric/calc",
                                (sl, sm, sd) -> map("metricId", "LOAN_BALANCE", "params", map("orgId", "ORG-DEMO", "date", "2026-10-31")),
                                (sl, d) -> "组合统计测算值=" + d.path("value").asText("-") + s(d.path("unit").asText(""), "")
                                        + "（确定性仿真，集中度 8.2% 未超限）"),
                        human("st6", "⚑ 人工确认节点：选择权方案与行权价格提交投资决策委员会授权；客户签署确认后生效", deps("st4", "st5")),
                        real("st7", "⑥ 调用 auth-agent 投委会授权操作人核验并登记选择权台账（授权流转待权限中心扩展，身份核验先行）", "auth", deps("st6"),
                                ":8109/api/bai/auth/login",
                                (sl, sm, sd) -> map("identity", map("userId", "IC-SEC-01", "name", "投委会秘书"),
                                        "factor", map("type", "PASSWORD", "credential", "******"),
                                        "context", map("deviceId", "WEB-SCENE", "deviceFp", "FP-S49", "ip", "10.0.0.41",
                                                "userAgent", "credit-web", "geo", "北京"),
                                        "scene", "BACKOFFICE"),
                                (sl, d) -> "投委会操作人核验 authId=" + d.path("authId").asText("-")
                                        + "，authenticated=" + d.path("authenticated").asText("-")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录选择权安排与授权全程留痕", deps("st7"))),
                "回复 1 提交投委会授权，回复 2 调整让价幅度重算，回复 3 导出协议草案",
                (sl, sm, sd) -> List.of(
                        block("行业评估",
                                "企业属半导体设备零部件赛道，成长期",
                                s(sm.get("st1"), "行业景气：详见滚动日志")),
                        block("选择权方案",
                                "利率由 3.75% 让价至 3.45%，换取未来 3 年内按最近一轮估值 1.2 亿认购不超过 1% 股权的选择权",
                                s(sm.get("st2"), "对价定价：详见滚动日志")),
                        statusBlock("合规与组合结论",
                                "ok", "符合商业银行投贷联动监管试点要求", "信贷与投资业务风险隔离",
                                "ok", "本行投贷联动组合集中度 8.2%", "未超限"),
                        block("操作区",
                                "选择权协议条款、行权价格须投委会授权，客户签署确认后生效",
                                "回复 1 提交投委会授权，回复 2 调整让价幅度重算，回复 3 导出协议草案"))));

        // ============ S5-1 合同/借据智能生成 ============
        REGISTRY.put("S5-1", new SceneScript(
                "S5-1", "contract-draft-agent", "合同/借据智能生成",
                List.of("生成借款合同", "生成合同", "起草担保合同", "出借据", "借据"),
                "您好，我是合同生成助手。审批通过后告诉我客户名称或任务编号，我按产品模板自动生成借款合同、借据和担保合同，缺项会请您补充。",
                "本次将为您完成：1.模板调取 2.要素抽取 3.套模生成 4.完整性校验 5.确认留痕",
                List.of(
                        real("st1", "① 调用 knowledge-process-agent 调取产品合同模板与标准条款库（流贷模板）", "knowledge-process", deps(),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "DOC-S51-001", "source", "policy",
                                        "text", "流贷合同模板要素：借款人名称、借款金额、利率（LPR+加点）、期限、还款方式（等额本息/等额本金/先息后本）、担保方式（抵押/保证/信用）、违约责任、争议解决；最高额抵押合同须符合《民法典》担保编；借据要素与借款合同逐字一致。"),
                                null),
                        realNer("st2", "② 调用 ner-agent 从授信审批结果抽取合同要素", deps("st1"), "同意授信 500 万元，期限 24 个月，年利率 4.35%，抵押物为恒通大厦第 8 层物业，担保人恒通集团有限公司", "CONTRACT"),
                        realTextGen("st3", "③ 调用 text-gen-agent 套模生成借款合同、借据、担保合同文本", deps("st1", "st2")),
                        realQuality("st4", "④ 调用 data-quality-agent 校验要素完整性、缺失项标红", deps("st3"), "dwd_credit.contract"),
                        human("st5", "⚑ 人工确认节点：客户经理核对生成文本并确认缺项补充；缺项补充后重新生成", deps("st4")),
                        realAudit("st6", "⑤ 调用 audit-log-agent 记录生成与修改全程留痕", deps("st5"))),
                "回复 1 确认文本并进入条款合规检查，回复 2 指出需修改的条款，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("生成清单",
                                "借款合同 1 份、借据 1 份、最高额抵押合同 1 份（流贷模板）",
                                s(sm.get("st1"), "模板调取：详见滚动日志")),
                        block("要素套入核对表",
                                "借款人 华辰机械｜金额 600 万｜利率 3.75%｜期限 12 个月｜先息后本｜抵押物 厂房",
                                "逐项标注与审批结果一致（SX20261102-018）"),
                        block("缺项提示",
                                "无缺项，可提交核对"),
                        block("操作区",
                                "生成文本不直接进入签署，须经条款合规检查（S5-2）与要素一致性校验（S5-4）",
                                "回复 1 确认文本并进入条款合规检查，回复 2 指出需修改的条款，回复 3 结束"))));

        // ============ S5-2 合同条款合规检查 ============
        REGISTRY.put("S5-2", new SceneScript(
                "S5-2", "contract-compliance-agent", "合同条款合规检查",
                List.of("合同条款检查", "条款审查", "审合同", "法律风险", "合同条款合规"),
                "您好，我是合同合规检查助手。给我合同编号，我逐条审查必备条款、利率费用表述、违约责任与不公平条款，出具修改意见。",
                "本次将为您完成：1.版面解析 2.条款抽取 3.法规定位 4.逐条审查 5.意见与终审",
                List.of(
                        realDocParse("st1", "① 调用 doc-parse-agent 解析合同版面结构、条款定位", deps(), "loan-contract-S5-2.pdf", "loan_contract"),
                        realNer("st2", "② 调用 ner-agent 抽取关键条款要素", deps("st1"), "借款人恒通商贸有限公司；担保方式：抵押＋保证；还款方式按月等额本息；贷款用途经营周转；金额 500 万元", "CONTRACT"),
                        real("st3", "③ 调用 knowledge-retrieve-agent 定位相关法规条款（民法典合同编、担保编）", "knowledge-retrieve", deps(),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "民法典合同编 借款合同必备条款 担保编 格式条款公平性 违约金上限 争议解决管辖", "topK", 3),
                                (sl, d) -> "法规命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        realCompliance("st4", "④ 调用 compliance-check-agent 逐条审查必备条款、利率费用、违约责任、格式条款公平性", deps("st2", "st3"), "CONTRACT", map("clauses", "必备条款,利率费用,违约责任,格式条款")),
                        realTextGen("st5", "⑤ 调用 text-gen-agent 生成审查意见与修改建议", deps("st4")),
                        human("st6", "⚑ 人工确认节点：法律部门对重大条款终审；建议修改条款须确认后方可修改", deps("st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录审查全程留痕", deps("st6"))),
                "回复 1 采纳修改建议重新生成，回复 2 提交法律部门终审，回复 3 进入要素一致性校验",
                (sl, sm, sd) -> List.of(
                        withExtraLines(donutBlock("审查概览", "条",
                                "通过", 10, "建议修改", 2, "不合规", 0),
                                "共审查 12 条",
                                s(sm.get("st3"), "法规依据：详见滚动日志")),
                        statusBlock("问题条款清单",
                                "warn", "第 8 条 违约责任表述偏宽泛", "建议按民法典违约金上限调整",
                                "warn", "第 11 条 争议解决管辖约定不明确", "建议明确为贷款人住所地法院"),
                        block("修改建议对照",
                                "原条款与建议条款逐句对照已生成，采纳后回 S5-1 重新生成",
                                s(sm.get("st5"), "审查意见书：详见滚动日志")),
                        block("操作区",
                                "本场景只出审查意见，不替代法律部门终审；银团、跨境等重大复杂合同须转法律部门人工审查",
                                "回复 1 采纳修改建议重新生成，回复 2 提交法律部门终审，回复 3 进入要素一致性校验（衔接 S5-4）"))));

        // ============ S5-3 电子签约与意愿核验 ============
        REGISTRY.put("S5-3", new SceneScript(
                "S5-3", "e-sign-agent", "电子签约与意愿核验",
                List.of("电子签约", "发起签约", "签合同", "人脸核验", "意愿核验"),
                "您好，我是电子签约助手。确认合同后我会依次请签署人完成人脸核验和短信验证码确认，全程留证，未通过不会用印。",
                "本次将为您完成：1.人证核验 2.人脸活体 3.短信意愿确认 4.电子签章 5.证据留存",
                List.of(
                        realOcr("st1", "① 调用 ocr-agent 识别签署人身份证并做人证一致性核验", deps(), "ID_CARD", "IMG-S53-01"),
                        realCallExec("st2", "② 调用 call-exec-agent 调起人脸活体核验（视频核验通道未上线，联调待办）", deps("st1"), "DEF_ESIGN_001", map("contractNo", "HT-S5-3-001", "signerId", "IDN-S5-3-01")),
                        realNotify("st3", "③ 调用 notify-agent 发送签署意愿验证码短信", deps("st2"), "VERIFY_CODE", "陈志强", "13800005555", map("code", "863214")),
                        human("st4", "⚑ 人工确认节点：签署人在对话中回复验证码完成意愿核验；人证不一致或验证码错误超 3 次转线下见证签署", deps("st3")),
                        realCallExec("st5", "④ 调用 call-exec-agent 完成电子签章（esign-provider P12）并发放签署证书（电子签章平台未联调，联调待办）", deps("st4"), "DEF_ESIGN_001", map("contractNo", "HT-S5-3-001", "signerId", "IDN-S5-3")),
                        realEvent("st6", "⑤ 调用 event-subscribe-agent 对失败步骤重试与记录", deps("st5"), "call.failed", "S5-3-st6", map("callId", "CE-S5-3-st6")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 留存签约过程证据（人脸视频/时间戳/证书）", deps("st6"))),
                "回复 1 进入合同归档登记，回复 2 补发签署证书，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        withExtraLines(statusBlock("核验结果",
                                "ok", "人证一致", "",
                                "ok", "人脸活体通过", "",
                                "ok", "短信验证码校验通过", "双因素意愿核验"),
                                "未通过核验不签署，核验未通过转客户经理线下见证"),
                        withExtraLines(statusBlock("签署结果",
                                "ok", "借款合同已签章", "",
                                "ok", "担保合同已签章", ""),
                                "签署证书编号 ES20261102-008",
                                s(sm.get("st5"), "签章结果：详见滚动日志")),
                        block("证据留存清单",
                                "核验视频、时间戳、签署证书均已留存，可追溯，满足诉讼举证要求",
                                s(sm.get("st7"), "证据留痕：详见滚动日志")),
                        block("操作区",
                                "签署状态、核验记录、证书编号回写信贷管理系统合同台账",
                                "回复 1 进入合同归档登记（衔接 S5-5），回复 2 补发签署证书，回复 3 结束"))));

        // ============ S5-4 合同要素一致性校验 ============
        REGISTRY.put("S5-4", new SceneScript(
                "S5-4", "contract-consistency-agent", "合同要素一致性校验",
                List.of("一致性校验", "合同要素核对", "签前", "要素核对", "合同和审批", "校验一遍"),
                "您好，我是合同一致性校验助手。给我合同编号，我把合同文本、审批结果、产品模板三方要素逐项核对，不一致会拦截并告诉您差在哪。",
                "本次将为您完成：1.三方取数 2.要素抽取 3.完整性校验 4.交叉核对 5.差异处理",
                List.of(
                        real("st1", "① 调用 data-query-agent 调取审批结果与产品模板参数（审批结果存储未联调，以信贷台账白名单表近似）", "data-query", deps(),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        realNer("st2", "② 调用 ner-agent 抽取合同文本要素", deps("st1"), "合同编号 HT2026-1101-001，金额 100 万元，年利率 4.35%，期限 12 个月，放款账户 622288011101", "CONTRACT"),
                        realQuality("st3", "③ 调用 data-quality-agent 校验要素完整性", deps("st2"), "dwd_credit.contract"),
                        real("st4", "④ 调用 data-validate-agent 三方交叉核对（合同 vs 审批 vs 模板，合同 vs 借据）", "data-validate", deps("st1", "st3"),
                                ":8129/api/bai/validate/validate",
                                (sl, sm, sd) -> map("templateId", "T_RECON",
                                        "data", map("orgId", "ORG-DEMO", "applyDate", "2026-11-02",
                                                "loanAmount", 6000000, "repayAmount", 6000000,
                                                "contractNo", "HT20261102-018", "contractAmount", 6000000,
                                                "approvedAmount", 6000000, "templateAmount", 6000000, "iouAmount", 6000000)),
                                (sl, d) -> "三方交叉核对：" + d.path("result").asText("-")
                                        + "（通过 " + d.path("summary").path("passed").asInt(0) + " 项，警告 "
                                        + d.path("summary").path("warned").asInt(0) + "，失败 "
                                        + d.path("summary").path("failed").asInt(0) + "）"),
                        human("st5", "⚑ 人工确认节点：发现差异时客户经理确认差异处理，回 S5-1 重新生成；无差异方可签署", deps("st4")),
                        realAudit("st6", "⑤ 调用 audit-log-agent 记录校验全程留痕，防止阴阳合同与私自改约", deps("st5"))),
                "回复 1 通过校验并发起电子签约，回复 2 反馈差异项回退重新生成，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        withExtraLines(statusBlock("三方核对总表",
                                "ok", "借款人一致", "",
                                "ok", "金额一致 600 万", "",
                                "ok", "利率一致 3.75%", "",
                                "ok", "期限一致 12 个月", "",
                                "ok", "还款方式一致 先息后本", "",
                                "ok", "担保一致 厂房抵押", ""),
                                s(sm.get("st4"), "交叉核对结果：详见滚动日志")),
                        block("借据核对",
                                "借据金额与合同一致、利率一致，逐字核对通过"),
                        block("结论",
                                "三方一致，可发起签署（差异项将拦截并定位差异条款）"),
                        block("操作区",
                                "一致性校验为放款前操作风险防控硬性环节，差异项不直接改合同，回 S5-1 重新生成",
                                "回复 1 通过校验并发起电子签约（衔接 S5-3），回复 2 反馈差异项回退重新生成，回复 3 结束"))));

        // ============ S5-5 合同归档与登记 ============
        REGISTRY.put("S5-5", new SceneScript(
                "S5-5", "contract-archive-agent", "合同归档与登记",
                List.of("合同归档", "登记合同台账", "合同台账", "归档登记", "归档"),
                "您好，我是合同归档助手。签署完成后我自动归集已签合同电子件并登记台账；如有纸质扫描件，请直接发送资料图片或文件。",
                "本次将为您完成：1.电子件归集 2.纸质件接收 3.完整性校验 4.台账登记 5.待办督办",
                List.of(
                        realCallExec("st1", "① 调用 call-exec-agent 获取已签合同电子文件与签署证书（电子签章平台未联调，联调待办）", deps(), "DEF_ESIGN_001", map("contractNo", "HT-S5-1-001", "signerId", "IDN-S5-1")),
                        realDocParse("st2", "② 调用 doc-parse-agent 解析归档文档结构与目录", deps("st1"), "archive-docs-S5-5.pdf"),
                        realDocParse("st3", "③ 调用 doc-parse-agent 接收纸质扫描件并归集", deps("st1"), "paper-scan-S5-5.jpg"),
                        realQuality("st4", "④ 调用 data-quality-agent 校验归档完整性（电子件＋纸质件＋担保登记）", deps("st2", "st3"), "dwd_credit.loan_file"),
                        realCallExec("st5", "⑤ 调用 call-exec-agent 在信贷管理系统登记合同台账（台账写入接口未上线）", deps("st4"), "DEF_ACCT_DEDUCT_001", map("loanNo", "LN-S5-5-001", "amount", 3000000)),
                        human("st6", "⚑ 人工确认节点：担保登记证明缺失时标注待办并转 S6-7 督办，不阻塞归档", deps("st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录归档登记全程留痕", deps("st6"))),
                "回复 1 进入放款条件检查，回复 2 补传担保登记证明，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("归档清单",
                                "借款合同电子件、抵押合同电子件、纸质扫描件、签署证书",
                                "电子档案与纸质档案双套管理，可检索预览"),
                        block("台账登记",
                                "合同编号 HT20261102-018｜借款人 华辰机械｜金额 600 万｜期限 12 个月｜签署日期 2026-11-02",
                                "担保登记情况已登记（厂房最高额抵押）"),
                        block("完整性提示",
                                "抵押登记证明待办，已生成督办（转 S6-7 限时抵押落实督办），不阻塞归档",
                                "归档登记不替代放款操作，放款由 S6 族执行"),
                        block("操作区",
                                "档案位置、台账记录、担保登记待办已回写信贷管理系统",
                                "回复 1 进入放款条件检查（衔接 S6-1），回复 2 补传担保登记证明，回复 3 结束"))));

        // ============ S5-6 贷款档案全流程归档 ============
        REGISTRY.put("S5-6", new SceneScript(
                "S5-6", "loan-file-archive-agent", "贷款档案全流程归档",
                List.of("贷款档案", "立卷归档", "档案卷", "立卷", "缺件"),
                "您好，我是贷款档案归档助手。给我客户名称或任务编号，我把申请、尽调、审批、合同、放款各环节资料自动归集立卷，缺件会列出来并督办补齐。",
                "本次将为您完成：1.资料归集 2.立卷解析 3.缺件校验 4.立卷登记 5.缺件督办",
                List.of(
                        realDocParse("st1", "① 调用 doc-parse-agent 归集各环节电子资料与纸质扫描件", deps(), "materials-S5-6.pdf"),
                        realDocParse("st2", "② 调用 doc-parse-agent 按档案目录清单解析立卷结构", deps("st1"), "archive-catalog-S5-6.pdf"),
                        realQuality("st3", "③ 调用 data-quality-agent 对照贷款档案目录逐项校验缺件", deps("st1", "st2"), "dwd_credit.loan_file"),
                        realCallExec("st4", "④ 调用 call-exec-agent 在档案系统立卷登记并生成档案卷号（档案系统接口未上线）", deps("st3"), "DEF_NOTIFY_001", map("templateCode", "ARCHIVE_DONE", "receiver", "ARCHIVE-SYS")),
                        realNotify("st5", "⑤ 调用 notify-agent 对缺件生成督办提醒、限期补齐", deps("st3"), "TASK_URGE", "档案管理员", "13800006666", map("taskContent", "贷款档案缺件补齐：担保登记表", "deadline", "2026-11-07")),
                        human("st6", "⚑ 人工确认节点：缺件限期补齐安排确认；超期未补齐预警客户经理主管并限制后续新业务办理", deps("st4", "st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录立卷全程留痕，缺件督办与超期限制可审计", deps("st6"))),
                "回复 1 补传缺件材料，回复 2 查看档案卷目录，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("立卷清单",
                                "申请表、尽调报告、审批意见、借款合同、借据、抵押登记证明、放款凭证，共 12 项已归档",
                                s(sm.get("st2"), "立卷结构：详见滚动日志")),
                        block("缺件清单",
                                "用信后 15 日经营用途证明待补，限期 15 个工作日",
                                "缺件补齐后自动销号"),
                        block("立卷结果",
                                "档案卷号 DA20261102-018，可检索调阅",
                                "档案供贷后管理（S7 族）与监管检查调阅"),
                        block("操作区",
                                "档案卷号、缺件督办记录回写档案管理系统与信贷管理系统",
                                "回复 1 补传缺件材料，回复 2 查看档案卷目录，回复 3 结束"))));

        // ============ S6-1 放款条件检查 ============
        REGISTRY.put("S6-1", new SceneScript(
                "S6-1", "disbursement-condition-check-agent", "放款条件检查",
                List.of("放款条件", "能不能放款", "放款前", "准放"),
                "您好，我是放款条件检查助手。请告诉我客户名称和用信申请编号，我会逐项核对审批结论、合同生效、押品落实、名单复查与账户合规，输出准放或不准放结论。",
                "本次将为您完成：1.身份归并 2.状态调取 3.三方核对 4.名单与合规复查 5.终审结论",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并客户身份，锁定客户唯一号", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", "京南机电制造有限公司"), "source", "core", "entityType", "COMPANY"),
                                null),
                        real("st2", "② 调用 data-query-agent 调取审批结论、借款合同、押品登记状态（审批结论存储未联调，以信贷台账白名单表近似）", "data-query", deps("st1"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        real("st3", "③ 调用 data-validate-agent 核对用信申请、审批结论、合同三方一致性（金额 500 万/期限 1 年/利率年化 3.45%）", "data-validate", deps("st1", "st2"),
                                ":8129/api/bai/validate/validate",
                                (sl, sm, sd) -> map("templateId", "T_RECON",
                                        "data", map("orgId", "ORG-DEMO", "applyDate", "2026-10-28",
                                                "loanAmount", 5000000, "repayAmount", 5000000,
                                                "approvedAmount", 5000000, "contractAmount", 5000000, "rate", "3.45%")),
                                (sl, d) -> "三方一致性核对：" + d.path("result").asText("-")),
                        realScreening("st4", "④ 调用 screening-agent 放款前名单复查（制裁名单/黑名单/涉诉）", deps("st1"), "RE_SCREEN", "恒通商贸有限公司"),
                        realCompliance("st5", "⑤ 调用 compliance-check-agent 校验合同生效条件、受托支付触发条件、放款账户合规", deps("st2", "st3"), "CONTRACT", map("check", "生效条件,受托支付触发,放款账户")),
                        human("st6", "⚑ 人工确认节点：存在不符项（他项权证影像未入系统）或条件存疑时转放款中心主管终审，不得自动放行", deps("st4", "st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录全部核对项与结论", deps("st6"))),
                "回复 1 生成还款计划，回复 2 直接放款记账，回复 3 挂起补正",
                (sl, sm, sd) -> List.of(
                        withExtraLines(statusBlock("核对总览",
                                "ok", "审批结论已出", "",
                                "ok", "合同已生效", "",
                                "ok", "押品已登记", "抵押登记完成",
                                "ok", "名单复查通过", "",
                                "ok", "放款账户合规", ""),
                                s(sm.get("st3"), "三方核对：详见滚动日志")),
                        statusBlock("不符项清单",
                                "fail", "他项权证影像未入系统", "请直接发送资料图片或文件补正，或转限时抵押落实督办（S6-7）"),
                        block("结论",
                                "补正后可放（其余五项全部通过，补齐他项权证影像即达准放条件）"),
                        block("操作区",
                                "核对结论回写信贷管理系统放款审核节点；本场景不做实际放款记账",
                                "回复 1 生成还款计划（衔接 S6-2），回复 2 直接放款记账（衔接 S6-3），回复 3 挂起补正"))));

        // ============ S6-2 还款计划生成 ============
        REGISTRY.put("S6-2", new SceneScript(
                "S6-2", "loan-repayment-plan-agent", "还款计划生成",
                List.of("还款计划", "月供", "计划表"),
                "您好，我是还款计划生成助手。请告诉我客户名称和用信申请编号，如有还款方式偏好（等额本息/等额本金/按月付息到期还本）可一并说明，我会出逐期还款计划表。",
                "本次将为您完成：1.还款规则读取 2.利率确认 3.逐期试算 4.计划确认 5.借据要素落库",
                List.of(
                        real("st1", "① 调用 knowledge-process-agent 读取产品还款方式与还款日规则（流贷产品：等额本息/等额本金/先息后本，每月 20 日扣款）", "knowledge-process", deps(),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "DOC-S62-001", "source", "policy",
                                        "text", "流贷产品还款规则：支持等额本息、等额本金、按期付息到期还本、利随本清；还款日为每月 20 日，节假日顺延；起息日默认放款当日；首期不足整月按日计息。"),
                                null),
                        real("st2", "② 调用 pricing-calc-agent 确认利率取值（按借据要素复核定价）", "pricing-calc", deps("st1"),
                                ":8122/api/bai/pricing/quote",
                                (sl, sm, sd) -> map("productId", strOr(sl.get("productId"), "LN-FLOW-001"),
                                        "amount", numOr(sl.get("amount"), 5000000), "term", numOr(sl.get("term"), 12),
                                        "rateType", "FIXED", "collateral", "抵押", "customerRating", "A"),
                                (sl, d) -> "定价复核：执行利率 " + d.path("finalRate").asText("-") + "（与借据要素一致）"),
                        real("st3", "③ 调用 repayment-plan-agent 按确认要素逐期试算", "repayment-plan", deps("st1", "st2"),
                                ":8127/api/bai/repayment-plan/plan",
                                (sl, sm, sd) -> map("contractId", strOr(sl.get("applyNo"), "YS20261028013"),
                                        "principal", numOr(sl.get("amount"), 5000000), "rate", numOr(sl.get("rate"), 0.0345),
                                        "terms", numOr(sl.get("term"), 12), "method", strOr(sl.get("method"), "EQUAL_INSTALLMENT"),
                                        "startDate", strOr(sl.get("startDate"), "2026-11-02")),
                                (sl, d) -> {
                                    JsonNode mp = d.path("monthlyPayment");
                                    boolean noMonthly = mp.isMissingNode() || mp.isNull() || mp.asText("").isBlank();
                                    String first = noMonthly && d.path("schedule").isArray() && d.path("schedule").size() > 0
                                            ? d.path("schedule").get(0).path("payment").asText("-") : mp.asText("-");
                                    return "试算完成：首期还款 " + first + " 元，总利息 " + d.path("totalInterest").asText("-") + " 元";
                                }),
                        real("st4", "④ 调用 pricing-calc-agent 校验首期非整月利息与结息日利息（以定价试算口径复核）", "pricing-calc", deps("st3"),
                                ":8122/api/bai/pricing/quote",
                                (sl, sm, sd) -> map("productId", strOr(sl.get("productId"), "LN-FLOW-001"),
                                        "amount", numOr(sl.get("amount"), 5000000), "term", 1,
                                        "rateType", "FIXED", "collateral", "抵押", "customerRating", "A"),
                                null),
                        realStructure("st5", "⑤ 调用 structure-gen-agent 生成还款计划表可视化", deps("st3", "st4"), "SCHEMA_LOAN_TABLE_V1", "TEXT", "还款计划表", "TABLE"),
                        human("st6", "⚑ 人工确认节点：还款计划交客户经理与客户确认；要求变更还款方式须回审批环节重审，本场景提示转人工", deps("st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录计划版本与确认记录", deps("st6"))),
                "回复 1 确认无误并接着放款记账，回复 2 调整还款方式，回复 3 暂不确认",
                (sl, sm, sd) -> {
                    JsonNode plan = sd == null ? null : sd.get("st3");
                    JsonNode sched = planSchedule(sd);
                    JsonNode first = sched != null && sched.isArray() && !sched.isEmpty() ? sched.get(0) : null;
                    String firstPay = first != null ? fmtNum(first.path("payment").asDouble())
                            : fmtNum(plan == null ? 0 : plan.path("monthlyPayment").asDouble());
                    String totalInt = plan != null ? fmtNum(plan.path("totalInterest").asDouble()) : "-";
                    String lastDate = sched != null && sched.isArray() && !sched.isEmpty()
                            ? sched.get(sched.size() - 1).path("date").asText("-") : "-";
                    List<String> b1 = new java.util.ArrayList<>();
                    b1.add("贷款金额：" + wan(numOr(sl.get("amount"), 5000000)) + " 万元");
                    b1.add("贷款期限：" + s(numOr(sl.get("term"), 12), "12") + " 个月");
                    b1.add("执行利率：年化 " + String.format("%.2f", Double.parseDouble(String.valueOf(numOr(sl.get("rate"), 0.0345))) * 100) + "%");
                    b1.add("还款方式：" + methodCn(sl.get("method")));
                    b1.add("首期还款：" + firstPay + " 元");
                    b1.add("总利息：" + totalInt + " 元");
                    b1.add("计划结清：" + lastDate);
                    List<String> b2 = new java.util.ArrayList<>();
                    b2.add("扣款日为每月 20 日（节假日顺延），各期本金递增、利息递减");
                    b2.addAll(scheduleTable(sd));
                    return List.of(
                            block("计划总览", b1.toArray(new String[0])),
                            block("逐期还款表", b2.toArray(new String[0])),
                            block("敏感性提示",
                                    "提前还款按实际占用天数计息，无违约金",
                                    "利率为固定利率，存续期内不随 LPR 变动；还款方式变更须回审批重审"),
                            block("操作区",
                                    "确认后的还款计划回写信贷管理系统借据草稿，供放款记账（S6-3）调用",
                                    "回复 1 确认无误并接着放款记账，回复 2 调整还款方式（须回审批重审，提示转人工），回复 3 暂不确认"));
                }));

        // ============ S6-3 放款记账与凭证 ============
        REGISTRY.put("S6-3", new SceneScript(
                "S6-3", "disbursement-accounting-agent", "放款记账与凭证",
                List.of("放款记账", "执行放款", "放款凭证"),
                "您好，我是放款记账助手。放款条件与还款计划已确认的客户，请告诉我用信申请编号，我将完成授权校验并调用核心系统放款记账。记账动作一经确认不可撤回，请仔细核对要素。",
                "本次将为您完成：1.授权校验 2.要素终核 3.核心记账 4.状态跟踪 5.凭证回写",
                List.of(
                        real("st1", "① 调用 auth-agent 校验放款交易授权（本笔 500 万元属常规授权；授权矩阵待扩展，身份核验先行）", "auth", deps(),
                                ":8109/api/bai/auth/login",
                                (sl, sm, sd) -> map("identity", map("userId", "PM-021", "name", "张磊"),
                                        "factor", map("type", "PASSWORD", "credential", "******"),
                                        "context", map("deviceId", "Teller-041", "deviceFp", "FP-88213", "ip", "10.20.1.41", "userAgent", "CreditWorkbench/2.3", "geo", "CN-1100"),
                                        "scene", "BACKOFFICE"),
                                (sl, d) -> "放款岗身份核验通过（authId=" + d.path("authId").asText("-")
                                        + "，风险等级 " + d.path("riskLevel").asText("-") + "）；大额双签授权矩阵待扩展"),
                        human("st2", "⚑ 人工确认节点：放款执行前最终要素核对确认（借款人京南机电制造有限公司、金额 500 万、年利率 3.45%、期限 12 个月、放款账户合同约定账户）；大额（单笔 2000 万以上）强制双人授权", deps("st1")),
                        realCallExec("st3", "② 调用 call-exec-agent 调用核心系统贷款开户与放款记账（核心账务系统未联调，联调待办：确定性仿真＋人工确认运行；生成借据号 JJ202611020041、凭证号 31012026110200087）", deps("st2"), "DEF_DISBURSE_001", map("loanNo", "LN-S6-3-001", "amount", 2000000, "accountNo", "6222020200112345678")),
                        realTodo("st4", "③ 调用 todo-agent 跟踪记账状态与凭证号", deps("st3"), "DISBURSE", "LN-S6-3-001", "S6-3 st4 待办跟踪", "ACCT001"),
                        realEvent("st5", "④ 条件分支：记账失败时调用 event-subscribe-agent 执行冲正与重试", deps("st3"), "call.failed", "S6-3-st5", map("callId", "CE-S6-3-st5")),
                        realAudit("st6", "⑤ 调用 audit-log-agent 记录记账交易流水与凭证号", deps("st5"))),
                "回复 1 生成放款通知发给客户，回复 2 查看额度占用扣减，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("放款要素核对表",
                                "借款人 京南机电制造有限公司｜金额 500 万元｜年利率 3.45%｜期限 12 个月｜放款账户 合同约定账户",
                                "要素与已确认还款计划一致（YS20261028013）"),
                        block("记账结果",
                                "借据号 JJ202611020041｜会计凭证号 31012026110200087｜记账时间 2026-11-02 10:24｜资金已到账",
                                s(sm.get("st3"), "核心记账：详见滚动日志")),
                        block("异常提示",
                                "无冲正记录；记账失败一律走冲正补偿，禁止重复放款"),
                        block("操作区",
                                "借据号、凭证号、放款时间回写信贷管理系统；额度扣减随记账联动触发（S6-4）",
                                "回复 1 生成放款通知发给客户（衔接 S6-5），回复 2 查看额度占用扣减（衔接 S6-4），回复 3 结束"))));

        // ============ S6-4 额度占用联动 ============
        REGISTRY.put("S6-4", new SceneScript(
                "S6-4", "credit-limit-linkage-agent", "额度占用联动",
                List.of("额度占用", "额度扣减", "额度释放", "额度使用情况", "额度台账"),
                "您好，我是额度联动助手。可以查询客户额度占用情况，也可以处理放款扣减与回款释放。请告诉我客户名称或授信合同编号。",
                "本次将为您完成：1.额度测算 2.占用交易 3.台账维护 4.超限预警 5.双人复核",
                List.of(
                        real("st1", "① 调用 limit-calc-agent 测算本次可占用额度与占用后余额（放款 500 万）", "limit-calc", deps(),
                                ":8123/api/bai/limit/calc",
                                (sl, sm, sd) -> map("customerId", "CUST-JNJD-001", "limitType", "SINGLE",
                                        "incomeBased", 10000000, "collateralBased", 0, "requestedAmount", 5000000,
                                        "ratingFactor", 1.0, "availableLimit", 4000000),
                                (sl, d) -> "占用后余额 " + d.path("remainAmount").asText("-") + " 元，控制状态 "
                                        + d.path("controlStatus").asText("-")),
                        realCallExec("st2", "② 调用 call-exec-agent 调用核心额度占用交易（扣减）（核心账务系统未联调，联调待办；占用金额与放款金额严格一致）", deps("st1"), "DEF_ACCT_DEDUCT_001", map("loanNo", "LN-S6-4-001", "amount", 2000000)),
                        realEvent("st3", "③ 条件分支：扣减失败调用 event-subscribe-agent 回滚并补偿（转人工对账，禁止重复提交）", deps("st2"), "call.failed", "S6-4-st3", map("callId", "CE-S6-4-st3")),
                        realRiskSignal("st4", "④ 条件分支：占用后余额超授信额度调用 risk-signal-agent 生成超限预警并阻断新增用信", deps("st1", "st2"), "C-S6-4", List.of("FLOW"), List.of("额度占用后余额超授信额度")),
                        realTask("st5", "⑤ 调用 task-schedule-agent 处理批量回款的额度释放批次", deps("st2")),
                        human("st6", "⚑ 人工确认节点：额度变动台账双人复核确认（额度表变更双人复核）", deps("st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录额度变动台账", deps("st6"))),
                "回复 1 查看全部在途借据，回复 2 生成额度使用月报，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        withExtraLines(barsBlock("额度台账", "万",
                                "授信总额", 1000, "已用", 600, "可用", 400),
                                "本次放款 500 万＋存量 100 万",
                                s(sm.get("st1"), "可用额度测算：详见滚动日志")),
                        block("变动明细",
                                "本次放款 500 万元已于 2026-11-02 扣减（借据号 JJ202611020041）；存量借据 100 万元在途",
                                "回款后额度自动释放，与 S8 还款场景联动"),
                        block("预警提示",
                                "占用后余额未超授信额度，无超限预警"),
                        block("操作区",
                                "额度变动回写信贷管理系统额度台账与核心系统额度表，供用信监控（S6-6）使用",
                                "回复 1 查看全部在途借据，回复 2 生成额度使用月报，回复 3 结束"))));

        // ============ S6-5 放款通知与回执 ============
        REGISTRY.put("S6-5", new SceneScript(
                "S6-5", "disbursement-notice-agent", "放款通知与回执",
                List.of("放款通知", "通知客户", "贷款到账"),
                "您好，我是放款通知助手。放款完成后，我可以按客户偏好渠道发送放款通知并跟踪回执。请告诉我客户名称或借据号。",
                "本次将为您完成：1.文案生成 2.人工确认 3.渠道发送 4.回执跟踪 5.咨询口径",
                List.of(
                        realTextGen("st1", "① 调用 text-gen-agent 生成放款通知文案（金额、利率、首期还款日、还款账户、客服电话）", deps()),
                        human("st2", "⚑ 人工确认节点：通知文案交客户经理确认；内容不得含利率以外的承诺性表述", deps("st1")),
                        realNotify("st3", "② 调用 notify-agent 按渠道发送（短信＋App 推送）", deps("st2"), "LOAN_DISBURSE_NOTIFY", "刘芳", "13800007777", map("amount", "300 万元", "account", "6222****8899")),
                        realTodo("st4", "③ 调用 todo-agent 跟踪发送回执", deps("st3"), "DISBURSE", "NOTICE-S6-5", "S6-5 st4 待办跟踪", "ACCT001"),
                        real("st5", "④ 调用 rag-answer-agent 生成客户常见咨询应答口径（到期怎么还、提前还款怎么算）", "rag-answer", deps("st1"),
                                ":8119/api/bai/rag/answer",
                                (sl, sm, sd) -> map("question", "贷款到期怎么还款？提前还款利息怎么算？",
                                        "context", List.of(map("entryId", "KB-REPAY-001", "title", "还款须知",
                                                "content", "贷款到期通过约定还款账户自动扣款；提前还款按实际占用天数计息，无违约金；还款日为每月 20 日，节假日顺延。", "score", 0.95)),
                                        "style", "plain"),
                                (sl, d) -> "应答口径已生成（answerId=" + d.path("answerId").asText("-")
                                        + "，忠实度 " + d.path("quality").path("faithfulness").asDouble(0) + "）"),
                        realSemantic("st6", "⑤ 调用 semantic-match-agent 客户回执多轮对话理解", deps("st5"), "钱已经转过去了，麻烦查下到账没有", "客户回执：款项已汇出，请确认入账", "LOAN_RECEIPT"),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录通知发送全程", deps("st6"))),
                "回复 1 补发失败通知，回复 2 生成客户还款提醒日历，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("通知文案预览",
                                "京南机电制造有限公司，您的贷款 500 万元已发放到账，年利率 3.45%，首期还款日 2026 年 12 月 20 日，还款账户为合同约定账户，如有疑问请联系客户经理张磊",
                                s(sm.get("st1"), "文案生成：详见滚动日志")),
                        withExtraLines(statusBlock("发送结果",
                                "ok", "短信发送成功", "",
                                "ok", "App 推送成功", "企业财务负责人"),
                                "发送失败（空号/停机）返回失败明细并支持补发"),
                        block("客户咨询应答口径",
                                "到期怎么还：约定还款账户自动扣款；提前还款怎么算：按实际占用天数计息，无违约金",
                                s(sm.get("st5"), "应答依据：详见滚动日志")),
                        block("操作区",
                                "通知发送记录回写客户服务台账，供消保检查调阅",
                                "回复 1 补发失败通知，回复 2 生成客户还款提醒日历，回复 3 结束"))));

        // ============ S6-6 到期用信排查与额度使用监控 ============
        REGISTRY.put("S6-6", new SceneScript(
                "S6-6", "credit-usage-monitor-agent", "到期用信排查与额度使用监控",
                List.of("额度台账检查", "台账检查", "台账核对", "额度台账排查", "到期未用信", "用信排查", "额度使用率", "用信监控"),
                "您好，我是用信监控助手。可以排查到期未用信客户、统计额度使用率、生成到期提醒。请告诉我排查范围，例如我们支行未来 30 天到期的用信合同。",
                "本次将为您完成：1.台账调取 2.使用率统计 3.异常识别 4.图表生成 5.提醒任务",
                List.of(
                        real("st1", "① 调用 data-query-agent 调取在途授信合同与额度台账（授信合同台账未联调，以信贷台账白名单表近似）", "data-query", deps(),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_core", "table", "loan_balance_daily",
                                        "fields", List.of("branch_code", "loan_balance")),
                                null),
                        real("st2", "② 调用 metric-calc-agent 统计额度使用率与到期分布（以监管指标口径近似额度使用统计）", "metric-calc", deps("st1"),
                                ":8128/api/bai/metric/calc",
                                (sl, sm, sd) -> map("metricId", "LOAN_BALANCE", "params", map("orgId", "ORG-DEMO", "date", "2026-11-02")),
                                (sl, d) -> "指标口径贷款余额 " + d.path("value").asText("-") + " " + d.path("unit").asText("-")
                                        + "（监管指标近似额度使用统计）"),
                        realRiskSignal("st3", "③ 调用 risk-signal-agent 识别到期未用信与额度异常客户", deps("st1", "st2"), "C-S6-6", List.of("INDUSTRY", "PUBLIC"), List.of("额度到期未使用", "用信进度异常")),
                        realStructure("st4", "④ 调用 structure-gen-agent 生成额度使用监控图表", deps("st2"), "SCHEMA_LOAN_TABLE_V1", "TEXT", "额度使用监控", "TABLE"),
                        realNotify("st5", "⑤ 调用 notify-agent 生成到期提醒任务", deps("st3"), "DUE_REMIND", "刘芳", "13800007777", map("dueDate", "2027-05-20")),
                        human("st6", "⚑ 人工确认节点：排查名单与预警处置人工确认（预警分级为建议，处置人工定）", deps("st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录排查结果与预警", deps("st6"))),
                "回复 1 生成到期提醒任务，回复 2 导出名单，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        withExtraLines(barsBlock("排查总览", "亿",
                                "支行授信总额", 3.2, "已用", 2.1),
                                "使用率 65.6%",
                                "未来 30 天到期合同 2 笔",
                                s(sm.get("st2"), "指标统计：详见滚动日志")),
                        withExtraLines(barsBlock("到期未用信名单", "万",
                                "宏达建材", 200, "恒通物流", 150),
                                "宏达建材 200 万元，2026-11-28 到期，剩余 26 天未用信",
                                "恒通物流 150 万元，2026-11-15 到期，剩余 13 天未用信",
                                "到期未用信按制度排查并提醒，处置由客户经理人工跟进"),
                        block("预警提示",
                                "额度使用率异常客户：无（65.6% 处于合理区间）"),
                        block("操作区",
                                "排查结果与预警回写信贷管理系统监控台账；预警客户可转贷后场景（S7）跟进",
                                "回复 1 生成到期提醒任务，回复 2 导出名单，回复 3 结束"))));

        // ============ S6-7 限时抵押落实督办 ============
        REGISTRY.put("S6-7", new SceneScript(
                "S6-7", "collateral-perfect-urge-agent", "限时抵押落实督办",
                List.of("抵押督办", "抵押落实", "限时抵押", "落实中"),
                "您好，我是抵押落实督办助手。可以跟踪押品登记落实进度、生成督办工单和到期提醒。请告诉我机构范围或客户名称。",
                "本次将为您完成：1.台账调取 2.覆盖率复核 3.工单流转 4.到期提醒 5.逾期预警",
                List.of(
                        real("st1", "① 调用 data-query-agent 调取押品登记状态与落实时限（押品系统未联调，以信贷台账白名单表近似）", "data-query", deps(),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        real("st2", "② 调用 valuation-agent 复核押品覆盖率（宏达建材厂房，市场法）", "valuation", deps("st1"),
                                ":8124/api/bai/valuation/value",
                                (sl, sm, sd) -> map("assetType", "REAL_ESTATE", "method", "MARKET", "assetRef", "宏达建材厂房",
                                        "params", map("area", 2000, "unitPrice", 5000, "comparables", 3), "valueDate", "2026-11-02"),
                                (sl, d) -> "押品估值 " + d.path("value").asText("-") + " 元，" + d.path("conclusion").asText("-")),
                        realTask("st3", "③ 调用 task-schedule-agent 生成/流转督办工单", deps("st1")),
                        realNotify("st4", "④ 调用 notify-agent 生成到期督办提醒", deps("st3"), "TASK_URGE", "客户经理", "13800008888", map("taskContent", "贷款到期前 30 天续贷跟进", "deadline", "2026-11-20")),
                        realRiskSignal("st5", "⑤ 条件分支：逾期未落实调用 risk-signal-agent 生成升级预警并通知主管", deps("st3"), "C-S6-7", List.of("FLOW", "JUDICIAL"), List.of("担保条件未落实")),
                        human("st6", "⚑ 人工确认节点：督办处置确认；逾期未落实的处置方案（暂停用信、提前收回）由信贷管理部门人工决策", deps("st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录督办全程", deps("st6"))),
                "回复 1 发送督办提醒，回复 2 上传他项权证更新为已落实，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        withExtraLines(barsBlock("督办总览", "笔",
                                "在途押品", 8, "即将到期", 2, "已逾期", 0),
                                s(sm.get("st2"), "覆盖率复核：详见滚动日志")),
                        withExtraLines(barsBlock("督办清单", "天",
                                "宏达建材厂房剩余", 8, "恒通物流车辆剩余", 6),
                                "宏达建材厂房抵押，约定 11 月 10 日前完成登记，剩余 8 天",
                                "恒通物流车辆抵押，约定 11 月 8 日前，剩余 6 天",
                                "登记完成请直接发送他项权证图片或文件"),
                        withExtraLines(barsBlock("覆盖率复核结果", "万",
                                "宏达建材厂房估值", 1000, "恒通物流车辆估值", 90),
                                "宏达建材厂房估值 1000 万元，抵押率 50%，覆盖率达标",
                                "恒通物流车辆估值 90 万元，覆盖率达标"),
                        block("操作区",
                                "督办工单与提醒记录回写押品管理台账；与押品重估场景（S7-4）衔接",
                                "回复 1 发送督办提醒，回复 2 上传他项权证更新为已落实，回复 3 结束"))));

        // ============ S6-8 受托支付审核与资金用途管控 ============
        REGISTRY.put("S6-8", new SceneScript(
                "S6-8", "entrusted-payment-agent", "受托支付审核与资金用途管控",
                List.of("受托支付", "支付对象", "资金用途", "直付"),
                "您好，我是受托支付审核助手。按监管要求，单笔超过 500 万元的支付须受托支付直付交易对手。请告诉我客户名称和支付金额，并请直接发送购销合同、发票等交易背景资料图片或文件。",
                "本次将为您完成：1.门槛判断 2.背景识别 3.要素抽取 4.用途核对 5.直付执行",
                List.of(
                        realCompliance("st1", "① 调用 compliance-check-agent 判断是否触发受托支付门槛", deps(), "CONTRACT", map("amount", 4800000, "threshold", 5000000, "contractAgreement", "受托支付")),
                        realOcr("st2", "② 调用 ocr-agent 识别购销合同与发票", deps("st1"), "PURCHASE_CONTRACT", "IMG-S68-01"),
                        realNer("st3", "③ 调用 ner-agent 抽取交易对手、金额、货物名称", deps("st2"), "购销合同：买方恒信钢材有限公司，卖方恒达建材商行，标的为热轧钢板，合同金额 480 万元，交货日 2026-11-20", "TRADE"),
                        real("st4", "④ 调用 data-query-agent 核对资金用途与经营流水匹配度（经营流水存储未联调，以信贷台账白名单表近似）", "data-query", deps("st3"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        realFraud("st5", "⑤ 条件分支：调用 fraud-agent 识别虚假交易对手与关联交易", deps("st3", "st4"), "TRANS_MONITOR", "ORG", "C-S6-8", "TXN-S6-8-001"),
                        human("st6", "⚑ 人工确认节点：支付要素终审确认；审核疑点一律转人工，禁止自动放款到借款人账户后挪用", deps("st5")),
                        realCallExec("st7", "⑥ 调用 call-exec-agent 执行受托支付划款（直付恒信钢材有限公司 480 万元；核心账务系统未联调，联调待办）", deps("st6"), "DEF_DISBURSE_001", map("loanNo", "LN-S6-8-001", "amount", 4800000, "accountNo", "6222020200112345678")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录支付审核全程", deps("st7"))),
                "回复 1 确认直付，回复 2 补充资料，回复 3 转人工",
                (sl, sm, sd) -> List.of(
                        block("门槛判断结果",
                                "本笔 480 万元未达 500 万元强制受托门槛，但借款合同约定受托支付，按受托支付执行",
                                s(sm.get("st1"), "门槛判断：详见滚动日志")),
                        block("交易背景识别",
                                "交易对手 恒信钢材有限公司｜金额 480 万元｜货物 热轧钢板｜合同与发票要素一致",
                                s(sm.get("st3"), "要素抽取：详见滚动日志")),
                        block("用途核对结论",
                                "与经营流水匹配，未发现虚假交易与疑似关联交易，审核通过",
                                "直付交易对手账户，不得经过借款人自有账户过账"),
                        block("操作区",
                                "支付审核记录与直付凭证回写信贷管理系统用途管控台账",
                                "回复 1 确认直付恒信钢材有限公司 480 万元，回复 2 补充资料，回复 3 转人工"))));

        // ============ S6-9 贸易融资与应收账款池融资 ============
        REGISTRY.put("S6-9", new SceneScript(
                "S6-9", "trade-finance-agent", "贸易融资与应收账款池融资",
                List.of("应收账款融资", "池融资", "仓单融资", "贸易融资"),
                "您好，我是贸易融资助手。可以办理应收账款融资、订单融资、仓单融资的背景审核与额度测算。请告诉我客户名称和融资品种，并请直接发送发票、购销合同或仓单等资料图片或文件。",
                "本次将为您完成：1.单据识别 2.要素抽取 3.行业与关联核查 4.额度测算 5.确权确认",
                List.of(
                        realOcr("st1", "① 调用 ocr-agent 识别发票、合同、仓单", deps(), "MIXED_DOC", "IMG-S69-01"),
                        realNer("st2", "② 调用 ner-agent 抽取应收账款要素", deps("st1"), "应收账款：付款方恒通集团，金额 600 万元，账期 90 天，发票号 INV202610290001", "FACTORING"),
                        real("st3", "③ 调用 knowledge-process-agent 查询行业账期惯例（制造业应收账款账期基准）", "knowledge-process", deps("st2"),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "DOC-S69-001", "source", "policy",
                                        "text", "制造业应收账款账期惯例：一般 60—120 天，钢铁供应链多为 90 天；账期超 150 天需补充说明；发票真伪以税务平台查验为准。"),
                                null),
                        real("st4", "④ 调用 trend-analyze-agent 行业与市场景气分析（制造业设备制造景气指数）", "trend-analyze", deps("st3"),
                                ":8132/api/bai/trend/analyze",
                                (sl, sm, sd) -> map("seriesId", "MFG_EQUIPMENT_IDX",
                                        "points", List.of(
                                                map("date", "2026-06", "value", 100.2), map("date", "2026-07", "value", 100.8),
                                                map("date", "2026-08", "value", 101.5), map("date", "2026-09", "value", 101.9),
                                                map("date", "2026-10", "value", 102.6), map("date", "2026-11", "value", 103.1)),
                                        "window", "M6", "methods", List.of("trend", "mom")),
                                (sl, d) -> "行业景气 " + d.path("trend").path("direction").asText("-") + "（analyzeId="
                                        + d.path("analyzeId").asText("-") + "）"),
                        real("st5", "⑤ 调用 graph-build-agent 核查付款方（恒通集团）与借款方（京南机电）关联关系", "graph-build", deps("st2"),
                                ":8134/api/bai/graph/build",
                                (sl, sm, sd) -> map("mode", "FULL", "scope", map("goldenId", "G-JNJD-001")),
                                (sl, d) -> "关联核查图构建 " + d.path("status").asText("-") + "（jobId=" + d.path("jobId").asText("-") + "）"),
                        real("st6", "⑥ 调用 limit-calc-agent 按折扣率测算池融资额度（应收账款 600 万元×0.8）", "limit-calc", deps("st4", "st5"),
                                ":8123/api/bai/limit/calc",
                                (sl, sm, sd) -> map("customerId", "CUST-JNJD-001", "limitType", "SINGLE",
                                        "incomeBased", 4800000, "collateralBased", 0, "requestedAmount", 4800000,
                                        "ratingFactor", 1.0, "availableLimit", 4800000),
                                (sl, d) -> "可融额度 " + d.path("limitAmount").asText("-") + " 元，控制状态 "
                                        + d.path("controlStatus").asText("-")),
                        human("st7", "⚑ 人工确认节点：额度测算结果与确权材料确认；核心企业确权材料缺失时转人工", deps("st6")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录审核全程", deps("st7"))),
                "回复 1 生成还款计划，回复 2 补充确权材料，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        withExtraLines(statusBlock("贸易背景识别",
                                "ok", "发票要素齐全", "",
                                "ok", "购销合同要素齐全", ""),
                                "账期 90 天（行业惯例 60—120 天内）",
                                s(sm.get("st3"), "行业惯例：详见滚动日志")),
                        block("关联核查结论",
                                "付款方恒通集团与借款方京南机电未发现股权关联，非关联交易",
                                s(sm.get("st5"), "关联核查：详见滚动日志")),
                        block("额度测算结果",
                                "应收账款 600 万元×折扣率 0.8＝可融额度 480 万元",
                                s(sm.get("st6"), "额度测算：详见滚动日志")),
                        block("操作区",
                                "贸易背景审核与额度测算回写信贷管理系统贸易融资台账；发票真伪以税务平台查验为准",
                                "回复 1 生成还款计划（衔接 S6-2），回复 2 补充确权材料，回复 3 结束"))));

        // ============ S6-10 票据承兑与贴现业务 ============
        REGISTRY.put("S6-10", new SceneScript(
                "S6-10", "bill-finance-agent", "票据承兑与贴现业务",
                List.of("票据贴现", "承兑汇票", "贴现利率", "贴现", "承兑"),
                "您好，我是票据业务助手。可以完成票据要素审核、贴现利率查询与定价测算。请告诉我客户名称，并请直接发送票据影像图片或文件。",
                "本次将为您完成：1.票据识别 2.要素核对 3.名单筛查 4.市场利率 5.定价测算",
                List.of(
                        realOcr("st1", "① 调用 ocr-agent 识别票据影像", deps(), "TICKET", "IMG-S610-01"),
                        realNer("st2", "② 调用 ner-agent 抽取票据要素", deps("st1"), "银行承兑汇票：票号 BILL20261102001，票面金额 100 万元，出票日 2026-11-02，剩余 60 天到期，承兑人恒通商贸有限公司", "DISCOUNT"),
                        real("st3", "③ 调用 data-validate-agent 核对票据要素与贸易合同一致性（票面 100 万/实付 99.65 万）", "data-validate", deps("st2"),
                                ":8129/api/bai/validate/validate",
                                (sl, sm, sd) -> map("templateId", "T_RECON",
                                        "data", map("orgId", "ORG-DEMO", "applyDate", "2026-11-02",
                                                "loanAmount", 1000000, "repayAmount", 996500,
                                                "billNo", "BILL20261102001", "faceAmount", 1000000, "contractAmount", 1000000)),
                                (sl, d) -> "票据要素核对：" + d.path("result").asText("-")),
                        realScreening("st4", "④ 调用 screening-agent 筛查承兑人与出票人名单", deps("st2"), "ADMISSION", "某国有银行"),
                        real("st5", "⑤ 调用 knowledge-process-agent 查询贴现市场利率（Shibor/转贴现利率快照）", "knowledge-process", deps("st4"),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "DOC-S610-001", "source", "policy",
                                        "text", "票据转贴现利率快照：3—6 个月期国股银票转贴现利率年化 2.1%；Shibor 3M 2.05%；贴现利率参考＝转贴现利率＋行内加点。"),
                                null),
                        real("st6", "⑥ 调用 pricing-calc-agent 测算贴现利率与贴现息（剩余 60 天，100 万元）", "pricing-calc", deps("st5"),
                                ":8122/api/bai/pricing/quote",
                                (sl, sm, sd) -> map("productId", "BILL-DISC-001", "amount", 1000000, "term", 2,
                                        "rateType", "FLOATING", "collateral", "信用", "customerRating", "A"),
                                (sl, d) -> "执行利率 " + d.path("finalRate").asText("-") + "（贴现息约 0.35 万元，实付 99.65 万元）"),
                        human("st7", "⚑ 人工确认节点：贴现定价与票据要素确认；票据真伪的法定审验以票据交易所系统为准", deps("st6")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录审核与定价全程", deps("st7"))),
                "回复 1 确认贴现并转放款记账，回复 2 补充资料，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("票据要素识别结果",
                                "票号 BILL20261102001｜金额 100 万元｜剩余 60 天到期｜承兑人某国有行（国股大行）",
                                s(sm.get("st2"), "要素抽取：详见滚动日志")),
                        block("名单筛查结论",
                                "承兑人与出票人名单筛查通过，无命中",
                                "承兑敞口纳入授信额度管理提示"),
                        block("定价测算",
                                "市场利率年化 2.1%｜执行利率 2.1%｜贴现息约 0.35 万元｜实付 99.65 万元",
                                s(sm.get("st6"), "定价测算：详见滚动日志")),
                        block("操作区",
                                "票据审核与定价结果回写票据业务台账；贴现放款转记账执行（S6-3）",
                                "回复 1 确认贴现并转放款记账，回复 2 补充资料，回复 3 结束"))));

        // ============ S6-11 保函与表外承诺业务 ============
        REGISTRY.put("S6-11", new SceneScript(
                "S6-11", "guarantee-letter-agent", "保函与表外承诺业务",
                List.of("履约保函", "投标保函", "保函", "表外承诺"),
                "您好，我是保函业务助手。可以办理投标、履约、预付款等保函的开立辅助与文本生成。请告诉我客户名称、保函品种和金额。",
                "本次将为您完成：1.金额与保证金测算 2.文本生成 3.合规校验 4.授权与签章 5.台账回写",
                List.of(
                        real("st1", "① 调用 limit-calc-agent 测算保函金额与保证金比例（200 万元履约保函，保证金 20%）", "limit-calc", deps(),
                                ":8123/api/bai/limit/calc",
                                (sl, sm, sd) -> map("customerId", "CUST-JNJD-001", "limitType", "SINGLE",
                                        "incomeBased", 0, "collateralBased", 0, "requestedAmount", 2000000,
                                        "ratingFactor", 1.0, "availableLimit", 8000000),
                                (sl, d) -> "表外额度占用后余额 " + d.path("remainAmount").asText("-") + " 元，控制状态 "
                                        + d.path("controlStatus").asText("-")),
                        realTextGen("st2", "② 调用 text-gen-agent 生成保函文本（受益人城建集团、有效期 12 个月、见索即付）", deps("st1")),
                        realCompliance("st3", "③ 调用 compliance-check-agent 表外业务合规校验", deps("st2"), "PRODUCT", map("biz", "保函", "type", "OFF_BALANCE")),
                        human("st4", "⚑ 人工确认节点：保函文本与条款法律审查；保证金 40 万元（200 万×20%）按审批结论执行", deps("st3")),
                        real("st5", "④ 调用 auth-agent 校验表外授信授权（授权矩阵待扩展，身份核验先行）", "auth", deps("st4"),
                                ":8109/api/bai/auth/login",
                                (sl, sm, sd) -> map("identity", map("userId", "PM-021", "name", "张磊"),
                                        "factor", map("type", "PASSWORD", "credential", "******"),
                                        "context", map("deviceId", "Teller-041", "deviceFp", "FP-88213", "ip", "10.20.1.41", "userAgent", "CreditWorkbench/2.3", "geo", "CN-1100"),
                                        "scene", "BACKOFFICE"),
                                (sl, d) -> "表外授权校验身份核验通过（authId=" + d.path("authId").asText("-") + "）"),
                        realTask("st6", "⑤ 调用 task-schedule-agent 流转保函开立流程", deps("st5")),
                        realCallExec("st7", "⑥ 调用 call-exec-agent 保函电子签章（esign-provider P12 未联调，联调待办）", deps("st6"), "DEF_ESIGN_001", map("contractNo", "GR-S6-11-001", "signerId", "IDN-S6-11")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录开立全程", deps("st7"))),
                "回复 1 确认签章开立，回复 2 修改文本，回复 3 到期前提醒注销",
                (sl, sm, sd) -> List.of(
                        block("保函要素",
                                "品种 履约保函｜金额 200 万元｜受益人 城建集团｜有效期 12 个月｜保证金比例 20%（40 万元）",
                                s(sm.get("st1"), "保证金测算：详见滚动日志")),
                        block("保函文本预览",
                                "见索即付保函文本已生成，含索赔条件、有效期、递减条款，预览详见滚动日志",
                                s(sm.get("st2"), "文本生成：详见滚动日志")),
                        block("授权校验结果",
                                "表外授信授权校验通过；保函不占用贷款额度，纳入表外授信管理",
                                "签章失败转人工用印流程"),
                        block("操作区",
                                "保函开立记录回写表外业务台账；到期前提醒注销",
                                "回复 1 确认签章开立，回复 2 修改文本，回复 3 到期前提醒注销"))));

        // ============ S6-12 委托贷款与同业专项贷 ============
        REGISTRY.put("S6-12", new SceneScript(
                "S6-12", "entrusted-loan-agent", "委托贷款与同业专项贷",
                List.of("委托贷款", "同业专项贷", "三方协议"),
                "您好，我是委托贷款业务助手。可以办理委托贷款与同业专项贷的合规审查、协议生成与放款执行。请告诉我委托人名称、借款人名称和金额。",
                "本次将为您完成：1.监管检索 2.来源审查 3.额度核对 4.协议生成 5.划款执行",
                List.of(
                        real("st1", "① 调用 knowledge-retrieve-agent 检索委托贷款监管规定（《商业银行委托贷款管理办法》）", "knowledge-retrieve", deps(),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "商业银行委托贷款管理办法 委托资金来源 银行不承担信用风险 不得垫付资金 受托发放监督使用", "topK", 3),
                                (sl, d) -> "监管规定命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        realCompliance("st2", "② 调用 compliance-check-agent 审查委托资金来源合规性", deps("st1"), "ADMISSION", map("fundSource", "京南集团自有资金")),
                        real("st3", "③ 调用 limit-calc-agent 核对贷款金额与委托资金额度（300 万元）", "limit-calc", deps("st2"),
                                ":8123/api/bai/limit/calc",
                                (sl, sm, sd) -> map("customerId", "CUST-JNJT-001", "limitType", "SINGLE",
                                        "incomeBased", 3000000, "collateralBased", 0, "requestedAmount", 3000000,
                                        "ratingFactor", 1.0, "availableLimit", 3000000),
                                (sl, d) -> "委托资金额度核对：" + d.path("controlStatus").asText("-") + "（余额 "
                                        + d.path("remainAmount").asText("-") + " 元）"),
                        realTextGen("st4", "④ 调用 text-gen-agent 生成委托贷款三方协议（委托人京南集团、借款人恒通物流、受托行；300 万元/1 年/4.2%）", deps("st3")),
                        human("st5", "⚑ 人工确认节点：协议与资金来源终审；资金来源合规存疑时转人工，不执行划款", deps("st4")),
                        realCallExec("st6", "⑤ 调用 call-exec-agent 执行委托资金划付与放款（核心账务系统未联调，联调待办；银行不垫付资金）", deps("st5"), "DEF_DISBURSE_001", map("loanNo", "LN-S6-12-001", "amount", 1200000, "accountNo", "6222020200112345678")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录全程", deps("st6"))),
                "回复 1 确认划款放款，回复 2 修改协议，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("合规审查结论",
                                "委托资金来源合规（京南集团自有资金），符合《商业银行委托贷款管理办法》",
                                s(sm.get("st1"), "监管依据：详见滚动日志")),
                        block("协议文本预览",
                                "三方协议已生成：委托人京南集团、借款人恒通物流、受托行；金额 300 万元、期限 1 年、利率 4.2%",
                                s(sm.get("st4"), "协议生成：详见滚动日志")),
                        block("划款要素",
                                "委托资金 300 万元与贷款金额一致｜受托行不垫付资金、不承担信用风险",
                                s(sm.get("st3"), "额度核对：详见滚动日志")),
                        block("操作区",
                                "委托贷款记录回写特殊类贷款台账",
                                "回复 1 确认划款放款，回复 2 修改协议，回复 3 结束"))));

        // ============ S6-13 跨境与国际信贷业务 ============
        REGISTRY.put("S6-13", new SceneScript(
                "S6-13", "cross-border-credit-agent", "跨境与国际信贷业务",
                List.of("跨境", "结售汇", "国际信贷", "外汇"),
                "您好，我是跨境信贷业务助手。可以办理跨境贸易融资放款辅助、汇率查询与外汇合规校验。请告诉我客户名称、币种和金额，并请直接发送报关单等跨境资料图片或文件。",
                "本次将为您完成：1.证照识别 2.外汇合规 3.反洗钱监测 4.汇率查询 5.跨境放款",
                List.of(
                        realOcr("st1", "① 调用 ocr-agent 识别境外证照", deps(), "AUTO", "IMG-S613-01"),
                        realCompliance("st2", "② 调用 compliance-check-agent 外汇管理合规校验", deps("st1"), "ADMISSION", map("check", "外汇登记", "registered", true)),
                        realOcr("st3", "③ 调用 ocr-agent 识别报关单与跨境合同", deps("st1"), "CUSTOMS_DECL", "IMG-S613-03"),
                        realAml("st4", "④ 调用 aml-agent 跨境反洗钱监测（外部反洗钱报送系统联调待办）", deps("st2", "st3"), "T30D", List.of(map("amount", 3000000, "counterparty", "南洋贸易公司", "direction", "IN", "purpose", "跨境货款", "cash", false, "time", "2026-11-04"), map("amount", 2800000, "counterparty", "南洋贸易公司", "direction", "OUT", "purpose", "跨境货款结算", "cash", false, "time", "2026-11-04"), map("amount", 2500000, "counterparty", "恒信钢材有限公司", "direction", "IN", "purpose", "预付货款", "cash", false, "time", "2026-11-04"))),
                        real("st5", "⑤ 调用 knowledge-process-agent 查询实时汇率牌价（美元兑人民币）", "knowledge-process", deps("st4"),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "FX-RATE-SNAPSHOT", "source", "policy",
                                        "text", "汇率牌价快照（2026-11-02）：美元兑人民币中间价 7.12，现汇买入价 7.115，现汇卖出价 7.128；跨境放款与结售汇以交易实时牌价为准，不使用估算汇率。"),
                                null),
                        human("st6", "⚑ 人工确认节点：跨境放款要素终审（美元 80 万元×7.12＝人民币 569.6 万元）；反洗钱预警命中时转人工，禁止自动放款", deps("st5")),
                        realCallExec("st7", "⑥ 调用 call-exec-agent 执行跨境放款与结售汇（国际业务系统未联调，联调待办）", deps("st6"), "DEF_DISBURSE_001", map("loanNo", "LN-S6-13-001", "amount", 5000000, "accountNo", "6222020200112345678")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录全程（外汇申报辅助记录同步生成）", deps("st7"))),
                "回复 1 确认跨境放款，回复 2 补充资料，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("外汇合规校验结论",
                                "跨境贸易融资外汇合规校验通过，外汇登记手续提示按外汇管理规定由人工办理",
                                s(sm.get("st2"), "合规校验：详见滚动日志")),
                        withExtraLines(statusBlock("跨境单据识别结果",
                                "ok", "境外证照识别正常", "",
                                "ok", "出口报关单 80 万美元要素齐全", "",
                                "ok", "跨境合同要素齐全", ""),
                                s(sm.get("st4"), "反洗钱监测：详见滚动日志")),
                        block("汇率与结售汇测算",
                                "美元兑人民币 7.12｜美元 80 万元＝人民币 569.6 万元",
                                s(sm.get("st5"), "牌价快照：详见滚动日志")),
                        block("操作区",
                                "跨境放款记录回写国际业务部台账，同步外汇申报辅助记录",
                                "回复 1 确认跨境放款，回复 2 补充资料，回复 3 结束"))));

        // ============ S7-1 贷后检查任务生成与执行 ============
        REGISTRY.put("S7-1", new SceneScript(
                "S7-1", "post-loan-inspection-agent", "贷后检查任务生成与执行",
                List.of("贷后检查", "检查任务", "逾期未检", "排检查", "检查报告"),
                "您好，我是贷后检查助手。您可以说'排一下我名下本月到期的检查任务'，或指定客户名，我会自动生成检查计划、推送工单并陪您完成检查记录。",
                "本次将为您完成：1.检查排程 2.工单派发 3.逾期督办 4.记录与报告 5.确认归档",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并客户身份，锁定客户唯一号", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", "恒达商贸有限公司"), "source", "core", "entityType", "COMPANY"),
                                null),
                        real("st2", "② 调用 data-query-agent 拉取在贷余额、五级分类、上次检查日期（贷后台账未联调，以信贷台账白名单表近似）", "data-query", deps("st1"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        realTask("st3", "③ 调用 task-schedule-agent 按风险等级排定检查频次与日期（正常类每半年、关注类每季度、次级类每月）", deps("st2")),
                        realTask("st4", "④ 调用 task-schedule-agent 生成检查工单并流转至客户经理", deps("st3")),
                        realNotify("st5", "∥ ⑤ 调用 notify-agent 对逾期未检客户生成督办提醒", deps("st3"), "TASK_URGE", "客户经理", "13800009999", map("taskContent", "逾期未贷后检查客户督办", "deadline", "2026-11-06")),
                        realDocParse("st6", "⑥ 接收现场检查照片、用途凭证并解析要素", deps("st4"), "site-check-S7-1.jpg", "voucher"),
                        realRiskSignal("st7", "? 条件分支：检查发现财务异常/逾期迹象，调用 risk-signal-agent 生成预警信号转 S7-2 跟踪", deps("st6"), "C-S7-1", List.of("TAX", "PUBLIC"), List.of("检查发现财务异常迹象")),
                        realReport("st8", "⑦ 调用 report-gen-agent 生成贷后检查报告", deps("st6"), "T_LOAN_AFTER_CHECK", map("orgId", "普惠金融部", "date", "2026-11-04", "granularity", "ORG", "custName", "恒通商贸", "checkDate", "2026-11-04", "loanId", "LN20260901002", "findings", "经营正常，回款稳定")),
                        human("st9", "⚑ 人工确认节点：检查结论、风险初判、报告归档由客户经理确认；重大风险（如挪用贷款）加签支行行长复核", deps("st7", "st8")),
                        realAudit("st10", "⑧ 调用 audit-log-agent 记录检查全程留痕", deps("st9"))),
                "回复 1 查看逾期未检清单并督办，回复 2 对本笔贷款发起五级分类复评，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("检查任务清单",
                                "恒达商贸 100 万元经营贷｜关注类｜每季度一次｜上次检查 2026-08-10｜本月 11-10 到期｜现场检查",
                                s(sm.get("st2"), "在贷数据：详见滚动日志")),
                        withExtraLines(donutBlock("执行进度", "笔",
                                "已完成", 9, "进行中", 0, "逾期未检", 3),
                                "本月到期 12 笔",
                                "逾期超 15 天升级提醒支行风险经理"),
                        withExtraLines(statusBlock("检查报告摘要",
                                "ok", "经营情况正常", "",
                                "ok", "进货发票与贷款用途一致", "",
                                "ok", "无挪用迹象", ""),
                                "风险初判：维持关注类",
                                s(sm.get("st8"), "报告生成：详见滚动日志")),
                        block("预警联动提示",
                                "本次检查未发现需上报的风险信号；如发现异常将自动转 S7-2 风险预警跟踪",
                                "回复 1 查看逾期未检清单并督办，回复 2 对本笔贷款发起五级分类复评（衔接 S7-3），回复 3 结束"))));

        // ============ S7-2 风险信号识别与预警 ============
        REGISTRY.put("S7-2", new SceneScript(
                "S7-2", "risk-signal-agent", "风险信号识别与预警",
                List.of("风险信号", "异常流水", "涉诉", "舆情", "预警"),
                "您好，我是风险预警助手。您可以说'查一下恒达商贸近三个月的风险信号'，我会从流水、舆情、司法、征信、名单五个渠道自动核查并分级预警。",
                "本次将为您完成：1.多源接入 2.信号识别 3.关联传导 4.分级预警 5.处置确认",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并客户身份，匹配他行同名主体", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", "恒达商贸有限公司"), "source", "core", "entityType", "COMPANY"),
                                null),
                        real("st2", "② 调用 data-query-agent 接入流水、征信、司法、舆情外部数据（外部信号源未联调，以信贷台账白名单表近似）", "data-query", deps("st1"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        real("st3", "③ 调用 data-query-agent 分析流水异常模式（近一月两笔 80 万元快进快出、归集转出）", "data-query", deps("st2"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_core", "table", "loan_balance_daily",
                                        "fields", List.of("branch_code", "loan_balance")),
                                null),
                        real("st4", "∥ ④ 调用 text-classify-agent 对舆情文本打标", "text-classify", deps("st2"),
                                ":8112/api/bai/textclass/classify",
                                (sl, sm, sd) -> map("text", "网络帖子：XX 银行又抽贷了，我们厂子资金链紧张，大家注意！",
                                        "task", "feedback_text", "scene", "SENTIMENT_TAG"),
                                (sl, d) -> "打标完成：" + truncate(d, 60)),
                        realScreening("st5", "∥ ⑤ 调用 screening-agent 核查失信、被执行、黑名单命中", deps("st1"), "AML", "恒通商贸有限公司"),
                        realGraphPenetrate("st6", "⑥ 调用 graph-penetrate-agent 查询担保圈与关联企业风险传导", deps("st1"), "C-S7-2", "DOWNSTREAM", 3),
                        realRiskSignal("st7", "⑦ 调用 risk-signal-agent 汇总信号，评定红/橙/黄预警级别，生成预警任务（信号规则引擎为确定性仿真）", deps("st3", "st4", "st5", "st6"), "C-S7-2", List.of("FLOW", "JUDICIAL", "INDUSTRY", "TAX", "PUBLIC"), List.of("经营流水波动", "新增涉诉")),
                        human("st8", "⚑ 人工确认节点：预警级别与处置建议由管户经理确认；超时 24 小时未确认，黄色预警自动降级为持续跟踪并提醒", deps("st7")),
                        real("st9", "⑧ 调用 metric-calc-agent 汇总本期预警统计（以监管指标口径近似预警统计）", "metric-calc", deps("st8"),
                                ":8128/api/bai/metric/calc",
                                (sl, sm, sd) -> map("metricId", "LOAN_BALANCE", "params", map("orgId", "ORG-DEMO", "date", "2026-11-02")),
                                (sl, d) -> "指标口径贷款余额 " + d.path("value").asText("-") + " " + d.path("unit").asText("-")
                                        + "（监管指标近似预警统计）")),
                "回复 1 安排现场核实，回复 2 上报分类复评，回复 3 持续跟踪",
                (sl, sm, sd) -> List.of(
                        withExtraLines(donutBlock("信号总览", "条",
                                "红", 0, "橙", 1, "黄", 3),
                                "命中信号 4 条：流水异常 2、舆情负面 2｜橙色预警",
                                s(sm.get("st2"), "信号源数据：详见滚动日志")),
                        block("明细信号",
                                "流水异常：近一月两笔 80 万元快进快出｜舆情负面：欠薪传言 2 条｜关联企业被执行记录 1 条｜征信新增对外担保 150 万元",
                                "外部数据仅作内部风险参考，不对客户披露原始数据"),
                        block("关联传导图谱数据形态",
                                "恒达商贸—关联企业—被执行企业传导路径 2 条（图谱数据形态，前端可展示）",
                                s(sm.get("st6"), "图谱穿透：详见滚动日志")),
                        block("预警任务与处置选项",
                                "预警信号已回写预警中心；预警确认后可触发五级分类复评（S7-3）",
                                "回复 1 安排现场核实，回复 2 上报分类复评（衔接 S7-3），回复 3 持续跟踪"))));

        // ============ S7-3 五级分类与风险评级 ============
        REGISTRY.put("S7-3", new SceneScript(
                "S7-3", "loan-classification-agent", "五级分类与风险评级",
                List.of("五级分类", "分类复评", "风险评级", "归哪一类", "迁徙"),
                "您好，我是五级分类助手。您直接说客户名或贷款账号，我会按《贷款风险分类指引》口径自动复评分类并给出依据。",
                "本次将为您完成：1.制度检索 2.征信与财报解析 3.指标计算 4.模型推理 5.分类认定",
                List.of(
                        real("st1", "① 调用 knowledge-retrieve-agent 检索《贷款风险分类指引》对应条款与本行分类制度", "knowledge-retrieve", deps(),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "贷款风险分类指引 正常 关注 次级 可疑 损失 分类认定标准 还款能力 逾期天数", "topK", 3),
                                (sl, d) -> "分类条款命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        real("st2", "∥ ② 调用 credit-report-agent 发起征信查询授权（贷后管理用途）", "credit-report", deps("st1"),
                                ":8110/api/bai/credit-report/authorize",
                                (sl, sm, sd) -> map("action", "CREATE",
                                        "customer", map("certNo", "91110108MA01C3Y86X", "name", "恒达商贸有限公司"),
                                        "authDoc", map("docNo", "SQ2026Q3-887", "certNo", "91110108MA01C3Y86X",
                                                "name", "恒达商贸有限公司", "signedAt", "2026-10-20"),
                                        "purpose", "贷后管理", "validDays", 365),
                                null),
                        real("st3", "∥ ② 调用 credit-report-agent 解析征信，提取逾期记录与对外担保", "credit-report", deps("st2"),
                                ":8110/api/bai/credit-report/report",
                                (sl, sm, sd) -> map("authId", sd.containsKey("st2") ? sd.get("st2").path("authId").asText(null) : null,
                                        "reportType", "COMPANY"),
                                (sl, d) -> "征信报告 " + d.path("report").path("reportNo").asText("-") + " 解析完成"),
                        real("st4", "∥ ③ 调用 financial-statement-agent 分析最新财报偿债能力（贷后分析场景 LOAN_AFTER）", "financial-statement", deps("st1"),
                                ":8139/api/bai/financial-statement/analyze",
                                (sl, sm, sd) -> map("entityId", "G-HENGDA-001", "period", "2026Q3", "scenario", "LOAN_AFTER",
                                        "statements", List.of(
                                                map("type", "BALANCE", "items", map("totalAssets", 9800000, "totalLiab", 7644000, "totalEquity", 2156000)),
                                                map("type", "INCOME", "items", map("revenue", 12500000, "netProfit", 310000)),
                                                map("type", "CASHFLOW", "items", map("operatingCashFlow", -420000)))),
                                (sl, d) -> "财报分析 " + d.path("analysisNo").asText("-") + " 完成（舞弊分 "
                                        + d.path("fraudScore").asText("-") + "）"),
                        realMetric("st5", "④ 调用 metric-calc-agent 计算资产负债率 78%、流动比率 0.9", deps("st4"), "SOLVENCY_SET", map("totalAssets", 85000000, "totalLiabilities", 66300000, "currentAssets", 32000000, "currentLiabilities", 35500000)),
                        realModelInfer("st6", "⑤ 调用 model-infer-agent 推理违约概率与风险等级（PD 升至 3.2%）", deps("st3", "st5"), "RATING", "SCORECARD", "CRB-SC-2025-V3", "C-S7-3", map("annualIncome", 1200000, "assetLiabRatio", 0.35, "netCashRatio", 0.42)),
                        human("st7", "⚑ 人工确认节点：关注类→次级类下调由有权人审批认定；跨级下调（正常直接降次级）加签风险总监", deps("st6")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录分类迁徙轨迹（近 4 季：正常→关注→关注→次级建议）", deps("st7"))),
                "回复 1 确认分类下调，回复 2 补充材料复评，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("分类结论",
                                "当前分类 关注类 → 建议分类 次级类（迁徙方向：下调）｜依据：还款能力出现明显问题",
                                "分类建议为辅助口径，最终结果以有权人工认定为准"),
                        block("依据链",
                                "征信：新增对外担保 150 万元，无新增逾期｜财务：资产负债率升至 78%，流动比率 0.9 低于 1｜模型：PD 升至 3.2%｜条款：《贷款风险分类指引》次级类认定标准",
                                s(sm.get("st4"), "财务依据：详见滚动日志")),
                        block("迁徙轨迹",
                                "近 4 个季度：正常 → 关注 → 关注 → 次级（建议）",
                                "分类结果作为拨备计提（S9-1）与监管报送（S9-4）上游输入"),
                        block("确认选项",
                                "次级及以上下调须审批；分类结果回写信贷管理系统",
                                "回复 1 确认分类下调，回复 2 补充材料复评，回复 3 结束"))));

        // ============ S7-4 押品管理与重估 ============
        REGISTRY.put("S7-4", new SceneScript(
                "S7-4", "collateral-management-agent", "押品管理与重估",
                List.of("押品重估", "押品管理", "押品覆盖率", "重估", "补押"),
                "您好，我是押品管理助手。您直接说客户名或押品编号，我会完成重估、覆盖率测算和状态核查。",
                "本次将为您完成：1.台账接入 2.押品重估 3.影像质检 4.覆盖率测算 5.工单归档",
                List.of(
                        real("st1", "① 调用 data-query-agent 拉取押品台账与对应贷款余额（押品系统未联调，以信贷台账白名单表近似）", "data-query", deps(),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        real("st2", "② 调用 valuation-agent 按房价指数重估城东工业园厂房（市场法）", "valuation", deps("st1"),
                                ":8124/api/bai/valuation/value",
                                (sl, sm, sd) -> map("assetType", "REAL_ESTATE", "method", "MARKET", "assetRef", "城东工业园厂房",
                                        "params", map("area", 1000, "unitPrice", 1320, "comparables", 3), "valueDate", "2026-11-02"),
                                (sl, d) -> "重估估值 " + d.path("value").asText("-") + " 元（较原评估 150 万下降 12%）"),
                        realImageQ("st3", "∥ ③ 调用 image-agent 质检押品影像是否存在翻拍缺失", deps("st2"), "collateral-photo-S7-4.jpg", "collateral"),
                        real("st4", "④ 调用 limit-calc-agent 按 70% 折扣测算覆盖率与可贷额度", "limit-calc", deps("st2", "st3"),
                                ":8123/api/bai/limit/calc",
                                (sl, sm, sd) -> map("customerId", "CUST-HENGDA-001", "limitType", "SINGLE",
                                        "incomeBased", 0, "collateralBased", 924000, "requestedAmount", 860000,
                                        "ratingFactor", 1.0, "availableLimit", 924000),
                                (sl, d) -> "折扣后可贷额度 " + d.path("limitAmount").asText("-") + " 元（覆盖率 107%，低于 130% 阈值）"),
                        human("st5", "⚑ 人工确认节点：重估结果与补押通知确认；大额重估差异须评估机构出具报告，重估双人复核", deps("st4")),
                        realTask("st6", "⑤ 调用 task-schedule-agent 生成补押预警工单并归档重估记录", deps("st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录重估全过程", deps("st6"))),
                "回复 1 确认重估并发起补押通知，回复 2 委托外部评估机构再评，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("押品台账总览",
                                "城东工业园厂房｜权证号（略）｜原评估价 150 万元｜上次重估 2025-11｜贷款余额 86 万元",
                                s(sm.get("st1"), "台账数据：详见滚动日志")),
                        block("重估结果",
                                "新估值 132 万元，较原值下降 12%｜重估依据：房价指数＋可比案例 3 个",
                                "押品状态核查：无查封、无异议登记"),
                        block("覆盖率测算",
                                "押品估值 132 万×折扣 70%＝92.4 万｜覆盖率 107%，低于 130% 阈值，触发补押预警",
                                s(sm.get("st4"), "覆盖率测算：详见滚动日志")),
                        block("状态核查与建议",
                                "补押通知将推送管户经理联系客户；覆盖率不足不自动调额",
                                "回复 1 确认重估并发起补押通知，回复 2 委托外部评估机构再评，回复 3 结束"))));

        // ============ S7-5 贷后评价（客户/产品/机构） ============
        REGISTRY.put("S7-5", new SceneScript(
                "S7-5", "post-loan-review-agent", "贷后评价（客户/产品/机构）",
                List.of("贷后评价", "后评价", "产品表现", "资产质量"),
                "您好，我是贷后后评价助手。您直接说评价对象和周期，例如'做二季度经营贷产品后评价'，我会自动算指标、出报告。",
                "本次将为您完成：1.制度检索 2.指标计算 3.趋势分析 4.结论提炼 5.报告确认",
                List.of(
                        real("st1", "① 调用 knowledge-retrieve-agent 检索本行后评价制度与指标口径", "knowledge-retrieve", deps(),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "贷后后评价制度 不良率 迁徙率 综合收益率 评价指标口径 产品评价 机构评价", "topK", 3),
                                (sl, d) -> "评价制度命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        realMetric("st2", "② 调用 metric-calc-agent 计算不良率 1.2%、迁徙率 0.8%、综合收益率 4.6%", deps("st1"), "ASSET_QUALITY_SET", map("nplBalance", 12000000, "loanBalance", 1000000000, "migrationAmount", 8000000, "loanBalancePrev", 1000000000, "interestIncome", 46000000, "avgAssets", 1000000000)),
                        real("st3", "∥ ③ 调用 trend-analyze-agent 做不良迁徙与收益趋势分析（经营贷近 6 期）", "trend-analyze", deps("st2"),
                                ":8132/api/bai/trend/analyze",
                                (sl, sm, sd) -> map("seriesId", "OP_LOAN_NPL",
                                        "points", List.of(
                                                map("date", "2026-05", "value", 0.9), map("date", "2026-06", "value", 1.0),
                                                map("date", "2026-07", "value", 1.0), map("date", "2026-08", "value", 1.1),
                                                map("date", "2026-09", "value", 1.2), map("date", "2026-10", "value", 1.2)),
                                        "window", "M6", "methods", List.of("trend", "mom")),
                                (sl, d) -> "趋势 " + d.path("trend").path("direction").asText("-") + "（analyzeId="
                                        + d.path("analyzeId").asText("-") + "）"),
                        realStructure("st4", "∥ ④ 调用 structure-gen-agent 生成评价图表", deps("st2"), "SCHEMA_REPORT_OUTLINE_V1", "TEXT", "贷后评价图表", "JSON"),
                        realSummary("st5", "⑤ 调用 summary-agent 提炼评价结论与改进建议", deps("st2", "st3"), "该笔经营贷按期还款 12 期，销售回款稳定，库存周转天数由 90 天降至 72 天；建议续授信并维持现有利率；风险点：下游客户集中度偏高。"),
                        realReport("st6", "⑥ 调用 report-gen-agent 生成后评价报告", deps("st5"), "T_POST_EVALUATION", map("orgId", "资产管理部", "date", "2026-10-31", "granularity", "ORG", "loanId", "LN20251201008", "period", "2026-10", "custName", "恒达建材商行")),
                        human("st7", "⚑ 人工确认节点：报告结论由评价岗确认；结果为管理参考，不直接决定客户续贷或机构奖罚", deps("st6"))),
                "回复 1 将改进建议转授信政策委员会，回复 2 导出评价明细数据，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("评价总览",
                                "对象 经营贷产品｜周期 2026 年二季度｜综合评级 B+",
                                s(sm.get("st1"), "评价口径：详见滚动日志")),
                        withExtraLines(progressBlock("指标明细",
                                "不良率", 1.2, "1.2%",
                                "迁徙率", 0.8, "0.8%",
                                "综合收益率", 4.6, "4.6%"),
                                "不良率环比升 0.3 个百分点",
                                "样本 128 笔，口径按本行后评价制度"),
                        block("趋势图表",
                                "不良率近 6 期缓步上行（趋势数据形态，前端可展示）",
                                s(sm.get("st3"), "趋势分析：详见滚动日志")),
                        block("结论与建议",
                                "不良上行集中在批发零售业，建议收紧该子行业准入并提高定价覆盖",
                                "回复 1 将改进建议转授信政策委员会，回复 2 导出评价明细数据，回复 3 结束"))));

        // ============ S7-6 智能体进化（模型监控与迭代） ============
        REGISTRY.put("S7-6", new SceneScript(
                "S7-6", "model-evolution-agent", "智能体进化（模型监控与迭代）",
                List.of("模型监控", "模型漂移", "评分卡", "重训", "PSI"),
                "您好，我是模型监控助手。您直接说模型名称和周期，例如'看一下经营贷评分卡近三个月的稳定性'，我会自动出监控报告。",
                "本次将为您完成：1.样本接入 2.指标计算 3.漂移归因 4.重训触发 5.版本审批",
                List.of(
                        real("st1", "① 调用 data-query-agent 接入线上评分样本与表现期数据（模型样本库未联调，以信贷台账白名单表近似）", "data-query", deps(),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        realMetric("st2", "② 调用 metric-calc-agent 计算 PSI 0.28、KL 散度、bad rate 环比升 0.5pct、KS 由 0.42 降至 0.35", deps("st1"), "DRIFT_SET", map("expectedDist", map("A", 0.4, "B", 0.35, "C", 0.25), "actualDist", map("A", 0.2, "B", 0.33, "C", 0.47), "badRateNow", 0.012, "badRatePrev", 0.007)),
                        real("st3", "∥ ③ 调用 trend-analyze-agent 分析客群结构变化（批发零售业客群占比近 6 期）", "trend-analyze", deps("st2"),
                                ":8132/api/bai/trend/analyze",
                                (sl, sm, sd) -> map("seriesId", "RETAIL_CUST_RATIO",
                                        "points", List.of(
                                                map("date", "2026-05", "value", 45.0), map("date", "2026-06", "value", 47.5),
                                                map("date", "2026-07", "value", 50.2), map("date", "2026-08", "value", 53.8),
                                                map("date", "2026-09", "value", 57.1), map("date", "2026-10", "value", 60.0)),
                                        "window", "M6", "methods", List.of("trend", "mom")),
                                (sl, d) -> "客群占比 " + d.path("trend").path("direction").asText("-") + "（analyzeId="
                                        + d.path("analyzeId").asText("-") + "）"),
                        realSummary("st4", "∥ ④ 调用 summary-agent 提炼漂移归因", deps("st2", "st3"), "PSI 0.28 主要由客群结构迁移导致：新客群中个体工商户占比上升 12pct，评分分布整体左移；建议增加稳定职业客群准入权重。"),
                        realEvent("st5", "? 条件分支：PSI 0.28>0.25 阈值，调用 event-subscribe-agent 生成重训工单", deps("st2"), "risk.alert", "S7-6-st5", map("alertId", "PSI-S7-6", "level", "ORANGE")),
                        realReport("st6", "⑤ 调用 report-gen-agent 生成模型监控报告", deps("st2", "st4"), "T_MODEL_MONITOR", map("orgId", "风险管理部", "date", "2026-10-31", "granularity", "ORG", "modelName", "评分卡SC-V3", "psi", 0.28, "month", "2026-10")),
                        human("st7", "⚑ 人工确认节点：重训与版本切换由模型管理岗审批，迭代须经风险政策委员会审议；本场景只监控与建议，不直接下线或切换线上模型", deps("st5", "st6")),
                        realAudit("st8", "⑥ 调用 audit-log-agent 记录版本迭代留痕", deps("st7"))),
                "回复 1 提交重训申请，回复 2 继续观察一个周期，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("监控总览",
                                "模型 经营贷评分卡 v3.2｜周期 近 3 月｜判定 显著漂移（PSI 超 0.25 阈值）",
                                s(sm.get("st1"), "样本数据：详见滚动日志")),
                        withExtraLines(barsBlock("指标明细", "",
                                "PSI", 0.28, "阈值", 0.25),
                                "bad rate 环比升 0.5 个百分点",
                                "KS 0.42→0.35（衰减 16.7%）",
                                "阈值口径按本行模型管理制度配置"),
                        block("漂移归因",
                                "批发零售业客群占比由 45% 升至 60%，客群结构迁移为主因",
                                s(sm.get("st3"), "客群趋势：详见滚动日志")),
                        block("迭代建议",
                                "已生成重训工单，建议扩大批发零售业样本并复核准入阈值",
                                "回复 1 提交重训申请，回复 2 继续观察一个周期，回复 3 结束"))));

        // ============ S7-7 贷后资料自动收集（15 日用信材料） ============
        REGISTRY.put("S7-7", new SceneScript(
                "S7-7", "post-loan-material-agent", "贷后资料自动收集（15 日用信材料）",
                List.of("用信材料", "材料收集", "催材料", "用途证明", "发票"),
                "您好，我是用信材料收集助手。放款后需在 15 日内补充用途证明材料，请直接在对话里发送发票、合同图片或文件，我会自动识别核验。",
                "本次将为您完成：1.放款信息接入 2.材料接收识别 3.要素抽取 4.用途校验 5.核销确认",
                List.of(
                        real("st1", "① 调用 data-query-agent 接入放款记录、支付方式与支付对象（受托支付：恒达建材商行 96 万元；支付台账未联调，以信贷台账白名单表近似）", "data-query", deps(),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        realDocParse("st2", "② 调用 doc-parse-agent 接收客户上传的发票照片 2 张、合同照片 1 张", deps("st1"), "invoice-S7-7.jpg", "voucher"),
                        realOcr("st3", "∥ ③ 调用 ocr-agent 识别文字内容", deps("st2"), "MIXED_DOC", "IMG-S77-01"),
                        realOcr("st4", "∥ ④ 调用 ocr-agent 抽取发票要素", deps("st2"), "INVOICE", "IMG-S77-02"),
                        realDocParse("st5", "∥ ⑤ 调用 doc-parse-agent 解析购销合同版面", deps("st2"), "purchase-contract-S7-7.pdf", "loan_contract"),
                        realNer("st6", "⑥ 调用 ner-agent 汇总要素", deps("st3", "st4", "st5"), "发票合计金额 96 万元，销方恒达建材商行，开票日期 2026-11-01", "TRADE"),
                        real("st7", "⑦ 调用 data-validate-agent 与放款金额、支付对象交叉校验用途真实性", "data-validate", deps("st1", "st6"),
                                ":8129/api/bai/validate/validate",
                                (sl, sm, sd) -> map("templateId", "T_RECON",
                                        "data", map("orgId", "ORG-DEMO", "applyDate", "2026-11-01",
                                                "loanAmount", 960000, "repayAmount", 960000,
                                                "invoiceAmount", 960000, "payeeAmount", 960000)),
                                (sl, d) -> "用途交叉校验：" + d.path("result").asText("-")),
                        realNotify("st8", "? 条件分支：材料缺项或超期，调用 notify-agent 生成催收提醒", deps("st7"), "OVERDUE_COLLECTION", "恒达建材商行", "13800010101", map("overdueDays", "15")),
                        human("st9", "⚑ 人工确认节点：材料核销与用途核验结论确认；识别置信度不足的材料转人工复核", deps("st7", "st8"))),
                "回复 1 查看超期未交客户清单，回复 2 将用途不一致材料转风险预警，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        withExtraLines(statusBlock("材料清单核销状态",
                                "ok", "增值税发票 已收", "2 张",
                                "ok", "购销合同关键页 已收", "1 张"),
                                "经营流水 不适用（受托支付）",
                                "应交 2 项、已交 2 项、缺项 0"),
                        block("识别结果",
                                "发票合计 96 万元，销方恒达建材商行｜合同交易对象与支付对象一致",
                                s(sm.get("st6"), "要素抽取：详见滚动日志")),
                        block("用途校验结论",
                                "发票金额与放款金额一致、支付对象一致，核验通过",
                                s(sm.get("st7"), "交叉校验：详见滚动日志")),
                        block("后续引导",
                                "材料已收齐并核验通过，识别结果回写用信档案；用途不一致材料将转 S7-2 风险预警",
                                "回复 1 查看超期未交客户清单，回复 2 将用途不一致材料转风险预警（衔接 S7-2），回复 3 结束"))));

        // ============ S7-8 到期转贷代办 ============
        REGISTRY.put("S7-8", new SceneScript(
                "S7-8", "loan-rollover-agent", "到期转贷代办",
                List.of("无还本续贷", "续贷", "转贷", "到期提醒"),
                "您好，我是转贷代办助手。您的经营贷即将到期，如符合无还本续贷条件，我可以代办续贷方案测算与办理。请告诉我贷款账号或直接说明续贷意向。",
                "本次将为您完成：1.到期识别 2.资格判定 3.方案测算 4.双向确认 5.交易执行",
                List.of(
                        real("st1", "① 调用 data-query-agent 接入到期贷款清单与还款表现（余额 86 万元，2026-12-02 到期，近 24 期无逾期；到期台账未联调，以信贷台账白名单表近似）", "data-query", deps(),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        realNotify("st2", "② 调用 notify-agent 向客户与管户经理推送到期提醒", deps("st1"), "DUE_REMIND", "万科供应链公司", "13800010202", map("dueDate", "2026-11-25")),
                        realCompliance("st3", "③ 续贷资格判定（正常类、生产经营正常、无逾期记录）", deps("st1"), "ADMISSION", map("classification", "正常类", "overdue", false, "business", "正常")),
                        real("st4", "∥ ④ 调用 limit-calc-agent 测算续贷额度（86 万元）", "limit-calc", deps("st3"),
                                ":8123/api/bai/limit/calc",
                                (sl, sm, sd) -> map("customerId", "CUST-HENGDA-001", "limitType", "SINGLE",
                                        "incomeBased", 860000, "collateralBased", 0, "requestedAmount", 860000,
                                        "ratingFactor", 1.0, "availableLimit", 860000),
                                (sl, d) -> "续贷额度 " + d.path("limitAmount").asText("-") + " 元，控制状态 "
                                        + d.path("controlStatus").asText("-")),
                        real("st5", "∥ ⑤ 调用 pricing-calc-agent 测算续贷利息与月供（1 年/年化 3.45%/按期付息到期还本）", "pricing-calc", deps("st3"),
                                ":8122/api/bai/pricing/quote",
                                (sl, sm, sd) -> map("productId", "LN-FLOW-001", "amount", 860000, "term", 12,
                                        "rateType", "FIXED", "collateral", "抵押", "customerRating", "A"),
                                (sl, d) -> "执行利率 " + d.path("finalRate").asText("-") + "（月供利息约 2470 元）"),
                        realTextGen("st6", "⑥ 调用 text-gen-agent 生成转贷方案（期限 1 年、年利率 3.45%、按期付息到期还本）", deps("st4", "st5")),
                        human("st7", "⚑ 人工确认节点：客户与管户经理双向确认方案；超时 7 天未确认视为放弃续贷，转到期还款提醒；不符合条件的客户只提示正常还款安排", deps("st6")),
                        realCallExec("st8", "? 条件分支：确认通过，调用 call-exec-agent 执行转贷交易（核心账务系统未联调，联调待办；签约环节转电子签约 S5-3）", deps("st7"), "DEF_ACCT_DEDUCT_001", map("loanNo", "LN-S7-8-001", "amount", 1500000)),
                        realTodo("st9", "⑦ 调用 todo-agent 跟踪办理进度", deps("st8"), "INSPECT", "RENEW-S7-8", "S7-8 st9 待办跟踪", "RM001")),
                "回复 1 查看办理进度，回复 2 续贷成功后安排用信材料收集，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("到期信息",
                                "贷款余额 86 万元｜到期日 2026-12-02（剩余 30 天）｜原利率 3.45%｜按期付息到期还本",
                                s(sm.get("st1"), "还款表现：详见滚动日志")),
                        block("续贷资格初判",
                                "符合无还本续贷条件（正常类、生产经营正常、近 24 期无逾期）",
                                "依据银保监发〔2019〕23 号续贷政策要求"),
                        block("转贷方案",
                                "续贷额度 86 万元｜期限 1 年｜年利率 3.45%｜月供利息约 2470 元",
                                s(sm.get("st5"), "利息试算：详见滚动日志")),
                        block("确认选项",
                                "续贷不视为逾期记录；办理结果回写信贷管理系统",
                                "回复 1 确认办理续贷，回复 2 到期正常还款，回复 3 调整期限或还款方式"))));

        // ============ S7-9 全域风险预警与统一处置 ============
        REGISTRY.put("S7-9", new SceneScript(
                "S7-9", "enterprise-risk-warning-agent", "全域风险预警与统一处置",
                List.of("全域风险", "全行预警", "风险汇总", "统一处置"),
                "您好，我是全域风险处置助手。您直接说范围和周期，例如'汇总本周全行橙色以上预警并生成统一处置方案'，我会自动汇聚信号、识别传导、生成处置工单。",
                "本次将为您完成：1.信号汇聚 2.关联挖掘 3.传导识别 4.工单生成 5.授权派发",
                List.of(
                        realRiskSignal("st1", "① 调用 risk-signal-agent 汇聚全域预警信号清单 37 条（跨客群/跨产品/跨机构）", deps(), "C-S7-9", List.of("FLOW", "JUDICIAL", "INDUSTRY", "TAX", "PUBLIC"), List.of("跨客群预警信号", "跨产品预警信号", "跨机构预警信号")),
                        real("st2", "② 调用 graph-build-agent 挖掘信号间关联关系（担保圈/集团/同行业）", "graph-build", deps("st1"),
                                ":8134/api/bai/graph/build",
                                (sl, sm, sd) -> map("mode", "FULL", "scope", map("goldenId", "G-HENGDA-001")),
                                (sl, d) -> "关联挖掘图构建 " + d.path("status").asText("-") + "（jobId=" + d.path("jobId").asText("-")
                                        + "，识别担保圈 5 个）"),
                        realGraphPenetrate("st3", "∥ ③ 调用 graph-penetrate-agent 查询传导路径与中心风险节点", deps("st2"), "C-S7-9", "DOWNSTREAM", 4),
                        real("st4", "∥ ④ 调用 trend-analyze-agent 统计全域风险分布与趋势（近 6 周橙红信号数）", "trend-analyze", deps("st1"),
                                ":8132/api/bai/trend/analyze",
                                (sl, sm, sd) -> map("seriesId", "WEEKLY_ORANGE_RED",
                                        "points", List.of(
                                                map("date", "W37", "value", 22), map("date", "W38", "value", 25),
                                                map("date", "W39", "value", 28), map("date", "W40", "value", 30),
                                                map("date", "W41", "value", 33), map("date", "W42", "value", 37)),
                                        "window", "M6", "methods", List.of("trend", "mom")),
                                (sl, d) -> "风险趋势 " + d.path("trend").path("direction").asText("-") + "（analyzeId="
                                        + d.path("analyzeId").asText("-") + "）"),
                        realStructure("st5", "∥ ⑤ 调用 structure-gen-agent 生成风险态势图表", deps("st4"), "SCHEMA_REPORT_OUTLINE_V1", "TEXT", "风险态势图表", "JSON"),
                        realTask("st6", "⑥ 统一生成分级处置工单 12 张（2 户压降、4 户补押、6 户升级审查）", deps("st2", "st3", "st4")),
                        realTask("st7", "? 条件分支：批量处置，调用 task-schedule-agent 批量派发工单", deps("st6")),
                        realApproval("st8", "⑦ 调用 approval-route-agent 流转处置审批与销项跟踪", deps("st7"), "RISK_DISPOSAL", 2000000, "MEDIUM"),
                        human("st9", "⚑ 人工确认节点：处置措施与授权匹配校验后由有权人确认；越权处置工单一律拦截并路由至有权审批人", deps("st8")),
                        realAudit("st10", "⑧ 调用 audit-log-agent 记录处置留痕", deps("st9"))),
                "回复 1 跟踪处置销项进度，回复 2 导出全域风险态势报告，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        withExtraLines(donutBlock("全域风险总览", "条",
                                "经营贷", 21, "按揭", 9, "票据", 5, "保函", 2),
                                "本周橙红信号 37 条｜涉及 6 家支行",
                                s(sm.get("st4"), "风险趋势：详见滚动日志")),
                        block("关联传导图谱数据形态",
                                "最大担保圈涉及 8 户、敞口合计 2300 万元；中心风险节点已标注（图谱数据形态，前端可展示）",
                                s(sm.get("st2"), "关联挖掘：详见滚动日志")),
                        withExtraLines(donutBlock("处置工单清单", "户",
                                "压降", 2, "补押", 4, "升级审查", 6),
                                "12 张工单：压降 2 户（授权：风险总监）、补押 4 户（授权：支行行长）、升级审查 6 户（授权：风险经理）",
                                "处置工单与既有工单自动合并去重"),
                        block("处置确认",
                                "工单按授权层级派发至各支行风险经理，销项状态回写预警中心",
                                "回复 1 确认批量派发，回复 2 逐单调整，回复 3 结束"))));

        // ============ S7-10 授信年检与额度重检 ============
        REGISTRY.put("S7-10", new SceneScript(
                "S7-10", "credit-annual-review-agent", "授信年检与额度重检",
                List.of("授信年检", "年检", "额度重检", "额度调整"),
                "您好，我是授信年检助手。您直接说客户名称，我会重新查询征信、复评信用、测算额度，给出年检结论。",
                "本次将为您完成：1.在贷接入 2.征信复评 3.押品引用 4.额度重算 5.审批流转",
                List.of(
                        real("st1", "① 调用 data-query-agent 接入原授信与在贷余额（原授信 200 万元，在贷 86 万元，有效期至 2026-12-15；授信台账未联调，以信贷台账白名单表近似）", "data-query", deps(),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        real("st2", "∥ ② 调用 credit-report-agent 发起最新征信查询授权（贷后管理用途）", "credit-report", deps("st1"),
                                ":8110/api/bai/credit-report/authorize",
                                (sl, sm, sd) -> map("action", "CREATE",
                                        "customer", map("certNo", "91110108MA01C3Y86X", "name", "恒达商贸有限公司"),
                                        "authDoc", map("docNo", "SQ2026Q4-015", "certNo", "91110108MA01C3Y86X",
                                                "name", "恒达商贸有限公司", "signedAt", "2026-11-01"),
                                        "purpose", "贷后管理", "validDays", 365),
                                null),
                        real("st3", "∥ ② 调用 credit-report-agent 解析最新人行征信（无新增逾期）", "credit-report", deps("st2"),
                                ":8110/api/bai/credit-report/report",
                                (sl, sm, sd) -> map("authId", sd.containsKey("st2") ? sd.get("st2").path("authId").asText(null) : null,
                                        "reportType", "COMPANY"),
                                (sl, d) -> "最新征信报告 " + d.path("report").path("reportNo").asText("-") + " 解析完成"),
                        realModelInfer("st4", "∥ ③ 调用 model-infer-agent 重新推理信用等级（维持 AA）", deps("st3"), "RATING", "SCORECARD", "CRB-SC-2025-V3", "C-S7-10", map("annualIncome", 1200000, "assetLiabRatio", 0.35, "netCashRatio", 0.42)),
                        real("st5", "∥ ④ 调用 valuation-agent 引用押品最新重估值（城东工业园厂房 132 万元，引 S7-4 重估结果）", "valuation", deps("st1"),
                                ":8124/api/bai/valuation/value",
                                (sl, sm, sd) -> map("assetType", "REAL_ESTATE", "method", "MARKET", "assetRef", "城东工业园厂房",
                                        "params", map("area", 1000, "unitPrice", 1320, "comparables", 3), "valueDate", "2026-11-02"),
                                (sl, d) -> "押品重估值 " + d.path("value").asText("-") + " 元（引用 S7-4 重估结果）"),
                        real("st6", "⑤ 调用 limit-calc-agent 按重检口径重算额度（维持/调增/调减建议）", "limit-calc", deps("st4", "st5"),
                                ":8123/api/bai/limit/calc",
                                (sl, sm, sd) -> map("customerId", "CUST-HENGDA-001", "limitType", "SINGLE",
                                        "incomeBased", 2000000, "collateralBased", 924000, "requestedAmount", 2000000,
                                        "ratingFactor", 1.0, "availableLimit", 2000000),
                                (sl, d) -> "重检建议额度 " + d.path("limitAmount").asText("-") + " 元，控制状态 "
                                        + d.path("controlStatus").asText("-")),
                        real("st7", "? 条件分支：调整幅度未超经办权限，调用 auth-agent 校验操作授权（授权矩阵待扩展，身份核验先行）", "auth", deps("st6"),
                                ":8109/api/bai/auth/login",
                                (sl, sm, sd) -> map("identity", map("userId", "RM-015", "name", "王芳"),
                                        "factor", map("type", "PASSWORD", "credential", "******"),
                                        "context", map("deviceId", "Teller-041", "deviceFp", "FP-88213", "ip", "10.20.1.41", "userAgent", "CreditWorkbench/2.3", "geo", "CN-1100"),
                                        "scene", "BACKOFFICE"),
                                (sl, d) -> "年检操作身份核验通过（authId=" + d.path("authId").asText("-") + "）"),
                        realApproval("st8", "⑥ 调用 approval-route-agent 流转年检审批", deps("st7"), "ANNUAL_REVIEW", 1500000, "LOW"),
                        realNotify("st9", "∥ ⑦ 调用 notify-agent 对超期未检客户督办", deps("st8"), "TASK_URGE", "客户经理", "13800010303", map("taskContent", "超期未年检客户督办", "deadline", "2026-11-08")),
                        realReport("st10", "⑧ 调用 report-gen-agent 生成年检报告", deps("st8"), "T_ANNUAL_CHECK", map("orgId", "普惠金融部", "date", "2026-10-31", "granularity", "ORG", "custName", "恒通商贸", "year", "2026")),
                        human("st11", "⚑ 人工确认节点：年检结论确认上报审批；年检超期未完成自动暂停客户新增用信权限并升级督办", deps("st10"))),
                "回复 1 年检通过后衔接续贷办理，回复 2 查看超期未检客户清单，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("年检总览",
                                "客户 恒达商贸｜原额度 200 万元｜有效期至 2026-12-15｜年检结论 维持原额度",
                                s(sm.get("st1"), "在贷数据：详见滚动日志")),
                        block("复评依据",
                                "征信：无新增逾期｜信用等级：维持 AA｜押品：重估 132 万元（较原值下降 12%）",
                                s(sm.get("st3"), "征信解析：详见滚动日志")),
                        block("额度重测算",
                                "原额度 200 万元 → 建议额度 200 万元（维持）｜测算依据：第一还款来源稳定＋押品覆盖达标",
                                s(sm.get("st6"), "额度重算：详见滚动日志")),
                        block("审批选项",
                                "年检结论回写授信台账；年检通过后可衔接续贷（S7-8）或新授信（S4）",
                                "回复 1 确认上报审批，回复 2 补充材料重测，回复 3 结束"))));

        // ============ S8-1 正常还款（计划执行/扣款/入账） ============
        REGISTRY.put("S8-1", new SceneScript(
                "S8-1", "loan-repayment-agent", "正常还款（计划执行/扣款/入账）",
                List.of("正常还款", "批量扣款", "批扣", "扣款入账", "主动还款"),
                "您好，我是还款执行助手。您可以说'扣一下某某客户这期月供'或'查今天的批扣结果'，我会核对计划、执行扣款、入账并给您回执。",
                "本次将为您完成：1.计划核对 2.扣款执行 3.反洗钱监测 4.入账记账 5.留痕回执",
                List.of(
                        real("st1", "① 调用 repayment-plan-agent 核对借据 J20250318007 当期应还本金/利息/总额，校验期次未扣过（第 6 期）", "repayment-plan", deps(),
                                ":8127/api/bai/repayment-plan/plan",
                                (sl, sm, sd) -> map("contractId", "J20250318007", "principal", 300000, "rate", 0.0405,
                                        "terms", 12, "method", "EQUAL_INSTALLMENT", "startDate", "2026-05-18"),
                                (sl, d) -> "第 6 期应还（实算）=" + d.path("monthlyPayment").asText("-")
                                        + "，计划核对完成（期次未扣款）"),
                        realTask("st2", "② 调用 task-schedule-agent 对约定还款卡发起扣款（金额以实算计划为准），带幂等流水号 K20261102006 防重复（批量扣款执行未上线，联调待办：确定性仿真＋人工确认运行）", deps("st1")),
                        realAml("st3", "∥ ③ 调用 aml-agent 监测大额扣款是否触发可疑交易报告", deps("st2"), "T1D", List.of(map("amount", 520000, "counterparty", "恒通商贸", "direction", "OUT", "purpose", "贷款扣款", "cash", false, "time", "2026-11-04"))),
                        realTodo("st4", "∥ ④ 调用 todo-agent 记录扣款交易状态", deps("st2"), "COLLECT", "DEDUCT-S8-1", "S8-1 st4 待办跟踪", "COLL001"),
                        realCallExec("st5", "⑤ 调用 call-exec-agent 入账：本金、利息分别记入核心科目，更新贷款余额与期次状态（核心账务接口联调待办：确定性仿真＋人工确认运行）", deps("st2"), "DEF_ACCT_DEDUCT_001", map("loanNo", "LN-S8-1-001", "amount", 520000)),
                        human("st6", "⚑ 条件分支：单笔人工补扣超 50 万元或非约定账户还款须柜员主管确认后扣款（本笔常规还款默认无需触发）", deps("st2")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 留痕（扣款流水、入账凭证、操作人）", deps("st3", "st4", "st5", "st6"))),
                "回复 1 查看该借据后续还款计划，回复 2 结清时生成结清证明（衔接 S8-2），回复 3 结束",
                (sl, sm, sd) -> {
                    JsonNode p = sd.get("st1");
                    double mp = p == null ? 0 : p.path("monthlyPayment").asDouble(0);
                    double ti = p == null ? 0 : p.path("totalInterest").asDouble(0);
                    return List.of(
                            block("扣款回执",
                                    "借据 J20250318007｜第 6 期｜扣款账户尾号 6688｜交易流水号 K20261102006",
                                    "应扣金额（实算）：" + String.format("%.2f", mp) + " 元｜扣款时间 2026-11-02 10:24"),
                            block("入账明细",
                                    "入账口径：本金、利息分别记入核心科目，贷款余额与期次状态同步更新",
                                    "全周期利息合计（实算）：" + String.format("%.2f", ti) + " 元｜回执已同步核心系统电子回单"),
                            block("异常提示与后续操作",
                                    "本次扣款无失败笔数；扣款失败将自动转 S8-8 还款异常处理",
                                    "回复 1 查看该借据后续还款计划（S6-2），回复 2 结清时生成结清证明（衔接 S8-2），回复 3 结束"));
                }));

        // ============ S8-2 提前还款重算 ============
        REGISTRY.put("S8-2", new SceneScript(
                "S8-2", "prepayment-recalc-agent", "提前还款重算",
                List.of("提前还款", "部分还款", "一次性结清", "提前结清", "违约金"),
                "您好，我是提前还款助手。请告诉我借据号和想提前还多少（或说'一次性结清'），我会按合同条款算出应付利息、违约金和新月供。",
                "本次将为您完成：1.按日计息 2.额度测算 3.计划重算 4.违约金核算 5.审批确认",
                List.of(
                        real("st1", "① 调用 pricing-calc-agent 按日重算截至申请日的执行利率口径（上一还款日至申请日 15 天占用利息）", "pricing-calc", deps(),
                                ":8122/api/bai/pricing/quote",
                                (sl, sm, sd) -> map("productId", "LN-PREPAY-001", "amount", 100000, "term", 24,
                                        "rateType", "FLOATING", "collateral", "抵押", "customerRating", "A"),
                                (sl, d) -> "执行利率（实算）=" + d.path("finalRate").asText("-") + "%，按日计息口径确认"),
                        real("st2", "∥ ② 调用 limit-calc-agent 测算剩余本金与可提前还款金额（校验不低于合同最低提前还款额）", "limit-calc", deps("st1"),
                                ":8123/api/bai/limit/calc",
                                (sl, sm, sd) -> map("customerId", "CUST-WANGQIANG-001", "limitType", "SINGLE",
                                        "incomeBased", 0, "collateralBased", 3500000, "requestedAmount", 100000,
                                        "ratingFactor", 1.0, "availableLimit", 3500000),
                                (sl, d) -> "可提前还款额度校验通过（不低于合同最低额），剩余本金 35 万元"),
                        real("st3", "③ 调用 repayment-plan-agent 重算提前还款 10 万元后新还款计划（减少月供版）", "repayment-plan", deps("st2"),
                                ":8127/api/bai/repayment-plan/plan",
                                (sl, sm, sd) -> map("contractId", "J20250612023", "principal", 350000, "rate", 0.0345,
                                        "terms", 23, "method", "EQUAL_INSTALLMENT", "startDate", "2026-12-18"),
                                (sl, d) -> "新月供（实算）=" + d.path("monthlyPayment").asText("-") + "，剩余 23 期"),
                        real("st4", "④ 按合同条款计算提前还款违约金", "pricing-calc", deps("st2"),
                                ":8122/api/bai/pricing/quote",
                                (sl, sm, sd) -> map("productId", "LN-FLOW-001", "amount", 100000, "term", 12,
                                        "rateType", "FIXED", "collateral", "信用", "customerRating", "B",
                                        "pricingType", "PENALTY",
                                        "params", map("remainingPrincipal", 100000, "penaltyRate", 0.01)),
                                (sl, d) -> "违约金试算：" + truncate(d, 60)),
                        human("st5", "⚑ 人工确认节点：重算结果与违约金金额，超 50 万元或全额结清由有权审批人确认（本笔部分还款走常规审批；超时 48 小时挂起提醒）", deps("st3", "st4")),
                        realTextGen("st6", "⑤ 调用 text-gen-agent 生成提前还款通知单", deps("st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 留痕（重算过程、审批人、结果）", deps("st6"))),
                "回复 1 确认办理并衔接扣款入账，回复 2 改为缩短期限重算，回复 3 暂不办理",
                (sl, sm, sd) -> {
                    JsonNode pr = sd.get("st1");
                    double rate = pr == null ? 0 : pr.path("finalRate").asDouble(0);
                    double dayInt = 100000 * rate / 100 / 360 * 15;
                    JsonNode pl = sd.get("st3");
                    double mp = pl == null ? 0 : pl.path("monthlyPayment").asDouble(0);
                    return List.of(
                            block("提前还款测算单",
                                    "提前还本金 100,000 元｜按日计息（实算）：执行利率 " + rate + "%，15 天占用利息≈" + String.format("%.2f", dayInt) + " 元",
                                    "违约金 1,000 元（合同口径：提前还本金×1%）",
                                    "本次扣款合计以审批确认单为准（本金＋按日利息＋违约金）"),
                            block("还款后计划",
                                    "剩余本金 350,000 元｜减少月供版新月供（实算）：" + String.format("%.2f", mp) + " 元",
                                    "如需缩短期限版计划，回复 2 重算对比"),
                            block("确认选项",
                                    "⚑ 重算结果须经有权人审批后才允许实际扣款；审批通过后衔接 S8-1 扣款入账",
                                    "回复 1 确认办理，回复 2 改为缩短期限，回复 3 暂不办理"));
                }));

        // ============ S8-3 利率调整重算 ============
        REGISTRY.put("S8-3", new SceneScript(
                "S8-3", "rate-adjust-recalc-agent", "利率调整重算",
                List.of("利率调整", "LPR", "重定价", "存量房贷重算", "浮动利率重算"),
                "您好，我是利率重算助手。请告诉我调整依据（如'2026 年 12 月 LPR 调整'或行内文件）和适用范围，我会批量重算剩余计划并生成客户通知。",
                "本次将为您完成：1.LPR 查询 2.定价校验 3.批量重算 4.勾稽校验 5.抽样确认",
                List.of(
                        real("st1", "① 调用 knowledge-process-agent 查询最新 LPR（1 年期/5 年期以上）与生效口径", "knowledge-process", deps(),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "DOC-S83-001",
                                        "text", "2026 年 12 月 LPR 报价：1 年期 3.0%，5 年期以上 3.85%，自 2027 年 1 月 1 日重定价生效。",
                                        "source", "policy"),
                                null),
                        real("st2", "∥ ② 调用 pricing-calc-agent 校验新利率=新 LPR+合同加点是否在合规区间", "pricing-calc", deps("st1"),
                                ":8122/api/bai/pricing/quote",
                                (sl, sm, sd) -> map("productId", "LN-MORT-FLOAT", "amount", 800000, "term", 180,
                                        "rateType", "FLOATING", "collateral", "抵押", "customerRating", "A"),
                                (sl, d) -> "新利率校验（实算）=" + d.path("finalRate").asText("-")
                                        + "%（新 LPR＋合同加点，合规区间内）"),
                        real("st3", "③ 调用 repayment-plan-agent 对适用借据批量重算剩余期还款计划（以样本借据 J20190601001 演示，等额本息按新利率摊还）", "repayment-plan", deps("st2"),
                                ":8127/api/bai/repayment-plan/plan",
                                (sl, sm, sd) -> map("contractId", "J20190601001", "principal", 800000, "rate", 0.0410,
                                        "terms", 180, "method", "EQUAL_INSTALLMENT", "startDate", "2027-01-01"),
                                (sl, d) -> "样本借据重算后月供（实算）=" + d.path("monthlyPayment").asText("-")),
                        real("st4", "∥ ④ 调用 pricing-calc-agent 校验重算后总利息与逐期利息勾稽一致", "pricing-calc", deps("st3"),
                                ":8122/api/bai/pricing/quote",
                                (sl, sm, sd) -> map("productId", "LN-MORT-FLOAT", "amount", 800000, "term", 1,
                                        "rateType", "FLOATING", "collateral", "抵押", "customerRating", "A"),
                                (sl, d) -> "勾稽校验：重算口径利率 " + d.path("finalRate").asText("-") + "% 与逐期摊还一致"),
                        human("st5", "⚑ 人工确认节点：重算结果抽样（抽 5% 借据）与整体差异表由计财部确认；抽样未通过不发布通知", deps("st3", "st4")),
                        realTextGen("st6", "⑤ 调用 text-gen-agent 生成分客户利率调整通知", deps("st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 留痕（LPR 来源、重算批次、审批人）", deps("st6"))),
                "回复 1 确认发布并回写核心，回复 2 先看抽样明细，回复 3 暂缓",
                (sl, sm, sd) -> {
                    JsonNode p = sd.get("st3");
                    double mp = p == null ? 0 : p.path("monthlyPayment").asDouble(0);
                    return List.of(
                            block("重算总览",
                                    "适用范围：全部存量浮动利率房贷（演示样本 1 笔）｜新利率=新 LPR＋合同加点｜重定价日 2027-01-01",
                                    "批量重算以单笔样本演示，全量批次结果回写核心系统"),
                            block("抽样明细",
                                    "借据 J20190601001：重算后月供（实算）" + String.format("%.2f", mp) + " 元（新利率口径）",
                                    s(sm.get("st2"), "定价校验：详见滚动日志")),
                            block("通知预览",
                                    "【XX银行】您的房贷利率已按 2026 年 12 月 LPR 重定价，新月供及生效日详见还款计划调整单，如有疑问请致电客服专线。",
                                    "通知按消保要求展示新旧月供对照；固定利率贷款自动剔除不在重算范围"),
                            block("确认选项",
                                    "勾稽不平的借据单独挂起人工核查，不影响正常借据发布",
                                    "回复 1 确认发布并回写核心，回复 2 先看抽样明细，回复 3 暂缓"));
                }));

        // ============ S8-4 展期/转贷 ============
        REGISTRY.put("S8-4", new SceneScript(
                "S8-4", "extension-refinance-agent", "展期/转贷",
                List.of("贷款展期", "展期", "无还本续贷方案", "转贷方案"),
                "您好，我是展期续贷助手。请告诉我客户借据号、想展期多久（或申请无还本续贷）、困难原因一句话，我会先核对条件、生成方案并走审批。",
                "本次将为您完成：1.方案生成 2.计划重算 3.审批流转 4.协议生成 5.留痕归档",
                List.of(
                        realTextGen("st1", "① 调用 text-gen-agent 生成展期/转贷方案（展期期限、利率是否调整、还款方式）", deps()),
                        real("st2", "∥ ② 调用 repayment-plan-agent 重算展期后还款计划（展期 6 个月，展期期内按期付息、到期一次性还本）", "repayment-plan", deps("st1"),
                                ":8127/api/bai/repayment-plan/plan",
                                (sl, sm, sd) -> map("contractId", "J20250610015", "principal", 10000000, "rate", 0.0385,
                                        "terms", 6, "method", "PERIOD_INTEREST", "startDate", "2027-01-10"),
                                (sl, d) -> "展期后计划（实算）：按期付息月息=" + d.path("monthlyPayment").asText("-")
                                        + "，利息合计=" + d.path("totalInterest").asText("-")),
                        realApproval("st3", "③ 调用 approval-route-agent 发起审批流", deps("st1"), "COLLECTION_APPROVAL", 8000000, "HIGH"),
                        human("st4", "⚑ 人工确认节点：各级审批人在线确认，终审通过才生效；超时按审批制度升级提醒，不自动通过", deps("st3")),
                        realTextGen("st5", "④ 调用 text-gen-agent 生成展期协议/转贷合同（电子签约平台联调待办，签约环节转 S5-3）", deps("st4")),
                        realAudit("st6", "⑤ 调用 audit-log-agent 留痕（方案、审批链、协议版本）", deps("st5"))),
                "回复 1 审批通过后提交电子签约，回复 2 调整展期期限重算，回复 3 撤回申请",
                (sl, sm, sd) -> {
                    JsonNode p = sd.get("st2");
                    double mi = p == null ? 0 : p.path("monthlyPayment").asDouble(0);
                    return List.of(
                            block("方案摘要",
                                    "原贷款 1,000 万元｜利率维持 3.85%｜原到期日 2026-12-10 → 展期 6 个月至 2027-06-10",
                                    "展期期内按期付息（月息实算 " + String.format("%.2f", mi) + " 元）、到期一次性还本｜厂房抵押继续有效"),
                            block("审批进度",
                                    "客户经理→支行行长→信审｜当前节点：支行行长审批中",
                                    "已逾期或五级分类次级以下客户不受理展期，先转 S8-5/S8-9 处置"),
                            block("协议预览",
                                    "展期协议关键条款：展期期限、利率口径、担保延续、到期还款安排",
                                    "协议生成后进入电子签约意愿核验（衔接 e-signature，联调待办）"),
                            block("确认选项",
                                    "展期记录回写信贷管理系统，审批链回写流程引擎",
                                    "回复 1 审批通过后提交电子签约，回复 2 调整展期期限重算，回复 3 撤回申请"));
                }));

        // ============ S8-5 逾期识别与智能催收 ============
        REGISTRY.put("S8-5", new SceneScript(
                "S8-5", "overdue-collection-agent", "逾期识别与智能催收",
                List.of("智能催收", "逾期催收", "催收短信", "催收函", "逾期名单", "逾期分级", "催收"),
                "您好，我是智能催收助手。您可以说'看今天逾期名单''给这个逾期 20 天的客户拟一条催收短信''把 M2 客户的催收函生成一下'，我会按分级策略合规处理。",
                "本次将为您完成：1.身份归并 2.分级策略 3.任务排期 4.合规话术 5.确认触达",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并逾期客户身份（一人多借据合并催收）", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", "赵敏", "idCard", "110101199203070568"),
                                        "source", "core", "entityType", "PERSON"),
                                null),
                        realRiskSignal("st2", "② 调用 risk-signal-agent 按逾期天数分级（M1/M2/M3）并匹配催收策略（信号规则引擎为确定性仿真）", deps("st1"), "C-S8-5", List.of("FLOW"), List.of("贷款逾期 45 天")),
                        realTask("st3", "③ 调用 task-schedule-agent 生成催收任务排期（触达时间窗、频次上限）", deps("st2")),
                        real("st4", "∥ ④ 调用 credit-report-agent 发起征信查询授权（催收参考用途）", "credit-report", deps("st1"),
                                ":8110/api/bai/credit-report/authorize",
                                (sl, sm, sd) -> map("action", "CREATE",
                                        "customer", map("certNo", "110101199203070568", "name", "赵敏"),
                                        "authDoc", map("docNo", "SQ2026Q4-021", "certNo", "110101199203070568",
                                                "name", "赵敏", "signedAt", "2026-10-28"),
                                        "purpose", "贷后管理", "validDays", 365),
                                null),
                        real("st5", "∥ ④ 调用 credit-report-agent 查询征信逾期汇总作为催收参考", "credit-report", deps("st4"),
                                ":8110/api/bai/credit-report/report",
                                (sl, sm, sd) -> map("authId", sd.containsKey("st4") ? sd.get("st4").path("authId").asText(null) : null,
                                        "reportType", "PERSON"),
                                (sl, d) -> "征信报告 " + d.path("report").path("reportNo").asText("-") + " 解析完成（逾期汇总见滚动日志）"),
                        real("st6", "⑤ 调用 rag-answer-agent 检索合规催收话术库（时间窗、频次上限、禁止性规定）", "rag-answer", deps("st3"),
                                ":8119/api/bai/rag/answer",
                                (sl, sm, sd) -> map("question", "贷款逾期催收的合规话术要点与禁止性规定有哪些？",
                                        "context", List.of(map("entryId", "KB-COLLECT-001", "title", "催收作业合规指引",
                                                "content", "催收短信须注明银行名称与回电渠道；每日 22:00 至次日 8:00 不得外呼；同一客户每日电话催收不超过 3 次；严禁骚扰无关第三人、严禁冒充公检法、严禁威胁恐吓。", "score", 0.95)),
                                        "style", "plain"),
                                (sl, d) -> "合规话术口径已生成（answerId=" + d.path("answerId").asText("-")
                                        + "，忠实度 " + d.path("quality").path("faithfulness").asDouble(0) + "）"),
                        realSentiment("st7", "∥ ⑥ 调用 sentiment-agent 对客户历史回复情绪打标", deps("st1"), "资金月底就到账了，不用催，我自己会安排还的。", "COLLECTION"),
                        realTextGen("st8", "⑦ 调用 text-gen-agent 生成分级催收短信/电话话术", deps("st6")),
                        realTextGen("st9", "? 条件分支：M2/M3 客户调用 text-gen-agent 生成催收函/律师函草稿", deps("st6")),
                        human("st10", "⚑ 人工确认节点：话术与信函内容、发送名单经催收主管确认；客户已投诉或提出异议的立即停止自动触达转人工", deps("st8", "st9")),
                        realNotify("st11", "⑧ 调用 notify-agent 发送合规短信", deps("st10"), "COMPLIANCE_SMS", "赵敏", "13800010404", map("complianceTip", "催收沟通请注意文明用语，22:00 至次日 8:00 不外呼")),
                        realAudit("st12", "⑩ 调用 audit-log-agent 留痕（触达记录、话术、时间）", deps("st11"))),
                "回复 1 确认发送，回复 2 修改话术，回复 3 转人工跟进",
                (sl, sm, sd) -> List.of(
                        withExtraLines(donutBlock("逾期总览", "户",
                                "M1（1-30 天）", 39, "M2（31-60 天）", 6, "M3（61-90 天）", 2),
                                "当日批后逾期 47 户",
                                "逾期本金合计 386 万元",
                                s(sm.get("st5"), "征信参考：详见滚动日志")),
                        block("分级任务清单",
                                "M1：短信提醒（当日 18 点前）｜M2：电话＋催收函｜M3：转 S8-9 风险处置（逾期 76、82 天两户）",
                                "触达时间窗：22:00 至次日 8:00 不外呼；同一客户每日电话不超过 3 次"),
                        block("话术预览",
                                "【XX银行】尊敬的客户，您本期贷款应还 8,650 元已逾期 5 天，请于今日 18 点前存入还款卡，避免影响征信。如有疑问请回电 400-XXX。",
                                "合规要点：注明银行名称与回电渠道、无威胁表述、仅联系本人"),
                        block("确认选项",
                                "触达结果回流催收台账；失败工单（空号/停机）标记后人工核实，不重复轰炸",
                                "回复 1 确认发送，回复 2 修改话术，回复 3 该客户转人工；逾期 90 天以上自动转 S8-9"))));

        // ============ S8-6 罚息复利计算 ============
        REGISTRY.put("S8-6", new SceneScript(
                "S8-6", "penalty-interest-agent", "罚息复利计算",
                List.of("罚息复利", "罚息", "复利", "计息"),
                "您好，我是罚息计息助手。您可以说'算这笔借据到今天的罚息'或'跑本月罚息复利批量出账'，我会按合同口径计算并勾稽。",
                "本次将为您完成：1.罚息试算 2.计划更新 3.批量排期 4.出账确认 5.留痕通知",
                List.of(
                        real("st1", "① 调用 pricing-calc-agent 按日计算逾期本金罚息口径（本金×罚息日利率×逾期天数）与未付利息复利", "pricing-calc", deps(),
                                ":8122/api/bai/pricing/quote",
                                (sl, sm, sd) -> map("productId", "LN-PENALTY-001", "amount", 52000, "term", 1,
                                        "rateType", "FLOATING", "collateral", "信用", "customerRating", "B"),
                                (sl, d) -> "罚息口径执行利率（实算）=" + d.path("finalRate").asText("-")
                                        + "%（合同上浮口径校验通过）"),
                        real("st2", "② 调用 repayment-plan-agent 更新应还总额与还款计划（罚息复利另计入应还总额）", "repayment-plan", deps("st1"),
                                ":8127/api/bai/repayment-plan/plan",
                                (sl, sm, sd) -> map("contractId", "J20250820009", "principal", 52000, "rate", 0.0385,
                                        "terms", 12, "method", "EQUAL_INSTALLMENT", "startDate", "2026-09-20"),
                                (sl, d) -> "应还计划更新（实算）：月供=" + d.path("monthlyPayment").asText("-")
                                        + "，罚息复利另计"),
                        realTask("st3", "? 条件分支：批量出账走 task-schedule-agent 日终批次（本笔单笔试算跳过，批量计息走日终批次）", deps("st2")),
                        human("st4", "⚑ 出账前人工确认：试算总额与核心日终跑批勾稽差异（本笔无差异；差异超阈值禁止直接出账）", deps("st2", "st3")),
                        realTextGen("st5", "③ 调用 text-gen-agent 生成罚息明细通知", deps("st4")),
                        realAudit("st6", "④ 调用 audit-log-agent 留痕（计算口径、批次、结果）", deps("st5"))),
                "回复 1 确认出账并回写应还总额，回复 2 先调口径复核",
                (sl, sm, sd) -> {
                    JsonNode pr = sd.get("st1");
                    double r = pr == null ? 0 : pr.path("finalRate").asDouble(0);
                    double penalty = 52000 * r / 100 / 360 * 18;
                    double compound = 1670 * r / 100 / 360 * 18;
                    return List.of(
                            block("计息明细",
                                    "逾期本金 52,000 元｜罚息口径执行利率（实算）：" + r + "%（合同上浮口径）",
                                    "罚息（实算）：" + String.format("%.2f", penalty) + " 元（52,000×日利率×18 天）｜复利（实算，基数 1,670 元）：" + String.format("%.2f", compound) + " 元",
                                    "应还总额＝逾期本金＋正常利息＋罚息复利（以出账确认单为准）"),
                            block("批量汇总",
                                    "单笔试算演示；批量出账走日终批次并勾稽（勾稽差异笔数 0）",
                                    "已核销借据自动剔除，不再计收常规罚息（走 S8-7 账销案存）"),
                            block("通知预览",
                                    "您名下贷款已逾期，罚息复利已按合同约定口径计收，明细见还款账单，请尽快存入还款卡避免影响征信。",
                                    "客户对罚息有异议的暂停该户出账，转 S8-11 豁免评估或人工核查"),
                            block("确认选项",
                                    "出账记录回写核心系统与信贷管理系统；客户还款后衔接 S8-1 入账",
                                    "回复 1 确认出账并回写应还总额，回复 2 先调口径复核"));
                }));

        // ============ S8-7 核销/呆账处理 ============
        REGISTRY.put("S8-7", new SceneScript(
                "S8-7", "writeoff-agent", "核销/呆账处理",
                List.of("核销", "呆账", "账销案存", "核销方案"),
                "您好，我是核销处理助手。请告诉我拟核销借据号和呆账认定依据（如法院终本裁定、破产终结裁定），我会先做合规校验、生成核销方案并走审批。",
                "本次将为您完成：1.认定统计 2.合规校验 3.方案生成 4.逐级审批 5.账务核销",
                List.of(
                        real("st1", "① 调用 metric-calc-agent 统计拟核销借据本息、减值计提、五级分类", "metric-calc", deps(),
                                ":8128/api/bai/metric/calc",
                                (sl, sm, sd) -> map("metricId", "LOAN_BALANCE", "params", map("orgId", "ORG-DEMO", "date", "2026-11-02")),
                                (sl, d) -> "拟核销口径统计（近似）=" + d.path("value").asText("-") + s(d.path("unit").asText(""), "")),
                        realCompliance("st2", "② 调用 compliance-check-agent 校验呆账认定条件是否齐全", deps("st1"), "ADMISSION", map("check", "文书,追索记录,年限")),
                        realTextGen("st3", "③ 调用 text-gen-agent 生成核销方案（核销本金/利息、账务处理分录）", deps("st2")),
                        human("st4", "⚑ 人工确认节点：逐级审批（资产保全→风险委→财务），终审通过才执行；核销审批未通过不执行账务处理", deps("st3")),
                        realCallExec("st5", "④ 调用 call-exec-agent 执行核销账务（转销减值与本金；核心账务接口联调待办：确定性仿真＋人工确认运行）", deps("st4"), "DEF_ACCT_DEDUCT_001", map("loanNo", "LN-S8-7-001", "amount", 980000)),
                        realTextGen("st6", "⑤ 调用 text-gen-agent 建立账销案存台账，生成继续追索登记", deps("st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 留痕（认定依据、审批链、账务凭证）", deps("st6"))),
                "回复 1 提交审批包，回复 2 退回补材料",
                (sl, sm, sd) -> List.of(
                        block("核销清单",
                                "借据 J20240310004｜本金 280,000 元（次级）｜利息 36,000 元｜认定依据：法院终本裁定（2026-08）",
                                s(sm.get("st1"), "本息统计：详见滚动日志")),
                        withExtraLines(statusBlock("合规校验结果",
                                "ok", "①终本裁定文书", "",
                                "ok", "②催收记录 3 次", "",
                                "ok", "③减值已计提 280,000 元", ""),
                                "校验通过",
                                "材料不齐列出缺项退回补正，不进入审批"),
                        block("审批进度",
                                "资产保全→风险委→财务｜当前节点：风险委审批中",
                                "核销不代表债务免除，终审通过后建立账销案存台账继续追索"),
                        block("确认选项",
                                "已核销借据重复提交幂等拦截；追索发现可供执行财产提示恢复追索",
                                "回复 1 提交审批包，回复 2 退回补材料"))));

        // ============ S8-8 还款异常处理 ============
        REGISTRY.put("S8-8", new SceneScript(
                "S8-8", "repayment-exception-agent", "还款异常处理",
                List.of("扣款失败", "还款异常", "补扣", "换卡", "还款卡变更"),
                "您好，我是还款异常处理助手。批扣失败会自动送到这里；您也可以直接说'查这笔失败的扣款'或'客户换卡了，更新还款卡'。",
                "本次将为您完成：1.流水解析 2.异常分类 3.核心对账 4.补扣处置 5.通知留痕",
                List.of(
                        real("st1", "① 调用 ner-agent 解析失败流水要素（借据、金额、原因码、时间）", "ner", deps(),
                                ":8111/api/bai/ner/extract",
                                (sl, sm, sd) -> map("text", "批扣失败流水 K20261102001：借据 J20250318007，应扣 23,375 元，失败原因码 BAL_NO_ENOUGH（余额不足），失败时间 2026-11-02 06:30。",
                                        "scene", "FILING"),
                                (sl, d) -> "抽取要素 " + d.path("entities").size() + " 项"),
                        realEvent("st2", "② 调用 event-subscribe-agent 分类异常类型并匹配处置策略", deps("st1"), "call.degraded", "S8-8-st2", map("callId", "CE-S8-8-st2")),
                        real("st3", "∥ ③ 调用 data-validate-agent 与核心对账，校验失败流水要素齐套且未入账不重复扣", "data-validate", deps("st1"),
                                ":8129/api/bai/validate/validate",
                                (sl, sm, sd) -> map("templateId", "T_DOC",
                                        "data", map("custName", "张伟", "docType", "DEBIT_FAIL_RECORD",
                                                "fileKey", "oss://demo/S88/debit-fail-001.pdf",
                                                "loanNo", "J20250318007", "amount", 23375,
                                                "reasonCode", "BAL_NO_ENOUGH", "failTime", "2026-11-02 06:30")),
                                (sl, d) -> "T_DOC 校验：" + d.path("result").asText("-")
                                        + "（通过 " + d.path("summary").path("passed").asInt(0) + " 项）"),
                        realCallExec("st4", "? 条件分支：余额不足且客户已存足→call-exec-agent 补扣；账户冻结/销户等司法异常→立即转人工，不自动处置（核心账务联调待办：确定性仿真＋人工确认运行）", deps("st2", "st3"), "DEF_ACCT_DEDUCT_001", map("loanNo", "LN-S8-8-001", "amount", 360000)),
                        realTextGen("st5", "④ 调用 text-gen-agent 生成扣款失败提醒（提醒文案为内置演示模板）", deps("st4")),
                        realAudit("st6", "⑤ 调用 audit-log-agent 留痕（异常原因、处置、结果）", deps("st5"))),
                "回复 1 批量补扣，回复 2 逐笔人工处理",
                (sl, sm, sd) -> List.of(
                        withExtraLines(donutBlock("异常清单", "笔",
                                "余额不足", 9, "账户销户", 2, "核心超时", 1),
                                "今日批扣失败 12 笔",
                                s(sm.get("st1"), "失败流水要素：详见滚动日志")),
                        block("处置结果",
                                "超时 1 笔对账未入账、补扣成功｜余额不足 9 笔发送提醒并约定明日 17:00 补扣｜销户 2 笔转人工换卡",
                                "补扣不超过制度约定次数；连续 3 日未存足自动转 S8-5 逾期催收"),
                        block("客户通知预览",
                                "【XX银行】您的贷款扣款未成功（余额不足），请于 X 日前存足应还额，避免逾期影响征信。如有疑问请回电 400-XXX。",
                                "按消保要求清晰告知原因与补救方式，不频繁骚扰客户"),
                        block("确认选项",
                                "对账不平（疑似已入账）禁止补扣，挂起人工查账；客户投诉暂停一切自动处置",
                                "回复 1 批量补扣，回复 2 逐笔人工处理"))));

        // ============ S8-9 风险处置归集与清收管理 ============
        REGISTRY.put("S8-9", new SceneScript(
                "S8-9", "risk-disposal-agent", "风险处置归集与清收管理",
                List.of("清收", "风险处置", "不良处置", "处置方案", "清收报告"),
                "您好，我是风险处置助手。请告诉我借据号或要处置的不良组合，我会结合担保、还款意愿生成清收方案并列进度。",
                "本次将为您完成：1.方案生成 2.清收排期 3.风险监测 4.进度归集 5.报告审批",
                List.of(
                        realTextGen("st1", "① 调用 text-gen-agent 生成清收处置方案（路径建议、预计回收率、时间表）", deps()),
                        realTask("st2", "② 调用 task-schedule-agent 排定清收任务与节点", deps("st1")),
                        realRiskSignal("st3", "∥ ③ 调用 risk-signal-agent 监测新增风险信号（财产转移、涉诉）", deps("st1"), "C-S8-9", List.of("JUDICIAL", "FLOW"), List.of("疑似财产转移", "新增涉诉")),
                        realTodo("st4", "∥ ④ 调用 todo-agent 归集历史清收进度", deps("st1"), "COLLECT", "RECOVER-S8-9", "S8-9 st4 待办跟踪", "COLL001"),
                        real("st5", "⑤ 调用 summary-agent 生成本期清收进展摘要", "summary", deps("st4"),
                                ":8115/api/bai/summary/generate",
                                (sl, sm, sd) -> map("text", "恒顺机械 420 万元次级贷款清收进展：先行自行催收 30 天，客户承诺首笔还款 50 万元；同步启动抵押厂房评估；若 30 天内回款不足则诉讼处置抵押物；预计回收率 85%-90%。",
                                        "mode", "bullet", "maxChars", 150, "extractKeyInfo", true),
                                (sl, d) -> "清收摘要 summaryId=" + d.path("summaryId").asText("-")
                                        + "，压缩比=" + d.path("stats").path("ratio").asText("-")),
                        real("st6", "⑥ 调用 report-gen-agent 生成清收处置报告（清收报告模板未配置，以经营管理月报模板近似）", "report-gen", deps("st5"),
                                ":8131/api/bai/report/generate",
                                (sl, sm, sd) -> map("templateId", "T_MGMT_MONTHLY",
                                        "params", map("orgId", "ORG-DEMO", "date", "2026-10-31",
                                                "granularity", "ORG",
                                                "customer", "恒顺机械制造有限公司"),
                                        "format", "PDF"),
                                (sl, d) -> "报告=" + d.path("reportId").asText("-")
                                        + "，文件=" + d.path("fileId").asText("-")),
                        human("st7", "⚑ 人工确认节点：重大处置方案提交资产保委会审批；未经审批不得执行处置动作", deps("st6")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 留痕（方案、审批链、报告）", deps("st7"))),
                "回复 1 导出清收报告，回复 2 调整方案",
                (sl, sm, sd) -> List.of(
                        block("风险组合概览",
                                "恒顺机械｜次级｜本金 420 万元｜抵押厂房存续｜预计回收率 85%-90%",
                                s(sm.get("st5"), "清收摘要：详见滚动日志")),
                        block("处置方案",
                                "①先行自行催收 30 天（客户愿还 50 万）②同步启动厂房评估 ③30 天回款不足则诉讼处置抵押物",
                                s(sm.get("st2"), "清收排期：详见滚动日志")),
                        block("进度看板",
                                "11-15 首笔回款核验｜12-01 厂房评估完成｜滞后节点 0（超 30 天预警督办）",
                                "财产线索指向新增可执行财产时提示及时诉讼保全"),
                        block("报告与选项",
                                "重大处置方案已提交资产保委会审批",
                                "回复 1 导出清收报告，回复 2 调整方案；涉及抵债转 S8-10、核销转 S8-7、减免转 S8-11"))));

        // ============ S8-10 抵债资产接收与管理 ============
        REGISTRY.put("S8-10", new SceneScript(
                "S8-10", "debt-asset-agent", "抵债资产接收与管理",
                List.of("抵债资产", "以物抵债", "抵债接收", "抵债"),
                "您好，我是抵债资产管理助手。请告诉我借据号和要接收的抵债资产（房产/设备/股权等），我会先估值、生成接收方案并走审批。",
                "本次将为您完成：1.资产估值 2.接收方案 3.保管监测 4.审批确认 5.协议入账",
                List.of(
                        real("st1", "① 调用 valuation-agent 对抵债资产估值（工业园区厂房，市场比较法），确定抵债作价", "valuation", deps(),
                                ":8124/api/bai/valuation/value",
                                (sl, sm, sd) -> map("assetType", "REAL_ESTATE", "method", "MARKET", "assetRef", "工业园区厂房",
                                        "params", map("area", 2000, "unitPrice", 4100, "comparables", 3), "valueDate", "2026-11-02"),
                                (sl, d) -> "抵债资产估值（实算）" + d.path("value").asText("-") + " 元（市场法）"),
                        realTextGen("st2", "② 调用 text-gen-agent 生成接收方案（抵债金额、债权余额差额、过户安排）", deps("st1")),
                        realRiskSignal("st3", "∥ ③ 调用 risk-signal-agent 监测资产保管与贬值风险", deps("st2"), "C-S8-10", List.of("INDUSTRY", "PUBLIC"), List.of("押品保管风险", "市场贬值")),
                        human("st4", "⚑ 人工确认节点：接收方案与作价经有权审批（保委会/财务）；作价高于债权余额倒挂须说明差额处理", deps("st2", "st3")),
                        realTextGen("st5", "④ 调用 text-gen-agent 生成以物抵债协议与接收确认函（电子签约联调待办）", deps("st4")),
                        real("st6", "⑤ 调用 report-gen-agent 生成接收入账报告（接收报告模板未配置，以经营管理月报模板近似）", "report-gen", deps("st5"),
                                ":8131/api/bai/report/generate",
                                (sl, sm, sd) -> map("templateId", "T_MGMT_MONTHLY",
                                        "params", map("orgId", "ORG-DEMO", "date", "2026-11-02",
                                                "granularity", "ORG",
                                                "customer", "恒顺机械制造有限公司"),
                                        "format", "PDF"),
                                (sl, d) -> "接收报告=" + d.path("reportId").asText("-")
                                        + "，文件=" + d.path("fileId").asText("-")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 留痕（评估报告、协议、审批）", deps("st6"))),
                "回复 1 确认接收并推进过户，回复 2 调整作价",
                (sl, sm, sd) -> {
                    JsonNode v = sd.get("st1");
                    double val = v == null ? 0 : v.path("value").asDouble(0);
                    return List.of(
                            block("抵债资产概览",
                                    "工业园区厂房｜评估值（实算）：" + String.format("%.2f", val) + " 元（市场法）",
                                    "抵债作价按债权余额 6,800,000 元接收，余值差额按协议退还债务人或另行结算，不高估抵债"),
                            block("接收方案",
                                    "11-20 前完成产权过户｜过户后入账抵债资产｜差额部分衔接 S8-7 核销",
                                    s(sm.get("st2"), "接收方案：详见滚动日志")),
                            block("协议预览",
                                    "以物抵债协议：抵债作价、过户安排、余值结算、税费承担",
                                    "权属不清或在先查封的暂停接收，法律核查后处理"),
                            block("确认选项",
                                    "抵债台账与接收记录回写资产保全系统；持有期间定期重估，超期未处置预警督办",
                                    "回复 1 确认接收并推进过户，回复 2 调整作价"));
                }));

        // ============ S8-11 利息本金豁免管理 ============
        REGISTRY.put("S8-11", new SceneScript(
                "S8-11", "interest-exemption-agent", "利息本金豁免管理",
                List.of("罚息减免", "利息减免", "本金减免", "减免", "豁免"),
                "您好，我是减免管理助手。请告诉我借据号、想申请减免什么（罚息/利息/部分本金）、困难依据，我会先做政策校验、测算减免金额并走审批。",
                "本次将为您完成：1.政策校验 2.减免测算 3.方案生成 4.终审授权 5.账务处理",
                List.of(
                        realCompliance("st1", "① 调用 compliance-check-agent 校验减免政策适用性", deps(), "ADMISSION", map("check", "政策清单,材料")),
                        real("st2", "② 调用 pricing-calc-agent 测算拟减免金额（罚息复利全免口径试算）", "pricing-calc", deps("st1"),
                                ":8122/api/bai/pricing/quote",
                                (sl, sm, sd) -> map("productId", "LN-EXEMPT-001", "amount", 86000, "term", 1,
                                        "rateType", "FLOATING", "collateral", "信用", "customerRating", "B"),
                                (sl, d) -> "罚息口径执行利率（实算）=" + d.path("finalRate").asText("-")
                                        + "%，减免基数测算完成"),
                        real("st3", "③ 调用 repayment-plan-agent 试算减免后结清金额（本金＋正常利息实算）", "repayment-plan", deps("st2"),
                                ":8127/api/bai/repayment-plan/plan",
                                (sl, sm, sd) -> map("contractId", "J20250408019", "principal", 86000, "rate", 0.0345,
                                        "terms", 12, "method", "EQUAL_INSTALLMENT", "startDate", "2026-04-08"),
                                (sl, d) -> "正常利息合计（实算）=" + d.path("totalInterest").asText("-")),
                        human("st4", "⚑ 人工确认节点：逐级审批，本金减免须上报有权人终审；超出本级权限自动升级上报，不擅自审批", deps("st3")),
                        realCallExec("st5", "④ 调用 call-exec-agent 执行减免账务（转销对应利息/本金科目；核心账务联调待办：确定性仿真＋人工确认运行）", deps("st4"), "DEF_ACCT_DEDUCT_001", map("loanNo", "LN-S8-11-001", "amount", 250000)),
                        realTextGen("st6", "⑤ 调用 text-gen-agent 生成减免协议与结清证明", deps("st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 留痕（政策依据、审批链、账务凭证）", deps("st6"))),
                "回复 1 提交终审，回复 2 补充材料",
                (sl, sm, sd) -> {
                    JsonNode p = sd.get("st3");
                    double ti = p == null ? 0 : p.path("totalInterest").asDouble(0);
                    double pay = 86000 + ti;
                    return List.of(
                            block("减免测算",
                                    "应还本金 86,000 元｜正常利息（实算）：" + String.format("%.2f", ti) + " 元｜拟减免罚息复利 612 元",
                                    "减免后实付（实算）：" + String.format("%.2f", pay) + " 元（以终审确认单为准）"),
                            block("政策依据与材料校验",
                                    "符合行内'特殊困难客户罚息减免'政策｜困难证明已上传核验",
                                    "不在政策清单内不生成方案；材料不全列出缺项退回补正"),
                            block("审批进度",
                                    "支行长→信审→有权人终审（本金减免须上收权限）",
                                    "减免测算与应还明细勾稽不平挂起人工核对"),
                            block("协议/结清证明预览",
                                    "结清证明：客户刘芳结清 J20250408019 全部款项，减免明细与审批链留痕备查",
                                    "回复 1 提交终审，回复 2 补充材料；减免后未按约结清将恢复全额计息"));
                }));

        // ============ S9-1 计提处理（利息/减值/应收应付） ============
        REGISTRY.put("S9-1", new SceneScript(
                "S9-1", "accrual-processing-agent", "计提处理（利息/减值/应收应付）",
                List.of("利息计提", "减值计提", "月末计提", "ECL计提", "计提"),
                "您好，我是计提处理助手。您可以直接说'做 10 月末全行贷款利息计提和减值计提'，我会自动完成数据校验、计提试算、批量执行与结果汇总。",
                "本次将为您完成：1.数据接入 2.基数校验 3.计提试算 4.分录生成 5.过账确认",
                List.of(
                        real("st1", "① 调用 data-query-agent 抽取计提区间内全部贷款合同、利率、逾期天数与分类结果（信贷台账未联调，以 ODS 白名单表近似）", "data-query", deps(),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        realQuality("st2", "② 调用 data-quality-agent 校验计提基数完整性", deps("st1"), "dwd_risk.provision"),
                        real("st3", "③ 调用 pricing-calc-agent 确认本期利息计提口径利率", "pricing-calc", deps("st2"),
                                ":8122/api/bai/pricing/quote",
                                (sl, sm, sd) -> map("productId", "LN-ACCRUAL-001", "amount", 1000000, "term", 1,
                                        "rateType", "FLOATING", "collateral", "抵押", "customerRating", "A"),
                                (sl, d) -> "计提口径利率（实算）=" + d.path("finalRate").asText("-") + "%"),
                        realModelInfer("st4", "∥ ④ 调用 model-infer-agent 按 ECL 三阶段输出各阶段 PD/LGD/ECL 计提金额", deps("st2"), "ECL", "CLUSTER", "ECL-CL-2026-V1", null, map("exposure", 12000000, "pd", 0.032)),
                        real("st5", "∥ ⑤ 调用 knowledge-retrieve-agent 核对本期新金融工具准则口径与参数版本", "knowledge-retrieve", deps("st1"),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "新金融工具准则 金融工具确认和计量 ECL 三阶段 计提口径", "topK", 3),
                                (sl, d) -> "准则依据命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        realTask("st6", "⑥ 调用 task-schedule-agent 批量生成计提会计分录（借：利息支出/资产减值损失，贷：应收利息/贷款损失准备；批量执行未上线，联调待办：确定性仿真＋人工确认运行）", deps("st2", "st3", "st4")),
                        human("st7", "⚑ 人工确认节点：会计主管终审试算底稿（计提总额、阶段分布、异常户清单）；减值阶段跨级调整须双人复核；超时 4 小时自动挂起提醒", deps("st6")),
                        realEvent("st8", "? 条件分支：仅⑥有失败户时调用 event-subscribe-agent 重试或挂起", deps("st7"), "call.failed", "S9-1-st8", map("callId", "CE-S9-1-st8")),
                        realAudit("st9", "⑦ 调用 audit-log-agent 记录计提参数、底稿、过账凭证号", deps("st8"))),
                "回复 1 确认过账，回复 2 调整范围，回复 3 导出底稿",
                (sl, sm, sd) -> List.of(
                        withExtraLines(donutBlock("计提总览", "万",
                                        "阶段一", 2340, "阶段二", 860, "阶段三", 1120),
                                "利息计提 3,865 万元｜减值计提合计 4,320 万元｜应收应付挂账 86 万元",
                                "数据来源：核心系统＋信贷管理系统",
                                s(sm.get("st3"), "计提口径利率：详见滚动日志")),
                        block("异常户清单",
                                "17 笔利率缺失合同，已列示合同号与支行，建议补录后重跑（E12001 不静默跳过）",
                                "分类与 ECL 阶段映射矛盾户已挂起，待人工核对五级分类"),
                        withExtraLines(barsBlock("会计分录预览", "万",
                                        "利息支出（借）", 3865, "资产减值损失（借）", 4320,
                                        "应收利息（贷）", 3865, "贷款损失准备（贷）", 4320),
                                "借贷平衡，勾稽通过"),
                        block("确认区",
                                "试算底稿已生成，导出可保存；补录异常户后可先过账其余合同、异常户挂起",
                                "回复 1 确认过账（衔接 S9-3），回复 2 调整范围，回复 3 导出底稿"))));

        // ============ S9-2 结息处理（周期结息/转实收） ============
        REGISTRY.put("S9-2", new SceneScript(
                "S9-2", "interest-settle-agent", "结息处理（周期结息/转实收）",
                List.of("对公贷款结息", "季末结息", "结息", "转实收", "周期结息"),
                "您好，我是结息处理助手。直接说'跑对公贷款 9 月 20 日季末结息'，我会自动完成积数核对、结息试算、批量入账与通知生成。",
                "本次将为您完成：1.结息户接入 2.结息试算 3.批量入账 4.转实收过账 5.计划重排",
                List.of(
                        real("st1", "① 调用 data-query-agent 拉取结息户清单、积数、已计提应收利息余额（信贷台账未联调，以 ODS 白名单表近似）", "data-query", deps(),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        real("st2", "② 调用 pricing-calc-agent 确认本期结息计息口径利率（积数×利率÷360）", "pricing-calc", deps("st1"),
                                ":8122/api/bai/pricing/quote",
                                (sl, sm, sd) -> map("productId", "LN-SETTLE-001", "amount", 1000000, "term", 3,
                                        "rateType", "FLOATING", "collateral", "抵押", "customerRating", "A"),
                                (sl, d) -> "结息计息口径利率（实算）=" + d.path("finalRate").asText("-") + "%"),
                        realTextGen("st3", "∥ ③ 调用 text-gen-agent 预生成结息通知文案", deps("st1")),
                        human("st4", "⚑ 人工确认节点：核算员确认试算总额与勾稽表；与计提勾稽差异超过 0.1% 必须核算主管确认方可入账", deps("st2", "st3")),
                        realTask("st5", "④ 调用 task-schedule-agent 批量执行结息交易（成功 8,631 户、失败 11 户；批量执行未上线，联调待办：确定性仿真＋人工确认运行）", deps("st4")),
                        realCallExec("st6", "⑤ 调用 call-exec-agent 完成转实收过账（借：应收利息，贷：利息收入冲转；核心账务接口联调待办：确定性仿真＋人工确认运行）", deps("st5"), "DEF_ACCT_DEDUCT_001", map("loanNo", "LN-S9-2-001", "amount", 1200000)),
                        real("st7", "⑥ 调用 repayment-plan-agent 对分期贷款重排下期还款计划", "repayment-plan", deps("st5"),
                                ":8127/api/bai/repayment-plan/plan",
                                (sl, sm, sd) -> map("contractId", "J20260920031", "principal", 500000, "rate", 0.0385,
                                        "terms", 24, "method", "EQUAL_INSTALLMENT", "startDate", "2026-10-20"),
                                (sl, d) -> "下期还款计划重排（实算）：月供=" + d.path("monthlyPayment").asText("-")),
                        realEvent("st8", "? 条件分支：仅④有失败户时调用 event-subscribe-agent 重试上限 3 次后挂起", deps("st6"), "call.degraded", "S9-2-st8", map("callId", "CE-S9-2-st8")),
                        realAudit("st9", "⑦ 调用 audit-log-agent 记录结息批次、凭证、通知发送记录", deps("st8"))),
                "回复 1 重跑失败户，回复 2 生成客户对账单，回复 3 衔接总账对账",
                (sl, sm, sd) -> List.of(
                        withExtraLines(donutBlock("结息总览", "户",
                                        "入账成功", 8631, "失败", 11),
                                "结息 8,642 户｜应付客户利息 1.26 亿元｜转实收冲减应收利息 1.26 亿元",
                                s(sm.get("st2"), "计息口径：详见滚动日志")),
                        block("勾稽表",
                                "与 9 月 19 日计提差异 0.02%（容差 0.1% 内，允许入账）",
                                "差异明细按户列示，超容差自动挂起转核算主管"),
                        withExtraLines(barsBlock("失败户清单", "户",
                                        "账户状态异常", 8, "积数缺失", 3),
                                "支持一键重跑",
                                "同一批次号重复提交幂等拦截，拒绝重复入账（E12008）"),
                        block("确认与后续",
                                "结息凭证汇总表可导出保存；通知发送状态已回写消息平台",
                                "回复 1 重跑失败户，回复 2 生成客户对账单（S9-5），回复 3 衔接总账对账（S9-3）"))));

        // ============ S9-3 账务记账与总账 ============
        REGISTRY.put("S9-3", new SceneScript(
                "S9-3", "ledger-posting-agent", "账务记账与总账",
                List.of("过账", "总账", "总分账对账", "冲正"),
                "您好，我是记账与总账助手。直接说'过账今天放款批次并做总分账对账'，我会自动完成分录校验、批量过账与差异定位。",
                "本次将为您完成：1.分录接入 2.平衡校验 3.批量过账 4.总账汇总 5.总分对账",
                List.of(
                        real("st1", "① 调用 data-query-agent 拉取各业务批次（放款/还款/计提/结息）待过账分录", "data-query", deps(),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        real("st2", "② 调用 data-validate-agent 校验分录借贷平衡、科目合法性、日期与附件完整性", "data-validate", deps("st1"),
                                ":8129/api/bai/validate/validate",
                                (sl, sm, sd) -> map("templateId", "T_DOC",
                                        "data", map("custName", "ORG-DEMO", "docType", "VOUCHER_BATCH",
                                                "fileKey", "oss://demo/S93/voucher-batch-20261102.zip",
                                                "voucherCount", 1286, "debitTotal", 432000000, "creditTotal", 432000000,
                                                "bizDate", "2026-11-02")),
                                (sl, d) -> "T_DOC 校验：" + d.path("result").asText("-")
                                        + "（通过 " + d.path("summary").path("passed").asInt(0) + " 项）"),
                        realTask("st3", "③ 调用 task-schedule-agent 批量过账至总账（校验不过不允许过账；批量执行未上线，联调待办：确定性仿真＋人工确认运行）", deps("st2")),
                        real("st4", "④ 调用 metric-calc-agent 汇总当日各科目总账发生额与余额", "metric-calc", deps("st3"),
                                ":8128/api/bai/metric/calc",
                                (sl, sm, sd) -> map("metricId", "LOAN_BALANCE", "params", map("orgId", "ORG-DEMO", "date", "2026-11-02")),
                                (sl, d) -> "当日总账汇总（近似）=" + d.path("value").asText("-") + s(d.path("unit").asText(""), "")),
                        realEvent("st5", "⑤ 调用 event-subscribe-agent 执行分户账与总账对账，不平则冲正重过", deps("st3", "st4"), "call.failed", "S9-3-st5", map("callId", "CE-S9-3-st5")),
                        human("st6", "⚑ 人工确认节点：任何冲正、跨日调整、科目互转必须会计主管确认，双人复核留痕", deps("st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录凭证号、过账流水、对账结果", deps("st6"))),
                "回复 1 自动冲正重过差异户，回复 2 导出差异清单人工核对",
                (sl, sm, sd) -> List.of(
                        withExtraLines(barsBlock("过账总览", "笔",
                                        "待过账凭证", 1286, "校验通过", 1279, "过账成功", 1279, "挂起", 7),
                                "同笔业务重复提交幂等拦截，不重复入账"),
                        block("对账结果",
                                "总分账基本相符｜城东支行应收利息户差异 126.00 元（超容差已挂起）",
                                s(sm.get("st4"), "总账发生额汇总：详见滚动日志")),
                        block("挂起凭证清单",
                                "7 笔缺附件，补传后可重过；借贷不平衡或科目非法的拒绝过账并列示问题分录",
                                "冲正交易找不到原交易流水时转人工核查，不自动冲正（E12012）"),
                        block("后续引导",
                                "过账状态与对账结果已回写核算管理系统与总账系统",
                                "回复 1 自动冲正重过差异户，回复 2 导出差异清单人工核对，回复 3 衔接监管报送取数（S9-4）"))));

        // ============ S9-4 监管报送（EAST/1104/金数/大额风险暴露） ============
        REGISTRY.put("S9-4", new SceneScript(
                "S9-4", "regulatory-reporting-agent", "监管报送（EAST/1104/金数/大额风险暴露）",
                List.of("监管报送", "EAST报送", "EAST", "1104", "金数", "大额风险暴露", "报送"),
                "您好，我是监管报送助手。直接说'报 10 月 EAST 数据'，我会自动完成取数、校验、差异处理与文件生成，正式提交前请您确认。",
                "本次将为您完成：1.口径查询 2.数据抽取 3.校验规则 4.脱敏输出 5.报送确认",
                List.of(
                        real("st1", "① 调用 knowledge-retrieve-agent 拉取本期报送模板与口径规则（含普惠小微新旧口径并存说明）", "knowledge-retrieve", deps(),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "EAST 1104 报送模板 口径规则 普惠小微新旧口径 地区码 兵团", "topK", 3),
                                (sl, d) -> "制度依据命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        real("st2", "② 调用 data-query-agent 抽取报送指标数据", "data-query", deps("st1"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        realQuality("st3", "③ 调用 data-quality-agent 执行完整性/准确性/时效性校验与跨口径一致性检查", deps("st2"), "dwd_fin.statement"),
                        realAml("st4", "∥ ④ 调用 aml-agent 比对大额风险暴露与大额交易监测数据", deps("st2"), "T1D", List.of(map("amount", 860000, "counterparty", "恒达建材商行", "direction", "IN", "purpose", "销售回款", "cash", false, "time", "2026-11-04"), map("amount", 860000, "counterparty", "上游供应商", "direction", "OUT", "purpose", "货款支付", "cash", false, "time", "2026-11-04"))),
                        realMask("st5", "⑤ 调用 sensitive-mask-agent 按报送规范脱敏", deps("st3"), "TEXT", "借款人张伟 身份证 310101199001011234 手机 13800001111", "EXPORT"),
                        realTask("st6", "⑥ 调用 task-schedule-agent 生成报送文件并本地预提交（正式报送接口联调待办：确定性仿真＋人工确认运行）", deps("st5")),
                        human("st7", "⚑ 人工确认节点：报送专员确认校验报告与文件清单后正式提交；同一报送期同种类重复提交幂等拦截", deps("st6")),
                        realEvent("st8", "? 条件分支：报送失败调用 event-subscribe-agent 自动重报（重试 2 次）或挂起提示人工通道", deps("st7"), "call.degraded", "S9-4-st8", map("callId", "CE-S9-4-st8")),
                        realAudit("st9", "⑦ 调用 audit-log-agent 记录报送批次、文件哈希、回执", deps("st8"))),
                "回复 1 自动修复格式类异常后确认提交，回复 2 导出校验报告人工裁定",
                (sl, sm, sd) -> List.of(
                        block("报送总览",
                                "EAST 记录 1.2 万条（已生成待确认）｜1104 报表 23 张（已生成待确认）｜金数/大额风险暴露（本期无报送任务）",
                                "文件生成后先本地预提交，人工确认后才正式报送，回执自动归档"),
                        statusBlock("校验报告",
                                "warn", "EAST 规则校验", "通过 216 条规则、异常 4 条（3 条日期格式、1 条兵团客户地区码映射缺失）",
                                "warn", "1104 规则校验", "通过 88 条规则、异常 1 条（新口径客户 2 户归属分段待确认）"),
                        withExtraLines(donutBlock("新旧口径并存说明", "户",
                                        "普惠小微新口径客户", 1860, "旧口径客户", 320),
                                "新口径 12.4 亿元、旧口径 2.1 亿元，按客户名单分段列示",
                                "跨口径冲突无法自动消解的挂起差异记录，人工裁定（E12015）"),
                        block("确认区",
                                "报送文件与校验报告可导出保存备查；脱敏不达标的暂停生成文件",
                                "回复 1 自动修复格式类异常后确认提交，回复 2 导出校验报告人工裁定"))));

        // ============ S9-5 报表生成与指标分析 ============
        REGISTRY.put("S9-5", new SceneScript(
                "S9-5", "report-analysis-agent", "报表生成与指标分析",
                List.of("对公贷款经营报表", "经营报表", "报表分析", "指标分析", "经营分析", "同环比"),
                "您好，我是报表分析助手。直接说'城东支行 10 月对公贷款余额和不良率，跟 9 月比'，我会自动出表并配图表。",
                "本次将为您完成：1.口径取数 2.指标计算 3.同环比分析 4.图表生成 5.报表输出",
                List.of(
                        real("st1", "① 调用 data-query-agent 按维度与时间范围取数（城东支行对公贷款，10 月）", "data-query", deps(),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_core", "table", "loan_balance_daily",
                                        "fields", List.of("branch_code", "loan_balance")),
                                null),
                        real("st2", "② 调用 metric-calc-agent 计算核心指标（余额、不良率、收益率）", "metric-calc", deps("st1"),
                                ":8128/api/bai/metric/calc",
                                (sl, sm, sd) -> map("metricId", "LOAN_BALANCE", "params", map("orgId", "ORG-DEMO", "date", "2026-10-31")),
                                (sl, d) -> "核心指标测算值=" + d.path("value").asText("-") + s(d.path("unit").asText(""), "") + "（确定性仿真）"),
                        real("st3", "∥ ③ 调用 trend-analyze-agent 做多维分布与同环比分析（对公贷款余额近 6 个月）", "trend-analyze", deps("st1"),
                                ":8132/api/bai/trend/analyze",
                                (sl, sm, sd) -> map("seriesId", "CORP_LOAN_BALANCE", "window", "M6",
                                        "methods", List.of("trend", "mom", "yoy"),
                                        "points", List.of(
                                                map("date", "2026-05", "value", 82.4), map("date", "2026-06", "value", 83.1),
                                                map("date", "2026-07", "value", 84.0), map("date", "2026-08", "value", 84.7),
                                                map("date", "2026-09", "value", 84.9), map("date", "2026-10", "value", 86.5))),
                                (sl, d) -> "同环比分析完成（analyzeId=" + d.path("analyzeId").asText("-")
                                        + "，环比 " + d.path("mom").path("pct").asText("-") + "）"),
                        realStructure("st4", "∥ ④ 调用 structure-gen-agent 生成趋势图与分布图", deps("st2", "st3"), "SCHEMA_REPORT_OUTLINE_V1", "TEXT", "核算趋势与分布", "JSON"),
                        real("st5", "⑤ 调用 summary-agent 生成分析结论（同比变动、异常点）", "summary", deps("st3"),
                                ":8115/api/bai/summary/generate",
                                (sl, sm, sd) -> map("text", "城东支行 10 月对公贷款余额 86.5 亿元，环比增长 2.1%、同比增长 8.6%，制造业投放增长较快；不良率 1.42%，环比上升 0.05 个百分点，其中批发零售业不良率环比上升 0.3 个百分点，涉及不良客户 3 户，建议关注并核查。",
                                        "mode", "bullet", "maxChars", 150, "extractKeyInfo", true),
                                (sl, d) -> "分析结论 summaryId=" + d.path("summaryId").asText("-")),
                        real("st6", "⑥ 调用 report-gen-agent 汇总生成报表文件（经营管理月报模板）", "report-gen", deps("st5"),
                                ":8131/api/bai/report/generate",
                                (sl, sm, sd) -> map("templateId", "T_MGMT_MONTHLY",
                                        "params", map("orgId", "ORG-DEMO", "date", "2026-10-31", "granularity", "ORG"),
                                        "format", "PDF"),
                                (sl, d) -> "报告=" + d.path("reportId").asText("-")
                                        + "，文件=" + d.path("fileId").asText("-")
                                        + "，三校验=" + d.path("checks").path("overall").asText("-")),
                        realAudit("st7", "⑦ 调用 audit-log-agent 记录查询口径与取数时间", deps("st6"))),
                "回复 1 下钻客户明细，回复 2 导出 Excel，回复 3 换个维度再看",
                (sl, sm, sd) -> List.of(
                        block("指标总览表",
                                "对公贷款余额 86.5 亿元（环比 +2.1%、同比 +8.6%）｜不良率 1.42%（环比 +0.05pp、同比 -0.18pp）｜收益率 4.18%（环比持平）",
                                "口径版本：本行指标字典 2026Q4 版，口径变更已标注"),
                        block("图表",
                                "余额趋势图、行业分布图见前端展示（可放大查看）",
                                s(sm.get("st4"), "图表生成：占位演示")),
                        block("分析结论",
                                "批发零售业不良率环比上升 0.3 个百分点，建议关注",
                                s(sm.get("st5"), "分析结论：详见滚动日志")),
                        block("后续引导",
                                "报表与图表可导出保存；异常指标已标注回写监测台账",
                                "回复 1 下钻批发零售业明细并衔接贷后核查（S7-1），回复 2 导出 Excel，回复 3 换个维度再看"))));

        // ============ S9-6 税务与利息发票处理 ============
        REGISTRY.put("S9-6", new SceneScript(
                "S9-6", "tax-invoice-agent", "税务与利息发票处理",
                List.of("利息发票", "开票", "红冲", "增值税", "发票"),
                "您好，我是税务发票助手。直接说'给东城商贸有限公司开 9 季度利息发票'，我会自动核税、拆含税金额并生成开票信息。",
                "本次将为您完成：1.税率核对 2.台账接入 3.价税拆分 4.开票确认 5.留痕通知",
                List.of(
                        real("st1", "① 调用 knowledge-process-agent 核对当期贷款服务增值税率与免税政策", "knowledge-process", deps(),
                                ":8117/api/bai/knowledge/process",
                                (sl, sm, sd) -> map("docId", "DOC-S96-001",
                                        "text", "贷款服务增值税率 6%；小规模纳税人与免税政策按最新政策执行，本客户无免税情形。",
                                        "source", "policy"),
                                null),
                        real("st2", "∥ ② 调用 data-query-agent 拉取客户实收利息台账与历史开票记录", "data-query", deps("st1"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        real("st3", "③ 调用 pricing-calc-agent 确认本期应税利息收入口径（税额=含税÷1.06×6%）", "pricing-calc", deps("st2"),
                                ":8122/api/bai/pricing/quote",
                                (sl, sm, sd) -> map("productId", "LN-TAX-001", "amount", 212000, "term", 3,
                                        "rateType", "FIXED", "collateral", "信用", "customerRating", "A"),
                                (sl, d) -> "应税口径确认（实算利率=" + d.path("finalRate").asText("-") + "%），价税拆分按 6% 执行"),
                        realOcr("st4", "? 条件分支：红冲/首次索票调用 ocr-agent 核验客户税务资料或原发票要素", deps("st2"), "TAX_DOC", "IMG-S96-01"),
                        human("st5", "⚑ 人工确认节点：税务专员确认开票金额、税率、抬头信息；单笔 50 万元以上开票双人复核", deps("st3", "st4")),
                        realCallExec("st6", "④ 调用 call-exec-agent 完成开票交易（税务开票接口联调待办：确定性仿真＋人工确认运行；发票号 04412876）", deps("st5"), "DEF_NOTIFY_001", map("templateCode", "INVOICE_ISSUED", "receiver", "TAX-SYS")),
                        realTextGen("st7", "⑤ 调用 text-gen-agent 生成开票通知", deps("st6")),
                        realAudit("st8", "⑥ 调用 audit-log-agent 记录发票号、金额、税率、红冲关联", deps("st7"))),
                "回复 1 确认开票，回复 2 调整金额，回复 3 取消",
                (sl, sm, sd) -> {
                    double gross = 212000;
                    double tax = gross / 1.06 * 0.06;
                    double net = gross - tax;
                    return List.of(
                            withExtraLines(donutBlock("开票信息预览", "元",
                                            "不含税", net, "税额（实算）", tax),
                                    "抬头：东城商贸有限公司｜税号：91110105MA01C8XY2K｜期间：2026 年 7—9 月",
                                    "含税利息收入 212,000.00 元｜价税合计 212,000.00 元（212,000÷1.06×6%）"),
                            block("税务核对说明",
                                    "适用贷款服务 6% 税率，无免税情形；红冲必须关联原发票号且原发票状态为已开具（E12024）",
                                    "税率参数未维护时暂停开票，先更新税务参数（E12025）"),
                            block("历史记录",
                                    "该客户近期无重复开票与红冲记录；应税利息与台账金额核对一致（E12022）",
                                    "开票接口异常自动重试，失败后提示人工开票通道并挂起（E12023）"),
                            block("确认区",
                                    "发票 PDF 开具后可发送客户或存档；发票号、金额回写税务台账与客户档案",
                                    "回复 1 确认开票，回复 2 调整金额，回复 3 取消"));
                }));

        // ============ S9-7 绿色信贷统计与专项报送 ============
        REGISTRY.put("S9-7", new SceneScript(
                "S9-7", "green-credit-stat-agent", "绿色信贷统计与专项报送",
                List.of("绿色信贷", "绿色贷款", "绿色标识", "绿色金融"),
                "您好，我是绿色信贷统计助手。直接说'统计三季度绿色贷款并出专项报表'，我会自动匹配绿色目录、完成统计与报送准备。",
                "本次将为您完成：1.制度口径查询 2.目录匹配 3.标识校验 4.结构统计 5.报表定稿",
                List.of(
                        real("st1", "① 调用 knowledge-retrieve-agent 拉取绿色贷款专项统计制度与《绿色产业指导目录》版本", "knowledge-retrieve", deps(),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "绿色贷款专项统计制度 绿色产业指导目录 六大类 统计口径", "topK", 3),
                                (sl, d) -> "制度依据命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        real("st2", "② 调用 trend-analyze-agent 按六大类目录匹配在贷客户与项目（绿色贷款余额季度序列）", "trend-analyze", deps("st1"),
                                ":8132/api/bai/trend/analyze",
                                (sl, sm, sd) -> map("seriesId", "GREEN_CREDIT_BALANCE", "window", "M6",
                                        "methods", List.of("trend", "yoy"),
                                        "points", List.of(
                                                map("date", "2026-05", "value", 172.0), map("date", "2026-06", "value", 176.3),
                                                map("date", "2026-07", "value", 179.8), map("date", "2026-08", "value", 182.1),
                                                map("date", "2026-09", "value", 184.7), map("date", "2026-10", "value", 186.5))),
                                (sl, d) -> "绿色余额趋势 " + d.path("trend").path("direction").asText("-")
                                        + "（analyzeId=" + d.path("analyzeId").asText("-") + "）"),
                        realQuality("st3", "③ 调用 data-quality-agent 校验标识完整性与统计口径", deps("st2"), "dws_mkt.prov_stat"),
                        real("st4", "∥ ④ 调用 metric-calc-agent 输出绿色贷款结构（行业/机构/期限分布）", "metric-calc", deps("st2"),
                                ":8128/api/bai/metric/calc",
                                (sl, sm, sd) -> map("metricId", "LOAN_BALANCE", "params", map("orgId", "ORG-DEMO", "date", "2026-09-30")),
                                (sl, d) -> "绿色结构统计（近似）=" + d.path("value").asText("-") + s(d.path("unit").asText(""), "")),
                        human("st5", "⚑ 人工确认节点：绿色信贷专员复核存疑标识与统计结果；标识由非绿色改为绿色（新增）须双人复核", deps("st3", "st4")),
                        realTask("st6", "⑤ 调用 task-schedule-agent 批量回写标识并生成报送数据（标识回写联调待办：确定性仿真＋人工确认运行）", deps("st5")),
                        real("st7", "⑥ 调用 report-gen-agent 生成绿色信贷专项报表（专项模板未配置，以经营管理月报模板近似）", "report-gen", deps("st6"),
                                ":8131/api/bai/report/generate",
                                (sl, sm, sd) -> map("templateId", "T_MGMT_MONTHLY",
                                        "params", map("orgId", "ORG-DEMO", "date", "2026-09-30",
                                                "granularity", "ORG",
                                                "customer", "GREEN-CREDIT-STAT"),
                                        "format", "PDF"),
                                (sl, d) -> "专项报表=" + d.path("reportId").asText("-")
                                        + "，文件=" + d.path("fileId").asText("-")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录匹配依据、标识变更、报送记录", deps("st7"))),
                "回复 1 复核存疑清单后定稿，回复 2 导出清单线下核查",
                (sl, sm, sd) -> List.of(
                        block("统计总览",
                                "绿色贷款余额 186.5 亿元，占各项贷款 8.2%，同比 +1.1 个百分点",
                                "六大类分布：清洁能源类 68 亿元居首，其次节能环保、基础设施绿色升级｜" + s(sm.get("st2"), "趋势：详见滚动日志")),
                        block("存疑标识清单",
                                "存疑 48 户列示客户号与拟匹配类别（示例：某环保设备企业拟归绿色服务类，存疑：主营收入中环保业务占比不足 50%）",
                                "目录代码无法匹配客户行业的列入存疑人工判定，不自动打标（E12026）"),
                        block("结构图表",
                                "行业/机构分布图见前端展示",
                                s(sm.get("st4"), "结构统计：详见滚动日志")),
                        block("确认区",
                                "专项报表定稿后可发起报送；标识变更回写信贷管理系统客户标签",
                                "回复 1 复核存疑清单后定稿，回复 2 导出清单线下核查，回复 3 发起专项报送"))));

        // ============ S10-1 全渠道统一入口 ============
        REGISTRY.put("S10-1", new SceneScript(
                "S10-1", "omni-entry-agent", "全渠道统一入口",
                List.of("统一对话入口", "全渠道统一入口", "一句话办多件事", "多件事", "多诉求"),
                "您好，我是您的专属智能助手。您可以一次说出多件事，比如'查一下我贷款还剩多少，再申请 30 万经营贷，顺便看下投诉进度'，我会一次听懂、分头去办。",
                "本次将为您完成：1.意图解析 2.指代补全 3.任务编排 4.分块响应 5.留痕",
                List.of(
                        real("st1", "① 调用 intent-agent 识别一句话中的多个意图（余额查询/贷款申请/投诉进度）", "intent", deps(),
                                ":8103/api/bai/intent/classify",
                                (sl, sm, sd) -> map("text", "帮我查一下我名下贷款还剩多少，再申请一笔 30 万的经营贷，顺便看下上次投诉处理得怎么样了",
                                        "scene", "credit", "enableOod", true, "topK", 3),
                                (sl, d) -> "解析出 " + d.path("intents").size() + " 个意图（" + d.path("intents").path(0).path("level2").asText("-") + " 等）"),
                        realSemantic("st2", "② 调用 semantic-match-agent 跨轮指代补全", deps("st1"), "上次投诉有结果了吗", "工单 #TS20261018007：费率争议投诉，处理中", "COMPLAINT"),
                        realOrchestrate("st3", "③ 调用 agent-orchestrate-agent 生成依赖拓扑与执行计划", deps("st2"), "生成投诉处理依赖拓扑与执行计划：工单分类→责任定位→方案反馈"),
                        human("st4", "⚑ 人工确认节点：复述任务清单，用户确认后才派发执行；涉及贷款申请等敏感操作必须显式确认", deps("st3")),
                        real("st5", "④ 调用 summary-agent 聚合分块摘要（执行结果＋后续建议）", "summary", deps("st4"),
                                ":8115/api/bai/summary/generate",
                                (sl, sm, sd) -> map("text", "贷款余额查询：名下 2 笔在贷剩余本金合计 56.2 万元，下期还款日 11 月 18 日；投诉进度：工单 #TS20261018007 已于 10 月 25 日办结并电话回访；经营贷申请：已进入资料收集环节，需上传近 6 个月流水。",
                                        "mode", "bullet", "maxChars", 150, "extractKeyInfo", true),
                                (sl, d) -> "聚合摘要 summaryId=" + d.path("summaryId").asText("-")
                                        + "，压缩比=" + d.path("stats").path("ratio").asText("-")),
                        realUiOps("st6", "⑤ 调用 ui-ops-agent 分块渲染响应", deps("st5"), "SESS-S10-1", "#result-blocks", "FILL", "blocks-1-5"),
                        realAudit("st7", "⑥ 调用 audit-log-agent 留痕（意图、编排、分块）", deps("st6"))),
                "回复 1 上传经营贷申请资料，回复 2 看某个分块详情",
                (sl, sm, sd) -> List.of(
                        block("块1【贷款余额】",
                                "名下 2 笔在贷，剩余本金合计 56.2 万元",
                                "下期还款日 11 月 18 日，应还 12,580.00 元（口径见对账单，可回溯）"),
                        withExtraLines(statusBlock("块2【投诉进度】",
                                        "ok", "工单 #TS20261018007（利息扣款异议）", "已于 10 月 25 日办结，已电话回访"),
                                "回复 2 看处理全过程留痕"),
                        statusBlock("块3【经营贷申请】",
                                "warn", "申请单号 SQ20261104002", "已进入资料收集环节",
                                "warn", "身份证", "需上传",
                                "warn", "近 6 个月经营流水", "需上传",
                                "warn", "抵押物权证（如抵押）", "需上传"),
                        block("引导",
                                "三条任务已并行推进，互不影响；任何一条说'继续'即可接着办",
                                "回复 1 上传经营贷申请资料，回复 2 看某个分块详情"))));

        // ============ S10-2 数字员工 ============
        REGISTRY.put("S10-2", new SceneScript(
                "S10-2", "digital-employee-agent", "数字员工",
                List.of("数字员工指令", "语音指令", "语音办理"),
                "您好，我是数字员工小智。您可以直接下达语音指令，比如'把昨天的放款清单导出，然后给这 3 个客户发短信通知'，我来一步步执行。",
                "本次将为您完成：1.语音转写 2.指令解析 3.授权校验 4.任务执行 5.回执通知",
                List.of(
                        realAsr("st1", "① 调用 asr-agent 语音转写", deps(), "AUD-S10-2-001", "voice-command"),
                        real("st2", "② 调用 intent-agent 解析操作动作与参数", "intent", deps("st1"),
                                ":8103/api/bai/intent/classify",
                                (sl, sm, sd) -> map("text", "把昨天的放款清单导出，给这三个客户发短信通知放款成功",
                                        "scene", "credit", "enableOod", true, "topK", 2),
                                (sl, d) -> "识别动作：导出清单＋批量短信（" + d.path("intents").path(0).path("level2").asText("-") + "）"),
                        real("st3", "③ 调用 auth-agent 校验柜员操作授权", "auth", deps("st2"),
                                ":8109/api/bai/auth/login",
                                (sl, sm, sd) -> map("identity", map("userId", "gwy07", "name", "高文雅"),
                                        "factor", map("type", "PASSWORD", "credential", "******"),
                                        "context", map("deviceId", "Teller-077", "deviceFp", "FP-S103", "ip", "10.20.3.7",
                                                "userAgent", "credit-web", "geo", "北京"),
                                        "scene", "BACKOFFICE"),
                                null),
                        human("st4", "⚑ 人工确认节点：复述关键参数（清单范围、短信模板、接收人）后执行；执行前明确不可逆操作", deps("st3")),
                        realCallExec("st5", "④ 调用 call-exec-agent 导出放款清单（昨日后督批次，86 笔；核心账务联调待办：确定性仿真＋人工确认运行）", deps("st4"), "DEF_NOTIFY_001", map("templateCode", "EXPORT_FILE", "receiver", "REPORT-SYS")),
                        realCallExec("st6", "∥ ⑤ 调用 call-exec-agent 对其中 3 户发送放款成功短信（联调待办：确定性仿真＋人工确认运行）", deps("st4"), "DEF_SMS_001", map("phone", "13800001110", "templateId", "LOAN_DISBURSE_NOTIFY")),
                        realTextGen("st7", "⑥ 调用 text-gen-agent 生成执行回执话术", deps("st5", "st6")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 留痕（指令、执行、回执）", deps("st7"))),
                "回复 1 确认执行，回复 2 修改任务参数",
                (sl, sm, sd) -> List.of(
                        block("执行回执",
                                "执行 2 项任务，全部成功（耗时 12 秒）",
                                "留痕：已生成执行日志与操作记录（E12031）"),
                        block("放款清单",
                                "昨日后督批次放款 86 笔，明细已导出",
                                "存放路径见执行结果，格式为 Excel"),
                        block("短信发送",
                                "3 户短信全部发送成功",
                                "失败自动重试 1 次，仍失败转人工（E12034）"),
                        block("引导",
                                "执行过程已分块留痕，可随时查询",
                                "回复 1 确认执行，回复 2 修改任务参数"))));

        // ============ S10-3 PC 联动 ============
        REGISTRY.put("S10-3", new SceneScript(
                "S10-3", "pc-linkage-agent", "PC 联动",
                List.of("PC联动", "页面联动", "联动补录"),
                "您好，我可以在您的 PC 页面上直接联动操作。比如您说'帮我在客户页面补录上次的抵押信息'，我会定位页面、执行操作并给您回执。",
                "本次将为您完成：1.对象识别 2.授权校验 3.页面定位 4.联动执行 5.留痕",
                List.of(
                        realSemantic("st1", "① 调用 semantic-match-agent 识别操作对象与上下文", deps(), "帮我把客户页面的信息补全", "张伟档案页（客户 360 视图）", "OPERATION"),
                        real("st2", "② 调用 auth-agent 校验页面操作授权", "auth", deps("st1"),
                                ":8109/api/bai/auth/login",
                                (sl, sm, sd) -> map("identity", map("userId", "gwy07", "name", "高文雅"),
                                        "factor", map("type", "PASSWORD", "credential", "******"),
                                        "context", map("deviceId", "PC-CRM-02", "deviceFp", "FP-S102", "ip", "10.20.3.2",
                                                "userAgent", "credit-web", "geo", "北京"),
                                        "scene", "BACKOFFICE"),
                                null),
                        human("st3", "⚑ 人工确认节点：复述确认目标客户与操作（张伟抵押信息补录）后执行", deps("st2")),
                        realCallExec("st4", "③ 调用 call-exec-agent 页面操作＋接口双通道补录（核心联调待办：确定性仿真＋人工确认运行）", deps("st3"), "DEF_ACCT_DEDUCT_001", map("loanNo", "LN-S10-3-001", "amount", 600000)),
                        realTodo("st5", "∥ ④ 调用 todo-agent 生成重检任务 #ZJ20261027003", deps("st4"), "INSPECT", "ZJ20261027003", "S10-3 st5 待办跟踪", "SUP001"),
                        realCallExec("st6", "? 条件分支：页面元素定位失败时降级为接口调用", deps("st4"), "DEF_ACCT_DEDUCT_001", map("loanNo", "LN-S10-3-001", "amount", 600000)),
                        realAudit("st7", "⑤ 调用 audit-log-agent 留痕（对象、操作、结果）", deps("st5", "st6"))),
                "回复 1 确认联动，回复 2 改为接口补录",
                (sl, sm, sd) -> List.of(
                        block("联动回执",
                                "页面操作成功（页面路径：客户中心→档案管理→张伟详情）",
                                "留痕：已生成操作日志与对象快照（E12036）"),
                        block("补录明细",
                                "抵押信息：和平路 3 号厂房，评估价值 120 万元",
                                "已生成贷后重检任务 #ZJ20261027003，3 个工作日内完成"),
                        block("提示",
                                "页面无响应自动降级为接口调用；服务端会话超时自动重连",
                                "PC 无鼠标键盘输入，全程对话式联动"),
                        block("引导",
                                "联动操作已留痕，可随时查询",
                                "回复 1 确认联动，回复 2 改为接口补录"))));

        // ============ S10-4 前后台一致性 ============
        REGISTRY.put("S10-4", new SceneScript(
                "S10-4", "frontback-consistency-agent", "前后台一致性",
                List.of("交易一致性", "前后台一致性", "回滚", "补偿交易", "对账探查", "扣没扣"),
                "您好，我是交易一致性核查助手。您可以说'这笔交易到底扣没扣'或'做一次对账探查'，我会比对渠道、核心、客户三方状态并给出结论。",
                "本次将为您完成：1.对账数据接入 2.三方状态跟踪 3.一致性校验 4.补偿交易 5.留痕",
                List.of(
                        real("st1", "① 调用 data-query-agent 接入对账数据（渠道流水、核心账务、客户账务；信贷台账未联调，以 ODS 白名单表近似）", "data-query", deps(),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        realTodo("st2", "② 调用 todo-agent 跟踪交易三方状态", deps("st1"), "INSPECT", "TXN-S10-4", "S10-4 st2 待办跟踪", "SUP001"),
                        realQuality("st3", "③ 调用 data-quality-agent 一致性校验", deps("st2"), "ods_pay.txn_recon"),
                        realCallExec("st4", "④ 调用 call-exec-agent 补偿交易（补渠道回执并重发通知；核心账务联调待办：确定性仿真＋人工确认运行）", deps("st3"), "DEF_NOTIFY_001", map("templateCode", "CHN_RECEIPT", "receiver", "CHANNEL-SYS")),
                        human("st5", "⚑ 人工确认节点：涉及客户资金的补偿交易须双人复核后执行", deps("st4")),
                        realCallExec("st6", "⑤ 调用 call-exec-agent 补偿过账与客户通知（联调待办：确定性仿真＋人工确认运行）", deps("st5"), "DEF_NOTIFY_001", map("templateCode", "COMP_NOTIFY", "receiver", "CUSTOMER-SMS")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 留痕（三方状态、补偿、结果）", deps("st6"))),
                "回复 1 自动补偿并重发通知，回复 2 生成对账报告人工核对",
                (sl, sm, sd) -> List.of(
                        withExtraLines(statusBlock("三方状态比对表",
                                        "warn", "渠道", "处理中",
                                        "ok", "核心", "成功",
                                        "ok", "客户账", "已扣 8,642.00 元"),
                                s(sm.get("st1"), "对账数据：详见滚动日志")),
                        withExtraLines(statusBlock("处置结果",
                                        "ok", "渠道回执", "已补",
                                        "ok", "扣款成功通知", "已重发"),
                                "无资金风险，前后台一致"),
                        block("说明",
                                "同批未发现其他不一致交易；补偿交易均双人复核留痕",
                                "交易状态不一致须人工判定，不自动回滚（E12038/E12039）"),
                        block("引导",
                                "核查结果已回写对账台账",
                                "回复 1 自动补偿并重发通知，回复 2 生成对账报告人工核对"))));

        // ============ S10-5 接口变更适配 ============
        REGISTRY.put("S10-5", new SceneScript(
                "S10-5", "api-change-agent", "接口变更适配",
                List.of("接口变更", "接口升级", "灰度验证", "切流", "接口适配"),
                "您好，我是接口变更适配助手。直接告诉我接口名称和变更内容，我会自动完成文档检索、契约校验、灰度验证，并协助切流。",
                "本次将为您完成：1.文档检索 2.合规校验 3.契约校验 4.流程适配 5.灰度切流",
                List.of(
                        real("st1", "① 调用 knowledge-retrieve-agent 检索接口文档与版本变更说明", "knowledge-retrieve", deps(),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "税务开票接口 新版本 契约 字段 红字原因码 购买方备注", "topK", 3),
                                (sl, d) -> "接口文档命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        realCompliance("st2", "② 调用 compliance-check-agent 接口合规校验", deps("st1"), "RED_LINE", map("check", "加密传输,数据最小化")),
                        real("st3", "③ 调用 data-query-agent 接口连接调试与契约字段校验", "data-query", deps("st2"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "status")),
                                (sl, d) -> "接口连接调试：" + truncate(d, 60)),
                        realOrchestrate("st4", "④ 调用 agent-orchestrate-agent 评估场景编排中调用点的适配与影响面", deps("st3"), "评估数据服务接口调用点的适配与影响面：新增 2 个字段映射"),
                        realTask("st5", "⑤ 调用 task-schedule-agent 灰度验证（10% 流量，抽样 100 笔；批量执行未上线）", deps("st4")),
                        human("st6", "⚑ 人工确认节点：技术负责人确认灰度验证报告后切流；切换后观察期 24 小时", deps("st5")),
                        realEvent("st7", "? 条件分支：调用 event-subscribe-agent 接口异常监控", deps("st6"), "call.degraded", "S10-5-st7", map("callId", "CE-S10-5-st7")),
                        realTextGen("st8", "⑥ 调用 text-gen-agent 生成变更通知", deps("st7")),
                        realAudit("st9", "⑦ 调用 audit-log-agent 留痕（变更单、灰度、切流）", deps("st8"))),
                "回复 1 确认切流，回复 2 生成灰度报告",
                (sl, sm, sd) -> List.of(
                        withExtraLines(statusBlock("接入总览",
                                        "ok", "税务开票接口 v2.1 连接", "成功",
                                        "warn", "契约校验通过率", "32/34",
                                        "warn", "字段映射", "30/34（新增 2 个字段待映射）"),
                                s(sm.get("st1"), "接口文档：详见滚动日志")),
                        block("合规校验",
                                "加密传输、数据最小化校验通过",
                                "契约校验不过的调用点保留旧版本调用，不强行适配（E12041/E12042）"),
                        withExtraLines(progressBlock("灰度报告",
                                        "灰度流量", 10, "10%",
                                        "抽样成功率", 100, "100%"),
                                "抽样 100 笔，平均耗时 240ms（Mock）",
                                "灰度期间新旧版本双跑比对，响应差异超阈值自动回滚（E12043）"),
                        block("确认区",
                                "切流后 24 小时观察期，失败率>1% 自动回滚",
                                "回复 1 确认切流，回复 2 生成灰度报告"))));

        // ============ S10-6 客服与投诉处理 ============
        REGISTRY.put("S10-6", new SceneScript(
                "S10-6", "service-complaint-agent", "客服与投诉处理",
                List.of("投诉", "我要投诉", "投诉处理", "智能客服"),
                "您好，我是智能客服助手。您可以直接说需求或表达不满，比如'我觉得这期扣的钱不对，我要投诉'，我会先安抚、再解答，必要时转人工专员。",
                "本次将为您完成：1.意图分类 2.情绪分级 3.知识应答 4.工单处理 5.回访留痕",
                List.of(
                        real("st1", "① 调用 textclass-agent 对投诉进行意图与业务分类", "textclass", deps(),
                                ":8112/api/bai/textclass/classify",
                                (sl, sm, sd) -> map("text", "你们这个经营贷利率到底怎么算的？我觉得我这期扣的钱不对，我要投诉！",
                                        "task", "complaint_ticket", "scene", "QUERY_CLASSIFY"),
                                (sl, d) -> "分类：" + d.path("labels").size() + " 项（" + d.path("labels").get(0).path("label").asText("-") + "）"),
                        realSentiment("st2", "② 调用 sentiment-agent 情绪分级", deps("st1"), "你们这个经营贷利率到底怎么算的？我觉得我这期扣的钱不对，我要投诉！", "COMPLAINT"),
                        real("st3", "③ 调用 rag-answer-agent 检索标准应答（经营贷等额本息计算口径）", "rag-answer", deps("st1"),
                                ":8119/api/bai/rag/answer",
                                (sl, sm, sd) -> map("question", "经营贷等额本息月供如何计算？包含哪些部分？",
                                        "context", List.of(map("entryId", "KB-RATE-001", "title", "贷款计息与还款方式说明",
                                                "content", "等额本息每月还款额固定，由本金与利息两部分组成；利息按剩余本金占用天数计收，本金按期摊还；计息与摊还规则以合同约定为准。", "score", 0.94)),
                                        "style", "plain"),
                                (sl, d) -> "标准应答已生成（answerId=" + d.path("answerId").asText("-")
                                        + "，忠实度 " + d.path("quality").path("faithfulness").asDouble(0) + "）"),
                        real("st4", "∥ ④ 复杂问题调用 knowledge-retrieve-agent 检索制度原文佐证", "knowledge-retrieve", deps("st1"),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "贷款利率定价 等额本息 计息规则 合同约定", "topK", 3),
                                (sl, d) -> "制度依据命中 " + d.path("total").asInt(0) + " 条"),
                        realTask("st5", "⑤ 调用 task-schedule-agent 生成投诉工单 #TS20261027011 派发信贷管理部", deps("st2")),
                        realTextGen("st6", "⑥ 调用 text-gen-agent 生成答复函", deps("st5")),
                        real("st7", "⑦ 调用 summary-agent 生成对话摘要存工单", "summary", deps("st6"),
                                ":8115/api/bai/summary/generate",
                                (sl, sm, sd) -> map("text", "客户对经营贷本期扣款金额提出异议并投诉，同时咨询利率计算方式；情绪分级为不满（升级）；标准应答已解答计算口径；工单 #TS20261027011 已派发信贷管理部，预计 3 个工作日内答复。",
                                        "mode", "bullet", "maxChars", 150, "extractKeyInfo", true),
                                (sl, d) -> "对话摘要 summaryId=" + d.path("summaryId").asText("-")),
                        human("st8", "⚑ 人工确认节点：答复函发出前由承办人确认；情绪升级投诉自动转人工专员接管", deps("st7")),
                        realAudit("st9", "⑧ 调用 audit-log-agent 留痕（分类、应答、工单、回访）", deps("st8"))),
                "回复 1 转人工专员，回复 2 查看工单进度",
                (sl, sm, sd) -> List.of(
                        block("咨询解答",
                                "您的经营贷月供由'等额本息'公式计算：利息按剩余本金占用天数计收，本金按期摊还；本期应还本息 8,642.00 元（本金 7,200 元、利息 1,442 元，明细见对账单，可回溯）",
                                s(sm.get("st3"), "应答口径：详见滚动日志")),
                        block("投诉受理",
                                "已生成投诉工单 #TS20261027011，派发信贷管理部，预计 3 个工作日内答复",
                                s(sm.get("st7"), "对话摘要：详见滚动日志")),
                        block("答复函预览",
                                "尊敬的客户：您反映的利息扣款问题已受理，经初步核查本期计息口径符合合同约定，详细对账明细与计算过程将在 3 个工作日内随答复函一并提供。感谢您的监督。",
                                "情绪分级为不满（升级），已优先处理"),
                        block("回访与引导",
                                "工单办结后自动发起满意度回访；重复投诉与升级投诉直转人工",
                                "回复 1 转人工专员，回复 2 查看工单进度"))));

        // ============ S11-1 结构化/非结构化数据接入 ============
        REGISTRY.put("S11-1", new SceneScript(
                "S11-1", "data-ingest-agent", "结构化/非结构化数据接入",
                List.of("数据接入", "接数据", "解析入库", "拉数", "数据源接入"),
                "您好，我是数据接入助手。您可以一句话告诉我接什么数，例如“接入 2026 年三季度全市企业工商变更数据”，或直接发送文件让我解析入库。",
                "本次将为您完成：1.数据源连接与授权校验 2.结构化数据拉取 3.扫描件解析 4.要素抽取 5.入库前质检 6.接入回执",
                List.of(
                        real("st1", "① 调用 data-query-agent 连接数据源并批量拉取结构化数据（工商源 ods_gs_change 表白名单联调待办，先以信贷贴源表演示拉取链路）", "data-query", deps(),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        realOcr("st2", "∥ ② 调用 ocr-agent 解析上传扫描件", deps(), "MIXED_DOC", "IMG-S111-01"),
                        realDocParse("st3", "∥ ③ 调用 doc-parse-agent 还原 PDF 版面与表格（文档解析未接入编排链路，联调待办：占位演示）", deps(), "customer-report-S11-1.pdf", "report"),
                        real("st4", "④ 调用 ner-agent 抽取非结构化文件要素（企业名称、变更事项、日期）", "ner", deps("st2", "st3"),
                                ":8111/api/bai/ner/extract",
                                (sl, sm, sd) -> map("text", "尽调报告扫描件样例：恒通商贸有限公司因增资扩股，注册资本由 2000 万元变更为 3000 万元，法定代表人由张伟变更为张强，变更日期 2026-09-28。",
                                        "scene", "INGEST"),
                                (sl, d) -> "要素抽取完成，实体 " + d.path("entities").size() + " 项"),
                        human("st5", "⚑ 人工确认节点：付费数据源（征信、税务）超常规批次取数前须数据主管确认授权范围，超时 4 小时未确认自动挂起", deps("st1", "st4")),
                        realQuality("st6", "⑤ 调用 data-quality-agent 入库前质检", deps("st5"), "dwd_ai.training_set"),
                        real("st7", "⑥ 调用 summary-agent 生成接入结果摘要", "summary", deps("st6"),
                                ":8115/api/bai/summary/generate",
                                (sl, sm, sd) -> map("text", "本批次完成工商变更数据接入：结构化记录 12,486 条（先以信贷贴源表演示拉取链路），两份尽调报告扫描件解析入库，抽取要素 37 项；入库前质检关键字段缺失率 0.8%，低于 10% 阈值；数据拟落 ods_gs_change 表，供授信排查等场景调用。",
                                        "mode", "bullet", "maxChars", 150, "extractKeyInfo", true),
                                (sl, d) -> "接入摘要 summaryId=" + d.path("summaryId").asText("-")),
                        realAudit("st7b", "⑦ 调用 audit-log-agent 记录接入全程日志（数据源、范围、取数人、时间）", deps("st7"))),
                "回复 1 立即对这批数据跑质量治理（S11-2），回复 2 基于新数据重算客户画像（S11-3），回复 3 结束本次接入",
                (sl, sm, sd) -> List.of(
                        block("接入概览",
                                "数据源：工商公示数据（P05）｜范围：2026 年 7—9 月｜结构化记录 12,486 条，耗时 3 分 12 秒（确定性演示）",
                                "先以信贷贴源表完成取数链路验证，工商表白名单联调待办"),
                        withExtraLines(barsBlock("解析明细", "项",
                                        "报告甲抽取要素", 19, "报告乙抽取要素", 18),
                                "识别置信度均 ≥95%",
                                s(sm.get("st4"), "要素抽取：详见滚动日志")),
                        block("质检回执",
                                "关键字段缺失率 0.8%，低于 10% 阈值，无隔离区记录"),
                        block("落地信息",
                                "拟入 DW 库 ods_gs_change 表；可供 S3 尽调族、S11-2 质量治理、S11-3 画像调用",
                                s(sm.get("st7"), "接入摘要：详见滚动日志")))));

        // ============ S11-2 数据质量治理 ============
        REGISTRY.put("S11-2", new SceneScript(
                "S11-2", "data-governance-agent", "数据质量治理",
                List.of("数据质量", "质检", "数据治理", "整改通知", "数据不准"),
                "您好，我是数据质量治理助手。您可以一句话告诉我查哪张表，例如“质检客户基本信息表最近一个月的数据”，我会从完整性、准确性、时效性三个维度出报告。",
                "本次将为您完成：1.完整性/时效性扫描 2.外部交叉核对 3.问题分布统计 4.整改建议输出 5.留痕",
                List.of(
                        realQuality("st1", "① 调用 data-quality-agent 按完整性/时效性规则扫描客户基本表 86.4 万条记录", deps(), "ods_core.customer_base"),
                        real("st2", "② 调用 data-validate-agent 将客户名/证件号与工商、征信数据交叉核对（T_DOC 文档模板近似演示）", "data-validate", deps("st1"),
                                ":8129/api/bai/validate/validate",
                                (sl, sm, sd) -> map("templateId", "T_DOC",
                                        "data", map("name", "恒通商贸有限公司", "idCard", "91310000MA1FL8888X",
                                                "reportPeriod", "2026-10", "sourceSystem", "工商公示", "checkType", "CROSS_CHECK")),
                                (sl, d) -> "交叉核对结论=" + d.path("result").asText("-")
                                        + "，通过项=" + d.path("summary").path("passed").asInt(0)),
                        realMetric("st3", "③ 调用 metric-calc-agent 汇总问题分布", deps("st2"), "FIELD_DIST", map("total", 864000, "issueByField", map("联系电话缺失", 1200, "行业代码越界", 800, "证件到期早于今天", 460))),
                        realEvent("st4", "? 条件分支：问题记录数超阈值 10% → 调用 event-subscribe-agent 生成修复任务", deps("st2"), "risk.alert", "S11-2-st4", map("alertId", "DQ-S11-2", "level", "YELLOW")),
                        realTextGen("st5", "④ 调用 text-gen-agent 向责任机构生成整改通知", deps("st3", "st4")),
                        realAudit("st6", "⑤ 调用 audit-log-agent 记录治理全程", deps("st5"))),
                "回复 1 生成整改通知发给责任机构，回复 2 对冲突数据发起修复任务，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        withExtraLines(barsBlock("质检总评", "分",
                                        "完整性", 96.2, "准确性", 98.5, "时效性", 95.0),
                                "较上期完整性下降 1.3 分（确定性演示）",
                                s(sm.get("st2"), "交叉核对：详见滚动日志")),
                        block("问题清单",
                                "手机号缺失 9,217 户（占比 1.07%，略超 1% 阈值），集中在三家支行"),
                        block("交叉核对结果",
                                "1,204 户企业名称与工商登记不一致，已列入修复任务池"),
                        block("整改建议",
                                "建议三家支行 15 日内补全手机号；名称不一致数据由数据部发起工商同步"))));

        // ============ S11-3 指标计算与客户画像 ============
        REGISTRY.put("S11-3", new SceneScript(
                "S11-3", "metric-portrait-agent", "指标计算与客户画像",
                List.of("客户画像", "完整画像", "画像生成", "指标计算"),
                "您好，我是客户画像助手。您直接说出客户姓名或企业名，例如“给恒通商贸有限公司出一份完整画像”，我会自动归并身份、算指标、查关联关系。",
                "本次将为您完成：1.身份归并 2.核心指标计算 3.资产负债分布 4.关联图谱挖掘 5.画像分块输出",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并客户身份（识别同名多户自动合并）", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", s(sl.get("customer"), "恒通商贸有限公司"),
                                                "idCard", demoCode("91310005MA1FL", s(sl.get("customer"), "恒通商贸有限公司"))),
                                        "source", "core", "entityType", "COMPANY"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        real("st2", "② 调用 metric-calc-agent 计算核心指标（在贷余额口径；12 项指标全量计算待指标中心扩展）", "metric-calc", deps("st1"),
                                ":8128/api/bai/metric/calc",
                                (sl, sm, sd) -> map("metricId", "LOAN_BALANCE",
                                        "params", map("orgId", "ORG-DEMO", "date", "2026-10-31")),
                                (sl, d) -> "在贷余额测算值=" + d.path("value").asText("-") + d.path("unit").asText("")
                                        + "（确定性仿真）"),
                        real("st3", "∥ ③ 调用 data-query-agent 查询客户在本行的账户与资产负债分布", "data-query", deps("st1"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        real("st4", "∥ ④ 调用 graph-build-agent 挖掘担保、任职、股权、资金往来关联", "graph-build", deps("st1"),
                                ":8134/api/bai/graph/build",
                                (sl, sm, sd) -> map("mode", "FULL",
                                        "scope", map("goldenId", s(sd.containsKey("st1") ? sd.get("st1").path("goldenId").asText(null) : null, "G-DEMO"))),
                                (sl, d) -> "关联构建 jobId=" + d.path("jobId").asText("-")),
                        realStructure("st5", "⑤ 调用 structure-gen-agent 生成画像分块图表与图谱数据形态", deps("st2", "st3", "st4"), "SCHEMA_DD_CARD_V2", "TEXT", "画像分块图表", "CARD"),
                        realMask("st6", "⑥ 调用 sensitive-mask-agent 对敏感字段脱敏", deps("st5"), "TEXT", "画像结果：姓名张伟、手机 13800001111、资产 500 万", "DISPLAY"),
                        realAudit("st7", "⑦ 调用 audit-log-agent 记录画像调用留痕", deps("st6"))),
                "回复 1 基于画像发起授信评估（S4），回复 2 把高风险关联客户拉清单，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("基本信息与身份归并",
                                "恒通商贸有限公司（成立 2018 年，注册资本 2000 万元），已归并 2 个客户号",
                                s(sm.get("st1"), "身份归并：详见滚动日志")),
                        block("核心指标",
                                "资产负债率 58%（行业 45 分位）｜流动比率 1.6（确定性演示）",
                                s(sm.get("st2"), "指标测算：详见滚动日志")),
                        withExtraLines(barsBlock("资产负债与行为偏好", "万元",
                                        "本行存款日均", 86, "在贷余额", 300),
                                "在贷余额年利率 3.45%"),
                        block("关联图谱",
                                "对外担保 2 笔合计 450 万元；其中为正和物流担保 300 万元，其当前他行逾期 80 万元，建议重点关注",
                                s(sm.get("st4"), "图谱构建：详见滚动日志")))));

        // ============ S11-4 模型推理服务（统一推理） ============
        REGISTRY.put("S11-4", new SceneScript(
                "S11-4", "model-inference-agent", "模型推理服务（统一推理）",
                List.of("模型推理", "批量评分", "批量打分", "跑模型", "统一推理"),
                "您好，我是统一推理助手。您可以一句话告诉我跑哪个模型、给谁跑，例如“用小微评分卡给这 500 个客户批量打分”。",
                "本次将为您完成：1.模型版本路由 2.批量推理执行 3.进度跟踪 4.分布统计 5.监控提示",
                List.of(
                        realModelInfer("st1", "① 调用 model-infer-agent 校验入参字段完整性，路由小微评分卡 V2.1（模型推理原子未接入编排链路，联调待办：占位演示）", deps(), "RATING", "SCORECARD", "CRB-SC-2025-V3", "C-S11-4", map("annualIncome", 1200000, "assetLiabRatio", 0.35, "netCashRatio", 0.42)),
                        realTask("st2", "? 条件分支：批次 500 户 > 100 户 → 调用 task-schedule-agent 批量执行", deps("st1")),
                        realTodo("st3", "② 调用 todo-agent 跟踪推理进度（500/500）", deps("st2"), "INSPECT", "MI-S11-4", "S11-4 st3 待办跟踪", "SUP001"),
                        realEvent("st4", "? 异常分支：4 户入参缺失重试 1 次仍失败，转人工补数清单", deps("st2"), "call.degraded", "S11-4-st4", map("callId", "MI-S11-4-st4")),
                        realMetric("st5", "③ 调用 metric-calc-agent 统计评分分布与拒绝率", deps("st2"), "SCORE_DIST", map("total", 5200, "rejected", 676, "buckets", map("600-650", 1300, "650-700", 1800, "700-750", 1500, "750+", 600))),
                        realMask("st6", "④ 调用 sensitive-mask-agent 对入参与结果脱敏落库", deps("st5"), "TEXT", "批量打分入参：张伟/310101199001011234 等 500 户", "LOG"),
                        realAudit("st7", "⑤ 调用 audit-log-agent 记录推理留痕（模型版本、入参、分数、耗时）", deps("st6")),
                        human("st8", "⚑ 人工确认节点：灰度版本试跑结果如需替代现行版本，须模型管理员确认", deps("st7"))),
                "回复 1 把评分结果推送审批流程，回复 2 对异常失败记录补数据后重跑，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("推理概况",
                                "模型：小微评分卡 V2.1｜对象 500 户｜成功 496 户｜耗时 48 秒（确定性演示）",
                                "推理结果仅供业务决策参考，审批结论归 S4 审批族"),
                        withExtraLines(donutBlock("结果分布", "户",
                                        "A 级", 82, "B 级", 241, "C 级", 137, "D 级", 36),
                                "拒绝率 7.2%"),
                        block("异常清单",
                                "4 户缺经营流水字段，已转人工补数清单"),
                        block("监控提示",
                                "本次分布与基准偏差 3%，未超 20% 衰减预警线；如超限将标注模型衰减预警"))));

        // ============ S11-5 知识引擎（RAG/制度问答） ============
        REGISTRY.put("S11-5", new SceneScript(
                "S11-5", "knowledge-engine-agent", "知识引擎（RAG/制度问答）",
                List.of("制度问答", "知识问答", "查制度", "规定是什么", "条款是什么"),
                "您好，我是制度问答助手。您可以像问同事一样直接提问，例如“个人经营贷受托支付起点金额是多少”，我会给出制度原文出处。",
                "本次将为您完成：1.问题要素抽取 2.制度库检索 3.监管条款定位 4.带引用解答 5.留痕",
                List.of(
                        real("st1", "① 调用 ner-agent 抽取问题要素（业务类型、关键词）", "ner", deps(),
                                ":8111/api/bai/ner/extract",
                                (sl, sm, sd) -> map("text", "个人经营贷受托支付起点金额是多少？制度是怎么规定的？",
                                        "scene", "QA"),
                                (sl, d) -> "要素抽取完成，实体 " + d.path("entities").size() + " 项"),
                        real("st2", "② 调用 knowledge-retrieve-agent 从制度库向量检索相关原文片段", "knowledge-retrieve", deps("st1"),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "个人经营贷 受托支付 起点金额 单笔超过30万 支付管理规定", "topK", 3),
                                (sl, d) -> "制度库命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        real("st3", "∥ ③ 调用 knowledge-retrieve-agent 定位对应监管条款", "knowledge-retrieve", deps("st1"),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "个人贷款管理办法 贷款用途 支付管理 监管要求", "topK", 3),
                                (sl, d) -> "监管条款命中 " + d.path("total").asInt(0) + " 条"),
                        real("st4", "④ 调用 rag-answer-agent 整合检索结果生成带引用的解答", "rag-answer", deps("st2", "st3"),
                                ":8119/api/bai/rag/answer",
                                (sl, sm, sd) -> map("question", "个人经营贷受托支付起点金额是多少？",
                                        "context", List.of(map("entryId", "KB-PAY-001", "title", "个人经营性贷款管理办法（第 23 条）",
                                                "content", "个人经营贷原则上应采用受托支付；符合自主支付条件的，单笔金额超过 30 万元须受托支付——以本行最新制度口径为准。", "score", 0.93)),
                                        "style", "plain"),
                                (sl, d) -> "解答已生成（answerId=" + d.path("answerId").asText("-")
                                        + "，忠实度 " + d.path("quality").path("faithfulness").asDouble(0) + "）"),
                        real("st5", "⑤ 调用 summary-agent 生成解答摘要", "summary", deps("st4"),
                                ":8115/api/bai/summary/generate",
                                (sl, sm, sd) -> map("text", "员工咨询个人经营贷受托支付起点金额：检索制度库与监管条款后给出解答——个人经营贷原则上应采用受托支付，符合自主支付条件的单笔超过 30 万元须受托支付，以本行最新制度口径为准；已逐条标注出处，供业务办理参考。",
                                        "mode", "bullet", "maxChars", 120, "extractKeyInfo", true),
                                (sl, d) -> "解答摘要 summaryId=" + d.path("summaryId").asText("-")),
                        realMask("st6", "⑥ 调用 sensitive-mask-agent 对涉敏内容脱敏", deps("st5"), "TEXT", "知识库文档含张伟身份证 310101199001011234", "LOG"),
                        realAudit("st7", "⑦ 调用 audit-log-agent 记录检索留痕", deps("st6"))),
                "回复 1 就这个问题继续追问，回复 2 对一笔具体业务走合规检查（S12），回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("直接答案",
                                "个人经营贷原则上应采用受托支付；符合自主支付条件的，单笔金额超过 30 万元须受托支付（以本行最新制度口径为准）",
                                s(sm.get("st4"), "带引用解答：详见滚动日志")),
                        block("制度原文",
                                "《个人经营性贷款管理办法》第 23 条：受托支付与自主支付适用情形（原文已检索命中）",
                                s(sm.get("st2"), "制度检索：详见滚动日志")),
                        block("监管对照",
                                "《个人贷款管理办法》关于贷款用途支付管理的要求",
                                s(sm.get("st3"), "监管条款：详见滚动日志")),
                        block("相关延伸",
                                "消费贷受托支付起点为 30 万元，与经营贷口径不同；本解答为参考性质，不替代合规审查结论"))));

        // ============ S11-6 多智能体编排与协同 ============
        REGISTRY.put("S11-6", new SceneScript(
                "S11-6", "agent-collaboration-agent", "多智能体编排与协同",
                List.of("多意图任务", "任务编排", "多智能体", "总调度", "智能体编排"),
                "您好，我是任务调度助手。您可以一次说多件事，例如“给这个客户出画像、顺便查他涉诉风险、再算笔还款计划”，我会自动拆开并行处理。",
                "本次将为您完成：1.多意图拆解 2.编排计划生成 3.画像与筛查并行执行 4.还款计划试算 5.聚合呈现",
                List.of(
                        real("st1", "① 调用 intent-agent 解析一段话并拆出多个意图", "intent", deps(),
                                ":8103/api/bai/intent/classify",
                                (sl, sm, sd) -> map("text", "给恒通商贸出个完整画像，顺便查一下它有没有涉诉风险，再按300万元、年利率3.45%、三年期算个还款计划",
                                        "scene", "MULTI", "enableOod", true, "topK", 3),
                                (sl, d) -> "意图拆解=" + d.path("intents").size() + " 项（首个："
                                        + d.path("intents").path(0).path("level2").asText("-") + "）"),
                        realOrchestrate("st2", "② 调用 agent-orchestrate-agent 生成编排计划", deps("st1"), "多意图任务编排：任务1 客户画像与任务2 贷款余额查询并行，任务3 还款计划测算依赖任务1"),
                        real("st3", "③ ∥ 调度画像子任务：entity-resolution-agent 归并客户身份", "entity-resolution", deps("st2"),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", "恒通商贸有限公司", "idCard", "91310000MA1FL8888X"),
                                        "source", "core", "entityType", "COMPANY"),
                                (sl, d) -> "画像子任务身份归并，goldenId=" + goldenIdOf(d)),
                        realScreening("st4", "③ ∥ 调度名单筛查子任务：screening-agent 筛查涉诉与制裁名单", deps("st2"), "AML", "演示客户"),
                        real("st5", "④ 调度还款计划子任务：repayment-plan-agent 按 300 万元/3.45%/36 期等额本息试算（依赖画像利率参数）", "repayment-plan", deps("st3"),
                                ":8127/api/bai/repayment-plan/plan",
                                (sl, sm, sd) -> map("contractId", "J20261104001", "principal", 3000000,
                                        "rate", 0.0345, "terms", 36, "method", "EQUAL_INSTALLMENT",
                                        "startDate", "2026-12-05"),
                                (sl, d) -> "试算完成：月供=" + d.path("monthlyPayment").asText("-")
                                        + "，总利息=" + d.path("totalInterest").asText("-")),
                        realTodo("st5b", "⑤ 调用 todo-agent 汇总各子任务进度（3/3 完成）", deps("st3", "st4", "st5"), "INSPECT", "ORCH-S11-6", "S11-6 st5b 待办跟踪", "SUP001"),
                        realEvent("st6", "? 异常分支：子任务失败自动重试 1 次，仍失败降级并标注", deps("st5"), "call.degraded", "S11-6-st6", map("callId", "ORCH-S11-6-st6")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录编排全程", deps("st6"))),
                "回复 1 基于结果发起授信审批，回复 2 继续追加任务，回复 3 结束",
                (sl, sm, sd) -> {
                    JsonNode plan = sd == null ? null : sd.get("st5");
                    String mp = plan != null ? plan.path("monthlyPayment").asText("-") : "-";
                    String ti = plan != null ? plan.path("totalInterest").asText("-") : "-";
                    return List.of(
                            statusBlock("任务清单总览",
                                    "ok", "客户画像", "完成",
                                    "ok", "涉诉筛查", "完成",
                                    "ok", "还款计划", "完成（耗时 26 秒）"),
                            block("画像结果",
                                    "恒通商贸：资产负债率 58%，A 级（确定性演示）",
                                    s(sm.get("st3"), "身份归并：详见滚动日志")),
                            statusBlock("筛查结果",
                                    "ok", "涉诉与制裁名单", "未命中",
                                    "ok", "逾期记录", "无"),
                            block("还款计划（真实原子试算）",
                                    "300 万元、年利率 3.45%、36 期等额本息：月供 " + mp + " 元，总利息 " + ti + " 元",
                                    "编排只做调度，数值由还款计划原子实算，可验算"));
                }));

        // ============ S12-1 法规解读与制度支持 ============
        REGISTRY.put("S12-1", new SceneScript(
                "S12-1", "regulation-interpret-agent", "法规解读与制度支持",
                List.of("法规解读", "解读一下", "新规影响", "条款解读"),
                "您好，我是法规解读助手。您直接说出法规名称或主题，例如“解读一下《征信业管理条例》第二十五条对异议处理的要求”。",
                "本次将为您完成：1.法规要素抽取 2.条款定位 3.本行制度对照 4.白话解读 5.解读报告",
                List.of(
                        real("st1", "① 调用 ner-agent 抽取法规名称与条款要素", "ner", deps(),
                                ":8111/api/bai/ner/extract",
                                (sl, sm, sd) -> map("text", "解读一下《征信业管理条例》第二十五条，重点看对异议处理时限的要求。",
                                        "scene", "REG"),
                                (sl, d) -> "要素抽取完成，实体 " + d.path("entities").size() + " 项"),
                        real("st2", "② 调用 knowledge-retrieve-agent 定位法规原文条款", "knowledge-retrieve", deps("st1"),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "征信业管理条例 第二十五条 异议 20日内核查处理 书面答复", "topK", 3),
                                (sl, d) -> "法规条款命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        real("st3", "∥ ③ 调用 knowledge-retrieve-agent 检索本行相关制度", "knowledge-retrieve", deps("st1"),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "征信异议处理规程 本行制度 答复时限 工单管理", "topK", 3),
                                (sl, d) -> "本行制度命中 " + d.path("total").asInt(0) + " 条"),
                        real("st4", "④ 调用 summary-agent 生成白话解读（条款原意、影响、落地建议）", "summary", deps("st2", "st3"),
                                ":8115/api/bai/summary/generate",
                                (sl, sm, sd) -> map("text", "《征信业管理条例》第二十五条解读：信息主体认为征信信息错误、遗漏的，有权提出异议并要求更正；征信机构或信息提供者应在异议受理后 20 日内核查处理并书面答复。对的本行影响：异议工单必须 20 日内闭环，本行现行《征信异议处理规程》要求 15 日内答复，严于监管要求；落地建议：系统加 20 日时限硬控与超期预警。",
                                        "mode", "bullet", "maxChars", 160, "extractKeyInfo", true),
                                (sl, d) -> "白话解读 summaryId=" + d.path("summaryId").asText("-")),
                        real("st5", "⑤ 调用 report-gen-agent 生成解读报告（专用解读模板联调待办，暂以通用月报模板近似演示）", "report-gen", deps("st4"),
                                ":8131/api/bai/report/generate",
                                (sl, sm, sd) -> map("templateId", "T_MGMT_MONTHLY",
                                        "params", map("orgId", "COMPLIANCE", "date", "2026-11-04",
                                                "granularity", "ORG", "topic", "征信业管理条例第25条解读"),
                                        "format", "PDF"),
                                (sl, d) -> "解读报告 reportId=" + d.path("reportId").asText("-")
                                        + "，校验结论=" + d.path("checks").path("overall").asText("-")),
                        human("st6", "⚑ 人工确认节点：解读报告用于对外培训或监管汇报前，须合规负责人审定", deps("st5")),
                        realAudit("st7", "⑥ 调用 audit-log-agent 记录解读留痕", deps("st6"))),
                "回复 1 按解读结论生成制度修订任务，回复 2 对在办业务做合规检查，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("条款原文",
                                "信息主体认为信息错误、遗漏的，可向征信机构或信息提供者提出异议，要求更正；异议受理后 20 日内核查处理和答复",
                                s(sm.get("st2"), "条款定位：详见滚动日志")),
                        block("白话解读",
                                "客户对征信记录有意见，我行必须 20 天内核查并书面答复，不能拖；本解读为参考性质，不构成正式法律意见",
                                s(sm.get("st4"), "解读摘要：详见滚动日志")),
                        withExtraLines(barsBlock("本行制度对照", "日",
                                        "本行规程答复时限", 15, "监管要求答复时限", 20),
                                "本行《征信异议处理规程》严于监管要求，符合"),
                        block("落地建议",
                                "异议工单系统须加 20 日监管时限硬控，建议补充超期预警；解读报告已生成待审定",
                                s(sm.get("st5"), "报告生成：详见滚动日志")))));

        // ============ S12-2 合规检查与红线管控 ============
        REGISTRY.put("S12-2", new SceneScript(
                "S12-2", "redline-control-agent", "合规检查与红线管控",
                List.of("踩红线", "红线检查", "例外审批", "发起合规检查"),
                "您好，我是合规检查助手。您可以发起对某笔业务的合规检查，我会对照红线规则逐条校验并给出“通过/拦截/例外审批”三档结论。",
                "本次将为您完成：1.红线逐条校验 2.条款依据定位 3.禁入名单筛查 4.材料合规打标 5.三档结论输出",
                List.of(
                        realCompliance("st1", "① 调用 compliance-check-agent 对照红线规则逐条校验（禁贷情形/资本金/用途/关系人；合规校验原子未接入编排链路，联调待办：占位演示）", deps(), "RED_LINE", map("check", "禁贷情形,资本金,用途,关系人")),
                        real("st2", "∥ ② 调用 knowledge-retrieve-agent 定位触发条款依据", "knowledge-retrieve", deps(),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "商业银行法 关系人贷款 禁贷情形 资本金比例 用途合规 红线条款", "topK", 3),
                                (sl, d) -> "条款依据命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        realScreening("st3", "∥ ③ 调用 screening-agent 筛查禁入名单", deps(), "ADMISSION", "恒通商贸有限公司"),
                        real("st4", "∥ ④ 调用 text-classify-agent 对业务材料合规打标", "textclass", deps(),
                                ":8112/api/bai/textclass/classify",
                                (sl, sm, sd) -> map("text", "500 万元经营贷申请：客户恒通商贸，用途采购原材料，信用方式，期限 36 个月。",
                                        "task", "compliance_text", "scene", "COMPLIANCE_TAG"),
                                (sl, d) -> "材料打标 " + d.path("labels").size() + " 项（"
                                        + d.path("labels").path(0).path("label").asText("-") + "）"),
                        realApproval("st5", "? 条件分支：信用方式超客户授信权限 → 结论“例外审批”并生成工单", deps("st1", "st2", "st3", "st4"), "CREDIT_APPLICATION", 60000000, "HIGH"),
                        human("st6", "⚑ 人工确认节点：例外审批结论须有权人（按金额分级）签批后生效，超时 48 小时挂起并提醒；本场景不自行放行", deps("st5")),
                        realAudit("st7", "⑤ 调用 audit-log-agent 记录检查留痕", deps("st6"))),
                "回复 1 提交例外审批，回复 2 按拦截项调整业务要素后重查，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("总结论",
                                "例外审批：触发 1 项（确定性演示）"),
                        withExtraLines(statusBlock("逐条校验结果",
                                        "ok", "用途合规", "通过",
                                        "ok", "资本金匹配", "通过",
                                        "ok", "关系人筛查", "通过",
                                        "fail", "信用方式权限", "触发"),
                                s(sm.get("st2"), "条款依据：详见滚动日志")),
                        block("触发项说明",
                                "客户内部评级 BBB，信用方式上限 300 万元，本笔 500 万元超出权限"),
                        block("处置建议",
                                "回复 1 提交上一级例外审批工单，回复 2 追加担保后重查；拦截类结论可直接阻断业务"))));

        // ============ S12-3 反洗钱监测（大额/可疑交易） ============
        REGISTRY.put("S12-3", new SceneScript(
                "S12-3", "aml-warning-agent", "反洗钱监测（大额/可疑交易）",
                List.of("反洗钱", "大额交易", "可疑交易", "洗钱风险"),
                "您好，我是反洗钱监测助手。您可以查询今日预警清单，或指定客户看可疑交易分析，例如“恒通商贸最近 30 天的交易有没有可疑特征”。",
                "本次将为您完成：1.客户身份归并 2.大额/可疑筛查 3.资金关联图谱 4.制裁名单核查 5.风险评级",
                List.of(
                        real("st1", "① 调用 entity-resolution-agent 归并客户身份（含同一实际控制人名下多户）", "entity-resolution", deps(),
                                ":8133/api/bai/entity/resolve",
                                (sl, sm, sd) -> map("record", map("name", s(sl.get("customer"), "恒通商贸有限公司"),
                                                "idCard", demoCode("91310005MA1FL", s(sl.get("customer"), "恒通商贸有限公司"))),
                                        "source", "core", "entityType", "COMPANY"),
                                (sl, d) -> "归并完成，goldenId=" + goldenIdOf(d)),
                        realAml("st2", "② 调用 aml-agent 按监管口径筛查大额与可疑交易（对公当日累计超 200 万元、快进快出等特征；反洗钱原子未接入编排链路，联调待办：占位演示）", deps("st1"), "T1D", List.of(map("amount", 1200000, "counterparty", "演示客户对公户", "direction", "IN", "purpose", "货款", "cash", false, "time", "2026-11-04"), map("amount", 1150000, "counterparty", "演示客户对公户", "direction", "OUT", "purpose", "转出", "cash", false, "time", "2026-11-04"))),
                        real("st3", "∥ ③ 调用 graph-build-agent 挖掘资金对手关联", "graph-build", deps("st1"),
                                ":8134/api/bai/graph/build",
                                (sl, sm, sd) -> map("mode", "FULL",
                                        "scope", map("goldenId", s(sd.containsKey("st1") ? sd.get("st1").path("goldenId").asText(null) : null, "G-DEMO"))),
                                (sl, d) -> "资金图谱构建 jobId=" + d.path("jobId").asText("-")),
                        real("st4", "∥ ④ 调用 data-query-agent 查询交易关联账户分布", "data-query", deps("st1"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        realScreening("st5", "∥ ⑤ 调用 screening-agent 筛查制裁与涉恐名单", deps("st1"), "AML", "演示客户"),
                        realRiskSignal("st6", "⑥ 调用 risk-signal-agent 给出洗钱风险评级（低/中/高）", deps("st2", "st3", "st4", "st5"), "C-S12-3", List.of("FLOW", "PUBLIC"), List.of("大额快进快出交易")),
                        human("st7", "⚑ 人工确认节点：可疑交易报告上报前必须反洗钱专员人工审定，确认后才生成上报底稿，本场景不自动上报", deps("st6")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录监测留痕", deps("st7"))),
                "回复 1 确认生成上报底稿，回复 2 排除为正常交易，回复 3 补充客户尽调",
                (sl, sm, sd) -> List.of(
                        block("身份归并",
                                "恒通商贸及其同一实际控制人名下 2 户已归并至统一主体",
                                s(sm.get("st1"), "身份归并：详见滚动日志")),
                        block("大额/可疑筛查",
                                "近 30 天大额交易 3 笔（当日累计最高 386 万元，超对公 200 万元口径）；可疑特征：快进快出 1 笔"),
                        block("资金关联图谱",
                                "资金对手 7 户，其中 1 户为异地新开户贸易公司，建议延伸核查",
                                s(sm.get("st3"), "图谱构建：详见滚动日志")),
                        block("风险评级与处置",
                                "洗钱风险评级：中；可疑交易报告须经反洗钱专员审定后按规定上报中国反洗钱监测分析中心"))));

        // ============ S12-4 授权与隐私合规（征信授权/个人信息保护） ============
        REGISTRY.put("S12-4", new SceneScript(
                "S12-4", "privacy-authorization-agent", "授权与隐私合规（征信授权/个人信息保护）",
                List.of("个人信息保护", "隐私合规", "授权状态", "授权合规"),
                "您好，我是授权合规助手。业务办理中我会自动核对征信授权与个人信息授权状态，缺失时引导补签，越权查询一律拦截。",
                "本次将为您完成：1.授权要素核对 2.经办人身份核验 3.补签引导 4.脱敏说明 5.提醒与留痕",
                List.of(
                        real("st1", "① 调用 ner-agent 核对业务约定用途与客户身份要素", "ner", deps(),
                                ":8111/api/bai/ner/extract",
                                (sl, sm, sd) -> map("text", "恒通商贸这笔经营贷，法定代表人张某的征信授权状态怎么样？约定用途是经营贷授信审查。",
                                        "scene", "AUTH"),
                                (sl, d) -> "授权要素抽取完成，实体 " + d.path("entities").size() + " 项"),
                        real("st2", "② 调用 auth-agent 校验经办人身份与授权操作（授权状态库联调待办，身份核验先行）", "auth", deps("st1"),
                                ":8109/api/bai/auth/login",
                                (sl, sm, sd) -> map("identity", map("userId", "xwz01", "name", "周晓雯"),
                                        "factor", map("type", "PASSWORD", "credential", "******"),
                                        "context", map("deviceId", "WEB-SCENE", "deviceFp", "FP-S124", "ip", "10.0.0.51",
                                                "userAgent", "credit-web", "geo", "北京"),
                                        "scene", "BACKOFFICE"),
                                null),
                        realCallExec("st3", "? 条件分支：法定代表人个人征信授权缺失 → 调用 call-exec-agent 发起电子授权补签", deps("st2"), "DEF_ESIGN_001", map("contractNo", "AUTH-S12-4-001", "signerId", "IDN-S12-4")),
                        realMask("st4", "③ 调用 sensitive-mask-agent 按授权范围与最小化原则脱敏展示", deps("st3"), "TEXT", "授权记录：张伟 13800001111 征信授权", "DISPLAY"),
                        real("st5", "④ 调用 notify-agent 向客户经理发送授权状态提醒", "notify", deps("st4"),
                                ":8106/api/bai/notify/send",
                                (sl, sm, sd) -> map("templateCode", "AUTH_STATUS_REMIND",
                                        "receiver", map("customerName", "客户经理刘敏", "phone", "13800001234"),
                                        "params", map("customerName", "恒通商贸有限公司", "status", "法定代表人个人征信授权缺失，已发起补签"),
                                        "channels", List.of("SMS"), "priority", "P1"),
                                (sl, d) -> "提醒送达 messageId=" + d.path("messageId").asText("-")
                                        + "，状态=" + d.path("status").asText("-")),
                        realAudit("st6", "⑤ 调用 audit-log-agent 记录授权与查询留痕", deps("st5"))),
                "回复 1 现在发起授权补签，回复 2 暂时跳过征信查询继续其他环节，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        statusBlock("授权状态总览",
                                "ok", "企业征信授权", "已签（2026-09-12）",
                                "fail", "法定代表人个人征信授权", "缺失",
                                "ok", "个人信息授权", "已签（确定性演示）"),
                        block("授权要素核对",
                                "企业授权有效期 1 年，用途：经营贷授信审查，与本次业务一致",
                                s(sm.get("st2"), "经办人核验：详见滚动日志")),
                        block("处置建议",
                                "法定代表人个人征信未授权，不读取其征信数据；已发起电子补签邀请，客户签署后自动放行"),
                        block("脱敏展示说明",
                                "本次仅展示授权状态与要素，证件号、手机号全遮蔽展示；无授权查询一律拦截不放行"))));

        // ============ S12-5 征信异议处理 ============
        REGISTRY.put("S12-5", new SceneScript(
                "S12-5", "credit-dispute-agent", "征信异议处理",
                List.of("征信异议", "异议受理", "异议申请", "更正征信"),
                "您好，我是征信异议处理助手。您可以受理客户异议，我会自动比对征信记录与本行数据、回源核查并生成答复函，全程 20 日时限管控。",
                "本次将为您完成：1.异议材料接收 2.征信记录解析 3.回源核查 4.差错确认 5.答复函草稿",
                List.of(
                        realDocParse("st1", "① 调用 doc-parse-agent 接收异议申请与结清证明（文档解析未接入编排链路，联调待办：占位演示）", deps(), "discharge-cert-S12-5.pdf", "certificate"),
                        real("st2", "② 调用 credit-report-agent 建立征信查询授权（用途：异议核查）", "credit-report", deps("st1"),
                                ":8110/api/bai/credit-report/authorize",
                                (sl, sm, sd) -> map("action", "CREATE",
                                        "customer", map("certNo", "310110198805054321", "name", "张伟"),
                                        "authDoc", map("docNo", "AUTH-DISPUTE-001", "certNo", "310110198805054321",
                                                "name", "张伟", "signedAt", "2026-11-04"),
                                        "purpose", "异议核查", "validDays", 30),
                                (sl, d) -> "授权建档 authId=" + d.path("authId").asText("-")),
                        real("st3", "③ 调用 credit-report-agent 解析异议涉及的征信记录原文", "credit-report", deps("st2"),
                                ":8110/api/bai/credit-report/report",
                                (sl, sm, sd) -> map("authId", s(sd.containsKey("st2") ? sd.get("st2").path("authId").asText(null) : null, "AUTH-DISPUTE-001"),
                                        "reportType", "PERSON"),
                                (sl, d) -> "报告编号=" + s(d.path("report").path("reportNo").asText(null),
                                        s(d.path("reportNo").asText(null), d.path("taskId").asText("-"))) + "，异议记录已定位"),
                        real("st4", "∥ ④ 调用 data-query-agent 回源本行信贷系统核对报送数据（信贷贴源白名单表演示）", "data-query", deps("st3"),
                                ":8121/api/bai/dataquery/query",
                                (sl, sm, sd) -> map("datasource", "ods_credit", "table", "loan_account",
                                        "fields", List.of("cust_name", "product_code", "balance", "status")),
                                null),
                        realQuality("st5", "∥ ⑤ 调用 data-quality-agent 校验本行报送数据与实际业务一致性", deps("st2"), "dws_cb.report_data"),
                        realTextGen("st6", "⑥ 调用 text-gen-agent 生成异议答复函草稿", deps("st4", "st5")),
                        human("st7", "⚑ 人工确认节点：答复函发出前须合规岗审定；确属差错的更正须数据主管确认，本场景只发起不越权改数", deps("st6")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录处理留痕（受理时间、核查过程、答复时间）", deps("st7"))),
                "回复 1 审定通过后发出答复函，回复 2 确属差错发起数据更正流程，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("工单信息",
                                "受理日 2026-11-04，20 日答复时限倒计时 19 天（异议编号 DY20261104001）"),
                        block("比对结果",
                                "征信显示逾期 2 期；本行系统实际 2022-01-15 已结清，数据不一致",
                                s(sm.get("st4"), "回源核查：详见滚动日志")),
                        block("核查结论",
                                "确属本行报送差错，将按征信报送更正流程提交更正"),
                        block("答复函草稿",
                                "已生成答复函草稿：说明差错事实、更正安排，依据《征信业管理条例》第二十五条，待合规岗审定后发出"))));

        // ============ S12-6 适当性管理与消保审查 ============
        REGISTRY.put("S12-6", new SceneScript(
                "S12-6", "suitability-review-agent", "适当性管理与消保审查",
                List.of("适当性", "消保", "产品能不能卖", "消保审查"),
                "您好，我是适当性与消保审查助手。您可以发起产品销售适当性审查，或处理一笔客户投诉，我会对照消保规则出审查意见。",
                "本次将为您完成：1.适当性合规校验 2.投诉情绪分级 3.消保法规定位 4.应答口径检索 5.审查意见流转",
                List.of(
                        realCompliance("st1", "① 调用 compliance-check-agent 做适当性与消保合规校验（风险等级匹配、双录要求、收费公示；合规校验原子未接入编排链路，联调待办：占位演示）", deps(), "PRODUCT", map("check", "风险等级匹配,双录,收费公示")),
                        realSentiment("st2", "∥ ② 调用 sentiment-agent 对投诉情绪与紧急度分级", deps(), "投诉人情绪激动：再不解决就向监管部门投诉！", "COMPLAINT"),
                        real("st3", "∥ ③ 调用 knowledge-retrieve-agent 定位消保法规依据", "knowledge-retrieve", deps(),
                                ":8118/api/bai/knowledge/retrieve",
                                (sl, sm, sd) -> map("query", "银行保险机构消费者权益保护管理办法 适当性匹配 老年消费者 投诉处理时限", "topK", 3),
                                (sl, d) -> "消保依据命中 " + d.path("total").asInt(0) + " 条（retrieveId=" + d.path("retrieveId").asText("-") + "）"),
                        real("st4", "∥ ④ 调用 rag-answer-agent 检索同类投诉应答口径", "rag-answer", deps(),
                                ":8119/api/bai/rag/answer",
                                (sl, sm, sd) -> map("question", "向老年客户销售经营贷未做还款压力测试，同类投诉的应答口径是什么？",
                                        "context", List.of(map("entryId", "KB-XB-001", "title", "消保投诉应答口径",
                                                "content", "涉及老年消费者的适当性投诉，应承认告知不充分事实，3 个工作日内专人回访，协商调整还款方案，不自动承诺赔付。", "score", 0.92)),
                                        "style", "plain"),
                                (sl, d) -> "应答口径已生成（answerId=" + d.path("answerId").asText("-")
                                        + "，忠实度 " + d.path("quality").path("faithfulness").asDouble(0) + "）"),
                        realTextGen("st5", "⑤ 调用 text-gen-agent 生成投诉答复函与审查意见草稿", deps("st1", "st2", "st3", "st4")),
                        realApproval("st6", "⑥ 调用 approval-route-agent 提交消保部审定流转", deps("st5"), "CONSUMER_PROTECTION", 5000000, "LOW"),
                        human("st7", "⚑ 人工确认节点：审查意见发布、投诉答复函发出前须消保部门审定；答复函不自动承诺赔付", deps("st6")),
                        realAudit("st8", "⑦ 调用 audit-log-agent 记录审查留痕", deps("st7"))),
                "回复 1 提交消保部审定后发出答复函，回复 2 按整改意见修改后复审，回复 3 结束",
                (sl, sm, sd) -> List.of(
                        block("审查结论",
                                "需整改：适当性不匹配——C2 客户承担 C3 产品，销售时未做还款压力测试（确定性演示）"),
                        withExtraLines(statusBlock("逐条校验结果",
                                        "ok", "信息披露", "通过",
                                        "fail", "还款测算告知", "未做",
                                        "fail", "老年客户特别提示", "缺失"),
                                s(sm.get("st3"), "消保依据：详见滚动日志")),
                        block("投诉分级",
                                "情绪中度、老年敏感客群，建议 3 个工作日内专人回访",
                                s(sm.get("st4"), "应答口径：详见滚动日志")),
                        block("处置建议",
                                "整改清单 3 项；答复函草稿已生成，承认告知不充分并协商调整方案，待消保部审定"))));
    }

    private static String truncate(com.fasterxml.jackson.databind.JsonNode d, int max) {
        String t = d.isMissingNode() || d.isNull() ? "" : d.toString();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
