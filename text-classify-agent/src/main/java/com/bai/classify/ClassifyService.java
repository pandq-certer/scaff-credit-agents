package com.bai.classify;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文本分类服务。MVP 阶段为确定性仿真引擎（标注：仿真，用于联调），以关键词规则表模拟中文 BERT 系多标签分类模型；
 * TXC_2001 模型不可用降级走的关键词规则兜底即本表。
 * <p>处理链：预处理与 512 token 分段（仿真为整段）→ 多标签模型 → 规则增强（命中且置信度 ≥0.70 强制加入，source=RULE）
 * → 按任务聚合（MAX_CONF/MAJORITY）→ 内容合规并行检测（阈值 0.80，仅标记）→ 理由生成与落库（明文不落库）
 */
@Service
public class ClassifyService {

    private static final Logger log = LoggerFactory.getLogger(ClassifyService.class);

    private static final int MAX_TEXT = 20000;
    private static final int MAX_BATCH = 100000;
    private static final int MAX_LABELS = 5;

    /** 六类分类任务（体系内置快照）：类别阈值默认 0.70，合规 0.80、文档 0.65 */
    private static final Set<String> TASKS = Set.of(
            "complaint_ticket", "collection_text", "doc_category",
            "compliance_text", "marketing_text", "feedback_text");

    private record Rule(String[] keywords, String level1, String level2) {}

    /** 任务规则表（关键词规则即模型降级兜底；命中即强制加入类别，source=RULE） */
    private static final Map<String, List<Rule>> TASK_RULES = buildRules();

    /** 内容合规并行检测关键词（涉敏/涉诈/涉违规；仅标记不判罚，阈值 0.80） */
    private static final Map<String, String> COMPLIANCE_KEYWORDS = buildComplianceKeywords();

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final Map<String, ClassifyDtos.ClassifyTask> tasks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 同步单条分类（幂等：requestId 相同返回首次结果，缓存 24h） */
    public ClassifyDtos.ClassifyResult classify(ClassifyDtos.ClassifyRequest req, String requestId) {
        ClassifyDtos.ClassifyResult cached = idempotency.get("classify", requestId);
        if (cached != null) {
            return cached;
        }
        ClassifyDtos.ClassifyResult result = doClassify(req, requestId);
        idempotency.put("classify", requestId, result);
        return result;
    }

