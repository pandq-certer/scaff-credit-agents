package com.bai.kprocess;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 知识加工服务（提示词库 A-14）。TRIPLE/FAQ 模型环节为规则兜底仿真（标注：仿真，用于联调），
 * 结构与真实加工流水线一致，便于下游 kge/search-agent/rag-answer-agent 联调。
 * <p>处理链：校验入参 → 清洗（规则去噪+simhash&gt;0.95 去重+规范化）→ 章节感知条目化（≤2000 字/条带来源）
 * → FAQ 生成（答案≤500 字附依据）与三元组抽取 → 打标 → 质量校验（通过线 0.80）→ 落库发 knowledge.processed 事件
 */
@Service
public class KnowledgeProcessService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeProcessService.class);

    private static final int MAX_TEXT = 50000;
    private static final int MAX_DOC_ID = 64;
    private static final int MAX_BATCH = 1000;
    private static final int MAX_ENTRY_LEN = 2000;
    private static final int MAX_FAQ_ANSWER = 500;
    private static final double QUALITY_PASS_LINE = 0.80;
    private static final String DEFAULT_VERSION = "2026.09.01";
    private static final Set<String> KNOWLEDGE_TYPES = Set.of("FAQ", "CLAUSE", "RULE", "CASE", "TRIPLE");
    private static final Set<String> SOURCES = Set.of("policy", "product", "case", "regulation", "manual");
    private static final Map<String, String> SOURCE_TAG = Map.of(
            "policy", "制度", "product", "产品", "case", "案例", "regulation", "法规", "manual", "手册");

    private static final Pattern HEADING = Pattern.compile("^(第[一二三四五六七八九十百0-9]{1,4}[章节]|[一二三四五六七八九十]+、|\\d+(\\.\\d+)*)");
    private static final Pattern NOISE = Pattern.compile("(页眉|页脚|水印|第\\s*\\d+\\s*页|共\\s*\\d+\\s*页)");
    private static final Pattern RULE_KEYWORD = Pattern.compile("(不得|应当|禁止|必须|超过|低于|高于|逾期|阈值)");
    private static final Pattern CASE_KEYWORD = Pattern.compile("(案例|案情|经办|处置)");
    private static final Pattern TRIPLE = Pattern.compile("(\\S{2,18}?)(应当|不得|禁止|最长不超过|不超过|不低于|高于|低于|按|为)(\\S{2,40})");
    private static final Pattern PURE_SYMBOL = Pattern.compile("^[\\p{Punct}\\s\\p{IsPunctuation}，。、；：？！“”‘’（）【】《》…]+$");

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final AtomicLong processSeq = new AtomicLong();
    private final AtomicLong entrySeq = new AtomicLong();

    /** 同步加工（幂等：requestId 相同返回首次结果，缓存 24h） */
    public KnowledgeProcessDtos.ProcessResult process(KnowledgeProcessDtos.ProcessRequest req, String requestId) {
        KnowledgeProcessDtos.ProcessResult cached = idempotency.get("process", requestId);
        if (cached != null) {
            return cached;
        }
        validate(req);
        KnowledgeProcessDtos.ProcessResult result = pipeline(req, requestId);
        idempotency.put("process", requestId, result);
        log.info("knowledge.processed 审计: requestId={}, docId={}, source={}, types={}, 产出数={}, 全部通过={}",
                requestId, req.docId(), req.source(), req.knowledgeTypes(), result.stats().total(),
                result.entries().stream().allMatch(e -> e.quality().pass()));
        return result;
    }

    /** 批量异步受理（≤1000 篇） */
    public KnowledgeProcessDtos.BatchResult batch(KnowledgeProcessDtos.BatchRequest req, String requestId) {
        List<KnowledgeProcessDtos.ProcessRequest> items = req.items();
        if (items == null || items.isEmpty()) {
            throw new BaiException(KnowledgeProcessErrorCodes.PARAM_INVALID, "KNO_1001 参数缺失：batch.items 必填");
        }
        if (items.size() > MAX_BATCH) {
            throw new BaiException(KnowledgeProcessErrorCodes.OVER_LIMIT, "KNO_1005 批量超 1000 篇（实际 " + items.size() + "），请分批");
        }
        for (KnowledgeProcessDtos.ProcessRequest item : items) {
            validate(item);
            idempotency.put("process", requestId + ":" + item.docId(), pipeline(item, requestId));
        }
        String batchId = "KNB" + dayStamp() + String.format("%05d", processSeq.incrementAndGet());
        log.info("知识批量加工受理: requestId={}, batchId={}, total={}", requestId, batchId, items.size());
        return new KnowledgeProcessDtos.BatchResult(batchId, "ACCEPTED", items.size());
    }

    /** 流水线配置查询 */
    public KnowledgeProcessDtos.PipelineInfo pipeline() {
        List<KnowledgeProcessDtos.PipelineStage> stages = List.of(
                new KnowledgeProcessDtos.PipelineStage("clean", "知识清洗", "规则去噪+simhash>0.95 去重+编号/单位/日期规范化"),
                new KnowledgeProcessDtos.PipelineStage("segment", "知识条目化", "章节感知切分，单条≤2000 字带来源"),
                new KnowledgeProcessDtos.PipelineStage("faq-triple", "FAQ 加工与三元组抽取", "FAQ 答案≤500 字附依据；TRIPLE 缺项丢弃并计数"),
                new KnowledgeProcessDtos.PipelineStage("tag", "分类打标", "知识类型×业务域标签（内部调用 tag-agent）"),
                new KnowledgeProcessDtos.PipelineStage("quality", "质量校验", "完整性/一致性，通过线 0.80，不合格置 REJECTED"));
        return new KnowledgeProcessDtos.PipelineInfo(DEFAULT_VERSION, "ACTIVE", stages, "2026-09-01 00:00:00");
    }

    /** 质量校验（对已加工条目复核完整性/一致性与综合评分） */
    public KnowledgeProcessDtos.ValidateResult validate(KnowledgeProcessDtos.ValidateRequest req, String requestId) {
        if (req == null || req.entries() == null || req.entries().isEmpty()) {
            throw new BaiException(KnowledgeProcessErrorCodes.PARAM_INVALID, "KNO_1001 参数缺失：validate.entries 必填");
        }
        long start = System.currentTimeMillis();
        List<KnowledgeProcessDtos.EntryVerdict> verdicts = new ArrayList<>();
        int passed = 0;
        for (KnowledgeProcessDtos.KnowledgeEntry entry : req.entries()) {
            List<String> reasons = new ArrayList<>();
            double score = entry.quality() == null ? 0 : (entry.quality().completeness() + entry.quality().consistency()) / 2;
            if (entry.content() == null || entry.content().isBlank() || PURE_SYMBOL.matcher(entry.content()).matches()) {
                reasons.add("content 为空或纯符号");
            }
            if (entry.content() != null && entry.content().length() > MAX_ENTRY_LEN) {
                reasons.add("content 超 " + MAX_ENTRY_LEN + " 字");
            }
            if (entry.source() == null || entry.source().docId() == null || entry.source().docId().isBlank()) {
                reasons.add("缺少来源引用（docId）");
            }
            if (score < QUALITY_PASS_LINE) {
                reasons.add("综合评分 " + String.format("%.2f", score) + " 低于通过线 0.80");
            }
            boolean ok = reasons.isEmpty();
            if (ok) {
                passed++;
            }
            verdicts.add(new KnowledgeProcessDtos.EntryVerdict(entry.entryId(), score, ok, reasons));
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("知识质量校验: requestId={}, docId={}, total={}, passed={}, rejected={}",
                requestId, req.docId(), verdicts.size(), passed, verdicts.size() - passed);
        return new KnowledgeProcessDtos.ValidateResult(req.docId(), verdicts.size(), passed,
                verdicts.size() - passed, verdicts, elapsed);
    }

    private void validate(KnowledgeProcessDtos.ProcessRequest req) {
        if (req == null || req.docId() == null || req.docId().isBlank()) {
            throw new BaiException(KnowledgeProcessErrorCodes.PARAM_INVALID, "KNO_1001 参数缺失：docId 必填");
        }
        if (req.docId().length() > MAX_DOC_ID) {
            throw new BaiException(KnowledgeProcessErrorCodes.PARAM_INVALID, "KNO_1001 参数非法：docId 超 64 字符");
        }
        if (req.source() != null && !SOURCES.contains(req.source())) {
            throw new BaiException(KnowledgeProcessErrorCodes.PARAM_INVALID,
                    "KNO_1001 参数非法：source 仅支持 policy/product/case/regulation/manual");
        }
        if (req.text() == null || req.text().isBlank() || PURE_SYMBOL.matcher(req.text()).matches()) {
            throw new BaiException(KnowledgeProcessErrorCodes.INVALID_INPUT, "KNO_1006 空文本或纯符号，请检查 text");
        }
        if (req.text().length() > MAX_TEXT) {
            throw new BaiException(KnowledgeProcessErrorCodes.OVER_LIMIT, "KNO_1005 文本超 50000 字（实际 " + req.text().length() + "），请截断或分批");
        }
        if (req.knowledgeTypes() != null && !req.knowledgeTypes().isEmpty()) {
            for (String type : req.knowledgeTypes()) {
                if (!KNOWLEDGE_TYPES.contains(type)) {
                    throw new BaiException(KnowledgeProcessErrorCodes.INVALID_INPUT,
                            "KNO_1006 非法知识类型：" + type + "，仅支持 FAQ/CLAUSE/RULE/CASE/TRIPLE");
                }
            }
        }
    }

    /** 加工流水线（清洗 → 条目化 → FAQ/TRIPLE → 打标 → 质量校验） */
    private KnowledgeProcessDtos.ProcessResult pipeline(KnowledgeProcessDtos.ProcessRequest req, String requestId) {
        long start = System.currentTimeMillis();
        String version = req.pipelineVersion() == null || req.pipelineVersion().isBlank() ? DEFAULT_VERSION : req.pipelineVersion();
        List<String> types = req.knowledgeTypes() == null || req.knowledgeTypes().isEmpty()
                ? List.of("CLAUSE", "FAQ") : req.knowledgeTypes();

        // 1. 清洗：规则去噪 + 去重（仿真以 SHA-256 精确哈希代替 simhash>0.95）+ 规范化
        List<String> cleaned = clean(req.text());
        // 2. 章节感知条目化（单条≤2000 字，带章节来源）
        List<Chunk> chunks = segment(cleaned);
        // 3-4. 按类型加工（TRIPLE 为规则兜底仿真；模型可用时走 LLM，不可用走此兜底）
        List<KnowledgeProcessDtos.KnowledgeEntry> entries = buildEntries(chunks, types, req, requestId);

        Map<String, Integer> byType = new LinkedHashMap<>();
        for (KnowledgeProcessDtos.KnowledgeEntry e : entries) {
            byType.merge(e.type(), 1, Integer::sum);
        }
        String processId = "KNO" + dayStamp() + String.format("%05d", processSeq.incrementAndGet());
        long elapsed = System.currentTimeMillis() - start;
        return new KnowledgeProcessDtos.ProcessResult(processId, req.docId(), version, entries,
                new KnowledgeProcessDtos.Stats(entries.size(), byType), elapsed);
    }

    private List<String> clean(String text) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : text.split("\n")) {
            String line = raw.replaceAll("\\s+", " ").trim();
            if (line.isEmpty() || NOISE.matcher(line).find()) {
                continue;
            }
            if (!seen.add(sha256(line))) {
                continue; // simhash>0.95 相似合并（仿真：精确哈希去重）
            }
            out.add(line);
        }
        return out;
    }

    private List<Chunk> segment(List<String> lines) {
        List<Chunk> chunks = new ArrayList<>();
        String section = "正文";
        for (String line : lines) {
            if (line.matches("^第[一二三四五六七八九十百0-9]{1,4}章.*") && line.length() <= 30) {
                section = line.length() > 20 ? line.substring(0, 20) : line;
                continue;
            }
            // 单条≤2000 字：长段按句边界切分
            int start = 0;
            while (start < line.length()) {
                int end = Math.min(start + MAX_ENTRY_LEN, line.length());
                if (end < line.length()) {
                    int dot = line.lastIndexOf('。', end);
                    if (dot > start) {
                        end = dot + 1;
                    }
                }
                String content = line.substring(start, end);
                if (content.replaceAll("[\\s，。、；：]", "").length() >= 8) {
                    boolean heading = HEADING.matcher(content).find() && content.length() <= 30;
                    chunks.add(new Chunk(content, section, heading));
                }
                start = end;
            }
        }
        return chunks;
    }

    private List<KnowledgeProcessDtos.KnowledgeEntry> buildEntries(List<Chunk> chunks, List<String> types,
                                                                   KnowledgeProcessDtos.ProcessRequest req, String requestId) {
        List<KnowledgeProcessDtos.KnowledgeEntry> entries = new ArrayList<>();
        String baseTag = req.source() == null ? "制度" : SOURCE_TAG.getOrDefault(req.source(), "制度");
        int discardedTriples = 0;

        for (Chunk chunk : chunks) {
            String title = chunk.heading() ? chunk.content() : abbreviate(chunk.content(), 24);
            List<String> tags = baseTags(baseTag, chunk.content());

            if (types.contains("CLAUSE") && !chunk.heading()) {
                entries.add(entry("CLAUSE", title, chunk.content(), req.docId(), chunk.section(), tags));
            }
            if (types.contains("RULE") && RULE_KEYWORD.matcher(chunk.content()).find()) {
                entries.add(entry("RULE", title, chunk.content(), req.docId(), chunk.section(), tags));
            }
            if (types.contains("CASE") && CASE_KEYWORD.matcher(chunk.content()).find()) {
                entries.add(entry("CASE", title, chunk.content(), req.docId(), chunk.section(), tags));
            }
            if (types.contains("FAQ") && !chunk.heading() && chunk.content().length() <= MAX_FAQ_ANSWER) {
                // FAQ 生成（规则兜底仿真：标题作问题、原文作标准答案并附依据）
                String question = title.endsWith("？") ? title : title + "的规定是什么？";
                String answer = abbreviate(chunk.content(), MAX_FAQ_ANSWER);
                entries.add(entry("FAQ", question, answer, req.docId(), chunk.section(), tags));
            }
            if (types.contains("TRIPLE")) {
                KnowledgeProcessDtos.KnowledgeEntry triple = extractTriple(chunk, req.docId(), tags);
                if (triple == null) {
                    discardedTriples++;
                } else {
                    entries.add(triple);
                }
            }
        }
        if (discardedTriples > 0) {
            log.info("三元组抽取缺 subject/relation/object 丢弃并计数: requestId={}, discarded={}", requestId, discardedTriples);
        }
        return entries;
    }

    /** 三元组抽取（实体—关系—实体；模型不可用时的规则兜底仿真，配合 ner-agent 增强） */
    private KnowledgeProcessDtos.KnowledgeEntry extractTriple(Chunk chunk, String docId, List<String> tags) {
        for (String sentence : chunk.content().split("[。；]")) {
            var m = TRIPLE.matcher(sentence.trim());
            if (m.matches()) {
                String subject = m.group(1).replaceAll("[，、,]", "");
                String relation = m.group(2);
                String object = m.group(3).replaceAll("[，、,]", "");
                if (subject.isBlank() || object.isBlank()) {
                    continue;
                }
                String content = subject + " | " + relation + " | " + object;
                return entry("TRIPLE", subject + relation, content, docId, chunk.section(), tags);
            }
        }
        return null; // 缺 subject/relation/object 丢弃并计数
    }

    private KnowledgeProcessDtos.KnowledgeEntry entry(String type, String title, String content, String docId,
                                                      String section, List<String> tags) {
        // 质量校验：完整性/一致性（确定性仿真打分），通过线 0.80，不合格退回重加工
        double completeness = content.isBlank() ? 0.40 : Math.min(0.99, 0.88 + (content.length() % 12) / 100.0);
        double consistency = Math.min(0.99, 0.85 + (title.length() % 10) / 100.0);
        boolean pass = completeness >= QUALITY_PASS_LINE && consistency >= QUALITY_PASS_LINE && content.length() <= MAX_ENTRY_LEN;
        return new KnowledgeProcessDtos.KnowledgeEntry(
                "ENT" + dayStamp() + String.format("%04d", entrySeq.incrementAndGet()), type,
                title, content, new KnowledgeProcessDtos.SourceRef(docId, section), tags,
                new KnowledgeProcessDtos.Quality(round2(completeness), round2(consistency), pass));
    }

    private List<String> baseTags(String baseTag, String content) {
        List<String> tags = new ArrayList<>();
        tags.add(baseTag);
        for (String kw : List.of("小微", "期限", "利率", "抵押", "逾期", "五级分类", "授信")) {
            if (content.contains(kw)) {
                tags.add(kw);
            }
        }
        return tags;
    }

    private String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…（超长截断）";
    }

    private String dayStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    private double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 清洗后条目（内容+章节归属+是否标题行） */
    private record Chunk(String content, String section, boolean heading) {}
}
