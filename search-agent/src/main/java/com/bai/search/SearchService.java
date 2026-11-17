package com.bai.search;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import com.bai.common.TokenBuckets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多源搜索服务。MVP 阶段为确定性仿真引擎（标注：仿真，用于联调），internet 经搜索代理池、
 * knowledge 源转调 knowledge-retrieve-agent、重排转调 semantic-match-agent，均以事件日志占位。
 * <p>处理链：校验入参与源级授权 → 源并行查询（≤5 源，频控≤10 QPS/源）→ 结果归一化（simhash&gt;0.90 去重）
 * → 黑名单/时间/siteType 过滤 → 排序（sourceWeight×relevance×recencyDecay）与可信度标记
 * → 可选 semantic-match 重排 top50 → enableSnapshot 时 top3 存快照 → 落库（query 只存哈希）发事件
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private static final int MAX_QUERY = 200;
    private static final int MAX_BATCH = 5000;
    private static final int MAX_RESULTS = 50;
    private static final Set<String> SOURCES = Set.of("internet", "internal", "external", "knowledge");
    private static final Map<String, Double> SOURCE_WEIGHTS = Map.of(
            "internet", 0.30, "internal", 0.35, "external", 0.20, "knowledge", 0.15);
    private static final Map<String, Double> SITE_CREDIBILITY = Map.of(
            "GOV", 0.95, "OFFICIAL", 0.85, "MEDIA", 0.70, "OTHER", 0.50);
    /** 合规黑名单（query 命中即拒，仿真词表） */
    private static final List<String> FORBIDDEN = List.of("身份证号查询", "开房记录", "通话记录", "后台密码", "内网地址", "人脸库下载");
    private static final String[] SITE_TYPES = {"GOV", "OFFICIAL", "MEDIA", "OTHER"};
    private static final long HOT_TTL_MILLIS = 5 * 60 * 1000L; // 热门查询缓存 TTL 5min

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final Map<String, SearchDtos.Snapshot> snapshots = new ConcurrentHashMap<>();
    /** 源频控：≤10 QPS/源（超时返回部分结果，仿真直接跳过该源并发 search.source_error 事件） */
    private final TokenBuckets sourceLimiter = new TokenBuckets(10, 10);
    private final AtomicLong seq = new AtomicLong();

    /** 热门查询缓存（TTL 5min）：key=sha256(query|sources|topK) */
    private record HotEntry(SearchDtos.SearchOutput value, long expireAt) {}
    private final Map<String, HotEntry> hotCache = new ConcurrentHashMap<>();

    /** 同步搜索（幂等：requestId 相同返回首次结果，缓存 24h） */
    public SearchDtos.SearchOutput search(SearchDtos.SearchRequest req, String requestId) {
        SearchDtos.SearchOutput cached = idempotency.get("search", requestId);
        if (cached != null) {
            return cached;
        }
        String query = req == null ? null : req.query();
        if (query == null || query.isBlank() || isPureSymbol(query)) {
            throw new BaiException(SearchErrorCodes.INVALID_QUERY, "SEA_1006 空查询/纯符号，请修正输入");
        }
        if (query.length() > MAX_QUERY) {
            throw new BaiException(SearchErrorCodes.LIMIT_EXCEEDED, "SEA_1005 query 超 200 字，请截断");
        }
        for (String f : FORBIDDEN) {
            if (query.contains(f)) {
                throw new BaiException(SearchErrorCodes.INVALID_QUERY, "SEA_1006 黑名单命中，禁止检索该内容");
            }
        }
        List<String> sources = normalizeSources(req.sources());
        int topK = req.topK() == null ? 10 : req.topK();
        if (topK < 1 || topK > MAX_RESULTS) {
            throw new BaiException(SearchErrorCodes.PARAM_INVALID, "SEA_1001 topK 非法：取值 1—50");
        }
        boolean enableSnapshot = req.enableSnapshot() != null && req.enableSnapshot();
        boolean rerank = req.rerank() != null && req.rerank();

        String hotKey = sha256Hex(query + "|" + String.join(",", sources) + "|" + topK);
        SearchDtos.SearchOutput hot = hotGet(hotKey);
        if (hot != null) {
            idempotency.put("search", requestId, hot);
            return hot;
        }

        SearchDtos.SearchOutput output = simulate(query, sources, topK, req.filters(), enableSnapshot, rerank, requestId);
        idempotency.put("search", requestId, output);
        hotPut(hotKey, output);
        log.info("搜索审计(仿真): requestId={}, queryHash={}, sources={}, total={}, snapshotCount={}, elapsed={}ms",
                requestId, sha256Hex(query), sources, output.total(),
                output.results().stream().filter(i -> i.snapshotId() != null).count(), output.elapsedMs());
        return output;
    }

    /** 批量异步搜索（≤5000 条；条目未定义批量任务查询接口，结果由调度器落库） */
    public SearchDtos.BatchResult batch(SearchDtos.BatchRequest req, String requestId) {
        if (req == null || req.queries() == null || req.queries().isEmpty()) {
            throw new BaiException(SearchErrorCodes.PARAM_INVALID, "SEA_1001 参数缺失：queries 批量查询必填");
        }
        if (req.queries().size() > MAX_BATCH) {
            throw new BaiException(SearchErrorCodes.LIMIT_EXCEEDED, "SEA_1005 批量超 5000 条，请截断或分批");
        }
        for (String q : req.queries()) {
            if (q == null || q.isBlank() || isPureSymbol(q)) {
                throw new BaiException(SearchErrorCodes.INVALID_QUERY, "SEA_1006 批量项存在空查询，请修正后提交");
            }
        }
        String batchId = "SEAB" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%05d", seq.incrementAndGet());
        log.info("搜索批量任务受理(仿真): requestId={}, batchId={}, total={}（异步执行，结果落库 C_search_T）",
                requestId, batchId, req.queries().size());
        return new SearchDtos.BatchResult(batchId, "ACCEPTED", req.queries().size());
    }

    /** 快照查询（仅存公开内容、涉隐私脱敏，仅用于尽调留证不对外分发；保留≥1 年） */
    public SearchDtos.Snapshot snapshot(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new BaiException(SearchErrorCodes.NOT_FOUND, "SEA_1004 快照 ID 缺失，请校验参数");
        }
        SearchDtos.Snapshot snap = snapshots.get(snapshotId);
        if (snap == null) {
            throw new BaiException(SearchErrorCodes.NOT_FOUND,
                    "SEA_1004 快照不存在：请确认查询时 enableSnapshot=true 且 snapshotId 正确");
        }
        return snap;
    }

    /** 搜索源信息（weight 与源级频控） */
    public List<SearchDtos.SourceInfo> sources() {
        return List.of(
                new SearchDtos.SourceInfo("internet", 0.30, 10, true),
                new SearchDtos.SourceInfo("internal", 0.35, 10, true),
                new SearchDtos.SourceInfo("external", 0.20, 10, true),
                new SearchDtos.SourceInfo("knowledge", 0.15, 10, true));
    }

    // ---------------- 仿真引擎（确定性：同 requestId+query 结果一致） ----------------

    private record Scored(SearchDtos.ResultItem item, double score) {}

    private SearchDtos.SearchOutput simulate(String query, List<String> sources, int topK,
                                             SearchDtos.Filters filters, boolean enableSnapshot,
                                             boolean rerank, String requestId) {
        long start = System.currentTimeMillis();
        Random r = new Random(Objects.hash(requestId, query));
        List<Scored> scored = new ArrayList<>();
        int perSource = Math.max(1, topK / sources.size());
        for (String source : sources) {
            // 源频控 ≤10 QPS/源：排队≤5s 超时返回部分结果（仿真：跳过该源）
            if (!sourceLimiter.allow(source)) {
                log.warn("search.source_error 事件(仿真): requestId={}, source={}, reason=FREQ_CONTROL", requestId, source);
                continue;
            }
            if ("knowledge".equals(source)) {
                log.info("knowledge 源转调 knowledge-retrieve-agent(仿真): requestId={}", requestId);
            }
            for (int i = 0; i < perSource && scored.size() < topK; i++) {
                Scored s = generate(r, source, query, filters, i);
                if (s != null) {
                    scored.add(s);
                }
            }
        }
        if (scored.isEmpty()) {
            log.info("全部源无命中，返回 0 条(仿真)，建议：放宽 filters 或更换查询词, requestId={}", requestId);
        }

        // 排序：score = sourceWeight × relevance × recencyDecay 降序
        // 归一化+simhash&gt;0.90 去重：仿真生成数据天然唯一，不触发合并
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<SearchDtos.ResultItem> results = new ArrayList<>();
        for (Scored s : scored) {
            results.add(s.item());
        }

        if (rerank && results.size() > 5) {
            // rerank=true 且候选&gt;5：调 semantic-match-agent 对 top50 精排（仿真：保持相关度排序，仅记录事件）
            log.info("semantic-match 精排(仿真): requestId={}, candidates={}", requestId, results.size());
        }

        if (enableSnapshot) {
            for (int i = 0; i < Math.min(3, results.size()); i++) {
                SearchDtos.ResultItem item = results.get(i);
                String snapId = "SNAP" + Year.now().getValue()
                        + String.format("%05d", seq.incrementAndGet());
                snapshots.put(snapId, new SearchDtos.Snapshot(snapId, item.url(), item.source(),
                        LocalDateTime.now().withNano(0).toString(),
                        "HTML转文本快照（仿真，用于联调）\n标题: " + item.title() + "\n正文: " + item.snippet()));
                results.set(i, new SearchDtos.ResultItem(item.resultId(), item.source(), item.title(), item.url(),
                        item.snippet(), item.date(), item.siteType(), item.credibility(), snapId, item.warning()));
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        return new SearchDtos.SearchOutput("SEA" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%05d", seq.incrementAndGet()), query, results, results.size(), elapsed);
    }

    /** 单条结果生成：归一化字段映射 + 黑名单/时间/siteType 过滤（不合法返回 null） */
    private Scored generate(Random r, String source, String query, SearchDtos.Filters filters, int idx) {
        String siteType = SITE_TYPES[r.nextInt(SITE_TYPES.length)];
        double credibility = SITE_CREDIBILITY.get(siteType);
        int days = r.nextInt(180);
        String date = LocalDate.now().minusDays(days).toString();
        double relevance = round4(0.50 + r.nextDouble() * 0.49);
        double recencyDecay = round4(1.0 / (1.0 + days / 90.0));
        double score = round4(SOURCE_WEIGHTS.get(source) * relevance * recencyDecay);

        String title = "【" + siteType + "】" + query + " 相关信息（仿真，用于联调）#" + (idx + 1);
        String url = switch (source) {
            case "internal" -> "https://intranet.example.bank/kb/" + Integer.toHexString(r.nextInt(0x100000));
            case "external" -> "https://ext.example.com/data/" + Integer.toHexString(r.nextInt(0x100000));
            case "knowledge" -> "https://kbase.example.bank/wiki/" + Integer.toHexString(r.nextInt(0x100000));
            default -> "https://www.example-" + siteType.toLowerCase() + ".com/doc/"
                    + Integer.toHexString(r.nextInt(0x100000));
        };
        String snippet = "与「" + query + "」相关的仿真摘要：内容片段 " + (idx + 1) + "，发布于 " + date + "。（仿真，用于联调）";

        if (filters != null) {
            if (filters.blacklist() != null) {
                for (String b : filters.blacklist()) {
                    if (b != null && !b.isBlank() && (title.contains(b) || snippet.contains(b))) {
                        return null;
                    }
                }
            }
            if (filters.siteType() != null && !filters.siteType().isBlank()
                    && !siteType.equalsIgnoreCase(filters.siteType())) {
                return null;
            }
            if (filters.dateFrom() != null && date.compareTo(filters.dateFrom()) < 0) {
                return null;
            }
            if (filters.dateTo() != null && date.compareTo(filters.dateTo()) > 0) {
                return null;
            }
        }
        // credibility&lt;0.50 置 warning（仿真站点可信度下限 0.50，实际不触发）
        boolean warning = credibility < 0.50;
        String resultId = "RES" + Year.now().getValue() + String.format("%05d", seq.incrementAndGet());
        return new Scored(new SearchDtos.ResultItem(resultId, source, title, url, snippet, date,
                siteType, credibility, null, warning), score);
    }

    // ---------------- 校验与工具 ----------------

    private List<String> normalizeSources(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of("internet");
        }
        List<String> normalized = new ArrayList<>();
        for (String s : sources) {
            if (s == null || !SOURCES.contains(s)) {
                throw new BaiException(SearchErrorCodes.INVALID_QUERY,
                        "SEA_1006 非法源枚举：internet/internal/external/knowledge");
            }
            if (!normalized.contains(s)) {
                normalized.add(s);
            }
        }
        return normalized;
    }

    private boolean isPureSymbol(String text) {
        return text.codePoints().noneMatch(Character::isLetterOrDigit);
    }

    private SearchDtos.SearchOutput hotGet(String key) {
        HotEntry e = hotCache.get(key);
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() > e.expireAt()) {
            hotCache.remove(key);
            return null;
        }
        return e.value();
    }

    private void hotPut(String key, SearchDtos.SearchOutput value) {
        hotCache.put(key, new HotEntry(value, System.currentTimeMillis() + HOT_TTL_MILLIS));
    }

    private String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(text.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
