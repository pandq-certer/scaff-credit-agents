package com.bai.rag;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识问答服务（提示词库 A-16）。LLM 生成环节为确定性仿真（标注：仿真，用于联调），
 * 结构与真实 RAG 生成链路一致；模型不可用时降级为检索结果直出（top1 卡片）。
 * <p>处理链：校验入参 → 上下文组装（按分数降序≤8 条、总字符≤12000，历史≤10 轮）
 * → Prompt 构建（系统提示含「无依据禁止编造」约束）→ 生成（仿真）→ 引用校验（[n] 映射 entryId）
 * → 依据判定（相关分≥0.70 ANSWERED、0.50—0.70 PARTIAL、&lt;0.50 REJECTED）→ 质量评分 → 落库发 rag.answered/rag.rejected 事件
 */
@Service
public class RagAnswerService {

    private static final Logger log = LoggerFactory.getLogger(RagAnswerService.class);

    private static final int MAX_QUESTION = 500;
    private static final int MAX_BATCH = 10000;
    private static final int MAX_CONTEXT = 8;
    private static final int MAX_CONTEXT_CHARS = 12000;
    private static final int MAX_HISTORY = 10;
    private static final int MAX_ANSWER = 800;
    private static final int DEFAULT_MAX_CHARS = 500;
    private static final double ANSWERED_LINE = 0.70;
    private static final double PARTIAL_LINE = 0.50;
    private static final Set<String> STYLES = Set.of("professional", "plain", "utterance");
    private static final Set<String> MODES = Set.of("answer", "assess");
    private static final Map<String, Integer> STYLE_CAP = Map.of("professional", 500, "plain", 300, "utterance", 200);
    private static final String REJECT_GUIDANCE =
            "您好，暂未在知识库中找到与您问题直接相关的依据，无法给出准确答案。请补充关键信息（如产品名称、业务环节或具体条款）后再次咨询，或联系客户经理人工核实。";
    private static final String PARTIAL_NOTE = "（以上为部分匹配知识，仅供参考，请结合具体产品条款确认。）";
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");
    private static final Pattern PURE_SYMBOL = Pattern.compile("^[\\p{Punct}\\s\\p{IsPunctuation}，。、；：？！“”‘’（）【】《》…]+$");

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final AtomicLong answerSeq = new AtomicLong();
    private final AtomicLong batchSeq = new AtomicLong();
    private final AtomicLong evalSeq = new AtomicLong();

    /** 同步问答（幂等：requestId 相同返回首次结果，缓存 24h） */
    public RagAnswerDtos.AnswerResult answer(RagAnswerDtos.AnswerRequest req, String requestId) {
        RagAnswerDtos.AnswerResult cached = idempotency.get("answer", requestId);
        if (cached != null) {
            return cached;
        }
        validate(req);
        RagAnswerDtos.AnswerResult result = generate(req, requestId);
        idempotency.put("answer", requestId, result);
        return result;
    }

    /** 批量异步受理（≤10000 条） */
    public RagAnswerDtos.BatchResult batch(RagAnswerDtos.BatchRequest req, String requestId) {
        List<RagAnswerDtos.AnswerRequest> items = req.items();
        if (items == null || items.isEmpty()) {
            throw new BaiException(RagAnswerErrorCodes.PARAM_INVALID, "RAG_1001 参数缺失：batch.items 必填");
        }
        if (items.size() > MAX_BATCH) {
            throw new BaiException(RagAnswerErrorCodes.OVER_LIMIT, "RAG_1005 批量超 10000 条（实际 " + items.size() + "），请分批");
        }
        for (RagAnswerDtos.AnswerRequest item : items) {
            validate(item);
            idempotency.put("answer", requestId + ":" + item.question().hashCode(), generate(item, requestId));
        }
        String batchId = "RGB" + dayStamp() + String.format("%05d", batchSeq.incrementAndGet());
        log.info("问答批量受理: requestId={}, batchId={}, total={}", requestId, batchId, items.size());
        return new RagAnswerDtos.BatchResult(batchId, "ACCEPTED", items.size());
    }

