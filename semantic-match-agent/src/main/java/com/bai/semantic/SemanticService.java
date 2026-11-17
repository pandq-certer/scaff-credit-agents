package com.bai.semantic;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 语义匹配服务。MVP 阶段为确定性仿真引擎（标注：仿真，用于联调）：
 * 相似度以字符二元组 Dice 系数近似双塔（Sentence-BERT 系 768 维）余弦相似度；检索以查询哈希派生确定性分数模拟向量召回。
 * <p>处理链：双塔编码 → 余弦相似度与温度校准 → 按场景阈值四级分级 → 检索（仿真 TopK 派生）
 * → 交叉编码器重排（rerank=true 且候选 &gt;5，≤100 候选）→ 理由（最长公共子串）→ 落库与未命中事件
 */
@Service
public class SemanticService {

    private static final Logger log = LoggerFactory.getLogger(SemanticService.class);

    private static final int MAX_TEXT = 2000;
    private static final int MAX_BATCH = 100000;
    private static final int MAX_TOPK = 100;
    private static final int MAX_RERANK = 100;

    /** 语料库（仿真内置快照） */
    private static final Set<String> CORPUS_TYPES =
            Set.of("knowledge_faq", "case_library", "utterance_lib", "clause_lib");

    /** 场景阈值映射（默认 0.95/0.80/0.60；dedup、clause_compare/case_retrieval、utterance_recommend 可覆盖） */
    private static final Map<String, SemanticDtos.Threshold> SCENE_THRESHOLDS = Map.of(
            "dedup", new SemanticDtos.Threshold(0.92, 0.78, 0.55),
            "clause_compare", new SemanticDtos.Threshold(0.90, 0.75, 0.55),
            "case_retrieval", new SemanticDtos.Threshold(0.90, 0.75, 0.55),
            "utterance_recommend", new SemanticDtos.Threshold(0.88, 0.72, 0.50));
    private static final SemanticDtos.Threshold DEFAULT_THRESHOLD = new SemanticDtos.Threshold(0.95, 0.80, 0.60);

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final AtomicLong seq = new AtomicLong();

    /** 文本对相似度与分级（幂等：requestId 相同返回首次结果，缓存 24h） */
    public SemanticDtos.MatchResult match(SemanticDtos.MatchRequest req, String requestId) {
        SemanticDtos.MatchResult cached = idempotency.get("match", requestId);
        if (cached != null) {
            return cached;
        }
        SemanticDtos.MatchResult result = doMatch(req, requestId);
        idempotency.put("match", requestId, result);
        return result;
    }

    /** 语义检索/重排（幂等：requestId 相同返回首次结果，缓存 24h） */
    public SemanticDtos.SearchResult search(SemanticDtos.SearchRequest req, String requestId) {
        SemanticDtos.SearchResult cached = idempotency.get("search", requestId);
        if (cached != null) {
            return cached;
        }
        SemanticDtos.SearchResult result = doSearch(req, requestId);
        idempotency.put("search", requestId, result);
        return result;
    }