    /** 批量分类（≤100000 条，异步；MVP 仿真为同步完成并落任务存储供 GET /tasks/{taskId} 查询） */
    public ClassifyDtos.ClassifyTask batch(ClassifyDtos.BatchRequest req, String requestId) {
        ClassifyDtos.ClassifyTask cached = idempotency.get("batch", requestId);
        if (cached != null) {
            return cached;
        }
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BaiException(ClassifyErrorCodes.PARAM_INVALID, "TXC_1001 参数缺失：items 批量项必填");
        }
        if (req.items().size() > MAX_BATCH) {
            throw new BaiException(ClassifyErrorCodes.TEXT_TOO_LONG, "TXC_1005 批量超 100000 条，请截断或分批");
        }
        List<ClassifyDtos.ClassifyResult> results = new ArrayList<>();
        for (int i = 0; i < req.items().size(); i++) {
            try {
                results.add(doClassify(req.items().get(i), requestId + "-" + (i + 1)));
            } catch (BaiException e) {
                throw new BaiException(e.getCode(), e.getMessage() + "（第 " + (i + 1) + " 条）");
            }
        }
        ClassifyDtos.ClassifyTask task = new ClassifyDtos.ClassifyTask(nextId("TXCB"), "DONE", req.items().size(), results);
        idempotency.put("batch", requestId, task);
        tasks.put(task.taskId(), task);
        log.info("文本分类批量任务完成(仿真): requestId={}, taskId={}, total={}", requestId, task.taskId(), task.total());
        return task;
    }

    /** 异步任务查询 */
    public ClassifyDtos.ClassifyTask getTask(String taskId) {
        ClassifyDtos.ClassifyTask task = tasks.get(taskId);
        if (task == null) {
            throw new BaiException(ClassifyErrorCodes.TASK_NOT_FOUND, "TXC_1004 taskId 不存在，请校验任务号");
        }
        return task;
    }

    /** 分类体系（六类任务，仿真内置快照） */
    public ClassifyDtos.ClassifySchemas schemas() {
        return new ClassifyDtos.ClassifySchemas("v2026.09", List.of(
                new ClassifyDtos.SchemaTask("complaint_ticket", "投诉工单分类", 0.70, "MAX_CONF", List.of(
                        new ClassifyDtos.SchemaLevel1("service_complaint", "服务投诉", List.of(
                                new ClassifyDtos.SchemaLevel2("fee_complaint", "费用投诉"),
                                new ClassifyDtos.SchemaLevel2("attitude_complaint", "态度投诉"),
                                new ClassifyDtos.SchemaLevel2("process_complaint", "流程投诉"))),
                        new ClassifyDtos.SchemaLevel1("product_complaint", "产品投诉", List.of(
                                new ClassifyDtos.SchemaLevel2("rate_complaint", "利率投诉"))),
                        new ClassifyDtos.SchemaLevel1("other", "其他", List.of(
                                new ClassifyDtos.SchemaLevel2("unknown", "未知"))))),
                new ClassifyDtos.SchemaTask("collection_text", "催收文本状态", 0.70, "MAX_CONF", List.of(
                        new ClassifyDtos.SchemaLevel1("collection_response", "催收应答", List.of(
                                new ClassifyDtos.SchemaLevel2("promise_pay", "承诺还款"),
                                new ClassifyDtos.SchemaLevel2("negotiate", "协商延期"),
                                new ClassifyDtos.SchemaLevel2("hardship", "还款困难"),
                                new ClassifyDtos.SchemaLevel2("refuse", "拒绝联系"))))),
                new ClassifyDtos.SchemaTask("doc_category", "文档类别辅助", 0.65, "MAJORITY", List.of(
                        new ClassifyDtos.SchemaLevel1("document", "文档类别", List.of(
                                new ClassifyDtos.SchemaLevel2("contract", "合同协议"),
                                new ClassifyDtos.SchemaLevel2("report", "报告"),
                                new ClassifyDtos.SchemaLevel2("certificate", "证明流水"),
                                new ClassifyDtos.SchemaLevel2("application_form", "申请表单"))))),
                new ClassifyDtos.SchemaTask("compliance_text", "内容合规检测", 0.80, "MAX_CONF", List.of(
                        new ClassifyDtos.SchemaLevel1("compliance", "合规类别", List.of(
                                new ClassifyDtos.SchemaLevel2("fraud", "涉诈"),
                                new ClassifyDtos.SchemaLevel2("violence", "涉暴"),
                                new ClassifyDtos.SchemaLevel2("illegal", "涉违规"),
                                new ClassifyDtos.SchemaLevel2("info_leak", "涉敏感信息"))))),
                new ClassifyDtos.SchemaTask("marketing_text", "营销文本", 0.70, "MAX_CONF", List.of(
                        new ClassifyDtos.SchemaLevel1("marketing", "营销类别", List.of(
                                new ClassifyDtos.SchemaLevel2("promotion", "促销推广"),
                                new ClassifyDtos.SchemaLevel2("invitation", "邀请触达"))))),
                new ClassifyDtos.SchemaTask("feedback_text", "客户反馈", 0.70, "MAJORITY", List.of(
                        new ClassifyDtos.SchemaLevel1("feedback", "反馈类别", List.of(
                                new ClassifyDtos.SchemaLevel2("positive", "正面反馈"),
                                new ClassifyDtos.SchemaLevel2("suggestion", "改进建议"),
                                new ClassifyDtos.SchemaLevel2("negative", "负面反馈")))))));
    }

    // ---------------- 内部处理链 ----------------

    private ClassifyDtos.ClassifyResult doClassify(ClassifyDtos.ClassifyRequest req, String requestId) {
        if (req == null || req.text() == null) {
            throw new BaiException(ClassifyErrorCodes.PARAM_INVALID, "TXC_1001 参数缺失：text 必填");
        }
        String text = req.text();
        if (text.isBlank() || isPureSymbol(text)) {
            throw new BaiException(ClassifyErrorCodes.EMPTY_TEXT, "TXC_1006 空文本/纯符号，请修正输入");
        }
        if (text.length() > MAX_TEXT) {
            throw new BaiException(ClassifyErrorCodes.TEXT_TOO_LONG, "TXC_1005 文本超过 20000 字，请截断或分批");
        }
        if (req.task() == null || req.task().isBlank()) {
            throw new BaiException(ClassifyErrorCodes.PARAM_INVALID, "TXC_1001 参数缺失：task 分类任务必填");
        }
        if (!TASKS.contains(req.task())) {
            throw new BaiException(ClassifyErrorCodes.TASK_NOT_FOUND, "TXC_1004 分类任务不存在：" + req.task());
        }
        int topK = req.topK() == null ? 3 : req.topK();
        if (topK < 1 || topK > 5) {
            throw new BaiException(ClassifyErrorCodes.PARAM_INVALID, "TXC_1001 topK 非法：取值 1—5");
        }

        long start = System.currentTimeMillis();
        boolean enableRule = req.enableRule() == null || req.enableRule();
        boolean enableReason = req.enableReason() == null || req.enableReason();
        List<Rule> rules = TASK_RULES.get(req.task());

        // 3. 模型分类仿真：按规则命中数模拟标签分布（命中 ≥2 高置信、=1 中置信、无命中低置信 OTHER）
        Rule best = null;
        List<String> bestHits = List.of();
        int bestScore = 0;
        if (enableRule && rules != null) {
            for (Rule rule : rules) {
                List<String> hits = matchKeywords(text, rule.keywords());
                if (hits.size() > bestScore) {
                    bestScore = hits.size();
                    best = rule;
                    bestHits = hits;
                }
            }
        }
        List<ClassifyDtos.Label> labels = new ArrayList<>();
        List<ClassifyDtos.Candidate> candidates = new ArrayList<>();
        List<ClassifyDtos.Reason> reasons = new ArrayList<>();
        if (best == null) {
            // 最高置信度 <0.60 且体系含 OTHER → 输出 OTHER
            labels.add(new ClassifyDtos.Label("OTHER", null, 0.45, "MODEL"));
            candidates.add(new ClassifyDtos.Candidate("OTHER", 0.45));
            if (enableReason) {
                reasons.add(new ClassifyDtos.Reason("MODEL_SEGMENT", truncate(text, 20), 0.62));
            }
        } else {
            double modelConf = bestScore >= 2 ? 0.96 : 0.87;
            labels.add(new ClassifyDtos.Label(best.level1(), best.level2(), modelConf, "MODEL"));
            candidates.add(new ClassifyDtos.Candidate(best.level1() + "/" + best.level2(), modelConf));
            if (enableReason) {
                reasons.add(new ClassifyDtos.Reason("MODEL_SEGMENT", truncate(text, 20), modelConf));
            }
            // 4. 规则增强：命中且置信度 ≥0.70 强制加入（source=RULE），与模型结果融合
            if (enableRule) {
                double ruleConf = 0.94;
                upsertLabel(labels, new ClassifyDtos.Label(best.level1(), best.level2(), ruleConf, "RULE"));
                upsertCandidate(candidates, new ClassifyDtos.Candidate(best.level1() + "/" + best.level2(), ruleConf));
                for (String kw : bestHits) {
                    reasons.add(new ClassifyDtos.Reason("RULE_KEYWORD", kw, 1.0));
                }
                for (Rule rule : rules) {
                    if (rule == best || candidates.size() >= topK + MAX_LABELS) {
                        continue;
                    }
                    List<String> hits = matchKeywords(text, rule.keywords());
                    if (!hits.isEmpty()) {
                        labels.add(new ClassifyDtos.Label(rule.level1(), rule.level2(), ruleConf, "RULE"));
                        candidates.add(new ClassifyDtos.Candidate(rule.level1() + "/" + rule.level2(), ruleConf));
                    }
                }
            }
        }

        // 输出约束：每任务 ≤5 标签/条，按置信度截断 top5；候选取 topK
        labels.sort(Comparator.comparingDouble(ClassifyDtos.Label::confidence).reversed());
        if (labels.size() > MAX_LABELS) {
            labels = new ArrayList<>(labels.subList(0, MAX_LABELS));
        }
        candidates.sort(Comparator.comparingDouble(ClassifyDtos.Candidate::confidence).reversed());
        if (candidates.size() > topK) {
            candidates = new ArrayList<>(candidates.subList(0, topK));
        }
        if (!enableReason) {
            reasons = null;
        }

        // 6. 内容合规并行检测：命中即标记并附证据（仅标记，判罚归 compliance-check-agent）
        ClassifyDtos.Compliance compliance = detectCompliance(text);

        long elapsed = System.currentTimeMillis() - start;
        ClassifyDtos.ClassifyResult result = new ClassifyDtos.ClassifyResult(
                nextId("TXC"), req.task(), sha256Hex(text), labels, candidates, reasons, compliance, elapsed);

        log.info("文本分类审计(仿真): requestId={}, task={}, textHash={}, labels={}, complianceFlagged={}, elapsed={}ms",
                requestId, req.task(), result.textHash(), labels.size(), compliance.flagged(), elapsed);
        return result;
    }

    /** 合规并行检测：阈值 0.80 命中即强制标记（仿真固定置信 0.94） */
    private ClassifyDtos.Compliance detectCompliance(String text) {
        List<String> categories = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        for (Map.Entry<String, String> e : COMPLIANCE_KEYWORDS.entrySet()) {
            if (text.contains(e.getKey())) {
                if (!categories.contains(e.getValue())) {
                    categories.add(e.getValue());
                }
                evidence.add(e.getKey());
            }
        }
        return new ClassifyDtos.Compliance(!categories.isEmpty(), categories, evidence);
    }

    private void upsertLabel(List<ClassifyDtos.Label> labels, ClassifyDtos.Label label) {
        for (int i = 0; i < labels.size(); i++) {
            ClassifyDtos.Label e = labels.get(i);
            if (e.level1().equals(label.level1()) && java.util.Objects.equals(e.level2(), label.level2())) {
                if (label.confidence() > e.confidence()) {
                    labels.set(i, label);
                }
                return;
            }
        }
        labels.add(label);
    }

    private void upsertCandidate(List<ClassifyDtos.Candidate> candidates, ClassifyDtos.Candidate candidate) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).label().equals(candidate.label())) {
                if (candidate.confidence() > candidates.get(i).confidence()) {
                    candidates.set(i, candidate);
                }
                return;
            }
        }
        candidates.add(candidate);
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

    private static Map<String, List<Rule>> buildRules() {
        Map<String, List<Rule>> m = new LinkedHashMap<>();
        m.put("complaint_ticket", List.of(
                new Rule(new String[]{"乱收费", "多扣", "利息不明", "扣错", "乱扣"}, "service_complaint", "fee_complaint"),
                new Rule(new String[]{"态度", "辱骂", "骂人", "推诿", "不作为"}, "service_complaint", "attitude_complaint"),
                new Rule(new String[]{"拖延", "进度慢", "一直不", "没人处理", "踢皮球"}, "service_complaint", "process_complaint"),
                new Rule(new String[]{"利率太高", "息费", "砍头息"}, "product_complaint", "rate_complaint")));
        m.put("collection_text", List.of(
                new Rule(new String[]{"一定还", "下周还", "承诺", "发了工资", "月底还"}, "collection_response", "promise_pay"),
                new Rule(new String[]{"没钱", "还不上", "无力", "困难", "生意亏"}, "collection_response", "hardship"),
                new Rule(new String[]{"别再打", "不要再打", "拒接", "别联系", "不要联系"}, "collection_response", "refuse"),
                new Rule(new String[]{"协商", "延期", "宽限", "减免", "分期"}, "collection_response", "negotiate")));
        m.put("doc_category", List.of(
                new Rule(new String[]{"合同", "协议", "签约", "甲方", "乙方"}, "document", "contract"),
                new Rule(new String[]{"报告", "尽调", "检查报告", "审批意见"}, "document", "report"),
                new Rule(new String[]{"证明", "流水", "回单", "对账单"}, "document", "certificate"),
                new Rule(new String[]{"申请表", "申请书", "授权书"}, "document", "application_form")));
        m.put("compliance_text", List.of(
                new Rule(new String[]{"诈骗", "骗局", "骗贷"}, "compliance", "fraud"),
                new Rule(new String[]{"威胁", "恐吓", "打人"}, "compliance", "violence"),
                new Rule(new String[]{"洗钱", "赌博", "传销", "高利贷"}, "compliance", "illegal"),
                new Rule(new String[]{"倒卖", "泄露", "卖信息"}, "compliance", "info_leak")));
        m.put("marketing_text", List.of(
                new Rule(new String[]{"优惠", "折扣", "免息", "利率低", "放款快"}, "marketing", "promotion"),
                new Rule(new String[]{"邀请", "提额", "专属", "限时", "名额"}, "marketing", "invitation")));
        m.put("feedback_text", List.of(
                new Rule(new String[]{"满意", "点赞", "感谢", "谢谢"}, "feedback", "positive"),
                new Rule(new String[]{"建议", "希望", "改进", "优化"}, "feedback", "suggestion"),
                new Rule(new String[]{"不满", "吐槽", "太慢", "太差"}, "feedback", "negative")));
        return m;
    }

    private static Map<String, String> buildComplianceKeywords() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("诈骗", "fraud");
        m.put("骗局", "fraud");
        m.put("洗钱", "illegal");
        m.put("赌博", "illegal");
        m.put("传销", "illegal");
        m.put("威胁", "violence");
        m.put("恐吓", "violence");
        m.put("倒卖", "info_leak");
        m.put("泄露", "info_leak");
        return m;
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
}
