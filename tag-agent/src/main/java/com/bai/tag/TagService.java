package com.bai.tag;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 智能打标服务。MVP 阶段为确定性仿真引擎（标注：仿真，用于联调），以标签字典规则表模拟 BERT 系多标签模型；
 * TAG_2001 模型不可用降级走的规则引擎打标（字典标签）即本表。
 * <p>处理链：文本清洗 → 多标签推理 → 按组阈值过滤（business 0.75 取 top3、risk 0.80 命中即输出、content 0.70 取 top3）
 * → 规则补充（置信度取 max，同组同义取高）→ 置信度降序截断 top5 → 标签解释（≤50 字命中片段）
 * → 风险标签命中触发预警事件与落库（明文不落库）
 */
@Service
public class TagService {

    private static final Logger log = LoggerFactory.getLogger(TagService.class);

    private static final int MAX_TEXT = 5000;
    private static final int MAX_BATCH = 100000;
    private static final int MAX_TAGS = 5;
    private static final int EXPLAIN_MAX = 50;

    /** 标签体系版本（仿真内置快照；latest 映射最新版本） */
    private static final String LATEST_VERSION = "2026.09.01";
    private static final Set<String> VERSIONS = Set.of(LATEST_VERSION);
    private static final Set<String> GROUPS = Set.of("business", "risk", "content");

    /** 组阈值：business 0.75 取 top3、risk 0.80 命中即输出、content 0.70 取 top3（见 doGenerate 内 switch） */

    private record TagRule(String group, String tagId, String path, double confidence, String[] keywords) {}

    /** 标签字典规则表（规则命中即补充，置信度取 max；风险标签命中触发预警事件） */
    private static final List<TagRule> RULES = List.of(
            new TagRule("risk", "TAG_RISK_OVERDUE", "风险标签/逾期", 0.98,
                    new String[]{"逾期"}),
            new TagRule("risk", "TAG_RISK_PAY_DIFFICULTY", "风险标签/还款困难", 0.95,
                    new String[]{"没钱还", "真没钱", "无力偿还", "还不上", "生意亏", "周转不开", "困难"}),
            new TagRule("risk", "TAG_RISK_COMPLAINT", "风险标签/投诉威胁", 0.93,
                    new String[]{"投诉", "银保监", "举报", "监管"}),
            new TagRule("risk", "TAG_RISK_RELATED", "风险标签/关联风险", 0.90,
                    new String[]{"担保人", "失信", "关联方"}),
            new TagRule("business", "TAG_BIZ_REPAYMENT", "业务标签/还款", 0.86,
                    new String[]{"还款", "账单", "分期", "月供"}),
            new TagRule("business", "TAG_BIZ_APPLY", "业务标签/申请办理", 0.83,
                    new String[]{"申请", "办理", "额度", "续贷", "提额"}),
            new TagRule("content", "TAG_CONTENT_FEEDBACK", "内容标签/反馈", 0.82,
                    new String[]{"反馈", "建议", "吐槽"}),
            new TagRule("content", "TAG_CONTENT_CONSULT", "内容标签/咨询", 0.80,
                    new String[]{"咨询", "请问", "想了解"}));

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final AtomicLong seq = new AtomicLong();

    /** 同步打标（幂等：requestId 相同返回首次结果，缓存 24h） */
    public TagDtos.TagResult generate(TagDtos.GenerateRequest req, String requestId) {
        TagDtos.TagResult cached = idempotency.get("generate", requestId);
        if (cached != null) {
            return cached;
        }
        TagDtos.TagResult result = doGenerate(req, requestId);
        idempotency.put("generate", requestId, result);
        return result;
    }

