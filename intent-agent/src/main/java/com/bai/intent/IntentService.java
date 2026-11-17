package com.bai.intent;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图识别服务。MVP 阶段为确定性仿真引擎（标注：仿真，用于联调），以关键词规则表模拟 BERT 系意图分类模型；
 * INT_2001 模型不可用降级走的关键词规则兜底即本表。
 * <p>处理链：文本归一（语气词过滤、口语归一）→ 意图分类 → OOD 拒识检测（LOW 澄清一次/HIGH 转人工）
 * → 轻量槽位抽取 → 多轮修正（回退/指代消解/融合）→ 阈值处置 → 判定落库（明文不落库，存哈希+脱敏文本）
 */
@Service
public class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);

    private static final int MAX_TEXT = 500;
    private static final int MAX_BATCH = 10000;
    private static final int MAX_HISTORY = 5;

    /** 口语归一词典（仿真内置快照，实际来自配置中心热更新） */
    private static final Map<String, String> NORMALIZE_WORDS = buildNormalizeWords();

    /** 语气词（仅去除句尾，避免误伤语义） */
    private static final String[] PARTICLES = {"啊", "呢", "吧", "哈", "嘛", "哦", "呀", "啦", "呗", "咯", "喽", "呃"};

    /** 意图规则表（level1/level2 与意图树 schema 对齐） */
    private record Rule(String[] keywords, String level1, String level2) {
        String key() { return level1 + "/" + level2; }
    }

    private static final List<Rule> RULES = List.of(
            new Rule(new String[]{"一定还", "一定", "按时还", "承诺", "保证还", "下周还", "月底还", "还上", "还清", "发了工资就"},
                    "collection_response", "promise_pay"),
            new Rule(new String[]{"协商", "延期", "宽限", "分期", "缓几天", "减免"}, "collection_response", "negotiate"),
            new Rule(new String[]{"还不上", "没钱", "无力", "还不了", "困难"}, "collection_response", "hardship"),
            new Rule(new String[]{"投诉", "态度", "太差", "举报"}, "complaint", "service_complaint"),
            new Rule(new String[]{"提前还款", "结清", "全部还清", "一次性还"}, "repayment", "early_repay"),
            new Rule(new String[]{"借款", "贷款", "申请", "搞钱", "额度", "利息", "利率", "周转"}, "loan_apply", "apply_consult"),
            new Rule(new String[]{"进度", "审批", "下款", "放款", "到账", "多久"}, "loan_apply", "progress_query"),
            new Rule(new String[]{"余额", "账单", "明细", "欠多少"}, "account_service", "balance_query"),
            new Rule(new String[]{"修改", "手机号", "地址", "重置密码"}, "account_service", "profile_update"));

    /** 轻量槽位规则（复杂嵌套槽位转 ner-agent） */
    private static final Pattern P_PRODUCT = Pattern.compile("(经营贷|消费贷|住房贷款|房贷|车贷|信用贷|抵押贷款)");
    private static final Pattern P_AMOUNT = Pattern.compile("\\d+(?:\\.\\d+)?[万元]");
    private static final Pattern P_TERM = Pattern.compile("\\d+\\s*(?:个月|年|期)");
    private static final Pattern P_TAIL = Pattern.compile("尾号(\\d{4})");
    private static final Pattern P_LOAN_NO = Pattern.compile("(?:贷款编号|编号|合同号)[:：]?\\s*([A-Za-z0-9]{6,})");
    private static final Pattern P_DATE = Pattern.compile("(下周[一二三四五六日天]?)|(明天)|(后天)|(\\d{1,2}月\\d{1,2}日)");

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final AtomicLong seq = new AtomicLong();

    /** 同步单条识别（含多轮上下文；幂等：requestId 相同返回首次结果，缓存 24h） */
    public IntentDtos.IntentResult classify(IntentDtos.ClassifyRequest req, String requestId) {
        IntentDtos.IntentResult cached = idempotency.get("classify", requestId);
        if (cached != null) {
            return cached;
        }
        if (req == null || req.text() == null || req.text().isBlank() || isPureSymbol(req.text())) {
            throw new BaiException(IntentErrorCodes.EMPTY_TEXT, "INT_1006 空文本/纯符号，请修正输入");
        }
        if (req.text().length() > MAX_TEXT) {
            throw new BaiException(IntentErrorCodes.TEXT_TOO_LONG, "INT_1005 文本超过 500 字，请截断或分批");
        }
        int topK = req.topK() == null ? 3 : req.topK();
        if (topK < 1 || topK > 5) {
            throw new BaiException(IntentErrorCodes.PARAM_INVALID, "INT_1001 topK 非法：取值 1—5");
        }

        long start = System.currentTimeMillis();
        Random r = new Random(Objects.hash(requestId, req.text()));

        // 2. 文本归一：语气词过滤、口语/方言归一
        String normalized = normalize(req.text());

        // 3. 意图分类（BERT 系模型仿真）与置信度温度校准
        Rule best = null;
        int bestScore = 0;
        for (Rule rule : RULES) {
            int score = 0;
            for (String k : rule.keywords()) {
                if (normalized.contains(k)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = rule;
            }
        }
        double confidence = best == null ? round2(0.30 + r.nextDouble() * 0.10)
                : bestScore >= 2 ? round2(0.86 + r.nextDouble() * 0.12)
                : round2(0.50 + r.nextDouble() * 0.34);

        // 4. OOD 拒识检测（密度估计/能量法仿真：无规则命中即拒识）
        boolean enableOod = req.enableOod() == null || req.enableOod();
        IntentDtos.Ood ood;
        if (best == null && enableOod) {
            // 短语句澄清追问一次（LOW），长语句转人工（HIGH）
            ood = new IntentDtos.Ood(true, req.text().length() <= 8 ? "LOW" : "HIGH");
        } else {
            ood = new IntentDtos.Ood(false, "NONE");
        }

        // 二级意图置信度 <0.55 仅返回一级
        String level1 = best != null ? best.level1() : "other";
        String level2 = confidence < 0.55 ? null : (best != null ? best.level2() : "unknown");
        IntentDtos.IntentInfo intent = new IntentDtos.IntentInfo(level1, level2, confidence, normalized);

        // TopK 候选（OOD 命中不进入业务路由，仅给主候选）
        List<IntentDtos.Candidate> candidates = new ArrayList<>();
        candidates.add(new IntentDtos.Candidate(best != null ? best.key() : "other/unknown", confidence));
        if (best != null) {
            Random cr = new Random(Objects.hash(requestId, "cand"));
            for (Rule rule : RULES) {
                if (candidates.size() >= topK) {
                    break;
                }
                if (rule.key().equals(best.key())) {
                    continue;
                }
                candidates.add(new IntentDtos.Candidate(rule.key(), round2(0.05 + cr.nextDouble() * 0.15)));
            }
        }

        // 5. 轻量槽位抽取
        List<IntentDtos.Slot> slots = extractSlots(req.text());

        // 6. 多轮修正：指代消解、否定回退与上下文意图融合
        List<IntentDtos.HistoryItem> recent = req.history() == null ? List.of()
                : req.history().subList(Math.max(0, req.history().size() - MAX_HISTORY), req.history().size());
        boolean used = !recent.isEmpty();
        boolean revised = used && (containsAny(req.text(), "那个", "它", "这笔", "刚才", "一样", "也是")
                || req.text().startsWith("不") || req.text().startsWith("别"));
        IntentDtos.MultiTurn multiTurn = new IntentDtos.MultiTurn(used, revised, recent.size());

        long elapsed = System.currentTimeMillis() - start;
        IntentDtos.IntentResult result = new IntentDtos.IntentResult(nextId("INT"), intent, candidates,
                slots.isEmpty() ? null : slots, ood, multiTurn, elapsed);
        idempotency.put("classify", requestId, result);

        // 7. 判定落库（明文不落库，存哈希+脱敏文本）与审计
        log.info("意图判定审计(仿真): requestId={}, scene={}, sessionId={}, textHash={}, intent={}/{}, confidence={}, "
                        + "ood={}/{}, multiTurn={}/{}/{}, elapsed={}ms",
                requestId, req.scene(), req.sessionId(), sha256Hex(req.text()), intent.level1(), intent.level2(),
                confidence, ood.flagged(), ood.level(), used, revised, recent.size(), elapsed);
        return result;
    }

    /** 批量识别（≤10000 条，异步；条目未定义批量任务查询接口，结果由调度器落库） */
    public IntentDtos.BatchResult batch(IntentDtos.BatchRequest req, String requestId) {
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BaiException(IntentErrorCodes.PARAM_INVALID, "INT_1001 参数缺失：items 批量项必填");
        }
        if (req.items().size() > MAX_BATCH) {
            throw new BaiException(IntentErrorCodes.TEXT_TOO_LONG, "INT_1005 批量超 10000 条，请截断或分批");
        }
        for (IntentDtos.ClassifyRequest item : req.items()) {
            if (item == null || item.text() == null || item.text().isBlank() || isPureSymbol(item.text())) {
                throw new BaiException(IntentErrorCodes.EMPTY_TEXT, "INT_1006 批量项存在空文本/纯符号，请修正后提交");
            }
        }
        String batchId = "INTB" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%05d", seq.incrementAndGet());
        log.info("意图批量任务受理(仿真): requestId={}, batchId={}, total={}（异步执行，结果落库 B_intent_T，批量文本加密存储）",
                requestId, batchId, req.items().size());
        return new IntentDtos.BatchResult(batchId, "ACCEPTED", req.items().size());
    }

    /** 意图体系（意图树版本化，热更新 &lt;30s，新旧并存可回滚；仿真为内置快照） */
    public IntentDtos.IntentSchema schema() {
        return new IntentDtos.IntentSchema("v2026.09", "2026-09-30 10:00:00", List.of(
                new IntentDtos.SchemaLevel1("loan_apply", "贷款申请", List.of(
                        new IntentDtos.SchemaLevel2("apply_consult", "申请咨询"),
                        new IntentDtos.SchemaLevel2("apply_submit", "提交申请"),
                        new IntentDtos.SchemaLevel2("progress_query", "进度查询"))),
                new IntentDtos.SchemaLevel1("repayment", "还款", List.of(
                        new IntentDtos.SchemaLevel2("early_repay", "提前还款"),
                        new IntentDtos.SchemaLevel2("repayment_query", "还款查询"))),
                new IntentDtos.SchemaLevel1("collection_response", "催收应答", List.of(
                        new IntentDtos.SchemaLevel2("promise_pay", "承诺还款"),
                        new IntentDtos.SchemaLevel2("negotiate", "协商延期"),
                        new IntentDtos.SchemaLevel2("hardship", "困难减免"),
                        new IntentDtos.SchemaLevel2("refuse", "拒绝还款"))),
                new IntentDtos.SchemaLevel1("account_service", "账户服务", List.of(
                        new IntentDtos.SchemaLevel2("balance_query", "余额查询"),
                        new IntentDtos.SchemaLevel2("profile_update", "资料修改"))),
                new IntentDtos.SchemaLevel1("complaint", "投诉", List.of(
                        new IntentDtos.SchemaLevel2("service_complaint", "服务投诉"),
                        new IntentDtos.SchemaLevel2("rate_complaint", "费率投诉"))),
                new IntentDtos.SchemaLevel1("other", "其他", List.of(
                        new IntentDtos.SchemaLevel2("unknown", "未知意图")))));
    }

    // ---------------- 归一/槽位/工具 ----------------

    private static Map<String, String> buildNormalizeWords() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("搞钱", "借款");
        m.put("搞点钱", "借款");
        m.put("弄点钱", "借款");
        m.put("凑点钱", "借款");
        m.put("还钱", "还款");
        m.put("打钱", "转账还款");
        m.put("卡里没钱", "账户余额不足");
        return m;
    }

    private String normalize(String text) {
        String t = text;
        for (Map.Entry<String, String> e : NORMALIZE_WORDS.entrySet()) {
            t = t.replace(e.getKey(), e.getValue());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String p : PARTICLES) {
                if (t.endsWith(p)) {
                    t = t.substring(0, t.length() - p.length());
                    changed = true;
                }
            }
        }
        return t.trim();
    }

    private List<IntentDtos.Slot> extractSlots(String text) {
        List<IntentDtos.Slot> slots = new ArrayList<>();
        Matcher m = P_PRODUCT.matcher(text);
        if (m.find()) {
            slots.add(new IntentDtos.Slot("product", m.group(1), 0.94));
        }
        m = P_AMOUNT.matcher(text);
        if (m.find()) {
            slots.add(new IntentDtos.Slot("amount", m.group(), 0.92));
        }
        m = P_TERM.matcher(text);
        if (m.find()) {
            slots.add(new IntentDtos.Slot("term", m.group(), 0.88));
        }
        m = P_TAIL.matcher(text);
        if (m.find()) {
            slots.add(new IntentDtos.Slot("account_tail", m.group(1), 0.92));
        }
        m = P_LOAN_NO.matcher(text);
        if (m.find()) {
            slots.add(new IntentDtos.Slot("loan_no", m.group(1), 0.90));
        }
        m = P_DATE.matcher(text);
        if (m.find()) {
            slots.add(new IntentDtos.Slot("repay_date", resolveDate(m), 0.90));
        }
        return slots;
    }

    /** 相对日期归一 YYYY-MM-DD（下周/明天/后天/M月d日） */
    private String resolveDate(Matcher m) {
        LocalDate today = LocalDate.now();
        if (m.group(1) != null) {
            LocalDate nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            if (m.group(1).length() > 2) {
                DayOfWeek target = toDay(m.group(1).charAt(2));
                return nextMonday.plusDays(target.getValue() - 1L).toString();
            }
            return nextMonday.toString();
        }
        if (m.group(2) != null) {
            return today.plusDays(1).toString();
        }
        if (m.group(3) != null) {
            return today.plusDays(2).toString();
        }
        String[] parts = m.group(4).replace("月", " ").replace("日", "").trim().split(" ");
        LocalDate d = LocalDate.of(today.getYear(), Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        return d.isBefore(today) ? d.plusYears(1).toString() : d.toString();
    }

    private DayOfWeek toDay(char c) {
        return switch (c) {
            case '一' -> DayOfWeek.MONDAY;
            case '二' -> DayOfWeek.TUESDAY;
            case '三' -> DayOfWeek.WEDNESDAY;
            case '四' -> DayOfWeek.THURSDAY;
            case '五' -> DayOfWeek.FRIDAY;
            case '六' -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }

    private boolean isPureSymbol(String text) {
        return text.codePoints().noneMatch(Character::isLetterOrDigit);
    }

    private boolean containsAny(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
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

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
