package com.bai.scene.llm;

import com.bai.scene.script.SceneScript;
import com.bai.scene.script.SceneScripts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通用 LLM 意图路由器·两段式（对应《提示词库》S10-1 全渠道统一入口）：
 * <ol>
 *   <li>粗排召回：场景语料（标题+关键词+业务描述正向表述）与用户消息做加权融合打分——
 *       词面分（关键词命中 + 字符二元组 Dice）+ 语义分（bge-m3 向量余弦相似度）；
 *       embedding 不可用/失败时自动降级为纯词面打分，内存计算取 top-K 候选，无候选直接静默降级关键词路由</li>
 *   <li>LLM 精判：只看 top-K 候选的完整业务描述与边界说明，按业务意图判定归属并自评置信度；
 *       拿不准输出 low + 澄清反问（clarify），不在候选硬猜</li>
 *   <li>置信度门控：high → 返回命中场景；low/未命中 → 返回澄清话术（无澄清话术则静默降级关键词路由）；
 *       LLM 关闭/超时/异常/解析失败一律静默交回关键词路由，不中断已验证链路</li>
 * </ol>
 * 相比旧版「98 场景全量目录 + 字面重叠优先」单段路由，两段式解决：换话术误路由（召回语料+语义精判）、
 * 字面撞车（意图优先规则）、无出口硬选错（置信度门控），且精判 prompt 只含少量候选，token 更省。
 */