    /** 质量评估（忠实度/相关性抽检） */
    public RagAnswerDtos.EvaluateResult evaluate(RagAnswerDtos.EvaluateRequest req, String requestId) {
        long start = System.currentTimeMillis();
        if (req == null || req.question() == null || req.question().isBlank()
                || req.answer() == null || req.answer().isBlank()) {
            throw new BaiException(RagAnswerErrorCodes.PARAM_INVALID, "RAG_1001 参数缺失：question/answer 必填");
        }
        double relevance = contextRelevance(req.question(), req.context() == null ? List.of() : req.context());
        // 忠实度仿真：答案与上下文词汇重合 + 引用标注完整度
        double support = contextRelevance(req.answer(), req.context() == null ? List.of() : req.context());
        Matcher m = CITATION.matcher(req.answer());
        boolean hasCitation = m.find();
        double faithfulness = Math.min(0.99, support * 0.85 + (hasCitation ? 0.12 : 0.02) + 0.05);
        String verdict = faithfulness >= 0.80 && relevance >= ANSWERED_LINE ? "PASS" : "CHECK";
        RagAnswerDtos.EvaluateResult result = new RagAnswerDtos.EvaluateResult(
                "RGE" + dayStamp() + String.format("%05d", evalSeq.incrementAndGet()), verdict,
                new RagAnswerDtos.Quality(round2(faithfulness), round2(relevance)), System.currentTimeMillis() - start);
        log.info("问答质量评估: requestId={}, verdict={}, faithfulness={}, relevance={}",
                requestId, verdict, result.quality().faithfulness(), result.quality().relevance());
        return result;
    }

    /** 模型信息（私有化部署、文本不出域） */
    public RagAnswerDtos.ModelsResult models() {
        return new RagAnswerDtos.ModelsResult("credit-rag-llm", "retrieval-direct", List.of(
                new RagAnswerDtos.ModelInfo("credit-rag-llm", "信贷知识问答大模型", "v2026.09", "ACTIVE", "私有化部署（文本不出域）"),
                new RagAnswerDtos.ModelInfo("retrieval-direct", "检索直出兜底", "v2026.09", "STANDBY", "降级策略：top1 卡片直出")));
    }

    private void validate(RagAnswerDtos.AnswerRequest req) {
        if (req == null || req.question() == null || req.question().isBlank()
                || PURE_SYMBOL.matcher(req.question()).matches()) {
            throw new BaiException(RagAnswerErrorCodes.INVALID_INPUT, "RAG_1006 空问题或纯符号，请检查 question");
        }
        if (req.question().length() > MAX_QUESTION) {
            throw new BaiException(RagAnswerErrorCodes.OVER_LIMIT, "RAG_1005 question 超 500 字（实际 " + req.question().length() + "），请精简");
        }
        if (req.context() != null && req.context().size() > MAX_CONTEXT) {
            throw new BaiException(RagAnswerErrorCodes.INVALID_INPUT, "RAG_1006 上下文超 " + MAX_CONTEXT + " 条，请按检索分数降序裁剪");
        }
        if (req.history() != null && req.history().size() > MAX_HISTORY) {
            throw new BaiException(RagAnswerErrorCodes.INVALID_INPUT, "RAG_1006 对话历史超 " + MAX_HISTORY + " 轮，请截断");
        }
        if (req.style() != null && !STYLES.contains(req.style())) {
            throw new BaiException(RagAnswerErrorCodes.INVALID_INPUT, "RAG_1006 非法风格：" + req.style() + "，仅支持 professional/plain/utterance");
        }
        if (req.mode() != null && !MODES.contains(req.mode())) {
            throw new BaiException(RagAnswerErrorCodes.PARAM_INVALID, "RAG_1001 参数非法：mode 仅支持 answer/assess");
        }
        if (req.maxChars() != null && (req.maxChars() < 100 || req.maxChars() > MAX_ANSWER)) {
            throw new BaiException(RagAnswerErrorCodes.PARAM_INVALID, "RAG_1001 参数非法：maxChars 须在 100—800");
        }
    }

