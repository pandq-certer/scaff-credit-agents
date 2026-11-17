package com.bai.kretrieve;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 知识检索服务（提示词库 A-15）。向量/图谱通道为确定性仿真（标注：仿真，用于联调），
 * 结构与真实 FAISS/图谱召回一致，只读消费 kge 索引快照。
 * <p>处理链：校验入参 → query 向量化（仿真 768 维语义近似）→ 三通道并行召回（BM25 top50‖FAISS top50‖图谱一跳 maxHop=1）
 * → 过滤（类型/来源/业务域/时效）→ RFF 融合（keyword 0.30/vector 0.50/graph 0.20，同分向量优先）
 * → 可选 semantic-match 重排 → 卡片组装与溯源校验 → 落库发 knowledge.retrieve_miss 事件
 */
@Service
public class KnowledgeRetrieveService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrieveService.class);

    private static final int MAX_QUERY = 500;
    private static final int MAX_BATCH = 10000;
    private static final int MAX_PER_CHANNEL = 50;
    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 20;
    private static final Set<String> LIBRARIES = Set.of("faq", "policy", "product", "case", "regulation");
    private static final Set<String> CHANNELS = Set.of("keyword", "vector", "graph");
    private static final double LOW_CONFIDENCE = 0.50;

    /** 权重：keyword 0.30 / vector 0.50 / graph 0.20 */
    private static final Map<String, Double> CHANNEL_WEIGHT = Map.of("keyword", 0.30, "vector", 0.50, "graph", 0.20);

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final Map<String, CorpusEntry> corpus = new ConcurrentHashMap<>();
    private final Map<String, List<String[]>> graph = new ConcurrentHashMap<>(); // 实体 → [关系, 目标, 来源]
    private final AtomicLong retrieveSeq = new AtomicLong();
    private final AtomicLong batchSeq = new AtomicLong();
    /** SunBrain V23 客户端（provider=sunbrain 时启用） */
    private final SunbrainKbClient sunbrainClient;
    /** provider=sunbrain 且 api-key/binding-ref 已配置才启用 sunbrain 通道，否则一律内置语料快照 */
    private final boolean sunbrainEnabled;

    public KnowledgeRetrieveService(SunbrainKbClient sunbrainClient,
                                    @Value("${kb.provider:builtin}") String provider) {
        this.sunbrainClient = sunbrainClient;
        this.sunbrainEnabled = "sunbrain".equalsIgnoreCase(provider) && sunbrainClient.configured();
        if ("sunbrain".equalsIgnoreCase(provider) && !this.sunbrainEnabled) {
            log.warn("kb.provider=sunbrain 但 api-key/binding-ref 未配置，降级内置语料快照（builtin）");
        }
        loadCorpusSnapshot();
        loadGraphSnapshot();
    }

    /** 同步检索（幂等：requestId 相同返回首次结果，缓存 24h） */
    public KnowledgeRetrieveDtos.RetrieveResult retrieve(KnowledgeRetrieveDtos.RetrieveRequest req, String requestId) {
        KnowledgeRetrieveDtos.RetrieveResult cached = idempotency.get("retrieve", requestId);
        if (cached != null) {
            return cached;
        }
        validate(req);
        KnowledgeRetrieveDtos.RetrieveResult result = doRetrieve(req.query(), req.topK(), req.libraries(),
                req.channels(), req.rerank(), req.filters(), requestId);
        idempotency.put("retrieve", requestId, result);
        return result;
    }

    /** 图谱检索（一跳 maxHop=1，配合 ner-agent 实体识别） */
    public KnowledgeRetrieveDtos.RetrieveResult graph(KnowledgeRetrieveDtos.GraphRequest req, String requestId) {
        long start = System.currentTimeMillis();
        if (req == null || req.query() == null || req.query().isBlank()) {
            throw new BaiException(KnowledgeRetrieveErrorCodes.PARAM_INVALID, "KRE_1001 参数缺失：query 必填");
        }
        if (req.query().length() > MAX_QUERY) {
            throw new BaiException(KnowledgeRetrieveErrorCodes.OVER_LIMIT, "KRE_1005 query 超 500 字，请精简后重试");
        }
        int topK = req.topK() == null ? DEFAULT_TOP_K : Math.max(1, Math.min(MAX_TOP_K, req.topK()));

        List<KnowledgeRetrieveDtos.KnowledgeHit> hits = new ArrayList<>(buildGraphHits(req.query(), req.seedEntities()));
        List<KnowledgeRetrieveDtos.KnowledgeHit> top = hits.stream()
                .sorted(Comparator.comparingDouble(KnowledgeRetrieveDtos.KnowledgeHit::score).reversed())
                .limit(topK)
                .toList();
        warnIfLowConfidence(top, requestId);
        long elapsed = System.currentTimeMillis() - start;
        KnowledgeRetrieveDtos.RetrieveResult result = new KnowledgeRetrieveDtos.RetrieveResult(
                newRetrieveId(), req.query(), top, top.size(), elapsed);
        idempotency.put("graph", requestId, result);
        log.info("图谱检索审计: requestId={}, 命中={}", requestId, top.size());
        return result;
    }

    /** 批量异步受理（≤10000 条） */
    public KnowledgeRetrieveDtos.BatchResult batch(KnowledgeRetrieveDtos.BatchRequest req, String requestId) {
        List<KnowledgeRetrieveDtos.RetrieveRequest> items = req.items();
        if (items == null || items.isEmpty()) {
            throw new BaiException(KnowledgeRetrieveErrorCodes.PARAM_INVALID, "KRE_1001 参数缺失：batch.items 必填");
        }
        if (items.size() > MAX_BATCH) {
            throw new BaiException(KnowledgeRetrieveErrorCodes.OVER_LIMIT, "KRE_1005 批量超 10000 条（实际 " + items.size() + "），请分批");
        }
        for (KnowledgeRetrieveDtos.RetrieveRequest item : items) {
            validate(item);
        }
        String batchId = "KRB" + dayStamp() + String.format("%05d", batchSeq.incrementAndGet());
        log.info("知识批量检索受理: requestId={}, batchId={}, total={}", requestId, batchId, items.size());
        return new KnowledgeRetrieveDtos.BatchResult(batchId, "ACCEPTED", items.size());
    }

    /** 检索配置（RFF 权重等） */
    public KnowledgeRetrieveDtos.RetrieveConfig config() {
        return new KnowledgeRetrieveDtos.RetrieveConfig("2026.09.01", DEFAULT_TOP_K, MAX_TOP_K, MAX_PER_CHANNEL, 10, List.of(
                new KnowledgeRetrieveDtos.ChannelConfig("keyword", 0.30, "BM25 倒排召回 top50"),
                new KnowledgeRetrieveDtos.ChannelConfig("vector", 0.50, "FAISS IVF-PQ 向量召回 top50（仿真）"),
                new KnowledgeRetrieveDtos.ChannelConfig("graph", 0.20, "图谱一跳 maxHop=1（仿真）")));
    }

    private KnowledgeRetrieveDtos.RetrieveResult doRetrieve(String query, Integer topKReq, List<String> libraries,
                                                            List<String> channels, Boolean rerank,
                                                            Map<String, Object> filters, String requestId) {
        long start = System.currentTimeMillis();
        int topK = topKReq == null ? DEFAULT_TOP_K : Math.max(1, Math.min(MAX_TOP_K, topKReq));
        Set<String> libs = libraries == null || libraries.isEmpty() ? LIBRARIES : new HashSet<>(libraries);
        List<String> chans = channels == null || channels.isEmpty() ? List.of("keyword", "vector") : channels;

        // sunbrain 通道（kb.provider=sunbrain 时启用）：SunBrain V23 混合检索优先；
        // 不可达/超时/协议异常一律降级内置语料快照（下方原逻辑），对外契约不变。
        // 注：内置 libraries 库枚举仅作用于内置语料；sunbrain 侧过滤仅透传 filters.documentTypes。
        if (sunbrainEnabled) {
            Object types = filters == null ? null : filters.get("documentTypes");
            List<String> documentTypes = types instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList() : List.of();
            try {
                return sunbrainRetrieve(query, topK, documentTypes, requestId, start);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("sunbrain 检索不可用，降级内置语料快照: requestId={}, 原因={}: {}",
                        requestId, e.getClass().getSimpleName(), e.getMessage());
            }
        }

        // 三通道并行召回（每路≤50）：词汇相似度仿真 BM25；向量分=语义近似（确定性仿真）；图谱通道在 /retrieve 不启用
        Map<String, Double> lexical = new LinkedHashMap<>();
        List<String> queryGrams = grams(query);
        for (Map.Entry<String, CorpusEntry> e : corpus.entrySet()) {
            double s = similarity(queryGrams, grams(e.getValue().title() + " " + e.getValue().content() + " " + String.join(" ", e.getValue().tags())));
            if (s > 0) {
                lexical.put(e.getKey(), s);
            }
        }
        // 通道打分
        Map<String, Double> keywordScores = new LinkedHashMap<>();
        Map<String, Double> vectorScores = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : lexical.entrySet()) {
            keywordScores.put(e.getKey(), round3(e.getValue()));
            // 向量通道仿真：语义近似抬升（未接入真实 FAISS，仅用于联调）
            vectorScores.put(e.getKey(), round3(Math.min(0.99, e.getValue() * 0.85 + 0.30)));
        }

        // RFF 融合（按通道倒序排名加权，同分向量优先）
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, Double> bestChannelScore = new LinkedHashMap<>();
        for (String ch : List.of("keyword", "vector")) {
            if (!chans.contains(ch)) {
                continue;
            }
            Map<String, Double> scores = "keyword".equals(ch) ? keywordScores : vectorScores;
            List<String> ranked = scores.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(MAX_PER_CHANNEL)
                    .map(Map.Entry::getKey)
                    .toList();
            for (int rank = 0; rank < ranked.size(); rank++) {
                String id = ranked.get(rank);
                fused.merge(id, CHANNEL_WEIGHT.get(ch) / (60 + rank + 1), Double::sum);
                bestChannelScore.merge(id, scores.get(id), Math::max);
            }
        }
        if (fused.isEmpty()) {
            // 全部通道无命中：发 knowledge.retrieve_miss 事件供 FAQ 补全消费
            log.info("knowledge.retrieve_miss 事件: requestId={}, query 哈希={}, 命中=0, 建议=补充 FAQ/扩大检索库", requestId, query.hashCode());
            return new KnowledgeRetrieveDtos.RetrieveResult(newRetrieveId(), query, List.of(), 0, System.currentTimeMillis() - start);
        }

        double maxFused = fused.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        List<KnowledgeRetrieveDtos.KnowledgeHit> candidates = new ArrayList<>();
        for (Map.Entry<String, Double> e : fused.entrySet()) {
            CorpusEntry ce = corpus.get(e.getKey());
            if (ce == null || !libs.contains(ce.library())) {
                continue;
            }
            if (!passFilters(ce, filters)) {
                continue;
            }
            // 归一融合分：以该条最优通道分为峰值
            double score = round3((e.getValue() / maxFused) * bestChannelScore.getOrDefault(e.getKey(), 0.5));
            candidates.add(new KnowledgeRetrieveDtos.KnowledgeHit(e.getKey(), ce.library(), ce.type(), ce.title(),
                    abbreviate(ce.content(), 2000), ce.source(), score,
                    chans.contains("vector") && chans.contains("keyword") ? "FUSED" : chans.get(0).toUpperCase(), ce.tags()));
        }

        // 图谱通道（channels 含 graph 时并入融合候选，配合 ner-agent 实体识别仿真）
        if (chans.contains("graph")) {
            candidates.addAll(buildGraphHits(query, null));
        }

        // rerank=true 且候选>5 时语义精排（仿真：语义分=vectors；与融合分加权排序）
        if (Boolean.TRUE.equals(rerank) && candidates.size() > 5) {
            candidates.sort(Comparator.comparingDouble((KnowledgeRetrieveDtos.KnowledgeHit h) ->
                            0.6 * h.score() + 0.4 * vectorScores.getOrDefault(h.entryId(), 0.0)).reversed());
        } else {
            candidates.sort(Comparator.comparingDouble(KnowledgeRetrieveDtos.KnowledgeHit::score).reversed());
        }
        List<KnowledgeRetrieveDtos.KnowledgeHit> top = candidates.stream().limit(topK).toList();
        warnIfLowConfidence(top, requestId);
        log.info("检索审计: requestId={}, query 哈希={}, 库={}, 通道={}, 命中={}", requestId, query.hashCode(), libs, chans, top.size());
        return new KnowledgeRetrieveDtos.RetrieveResult(newRetrieveId(), query, top, top.size(), System.currentTimeMillis() - start);
    }

    /**
     * sunbrain 分支：V23 hits → 现有 KnowledgeHit 结构映射（library=kb、channel=KB 标注来源），
     * score 截断 0—1 后沿用低置信警示，响应结构与内置分支完全一致。
     * 任何异常向上抛出，由调用方统一降级。
     */
    private KnowledgeRetrieveDtos.RetrieveResult sunbrainRetrieve(String query, int topK, List<String> documentTypes,
                                                                  String requestId, long start) throws Exception {
        SunbrainKbClient.KbSearchResult result = sunbrainClient.search(query, topK, documentTypes);
        List<KnowledgeRetrieveDtos.KnowledgeHit> hits = new ArrayList<>();
        for (SunbrainKbClient.KbHit h : result.hits()) {
            if (h.text() == null || h.text().isBlank()) {
                continue;
            }
            hits.add(new KnowledgeRetrieveDtos.KnowledgeHit(
                    h.chunkRef().isBlank() ? "KBR" + String.format("%05d", Math.max(1, h.ordinal())) : h.chunkRef(),
                    "kb", "CHUNK", titleOf(h.text()), abbreviate(h.text(), 2000),
                    new KnowledgeRetrieveDtos.SourceRef(h.documentRef(),
                            h.page() > 0 ? "第" + h.page() + "页" : null, null),
                    round3(Math.max(0, Math.min(1, h.score()))),
                    "KB", List.of("sunbrain")));
        }
        hits.sort(Comparator.comparingDouble(KnowledgeRetrieveDtos.KnowledgeHit::score).reversed());
        List<KnowledgeRetrieveDtos.KnowledgeHit> top = hits.stream().limit(topK).toList();
        warnIfLowConfidence(top, requestId);
        if (top.isEmpty()) {
            log.info("knowledge.retrieve_miss 事件(sunbrain): requestId={}, query 哈希={}, 命中=0, 建议=检查 KB 绑定/补充语料", requestId, query.hashCode());
        }
        log.info("检索审计(sunbrain): requestId={}, query 哈希={}, searchRef={}, traceId={}, 命中={}",
                requestId, query.hashCode(), result.searchRef(), result.traceId(), top.size());
        return new KnowledgeRetrieveDtos.RetrieveResult(newRetrieveId(), query, top, top.size(),
                System.currentTimeMillis() - start);
    }

    /** 从 chunk 文本取标题（首行截 40 字，无换行取前 40 字） */
    private String titleOf(String text) {
        return abbreviate(text.strip().split("\n", 2)[0], 40);
    }

    private void validate(KnowledgeRetrieveDtos.RetrieveRequest req) {
        if (req == null || req.query() == null || req.query().isBlank()) {
            throw new BaiException(KnowledgeRetrieveErrorCodes.INVALID_INPUT, "KRE_1006 空查询，请检查 query");
        }
        if (req.query().length() > MAX_QUERY) {
            throw new BaiException(KnowledgeRetrieveErrorCodes.OVER_LIMIT, "KRE_1005 query 超 500 字（实际 " + req.query().length() + "），请精简");
        }
        if (req.libraries() != null) {
            for (String lib : req.libraries()) {
                if (!LIBRARIES.contains(lib)) {
                    throw new BaiException(KnowledgeRetrieveErrorCodes.INVALID_INPUT,
                            "KRE_1006 非法检索库：" + lib + "，仅支持 faq/policy/product/case/regulation");
                }
            }
        }
        if (req.channels() != null) {
            for (String ch : req.channels()) {
                if (!CHANNELS.contains(ch)) {
                    throw new BaiException(KnowledgeRetrieveErrorCodes.INVALID_INPUT,
                            "KRE_1006 非法通道：" + ch + "，仅支持 keyword/vector/graph");
                }
            }
        }
        if (req.topK() != null && (req.topK() < 1 || req.topK() > MAX_TOP_K)) {
            throw new BaiException(KnowledgeRetrieveErrorCodes.PARAM_INVALID, "KRE_1001 参数非法：topK 须在 1—20");
        }
    }

    /** 过滤：知识类型/来源/业务域/时效（法规默认仅取现行有效 effectiveDate≤today） */
    private boolean passFilters(CorpusEntry ce, Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        Object type = filters.get("type");
        if (type != null && !String.valueOf(type).equalsIgnoreCase(ce.type())) {
            return false;
        }
        Object source = filters.get("source");
        if (source != null && !String.valueOf(source).equalsIgnoreCase(ce.sourceType())) {
            return false;
        }
        Object bizDomain = filters.get("bizDomain");
        if (bizDomain != null && !ce.tags().contains(String.valueOf(bizDomain))) {
            return false;
        }
        return true; // 时效过滤：内置快照均为现行有效
    }

    /** 中文 2-gram + ASCII 词切分 */
    private List<String> grams(String text) {
        List<String> out = new ArrayList<>();
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        for (String word : normalized.split(" ")) {
            if (word.matches("[a-zA-Z0-9]+")) {
                out.add(word);
            } else {
                for (int i = 0; i + 2 <= word.length(); i++) {
                    out.add(word.substring(i, i + 2));
                }
                if (word.length() == 1) {
                    out.add(word);
                }
            }
        }
        return out;
    }

    /** Dice 相似度（词汇层仿真，代替 BM25 打分） */
    private double similarity(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> setA = new HashSet<>(a);
        Set<String> setB = new HashSet<>(b);
        long overlap = setA.stream().filter(setB::contains).count();
        if (overlap == 0) {
            return 0;
        }
        return (2.0 * overlap) / (setA.size() + setB.size());
    }

    /** 图谱一跳命中构建（仿真 ner-agent 实体识别：查询串与图谱实体匹配） */
    private List<KnowledgeRetrieveDtos.KnowledgeHit> buildGraphHits(String query, List<String> seedEntities) {
        List<KnowledgeRetrieveDtos.KnowledgeHit> hits = new ArrayList<>();
        Set<String> seeds = new HashSet<>(seedEntities == null || seedEntities.isEmpty() ? extractEntities(query) : seedEntities);
        int i = 1;
        for (String seed : seeds) {
            for (String[] parts : graph.getOrDefault(seed, List.of())) {
                hits.add(new KnowledgeRetrieveDtos.KnowledgeHit(
                        "GRP" + String.format("%03d", i++), "graph", "TRIPLE",
                        seed + "—" + parts[0] + "→" + parts[1],
                        String.format("图谱一跳（maxHop=1）：实体「%s」—%s→「%s」，关联知识：%s", seed, parts[0], parts[1], parts[2]),
                        new KnowledgeRetrieveDtos.SourceRef(parts[3], "知识图谱", parts[0]),
                        round3(0.72 + 0.05 * (parts[0].length() % 4)), "GRAPH", List.of("图谱", seed)));
            }
        }
        return hits;
    }

    /** 简易实体抽取（仿真 ner-agent：取查询中的名词短语，内置图谱实体匹配） */
    private Set<String> extractEntities(String query) {
        Set<String> hits = new HashSet<>();
        for (String entity : graph.keySet()) {
            if (query.contains(entity)) {
                hits.add(entity);
            }
        }
        return hits;
    }

    private void warnIfLowConfidence(List<KnowledgeRetrieveDtos.KnowledgeHit> hits, String requestId) {
        if (!hits.isEmpty() && hits.get(0).score() < LOW_CONFIDENCE) {
            log.warn("低置信检索: requestId={}, 最高分={} < 0.50", requestId, hits.get(0).score());
        }
    }

    private String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String newRetrieveId() {
        return "KRE" + dayStamp() + String.format("%05d", retrieveSeq.incrementAndGet());
    }

    private String dayStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    private double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** 只读消费 kge 索引快照（内置联调语料；真实环境增量同步≤10min） */
    private void loadCorpusSnapshot() {
        put("FAQ202600012", "faq", "FAQ", "提前还款是否收取违约金",
                "贷款发放后满 12 个月提前还款的，免收违约金；未满 12 个月提前还款按剩余本金的 1% 收取违约金。",
                new KnowledgeRetrieveDtos.SourceRef("DOC202605020", "产品手册第4章", "提前还款按剩余本金的 1% 收取违约金"),
                List.of("提前还款", "违约金"), "product");
        put("FAQ202600013", "faq", "FAQ", "贷款审批需要多长时间",
                "资料齐全情况下，个人贷款审批一般 3 个工作日内完成；小微企业贷款审批一般 5 个工作日内完成。",
                new KnowledgeRetrieveDtos.SourceRef("DOC202605020", "产品手册第2章", "审批时效"), List.of("审批", "时效"), "product");
        put("ENT202600001", "policy", "CLAUSE", "小微企业贷款期限",
                "小微企业流动资金贷款期限最长不超过 3 年，固定资产贷款期限最长不超过 5 年。",
                new KnowledgeRetrieveDtos.SourceRef("DOC202609001", "第3章第2节", null), List.of("制度", "小微", "期限"), "policy");
        put("ENT202600002", "policy", "RULE", "逾期分级规则",
                "逾期天数>90 天，五级分类下调至次级类。",
                new KnowledgeRetrieveDtos.SourceRef("DOC202609001", "第5章第1节", null), List.of("制度", "五级分类", "逾期"), "policy");
        put("PRD202600101", "product", "PRODUCT", "个人流动资金贷款产品要素",
                "个人流动资金贷款额度最高 100 万元，期限最长 3 年，担保方式支持信用与抵押，利率以审批为准。",
                new KnowledgeRetrieveDtos.SourceRef("DOC202604010", "产品要素表", null), List.of("产品", "额度", "期限"), "product");
        put("CASE202600201", "case", "CASE", "小微企业授信尽职免责案例",
                "某支行小微客户经理按规定流程完成贷前调查与贷后检查，因客户经营恶化形成逾期，经认定已尽职，予以免责。",
                new KnowledgeRetrieveDtos.SourceRef("DOC202603001", "案例第2篇", null), List.of("案例", "免责", "小微"), "case");
        put("REG202600110", "regulation", "REGULATION", "互联网贷款额度与期限要求",
                "单户用于消费的个人信用贷款授信额度应当不超过人民币 20 万元，到期一次性还本的，授信期限不超过一年。",
                new KnowledgeRetrieveDtos.SourceRef("REG202600110", "第8条", "授信额度应当不超过人民币 20 万元"),
                List.of("法规", "额度", "互联网贷款"), "regulation");
    }

    private void put(String id, String library, String type, String title, String content,
                     KnowledgeRetrieveDtos.SourceRef source, List<String> tags, String sourceType) {
        corpus.put(id, new CorpusEntry(id, library, type, title, content, source, tags, sourceType));
    }

    /** 内置知识图谱快照（实体 → 一跳关系：[关系, 目标, 关联知识, docId]） */
    private void loadGraphSnapshot() {
        graph.put("提前还款", List.of(
                new String[]{"涉及费用", "违约金", "FAQ202600012 提前还款违约金", "DOC202605020"},
                new String[]{"适用产品", "个人流动资金贷款", "PRD202600101 产品要素", "DOC202604010"}));
        graph.put("逾期", List.of(
                new String[]{"触发处置", "五级分类下调", "ENT202600002 逾期分级规则", "DOC202609001"},
                new String[]{"关联指标", "逾期天数", "ENT202600002 逾期分级规则", "DOC202609001"}));
        graph.put("小微企业", List.<String[]>of(
                new String[]{"适用制度", "授信管理办法", "ENT202600001 贷款期限", "DOC202609001"}));
    }

    /** 语料条目（内部结构） */
    private record CorpusEntry(String entryId, String library, String type, String title, String content,
                               KnowledgeRetrieveDtos.SourceRef source, List<String> tags, String sourceType) {}
}
