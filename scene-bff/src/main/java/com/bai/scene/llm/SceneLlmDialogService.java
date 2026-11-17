package com.bai.scene.llm;

import com.bai.scene.core.SceneContextStore;
import com.bai.scene.script.SceneScripts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 场景全对话大模型服务（通用框架，替代原 S62LlmDialogService）：
 * <ol>
 *   <li>意图识别：LLM 判断用户诉求属于哪个已接入对话的场景（由 {@link SceneDialogSpecs} 注册），未命中返回 null 交回全局路由</li>
 *   <li>槽位收集：LLM 多轮对话按场景配置补齐要素，缺要素主动追问；槽位规范化由场景配置完成</li>
 *   <li>结果解读：场景执行完成后 LLM 基于真实结果数据生成个性化解读（LLM 不参与计算/执行，数字一律取原子实算值）</li>
 *   <li>完成后追问：保留结果上下文支持解释性提问、修改要素重跑（READY）、跨场景转出（ROUTE_OTHER）</li>
 * </ol>
 * 全链路降级策略：任何 LLM 调用失败/解析失败 → 返回 null 或静态文案，回退已验证的关键词+模板链路。
 * 新场景接入：只需在 {@link SceneDialogSpecs} 注册一份场景配置，本框架自动生效。
 */
@Component
public class SceneLlmDialogService {

    private static final Logger log = LoggerFactory.getLogger(SceneLlmDialogService.class);

    private static final String ROUTER_SYSTEM_TPL = """
            你是 S10-1 全渠道统一入口智能体的意图解析器，所有用户消息统一先经本入口解析再分发。当前已接入大模型全对话的场景如下：
            %s
            解析规则：
            1. 先识别用户一段话中的业务诉求个数，再按业务意图判断归属（用户措辞与场景名称/关键词的字面重叠只作辅助参考）。
            2. 单一业务诉求：直接输出 {"scene":"对应场景编号"}，由入口分发至该场景处理链完成要素收集与执行，普通场景不做二次意图解析。
            3. 两个及以上业务诉求：输出 {"scene":"S10-1"}，交由统一入口编排（复述任务清单→确认→并行/串行调度→分块汇总）。
            4. 上述场景之外的业务诉求输出 {"scene":null}，交由全局路由处理。
            只输出 JSON，不要输出其他内容。""";

    private static final String COLLECT_SYSTEM_TPL = """
            你是银行信贷运营数字员工，当前处理场景为【%s %s】。后台原子服务按规则实算/实办，你负责对话收集要素与解读结果，规则：
            1. 要素收集与缺省规则：
            %s
            2. 你不得自行计算或编造任何业务数字与执行结果；回复中不出现执行结果。
            3. 语气专业简洁，银行对客口径，每次回复不超过 120 字。
            4. 用户中途表达其他业务诉求时转出。

            每轮只输出一个 JSON 对象（不要 markdown 代码块）：
            {"action":"ASK","reply":"向用户追问或确认要素的话"}
            {"action":"READY","reply":"要素复述与确认引导（不超过120字）","slots":{%s}}
            {"action":"CONFIRM_TURN","reply":"请回复 1 确认执行，2 修改要素，3 暂不处理。"}
            {"action":"ANSWER","reply":"基于已完成执行的数据解答用户追问"}
            {"action":"ROUTE_OTHER","reply":"告知用户将转交对应场景处理"}
            要素齐备即输出 READY，不必反复确认。
            若系统状态提示「场景会话已启动并等待人工确认」，除非用户明确提出新的要素调整（输出 READY），否则一律输出 CONFIRM_TURN，禁止重复执行场景。
            若系统状态提示「场景已执行完毕」，用户追问时输出 ANSWER（解释性内容只依据给定结果数据，涉及数字须注明「约」；要求改变要素重新执行时输出 READY）；用户提出其他场景诉求输出 ROUTE_OTHER。""";

    private static final int MAX_HISTORY = 24;

    /** 裸选项/短确认词（精确匹配）：会话待确认时的确定性短路，不过 LLM 意图解析 */
    private static final java.util.regex.Pattern BARE_OPTION = java.util.regex.Pattern.compile(
            "(?i)^[0-9]{1,2}$|^(确认|同意|好的|可以|继续|暂缓|修改|是|对|嗯|ok|yes|no)$");

    private final LlmClient llm;
    private final ObjectMapper mapper;

    /** userId → 进行中的场景对话（多轮槽位收集状态） */
    private final Map<String, Dialog> dialogs = new ConcurrentHashMap<>();