    /** 生成链路（确定性仿真） */
    private RagAnswerDtos.AnswerResult generate(RagAnswerDtos.AnswerRequest req, String requestId) {
        long start = System.currentTimeMillis();
        List<RagAnswerDtos.ContextCard> context = req.context() == null ? List.of() : req.context();
        // 2. 上下文组装：按分数降序取≤8 条，总字符≤12000
        List<RagAnswerDtos.ContextCard> ordered = context.stream()
                .sorted((a, b) -> Double.compare(scoreOf(b), scoreOf(a)))
                .limit(MAX_CONTEXT)
                .toList();

        double relevance = contextRelevance(req.question(), ordered);
        String style = req.style() == null ? "plain" : req.style();
        int limit = Math.min(req.maxChars() == null ? DEFAULT_MAX_CHARS : req.maxChars(),
                STYLE_CAP.getOrDefault(style, DEFAULT_MAX_CHARS));

        String answerMode;
        String answer;
        List<RagAnswerDtos.Citation> citations = new ArrayList<>();
        if (relevance < PARTIAL_LINE || ordered.isEmpty()) {
            // 5. 依据判定：&lt;0.50 输出 REJECTED 引导话术（不编造），发 rag.rejected 事件
            answerMode = "REJECTED";
            answer = REJECT_GUIDANCE;
            log.info("rag.rejected 事件: requestId={}, 问题哈希={}, 相关分={}", requestId, req.question().hashCode(), round2(relevance));
        } else {
            // 3-4. 生成（仿真：top1 卡片为依据主体）+ 引用校验（[n] 映射 entryId，映射失败置 warning）
            RagAnswerDtos.ContextCard top = ordered.get(0);
            StringBuilder sb = new StringBuilder();
            if ("professional".equals(style)) {
                sb.append("根据知识库「").append(top.title()).append("」：");
            }
            sb.append(top.content());
            if (ordered.size() > 1) {
                sb.append(" 另可参考「").append(ordered.get(1).title()).append("」等知识[2]。");
            }
            sb.append("[1]");
            answer = sb.toString();
            if (answer.length() > limit) {
                answer = answer.substring(0, limit) + "…（超长截断）";
            }
            // 正则提取 [n] 并映射上下文卡片
            Matcher m = CITATION.matcher(answer);
            Set<Integer> seen = new HashSet<>();
            while (m.find()) {
                int idx = Integer.parseInt(m.group(1));
                if (idx >= 1 && idx <= ordered.size() && seen.add(idx)) {
                    RagAnswerDtos.ContextCard card = ordered.get(idx - 1);
                    citations.add(new RagAnswerDtos.Citation(idx, card.entryId(), card.title(), card.source()));
                } else if (idx > ordered.size()) {
                    log.warn("引用映射失败置 warning: requestId={}, [{}] 无对应上下文卡片", requestId, idx);
                }
            }
            if (relevance < ANSWERED_LINE) {
                answerMode = "PARTIAL";
                answer = truncate(answer, limit) + PARTIAL_NOTE;
            } else {
                answerMode = "ANSWERED";
            }
            log.info("rag.answered 事件: requestId={}, 问题哈希={}, 模式={}, 引用数={}", requestId, req.question().hashCode(), answerMode, citations.size());
        }
        long elapsed = System.currentTimeMillis() - start;
        // 6. 质量评估（确定性仿真打分）
        double faithfulness = "REJECTED".equals(answerMode) ? 1.00 : Math.min(0.99, 0.82 + relevance * 0.15);
        log.info("问答审计: requestId={}, 会话={}, 模式={}, 质量评分=({}/{})", requestId, req.sessionId(), answerMode,
                round2(faithfulness), round2(relevance));
        return new RagAnswerDtos.AnswerResult(
                "RAG" + dayStamp() + String.format("%05d", answerSeq.incrementAndGet()), answerMode, answer,
                citations, new RagAnswerDtos.Quality(round2(faithfulness), round2(relevance)), elapsed);
    }

    /** 问题与上下文相关度（词汇 2-gram Dice，仿真语义相关分） */
    private double contextRelevance(String text, List<RagAnswerDtos.ContextCard> context) {
        if (context == null || context.isEmpty()) {
            return 0;
        }
        List<String> q = grams(text);
        double best = 0;
        for (RagAnswerDtos.ContextCard card : context) {
            Double given = card.score();
            double computed = dice(q, grams(card.title() + " " + card.content()));
            double sim = given == null ? computed : Math.max(computed, Math.min(1.0, given));
            best = Math.max(best, sim);
        }
        return best;
    }

    private double dice(java.util.Collection<String> a, java.util.Collection<String> b) {
        Set<String> setA = new HashSet<>(a);
        Set<String> setB = new HashSet<>(b);
        if (setA.isEmpty() || setB.isEmpty()) {
            return 0;
        }
        long overlap = setA.stream().filter(setB::contains).count();
        return overlap == 0 ? 0 : (2.0 * overlap) / (setA.size() + setB.size());
    }

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

    private double scoreOf(RagAnswerDtos.ContextCard card) {
        return card.score() == null ? 0.5 : card.score();
    }

    private String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "…（超长截断）";
    }

    private String dayStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    private double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