@Component
public class LlmSceneRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmSceneRouter.class);

    /** 路由为轻量短任务；取 6s 兼顾降级速度与命中率（异常仍降级关键词路由） */
    private static final int ROUTE_TIMEOUT_SECONDS = 6;
    /** 粗排召回候选数上限 */
    private static final int RECALL_TOP_K = 5;
    /** 关键词命中基础分 */
    private static final double KEYWORD_BASE_SCORE = 2.0;
    /** 字符二元组 Dice 相似度权重 */
    private static final double DICE_WEIGHT = 8.0;
    /** 语义向量余弦相似度权重（与词面分加权融合） */
    private static final double SEMANTIC_WEIGHT = 6.0;
    /** 场景语料批量向量化分片大小 */
    private static final int EMBED_BATCH = 16;

    private static final String JUDGE_SYSTEM_TPL = """
            你是银行信贷智能助理的场景意图判定器。根据用户最新消息，从下方候选场景中判定其业务意图属于哪个场景。
            规则：
            1. 只能在候选场景中选择，不得编造候选外的场景。
            2. 按业务意图判断，用户措辞与场景名称/关键词的字面重叠只作辅助参考；候选场景确实都不符合用户诉求时输出 {"sceneId":null}（宁可不命中，不要硬猜）。
            3. 同时对判定自评置信度：候选中有多个场景语义相近难以区分、或依据不足时输出 low，并用 clarify 给出一句面向用户的澄清反问（点出两个最可能的意图供用户选择）；判定明确输出 high，clarify 为空字符串。
            4. 只输出 JSON：{"sceneId":"编号或null","confidence":"high或low","clarify":"澄清反问或空字符串"}。
            易混淆判定参考（先例）：
            - 「存量客户里哪些有提额和复贷空间」→ S1-1（营销机会盘点圈客，不是睡眠户唤醒）
            - 「这笔贷款合规吗/合规检查」→ S4-6；「踩红线了吗/能例外审批吗」→ S12-2
            - 「看下客户完整画像」→ S11-3；泛指「客户画像/客户视图」→ S1-8
            候选场景（编号 名称：业务描述）：
            %s""";

    private final LlmClient llm;
    private final ObjectMapper mapper;

    /** 场景召回语料缓存（REGISTRY 为静态注册、启动后不变） */
    private final Map<String, Set<String>> corpusCache = new ConcurrentHashMap<>();
    /** 场景语料向量缓存（语义召回用，首次路由时批量初始化） */
    private final Map<String, double[]> vectorCache = new ConcurrentHashMap<>();
    /** 语义向量已初始化标志 */
    private volatile boolean vectorReady = false;
    /** 语义召回不可用标志（任何 embedding 失败后置位，本次运行期内不再尝试，全程词面召回） */
    private final AtomicBoolean vectorFailed = new AtomicBoolean(false);

    public LlmSceneRouter(LlmClient llm, ObjectMapper mapper) {
        this.llm = llm;
        this.mapper = mapper;
    }

    /** 路由结果：sceneId=命中场景；sceneId 为空且 clarify 非空=低置信澄清反问；两者皆空=未命中（静默降级关键词路由） */
    public record RouteResult(String sceneId, String clarify) {}

    /**
     * 两段式全局意图路由：粗排召回 top-K → LLM 精判 → 置信度门控。
     * 返回 null 表示静默降级关键词路由（LLM 关闭/超时/异常/无候选）。
     */
    public RouteResult route(String message) {
        if (message == null || message.isBlank()) return null;
        try {
            List<SceneScript> candidates = recall(message);
            if (candidates.isEmpty()) return null;
            String out = llm.chat(List.of(
                    new LlmClient.Msg("system", judgeSystem(candidates)),
                    new LlmClient.Msg("user", message)), true, ROUTE_TIMEOUT_SECONDS);
            JsonNode j = readJson(out);
            if (j == null) return null;
            String id = firstSceneId(j);
            String confidence = j.path("confidence").asText("high").trim();
            String clarify = blankToNull(j.path("clarify").asText(null));
            if (id == null || id.isBlank() || "null".equalsIgnoreCase(id)) {
                return new RouteResult(null, clarify);
            }
            SceneScript hit = SceneScripts.get(id.trim());
            if (hit == null) {
                log.warn("LLM 精判返回未注册场景编号 {}，按未命中处理", id);
                return new RouteResult(null, clarify);
            }
            boolean inCandidates = candidates.stream().anyMatch(c -> c.sceneId().equals(hit.sceneId()));
            if (!inCandidates) {
                log.warn("LLM 精判返回召回候选外场景 {}，按未命中处理", id);
                return new RouteResult(null, clarify);
            }
            if ("low".equalsIgnoreCase(confidence)) {
                log.info("LLM 精判低置信（倾向 {}），转澄清反问", id);
                return new RouteResult(null, clarify);
            }
            log.info("两段式意图路由命中：{}（{}）", id, hit.title());
            return new RouteResult(id, null);
        } catch (Exception e) {
            log.warn("两段式 LLM 意图路由失败，降级关键词路由：{}", e.getMessage());
            return null;
        }
    }

    // ---------- 粗排召回 ----------

    /**
     * 粗排召回 top-K 候选：词面分（关键词命中 + 语料字符二元组 Dice）与语义分（embedding 余弦相似度）加权融合。
     * <p>语义向量首次使用时批量初始化（98 场景语料 → bge-m3 1024 维缓存）；
     * embedding 未配置/超时/异常时置位 vectorFailed，全程静默降级词面打分，不改变调用方契约。
     */
    private List<SceneScript> recall(String message) {
        Set<String> msgBi = bigrams(normalize(message));
        double[] msgVec = null;
        if (!vectorFailed.get()) {
            try {
                initVectorsIfNeeded();
                msgVec = llm.embed(List.of(message)).get(0);
            } catch (Exception e) {
                vectorFailed.set(true);
                log.warn("语义召回不可用，降级词面召回：{}", e.getMessage());
            }
        }
        List<SceneScript> candidates = new ArrayList<>();
        List<Map.Entry<SceneScript, Double>> scored = new ArrayList<>();
        for (SceneScript s : SceneScripts.all().values()) {
            double sc = scoreOf(s, message, msgBi, msgVec);
            if (sc > 0) scored.add(Map.entry(s, sc));
        }
        scored.sort(Map.Entry.<SceneScript, Double>comparingByValue(Comparator.reverseOrder()));
        for (int i = 0; i < scored.size() && candidates.size() < RECALL_TOP_K; i++) {
            candidates.add(scored.get(i).getKey());
        }
        if (!candidates.isEmpty()) {
            log.info("路由粗排召回 {} 个候选（语义{}）：{}", candidates.size(),
                    msgVec != null ? "向量已融合" : "降级词面",
                    candidates.stream().map(SceneScript::sceneId).toList());
        }
        return candidates;
    }

    /** 词面打分（关键词命中，越长越具体权重越高）+ 语料 Dice 相似度 + 语义余弦相似度加权融合 */
    private double scoreOf(SceneScript s, String rawMessage, Set<String> msgBi, double[] msgVec) {
        double sc = 0;
        for (String kw : s.keywords()) {
            if (kw != null && !kw.isBlank() && rawMessage.contains(kw)) {
                sc += KEYWORD_BASE_SCORE + kw.length() * 0.5;
            }
        }
        Set<String> corpus = corpusCache.computeIfAbsent(s.sceneId(), k -> buildCorpus(s));
        if (!msgBi.isEmpty() && !corpus.isEmpty()) {
            int inter = 0;
            for (String b : msgBi) if (corpus.contains(b)) inter++;
            sc += DICE_WEIGHT * (2.0 * inter / (msgBi.size() + corpus.size()));
        }
        if (msgVec != null) {
            double[] sv = vectorCache.get(s.sceneId());
            if (sv != null && sv.length == msgVec.length) {
                sc += SEMANTIC_WEIGHT * cosine(msgVec, sv);
            }
        }
        return sc;
    }

    /** 场景语料向量懒加载初始化：分批调用 embedding 接口，失败抛出由调用方降级 */
    private synchronized void initVectorsIfNeeded() {
        if (vectorReady) return;
        List<SceneScript> scenes = List.copyOf(SceneScripts.all().values());
        List<String> texts = new ArrayList<>(scenes.size());
        for (SceneScript s : scenes) texts.add(corpusText(s));
        for (int i = 0; i < texts.size(); i += EMBED_BATCH) {
            List<String> chunk = texts.subList(i, Math.min(i + EMBED_BATCH, texts.size()));
            List<double[]> vecs = llm.embed(chunk);
            for (int j = 0; j < vecs.size(); j++) {
                vectorCache.put(scenes.get(i + j).sceneId(), vecs.get(j));
            }
        }
        vectorReady = true;
        log.info("语义召回向量初始化完成：{} 个场景语料已向量化", vectorCache.size());
    }

    /** 向量余弦相似度（bge-m3 输出未归一化，此处按完整公式计算） */
    private double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 召回语料文本：标题 + 关键词 + 业务描述的正向表述（排除性表述「注意：/仅针对」不入语料，避免负向语义污染） */
    private String corpusText(SceneScript s) {
        StringBuilder sb = new StringBuilder(s.title() == null ? "" : s.title());
        for (String kw : s.keywords()) sb.append(kw);
        SceneDialogSpec spec = SceneDialogSpecs.get(s.sceneId());
        if (spec != null && spec.routerDesc != null) {
            for (String line : spec.routerDesc.split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("注意") || t.startsWith("仅针对")) continue;
                sb.append(t.replaceAll("【[^】]*】", "").replaceFirst("^——", ""));
            }
        }
        return sb.toString();
    }

    /** 词面召回语料（字符二元组集合），供 Dice 相似度计算 */
    private Set<String> buildCorpus(SceneScript s) {
        return bigrams(normalize(corpusText(s)));
    }

    // ---------- LLM 精判 ----------

    private String judgeSystem(List<SceneScript> candidates) {
        StringBuilder catalog = new StringBuilder();
        for (SceneScript s : candidates) {
            SceneDialogSpec spec = SceneDialogSpecs.get(s.sceneId());
            String desc = spec != null && spec.routerDesc != null && !spec.routerDesc.isBlank()
                    ? spec.routerDesc.replace('\n', ' ')
                    : "关键词：" + String.join(",", s.keywords());
            catalog.append(s.sceneId()).append(' ').append(s.title()).append('：').append(desc).append('\n');
        }
        return JUDGE_SYSTEM_TPL.formatted(catalog.toString());
    }

    /** 解析多意图输出：优先 scenes 数组第一个，其次 sceneId */
    private String firstSceneId(JsonNode j) {
        JsonNode scenes = j.path("scenes");
        if (scenes.isArray() && scenes.size() > 0) {
            String first = scenes.get(0).asText(null);
            if (first != null && !first.isBlank()) return first;
        }
        return j.path("sceneId").asText(null);
    }

    // ---------- 工具 ----------

    /** 归一化：去空白与中英文标点 */
    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\p{Punct}，。？！、；：「」『』（）【】·]+", "").toLowerCase();
    }

    /** 字符二元组集合（中文词面相似度的基础单元） */
    private Set<String> bigrams(String norm) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 1 < norm.length(); i++) {
            set.add(norm.substring(i, i + 2));
        }
        return set;
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
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