    public SceneLlmDialogService(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    /** 清除指定用户的进行中对话残留（前端「新建对话」时调用），保证下一条消息重新走意图路由 */
    public boolean reset(String userId) {
        pendingDoc.remove(userId);
        return dialogs.remove(userId) != null;
    }

    /** 资料内容问答直达提示词：%s 为上传资料的解析结果；非资料问答时放行正常意图路由 */
    private static final String DOC_QA_SYSTEM = """
            你是信贷数字员工。用户此前上传了资料，doc-parse 解析结果如下：
            %s

            判断用户这条消息是否是对上述资料的直接内容问答（询问其中的字段、要素、金额、条款、名单等）：
            是 → 输出 JSON {"docRelated":true,"answer":"基于解析结果的回答"}，answer 必须严格依据解析结果，缺失字段如实说明、不得编造；
            否（要发起业务场景、调整场景要素、与资料无关的提问）→ 输出 {"docRelated":false}。
            只输出 JSON。""";

    /** 对话轮次结果：sceneId+slots 非空=要素齐备可开场景；routeOther=转交全局路由；否则为追问/澄清回复 */
    public record LlmTurn(String sceneId, String reply, Map<String, Object> slots, boolean routeOther) {}

    private static class Dialog {
        final List<LlmClient.Msg> history = new ArrayList<>();
        String sceneId;
        String lastSessionId;
        boolean stateNotified;
        /** 非空表示场景已执行完毕，进入「完成后追问」模式（值为结果数据 JSON） */
        String resultJson;
        /** 上传资料解析结果已注入对话上下文（收集提示词将允许直接作为要素来源） */
        boolean docAttached;
    }

    /** userId → 尚无进行中对话时暂存的上传资料解析摘要（下次对话创建时补注入） */
    private final Map<String, String> pendingDoc = new ConcurrentHashMap<>();

    /**
     * 上传资料解析后注入对话上下文（1.3.2 中途补资料）：
     * 有进行中对话 → 直接写入 history 返回 true；无对话 → 暂存，下一条消息建立对话时补注入（返回 false）。
     * 注入后 LLM 可在追问中引用资料内容，并在槽位收集时把解析字段直接作为业务要素（无需重复追问）。
     */
    public boolean attachDoc(String userId, String summary) {
        Dialog d = dialogs.get(userId);
        if (d == null) {
            if (summary != null && !summary.isBlank()) {
                pendingDoc.put(userId, summary);
            }
            return false;
        }
        d.history.add(new LlmClient.Msg("user",
                "（用户上传了资料，doc-parse 解析结果如下：\n" + summary
                        + "\n后续对话可引用上述内容；用户提及「这份资料/上传的合同」时即指以上解析内容）"));
        d.docAttached = true;
        return true;
    }

    /** 对话入口：返回 null 表示交回关键词路由（LLM 关闭/未命中/失败） */
    public LlmTurn handle(String userId, String message) {
        try {
            // 资料内容问答直达：用户追问上传资料（字段/要素/条款）时不走场景路由，直接依据解析结果回答；
            // pendingDoc 保留，连续追问可用；用户转而发起场景时正常路由（解析摘要仍随对话建立注入）
            String docSum = pendingDoc.get(userId);
            if (docSum != null && message != null && !message.isBlank()) {
                try {
                    String out = llm.chat(List.of(
                            new LlmClient.Msg("system", DOC_QA_SYSTEM.formatted(docSum)),
                            new LlmClient.Msg("user", message)));
                    JsonNode j = readJson(out);
                    if (j != null && j.path("docRelated").asBoolean(false)) {
                        return new LlmTurn(null, j.path("answer").asText(""), null, false);
                    }
                } catch (LlmClient.LlmException e) {
                    log.debug("资料问答直达失败，转正常路由：{}", e.getMessage());
                }
            }
            Dialog d = dialogs.get(userId);
            if (d == null) {
                // 首次：LLM 意图路由（场景目录运行时从配置注册表构建）
                String out = llm.chat(List.of(new LlmClient.Msg("system", routerSystem()),
                        new LlmClient.Msg("user", message)));
                JsonNode j = readJson(out);
                String sceneId = j != null ? j.path("scene").asText(null) : null;
                SceneDialogSpec spec = sceneId != null ? SceneDialogSpecs.get(sceneId) : null;
                if (spec == null) {
                    return null;
                }
                d = new Dialog();
                d.sceneId = spec.sceneId;
                dialogs.put(userId, d);
                // 用户此前（无进行中对话时）上传的资料解析摘要，随对话建立补注入
                String pd = pendingDoc.remove(userId);
                if (pd != null) {
                    d.history.add(new LlmClient.Msg("user",
                            "（用户此前上传了资料，doc-parse 解析结果如下：\n" + pd
                                    + "\n后续对话可引用上述内容；用户提及「这份资料/上传的合同」时即指以上解析内容）"));
                    d.docAttached = true;
                }
            }
            SceneDialogSpec spec = SceneDialogSpecs.get(d.sceneId);
            if (spec == null) {
                dialogs.remove(userId);
                return null;
            }
            // 确定性确认短路：场景会话等待人工确认时，"1/2/3"等裸选项与短确认词不再过 LLM
            //（裸数字无业务语义，意图解析极易误判为新咨询/转出，导致入口澄清语打断受理流程）；
            // 直接按对话轮次返回，由 /chat 附加活会话（sessionId + AWAITING_CONFIRM + blocks）供确认接口推进。
            if (d.lastSessionId != null && d.resultJson == null && message != null
                    && BARE_OPTION.matcher(message.strip()).matches()) {
                d.history.add(new LlmClient.Msg("user", message));
                return new LlmTurn(d.sceneId, "已收到您的选择，正在推进当前确认节点。", null, false);
            }
            // 会话状态感知：场景已启动待确认时告知 LLM，避免待确认期间重复执行场景（循环根因修复）
            if (d.lastSessionId != null && d.resultJson == null && !d.stateNotified) {
                d.history.add(new LlmClient.Msg("assistant",
                        "（系统状态：场景会话 " + d.lastSessionId + " 已启动并正在等待人工确认）"));
                d.stateNotified = true;
            }
            d.history.add(new LlmClient.Msg("user", message));
            String out = llm.chat(buildMessages(d, spec));
            JsonNode j = readJson(out);
            String reply = j != null ? j.path("reply").asText(out) : out;
            String action = j != null ? j.path("action").asText("ASK") : "ASK";
            if ("ROUTE_OTHER".equals(action)) {
                dialogs.remove(userId);
                return new LlmTurn(d.sceneId, reply, null, true);
            }
            if ("READY".equals(action)) {
                Map<String, Object> slots = spec.normalizer.apply(j.path("slots"));
                if (slots == null) {
                    d.history.add(new LlmClient.Msg("assistant",
                            reply == null ? "请补充必要业务要素。" : reply));
                    return new LlmTurn(d.sceneId, reply == null ? "请补充必要业务要素。" : reply, null, false);
                }
                d.history.add(new LlmClient.Msg("assistant", reply));
                return new LlmTurn(d.sceneId, reply, slots, false);
            }
            d.history.add(new LlmClient.Msg("assistant", reply));
            return new LlmTurn(d.sceneId, reply, null, false);
        } catch (LlmClient.LlmException e) {
            log.warn("场景对话 LLM 降级回关键词路由：{}", e.getMessage());
            return null;
        }
    }

    /** 修改分支：把当前要素作为对话上下文，让 LLM 询问调整内容；保留旧会话 ID 以便重跑时终止 */
    public String beginModify(String userId, Map<String, Object> currentSlots, String sceneId) {
        try {
            Dialog old = dialogs.get(userId);
            Dialog d = new Dialog();
            d.sceneId = old != null && old.sceneId != null ? old.sceneId : sceneId;
            d.lastSessionId = old != null ? old.lastSessionId : null;
            dialogs.put(userId, d);
            SceneDialogSpec spec = SceneDialogSpecs.get(d.sceneId);
            String cur = "当前要素：" + mapper.writeValueAsString(currentSlots == null ? Map.of() : currentSlots);
            d.history.add(new LlmClient.Msg("user",
                    "我要调整方案要素。" + cur + "（用户尚未说明具体调整内容，请直接询问想调整哪些要素，不要复述确认当前要素）"));
            String out = llm.chat(buildMessages(d, spec));
            JsonNode j = readJson(out);
            String reply = j != null ? j.path("reply").asText(out) : out;
            d.history.add(new LlmClient.Msg("assistant", reply));
            return reply;
        } catch (Exception e) {
            log.warn("场景对话 LLM 修改引导降级为静态文案：{}", e.getMessage());
            return "好的，请直接说明要调整的内容，我会按新要素重新执行。当前方案暂不执行。";
        }
    }

    /** 场景会话回写（修改重跑时终止旧会话用）；重跑后需重新通知 LLM 会话状态 */
    public void markSession(String userId, String sessionId) {
        Dialog d = dialogs.get(userId);
        if (d != null) {
            d.lastSessionId = sessionId;
            d.stateNotified = false;
            // 新场景会话启动即结束上一单的「追问」态：不清 resultJson 会导致
            // 短路条件失效、裸数字回复被 LLM 判为转出，退回全局路由开新场景
            d.resultJson = null;
        }
    }

    public String lastSession(String userId) {
        Dialog d = dialogs.get(userId);
        return d == null ? null : d.lastSessionId;
    }

    /**
     * 完成后进入「追问」模式：按场景配置抽取真实结果数据作为上下文，用户可继续就该结果提问。
     * 追问中要求改要素重算 → 走 READY 重跑；其他场景诉求 → ROUTE_OTHER 转出。
     */
    public void finish(String userId, SceneContextStore.SceneContext ctx) {
        try {
            SceneDialogSpec spec = SceneDialogSpecs.get(ctx.script.sceneId());
            if (spec == null) return;
            Dialog d = dialogs.get(userId);
            if (d == null) {
                d = new Dialog();
                d.sceneId = spec.sceneId;
                dialogs.put(userId, d);
            }
            d.resultJson = mapper.writeValueAsString(spec.facts.apply(ctx));
            d.history.add(new LlmClient.Msg("assistant",
                    "（系统状态：场景已执行完毕，进入结果追问模式。用户可继续就该结果提问，或提出新的业务诉求）"));
        } catch (Exception e) {
            log.warn("场景结果上下文保存失败（追问模式不可用）：{}", e.getMessage());
        }
    }

    /** 结束对话 */
    public void close(String userId) {
        dialogs.remove(userId);
    }

    /**
     * 结果解读：按场景配置基于真实结果数据生成个性化解读（3 行），LLM 失败或场景无解读配置返回 null。
     */
    public List<String> interpret(SceneContextStore.SceneContext ctx) {
        try {
            SceneDialogSpec spec = SceneDialogSpecs.get(ctx.script.sceneId());
            if (spec == null || spec.interpretPrompt == null) return null;
            Map<String, Object> facts = spec.facts.apply(ctx);
            if (facts == null || facts.isEmpty()) return null;
            String out = llm.chat(List.of(new LlmClient.Msg("system", spec.interpretPrompt),
                    new LlmClient.Msg("user", mapper.writeValueAsString(facts))));
            JsonNode j = readJson(out);
            if (j == null) return null;
            List<String> lines = new ArrayList<>();
            j.path("lines").forEach(n -> { String t = n.asText("").trim(); if (!t.isEmpty()) lines.add(t); });
            return lines.size() >= 2 ? lines.subList(0, Math.min(3, lines.size())) : null;
        } catch (Exception e) {
            log.warn("场景 LLM 结果解读失败（忽略，不阻断主链路）：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 图表类型智能重排（LLM 选型不选数）：基于已完成结果的分块数据形状，让大模型建议更贴切的图表类型。
     * 仅接受数据兼容的类型切换（数值与标签完全不经过大模型），无兼容数据或失败时静默返回。
     */
    public void retypeBlocks(SceneContextStore.SceneContext ctx) {
        try {
            List<Map<String, Object>> blocks = ctx.blocks;
            if (blocks == null || blocks.isEmpty()) return;
            // 只把可切换的分块列入候选：数据为 [{label,value(,unit)}] 数值形状
            StringBuilder catalog = new StringBuilder();
            List<Map<String, Object>> swappable = new ArrayList<>();
            for (Map<String, Object> b : blocks) {
                Object data = b.get("data");
                if (!(data instanceof List<?> rows) || rows.isEmpty()) continue;
                boolean numeric = true;
                boolean ratio = true;
                for (Object o : rows) {
                    if (!(o instanceof Map<?, ?> m) || !(m.get("value") instanceof Number n)) { numeric = false; break; }
                    double v = n.doubleValue();
                    if (v < 0 || v > 100) ratio = false;
                }
                if (!numeric) continue;
                swappable.add(b);
                List<String> labels = rows.stream().map(r -> String.valueOf(((Map<?, ?>) r).get("label"))).toList();
                List<String> values = rows.stream().map(r -> String.valueOf(((Map<?, ?>) r).get("value"))).toList();
                catalog.append("- ").append(b.get("title")).append(" [当前:").append(b.get("type"))
                        .append("] 标签:").append(labels).append(" 值:").append(values)
                        .append(ratio ? "（全部为0-100比率）" : "").append('\n');
            }
            if (swappable.isEmpty()) return;
            String sys = """
                    你是数据可视化顾问，根据分块内容的业务语义判断更贴切的图表类型。
                    可选类型：donut（构成占比）、bars（数量/金额等对比）、progress（0-100比率进度）。
                    规则：
                    - 只能对用户清单中的分块建议，不得改动数值与标签
                    - 建议类型与当前类型相同时不要输出，保持空数组即可
                    - 逐期明细/时序类内容不要建议 donut
                    - progress 仅当数值全部为0-100比率时才可建议
                    - 不要为了改而改，仅建议明显更贴切的；无建议则输出空数组
                    只输出 JSON：{"suggests":[{"title":"分块标题","type":"donut"}]}""";
            String out = llm.chat(List.of(new LlmClient.Msg("system", sys),
                    new LlmClient.Msg("user", catalog.toString())));
            log.info("图表类型重排 LLM 建议：{}", out);
            JsonNode j = readJson(out);
            if (j == null) return;
            j.path("suggests").forEach(s -> {
                String title = s.path("title").asText("").trim();
                String type = s.path("type").asText("").trim();
                if (title.isEmpty() || !List.of("bars", "donut", "progress").contains(type)) return;
                for (Map<String, Object> b : swappable) {
                    if (title.equals(b.get("title"))) {
                        if ("progress".equals(type)) {
                            boolean ratio = true;
                            for (Object o : (List<?>) b.get("data")) {
                                double v = ((Number) ((Map<?, ?>) o).get("value")).doubleValue();
                                if (v < 0 || v > 100) { ratio = false; break; }
                            }
                            if (!ratio) return;
                        }
                        b.put("type", type);
                        log.info("图表类型重排已应用：{} → {}", title, type);
                        break;
                    }
                }
            });
        } catch (Exception e) {
            log.warn("场景 LLM 图表类型建议失败（忽略，不阻断主链路）：{}", e.getMessage());
        }
    }

    // ---------- internal ----------

    /** 专用意图路由提示词（场景目录来自配置注册表，不写死清单） */
    private String routerSystem() {
        StringBuilder catalog = new StringBuilder();
        SceneDialogSpecs.all().values()
                .forEach(s -> catalog.append(s.sceneId).append(' ').append(titleOf(s.sceneId)).append('\n')
                        .append(s.routerDesc).append('\n'));
        return ROUTER_SYSTEM_TPL.formatted(catalog.toString());
    }

    private String titleOf(String sceneId) {
        var s = SceneScripts.get(sceneId);
        return s != null ? s.title() : sceneId;
    }

    private List<LlmClient.Msg> buildMessages(Dialog d, SceneDialogSpec spec) {
        List<LlmClient.Msg> msgs = new ArrayList<>();
        msgs.add(new LlmClient.Msg("system", collectSystem(d, spec)));
        int from = Math.max(0, d.history.size() - MAX_HISTORY);
        d.history.subList(from, d.history.size()).forEach(msgs::add);
        return msgs;
    }

    /** 收集态系统提示；追问模式附加已完成执行的结果数据 */
    private String collectSystem(Dialog d, SceneDialogSpec spec) {
        var script = SceneScripts.get(spec.sceneId);
        String sys = COLLECT_SYSTEM_TPL.formatted(spec.sceneId,
                script != null ? script.title() : spec.sceneId,
                spec.collectRules, spec.slotSchema);
        if (d.resultJson != null) {
            sys = sys + "\n系统状态：本场景已执行完毕，结果数据如下（回答追问只依据这些数据）：\n" + d.resultJson + "\n";
        }
        if (d.docAttached) {
            sys = sys + "\n用户上传资料的解析结果已出现在对话上下文中：可作为业务要素直接提取填槽（无需重复追问），"
                    + "追问涉及资料内容时以解析字段为准回答；缺失字段仍按要素规则处理。\n";
        }
        return sys;
    }

    /** 解析 LLM 输出 JSON（容忍 markdown 代码块包裹）；失败返回 null */
    private JsonNode readJson(String out) {
        if (out == null) return null;
        String t = out.trim();
        if (t.startsWith("```")) {
            t = t.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        try {
            return mapper.readTree(t);
        } catch (Exception e) {
            int l = t.indexOf('{');
            int r = t.lastIndexOf('}');
            if (l >= 0 && r > l) {
                try { return mapper.readTree(t.substring(l, r + 1)); } catch (Exception ignore) { }
            }
            return null;
        }
    }
}