    /** 批量匹配（≤100000 对，异步；条目未定义批量任务查询接口，结果由调度器落库） */
    public SemanticDtos.BatchResult batch(SemanticDtos.BatchRequest req, String requestId) {
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BaiException(SemanticErrorCodes.PARAM_INVALID, "SEM_1001 参数缺失：items 批量项必填");
        }
        if (req.items().size() > MAX_BATCH) {
            throw new BaiException(SemanticErrorCodes.TEXT_TOO_LONG, "SEM_1005 批量超 100000 对，请截断或分批");
        }
        for (int i = 0; i < req.items().size(); i++) {
            SemanticDtos.MatchRequest item = req.items().get(i);
            if (item == null || isBlankOrSymbol(item.queryText()) || isBlankOrSymbol(item.candidateText())) {
                throw new BaiException(SemanticErrorCodes.EMPTY_TEXT,
                        "SEM_1006 批量第 " + (i + 1) + " 对存在空文本/纯符号，请修正后提交");
            }
        }
        String batchId = "SEMB" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%05d", seq.incrementAndGet());
        log.info("语义批量任务受理(仿真): requestId={}, batchId={}, total={}（异步执行，结果落库 B_semantic_match_T）",
                requestId, batchId, req.items().size());
        return new SemanticDtos.BatchResult(batchId, "ACCEPTED", req.items().size());
    }

    /** 模型信息（仿真内置快照） */
    public SemanticDtos.SemanticModels models() {
        return new SemanticDtos.SemanticModels(
                "sentence-bert-v2（双塔编码器）", 768, "cross-encoder-v1（精排，≤100 候选）",
                "FAISS IVF-PQ（nprobe=64，库 ≤1000 万条）", "v2026.09");
    }

    // ---------------- 内部处理链 ----------------

    private SemanticDtos.MatchResult doMatch(SemanticDtos.MatchRequest req, String requestId) {
        if (req == null || req.queryText() == null || req.candidateText() == null) {
            throw new BaiException(SemanticErrorCodes.PARAM_INVALID, "SEM_1001 参数缺失：queryText/candidateText 必填");
        }
        validateText(req.queryText(), "queryText");
        validateText(req.candidateText(), "candidateText");

        long start = System.currentTimeMillis();
        String scene = req.scene() == null || req.scene().isBlank() ? "knowledge_qa" : req.scene();
        boolean enableReason = req.enableReason() == null || req.enableReason();

        // 2. 双塔编码仿真：字符二元组 Dice 系数近似余弦相似度（确定性）
        double score = similarity(req.queryText(), req.candidateText());
        SemanticDtos.Threshold threshold = SCENE_THRESHOLDS.getOrDefault(scene, DEFAULT_THRESHOLD);

        // 3. 相似度四级分级
        String level;
        if (score >= threshold.identical()) {
            level = "IDENTICAL";
        } else if (score >= threshold.highlySimilar()) {
            level = "HIGHLY_SIMILAR";
        } else if (score >= threshold.related()) {
            level = "RELATED";
        } else {
            level = "DIFFERENT";
        }

        // 6. 理由：token 级高相似片段（最长公共子串）
        List<SemanticDtos.MatchReason> reasons = null;
        if (enableReason) {
            String lcs = longestCommonSubstring(req.queryText(), req.candidateText());
            String basis = lcs.isEmpty() ? truncate(req.queryText(), 20) : lcs;
            reasons = List.of(new SemanticDtos.MatchReason(basis, score));
        }

        long elapsed = System.currentTimeMillis() - start;
        SemanticDtos.MatchResult result = new SemanticDtos.MatchResult(
                nextId("SEM"), scene, score, level, threshold, reasons, elapsed);

        // 7. 落库与事件（明文不落库；未命中事件供 FAQ 补全消费）
        if ("DIFFERENT".equals(level)) {
            log.info("语义未命中事件(仿真): requestId={}, scene={}, score={}", requestId, scene, score);
        }
        log.info("语义匹配审计(仿真): requestId={}, scene={}, textHash={}, score={}, level={}, elapsed={}ms",
                requestId, scene, Objects.hash(req.queryText(), req.candidateText()), score, level, elapsed);
        return result;
    }

    private SemanticDtos.SearchResult doSearch(SemanticDtos.SearchRequest req, String requestId) {
        if (req == null || req.queryText() == null) {
            throw new BaiException(SemanticErrorCodes.PARAM_INVALID, "SEM_1001 参数缺失：queryText/corpus 必填");
        }
        validateText(req.queryText(), "queryText");
        if (req.corpus() == null || !CORPUS_TYPES.contains(req.corpus())) {
            throw new BaiException(SemanticErrorCodes.NOT_FOUND, "SEM_1004 语料库不存在：" + req.corpus());
        }
        int topK = req.topK() == null ? 20 : req.topK();
        if (topK < 1 || topK > MAX_TOPK) {
            throw new BaiException(SemanticErrorCodes.PARAM_INVALID, "SEM_1001 topK 非法：取值 1—100");
        }
        boolean rerank = req.rerank() != null && req.rerank();

        long start = System.currentTimeMillis();
        String scene = req.scene() == null || req.scene().isBlank() ? "knowledge_qa" : req.scene();

        // 4. 语义检索仿真：以查询哈希派生确定性降序分数模拟向量召回（候选 ≤10000 精确检索）
        int seed = Math.abs(Objects.hash(req.queryText(), req.corpus()));
        double base = 0.94 - (seed % 15) * 0.01; // 0.80—0.94
        String source = rerank && topK > 5 ? "RERANK" : "VECTOR"; // rerank=true 且候选 >5 → 交叉编码器精排
        List<SemanticDtos.Hit> hits = new ArrayList<>();
        for (int i = 0; i < topK; i++) {
            double s = round4(Math.max(0.30, base - i * 0.015));
            hits.add(new SemanticDtos.Hit(
                    "DOC" + String.format("%08X", seed) + "-" + (i + 1),
                    req.corpus() + " 候选片段" + (i + 1) + "（仿真，用于联调）", s, i + 1, source));
        }

        long elapsed = System.currentTimeMillis() - start;
        SemanticDtos.SearchResult result = new SemanticDtos.SearchResult(
                nextId("SEM"), scene, req.corpus(), hits, topK, elapsed);

        boolean noHit = hits.isEmpty() || hits.get(0).score() < 0.60;
        log.info("语义检索审计(仿真): requestId={}, scene={}, corpus={}, textHash={}, topK={}, rerank={}, noHit={}, elapsed={}ms",
                requestId, scene, req.corpus(), Objects.hash(req.queryText()), topK, rerank, noHit, elapsed);
        return result;
    }

    private void validateText(String text, String field) {
        if (isBlankOrSymbol(text)) {
            throw new BaiException(SemanticErrorCodes.EMPTY_TEXT, "SEM_1006 空文本/纯符号：" + field);
        }
        if (text.length() > MAX_TEXT) {
            // 输出约束：单文本 ≤2000 字，超长截断
            throw new BaiException(SemanticErrorCodes.TEXT_TOO_LONG, "SEM_1005 " + field + " 超过 2000 字，请截断或分批");
        }
    }

    /** 字符二元组 Dice 系数（0—1，确定性） */
    private double similarity(String a, String b) {
        Set<String> ba = bigrams(a);
        Set<String> bb = bigrams(b);
        if (ba.isEmpty() || bb.isEmpty()) {
            return a.equals(b) ? 1.0 : 0.0;
        }
        Set<String> inter = new HashSet<>(ba);
        inter.retainAll(bb);
        return round4(2.0 * inter.size() / (ba.size() + bb.size()));
    }

    private Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        String t = s.replaceAll("\\s+", "");
        for (int i = 0; i < t.length() - 1; i++) {
            set.add(t.substring(i, i + 2));
        }
        return set;
    }

    /** 最长公共子串（滚动数组 DP，理由基准片段） */
    private String longestCommonSubstring(String a, String b) {
        String x = a.length() <= b.length() ? a : b;
        String y = a.length() <= b.length() ? b : a;
        int[] prev = new int[y.length() + 1];
        int[] cur = new int[y.length() + 1];
        int best = 0;
        int bestEnd = 0;
        for (int i = 1; i <= x.length(); i++) {
            for (int j = 1; j <= y.length(); j++) {
                cur[j] = x.charAt(i - 1) == y.charAt(j - 1) ? prev[j - 1] + 1 : 0;
                if (cur[j] > best) {
                    best = cur[j];
                    bestEnd = j;
                }
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
            Arrays.fill(cur, 0);
        }
        return best == 0 ? "" : y.substring(bestEnd - best, bestEnd);
    }

    private boolean isBlankOrSymbol(String text) {
        return text == null || text.isBlank() || text.codePoints().noneMatch(Character::isLetterOrDigit);
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private String nextId(String prefix) {
        return prefix + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%05d", seq.incrementAndGet());
    }
}