    /** 批量打标（≤100000 条，异步；条目未定义批量任务查询接口，结果由调度器落库） */
    public TagDtos.BatchResult batch(TagDtos.BatchRequest req, String requestId) {
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BaiException(TagErrorCodes.PARAM_INVALID, "TAG_1001 参数缺失：items 批量项必填");
        }
        if (req.items().size() > MAX_BATCH) {
            throw new BaiException(TagErrorCodes.TEXT_TOO_LONG, "TAG_1005 批量超 100000 条，请截断或分批");
        }
        for (int i = 0; i < req.items().size(); i++) {
            TagDtos.GenerateRequest item = req.items().get(i);
            if (item == null || item.text() == null || item.text().isBlank() || isPureSymbol(item.text())) {
                throw new BaiException(TagErrorCodes.EMPTY_TEXT, "TAG_1006 批量第 " + (i + 1) + " 条为空文本/纯符号，请修正后提交");
            }
        }
        String batchId = "TAGB" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%05d", seq.incrementAndGet());
        log.info("打标批量任务受理(仿真): requestId={}, batchId={}, total={}（异步执行，结果落库 B_tag_T）",
                requestId, batchId, req.items().size());
        return new TagDtos.BatchResult(batchId, "ACCEPTED", req.items().size());
    }

    /** 标签体系（版本化，仿真内置快照） */
    public TagDtos.Taxonomy taxonomy() {
        return new TagDtos.Taxonomy(LATEST_VERSION, RULES.stream()
                .map(r -> new TagDtos.TagDef(r.tagId(), r.group(), r.path(), 1, String.join("/", r.keywords())))
                .toList());
    }

    /** 模型信息（仿真内置快照） */
    public TagDtos.TagModels models() {
        return new TagDtos.TagModels("tag-bert-v2（多标签 sigmoid）", LATEST_VERSION,
                List.of("business", "risk", "content"));
    }

    // ---------------- 内部处理链 ----------------

    private TagDtos.TagResult doGenerate(TagDtos.GenerateRequest req, String requestId) {
        if (req == null || req.text() == null) {
            throw new BaiException(TagErrorCodes.PARAM_INVALID, "TAG_1001 参数缺失：text 必填");
        }
        String text = req.text();
        if (text.isBlank() || isPureSymbol(text)) {
            throw new BaiException(TagErrorCodes.EMPTY_TEXT, "TAG_1006 空文本/纯符号，请修正输入");
        }
        if (text.length() > MAX_TEXT) {
            throw new BaiException(TagErrorCodes.TEXT_TOO_LONG, "TAG_1005 文本超过 5000 字，请截断或分批");
        }
        List<String> groups = req.groups() == null || req.groups().isEmpty()
                ? List.of("business", "risk", "content") : req.groups();
        for (String g : groups) {
            if (!GROUPS.contains(g)) {
                throw new BaiException(TagErrorCodes.EMPTY_TEXT, "TAG_1006 非法组：" + g + "（取值 business/risk/content）");
            }
        }
        String version = resolveVersion(req.taxonomyVersion());
        boolean enableExplain = req.enableExplain() == null || req.enableExplain();

        long start = System.currentTimeMillis();

        // 2-3. 多标签推理与按组阈值过滤（仿真为规则命中，置信度固定即 max）
        List<TagDtos.TagHit> riskHits = new ArrayList<>();
        List<TagDtos.TagHit> businessHits = new ArrayList<>();
        List<TagDtos.TagHit> contentHits = new ArrayList<>();
        for (TagRule rule : RULES) {
            if (!groups.contains(rule.group())) {
                continue;
            }
            List<String> hits = matchKeywords(text, rule.keywords());
            if (hits.isEmpty()) {
                continue;
            }
            double confidence = rule.confidence();
            double threshold = switch (rule.group()) {
                case "business" -> 0.75;
                case "risk" -> 0.80;
                default -> 0.70;
            };
            if (confidence < threshold) {
                continue;
            }
            String explain = enableExplain ? explain(text, hits.get(0)) : null;
            TagDtos.TagHit hit = new TagDtos.TagHit(rule.group(), rule.tagId(), 1, rule.path(), confidence, explain);
            switch (rule.group()) {
                case "risk" -> riskHits.add(hit);
                case "business" -> businessHits.add(hit);
                default -> contentHits.add(hit);
            }
        }
        // business/content 取 top3；risk 命中即输出
        businessHits.sort(Comparator.comparingDouble(TagDtos.TagHit::confidence).reversed());
        contentHits.sort(Comparator.comparingDouble(TagDtos.TagHit::confidence).reversed());
        List<TagDtos.TagHit> tags = new ArrayList<>(riskHits);
        tags.addAll(businessHits.subList(0, Math.min(3, businessHits.size())));
        tags.addAll(contentHits.subList(0, Math.min(3, contentHits.size())));

        // 4. 排序截断：置信度降序取 top5
        tags.sort(Comparator.comparingDouble(TagDtos.TagHit::confidence).reversed());
        if (tags.size() > MAX_TAGS) {
            tags = new ArrayList<>(tags.subList(0, MAX_TAGS));
        }

        // 5. 风险事件：命中风险标签即触发预警事件（仿真为审计日志）
        List<String> riskTagIds = tags.stream().filter(t -> "risk".equals(t.group()))
                .map(TagDtos.TagHit::tagId).toList();
        if (!riskTagIds.isEmpty()) {
            log.warn("风险预警事件(仿真): requestId={}, riskTags={}", requestId, riskTagIds);
        }

        long elapsed = System.currentTimeMillis() - start;
        TagDtos.TagResult result = new TagDtos.TagResult(nextId("TAG"), version, tags, elapsed);
        log.info("打标审计(仿真): requestId={}, taxonomyVersion={}, textHash={}, tags={}, riskHits={}, elapsed={}ms",
                requestId, version, text.hashCode(), tags.size(), riskTagIds.size(), elapsed);
        return result;
    }

    private String resolveVersion(String requested) {
        if (requested == null || requested.isBlank() || "latest".equalsIgnoreCase(requested)) {
            return LATEST_VERSION;
        }
        if (!VERSIONS.contains(requested)) {
            throw new BaiException(TagErrorCodes.NOT_FOUND, "TAG_1004 体系版本不存在：" + requested);
        }
        return requested;
    }

    /** 标签解释：命中关键词所在句片段（≤50 字，规则命中依据） */
    private String explain(String text, String keyword) {
        for (String sentence : text.split("[。！？；!?;\n]")) {
            if (sentence.contains(keyword)) {
                String s = sentence.strip();
                return truncate(s, EXPLAIN_MAX);
            }
        }
        return truncate(keyword, EXPLAIN_MAX);
    }

    private List<String> matchKeywords(String text, String[] keywords) {
        List<String> hits = new ArrayList<>();
        for (String k : keywords) {
            if (text.contains(k)) {
                hits.add(k);
            }
        }
        return hits;
    }

    private boolean isPureSymbol(String text) {
        return text.codePoints().noneMatch(Character::isLetterOrDigit);
    }

    private String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String nextId(String prefix) {
        return prefix + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%05d", seq.incrementAndGet());
    }
}
