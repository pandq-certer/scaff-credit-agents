package com.bai.summary;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本摘要服务。MVP 阶段为确定性仿真引擎（标注：仿真，用于联调），以抽取式截取（首句/要点按序）模拟
 * 私有化 LLM 生成式摘要；SUM_2001 模型不可用时业务侧转抽取式摘要即本实现。
 * <p>处理链：文本清洗与长度路由（≤5000 直接生成，&gt;5000 分块 ≤2000 字重叠 200，仿真按句边界）→ 摘要生成
 * → 分块融合 → 关键信息提取归一 → 长度控制与截断修正 → 质量评估（consistency&lt;0.75 告警抽检）与落库（明文不落库）
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    private static final int MAX_TEXT = 20000;
    private static final int MAX_BATCH = 5000;
    private static final int CHUNK_SIZE = 2000;
    private static final int CHUNK_OVERLAP = 200;
    private static final int BULLET_POINT_MAX = 100;
    private static final int TERSE_MAX = 100;

    private static final Set<String> MODES = Set.of("overview", "bullet", "terse");

    /** 关键信息提取规则（金额归一到元保留 2 位小数、日期归 YYYY-MM-DD、期限归月/期、担保方式） */
    private static final Pattern P_AMOUNT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(万亿元|亿元|万元|亿|万|元)");
    private static final Pattern P_DATE = Pattern.compile("\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?");
    private static final Pattern P_TERM = Pattern.compile("\\d+\\s*(?:个月|年|期)");
    private static final Pattern P_GUARANTY = Pattern.compile("(?:抵押|质押|保证|信用)担保?");
    private static final Pattern P_PARTY = Pattern.compile(
            "(?:借款人|申请人)([\\u4e00-\\u9fa5]{2,3}?)(?=[，。！？、\\s]|向|在|于|与|和|已|将|因|申请|提供)");

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final AtomicLong seq = new AtomicLong();

    /** 同步生成摘要（幂等：requestId 相同返回首次结果，缓存 24h；重试 ≤1 次） */
    public SummaryDtos.SummaryResult generate(SummaryDtos.GenerateRequest req, String requestId) {
        SummaryDtos.SummaryResult cached = idempotency.get("generate", requestId);
        if (cached != null) {
            return cached;
        }
        SummaryDtos.SummaryResult result = doGenerate(req, requestId);
        idempotency.put("generate", requestId, result);
        return result;
    }

    /** 质量评估：一致性/忠实度/覆盖率/整体（0—1，确定性评分；consistency<0.75 告警抽检） */
    public SummaryDtos.QualityScore evaluate(SummaryDtos.EvaluateRequest req, String requestId) {
        if (req == null || req.text() == null || req.text().isBlank()
                || req.summary() == null || req.summary().isBlank()) {
            throw new BaiException(SummaryErrorCodes.PARAM_INVALID, "SUM_1001 参数缺失：text/summary 必填");
        }
        int seed = Math.abs(Objects.hash(req.text(), req.summary()));
        double consistency = 0.80 + (seed % 20) / 100.0;
        double faithfulness = 0.85 + (seed % 13) / 100.0;
        double coverage = 0.70 + (seed % 28) / 100.0;
        double overall = Math.round((consistency + faithfulness + coverage) / 3 * 10000.0) / 10000.0;
        if (consistency < 0.75) {
            log.warn("摘要质量一致性偏低，转抽检(仿真): requestId={}, consistency={}", requestId, consistency);
        }
        log.info("摘要质量评估(仿真): requestId={}, consistency={}, faithfulness={}, coverage={}, overall={}",
                requestId, consistency, faithfulness, coverage, overall);
        return new SummaryDtos.QualityScore(consistency, faithfulness, coverage, overall);
    }

    /** 批量摘要（≤5000 篇，异步；条目未定义批量任务查询接口，结果由调度器落库） */
    public SummaryDtos.BatchResult batch(SummaryDtos.BatchRequest req, String requestId) {
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BaiException(SummaryErrorCodes.PARAM_INVALID, "SUM_1001 参数缺失：items 批量项必填");
        }
        if (req.items().size() > MAX_BATCH) {
            throw new BaiException(SummaryErrorCodes.TEXT_TOO_LONG, "SUM_1005 批量超 5000 篇，请截断或分批");
        }
        for (int i = 0; i < req.items().size(); i++) {
            SummaryDtos.GenerateRequest item = req.items().get(i);
            if (item == null || item.text() == null || item.text().isBlank()) {
                throw new BaiException(SummaryErrorCodes.EMPTY_TEXT, "SUM_1006 批量第 " + (i + 1) + " 篇为空文本，请修正后提交");
            }
        }
        String batchId = "SUMB" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%05d", seq.incrementAndGet());
        log.info("摘要批量任务受理(仿真): requestId={}, batchId={}, total={}（异步执行，结果落库 B_summary_T）",
                requestId, batchId, req.items().size());
        return new SummaryDtos.BatchResult(batchId, "ACCEPTED", req.items().size());
    }

    /** 模型信息（仿真内置快照） */
    public SummaryDtos.SummaryModels models() {
        return new SummaryDtos.SummaryModels("summary-llm-v2", "LLM（生成式）",
                "私有化部署（文本不出域）", List.of("overview", "bullet", "terse"), "v2026.09");
    }

    // ---------------- 内部处理链 ----------------

    private SummaryDtos.SummaryResult doGenerate(SummaryDtos.GenerateRequest req, String requestId) {
        if (req == null || req.text() == null) {
            throw new BaiException(SummaryErrorCodes.PARAM_INVALID, "SUM_1001 参数缺失：text 必填");
        }
        String text = req.text();
        if (text.isBlank() || isPureSymbol(text)) {
            throw new BaiException(SummaryErrorCodes.EMPTY_TEXT, "SUM_1006 空文本/纯符号，请修正输入");
        }
        if (text.length() > MAX_TEXT) {
            throw new BaiException(SummaryErrorCodes.TEXT_TOO_LONG, "SUM_1005 文本超过 20000 字，请截断或分批");
        }
        String mode = req.mode() == null || req.mode().isBlank() ? "overview" : req.mode();
        if (!MODES.contains(mode)) {
            throw new BaiException(SummaryErrorCodes.PARAM_INVALID, "SUM_1001 mode 非法：overview/bullet/terse");
        }
        if (req.maxChars() != null && (req.maxChars() < 1 || req.maxChars() > 2000)) {
            throw new BaiException(SummaryErrorCodes.PARAM_INVALID, "SUM_1001 maxChars 超范围：取值 1—2000");
        }

        long start = System.currentTimeMillis();
        boolean chunked = text.length() > 5000;
        String scene = req.scene() == null || req.scene().isBlank() ? "default" : req.scene();
        List<String> sentences = splitSentences(text);

        // 2-4. 摘要生成与分块融合（仿真为按句序抽取，块间要点去重即同句不重复）
        int limit = effectiveLimit(mode, text.length(), req.maxChars());
        String summary = buildSummary(mode, sentences, limit);

        // 5. 关键信息提取（缺失标记 warnings 不阻断，仿真以空项跳过）
        boolean extractKeyInfo = req.extractKeyInfo() == null || req.extractKeyInfo();
        List<SummaryDtos.KeyInfo> keyInfo = extractKeyInfo ? extractKeyInfo(text, sentences) : null;

        // 6. 长度控制：maxChars 优先于模式默认
        if (req.maxChars() != null && summary.length() > req.maxChars()) {
            summary = summary.substring(0, req.maxChars());
        }

        int outputChars = summary.length();
        double ratio = text.isEmpty() ? 0 : Math.round(outputChars * 10000.0 / text.length()) / 10000.0;
        long elapsed = System.currentTimeMillis() - start;
        SummaryDtos.SummaryResult result = new SummaryDtos.SummaryResult(
                nextId("SUM"), scene, mode, summary, keyInfo, chunked,
                new SummaryDtos.Stats(text.length(), outputChars, ratio), elapsed);

        log.info("摘要生成审计(仿真): requestId={}, scene={}, mode={}, textHash={}, chunked={}, in={}chars, out={}chars, ratio={}, elapsed={}ms",
                requestId, scene, mode, Objects.hash(text), chunked, text.length(), outputChars, ratio, elapsed);
        return result;
    }

    /** 模式默认压缩率：overview 10%（上限 2000）/bullet 15%（上限 3000）/terse 100 字；maxChars 优先 */
    private int effectiveLimit(String mode, int inputChars, Integer maxChars) {
        if (maxChars != null) {
            return maxChars;
        }
        return switch (mode) {
            case "overview" -> Math.min(2000, Math.max(1, inputChars / 10));
            case "bullet" -> Math.min(3000, Math.max(1, inputChars * 15 / 100));
            default -> TERSE_MAX;
        };
    }

    /** 生成式摘要仿真：overview 连句概述 / bullet 编号要点（每点 ≤100 字，句序即原文引用）/ terse 首句极简 */
    private String buildSummary(String mode, List<String> sentences, int limit) {
        if (sentences.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if ("bullet".equals(mode)) {
            int no = 1;
            for (String s : sentences) {
                String point = truncate(s, BULLET_POINT_MAX);
                if (sb.length() + point.length() + 4 > limit && no > 1) {
                    break;
                }
                sb.append(no++).append(". ").append(point).append('\n');
            }
            if (sb.isEmpty()) {
                sb.append("1. ").append(truncate(sentences.get(0), Math.min(BULLET_POINT_MAX, limit)));
            }
            return sb.toString().strip();
        }
        for (String s : sentences) {
            if (sb.length() + s.length() > limit) {
                if (sb.isEmpty()) {
                    return truncate(s, limit);
                }
                break;
            }
            sb.append(s);
        }
        if (sb.isEmpty()) {
            return truncate(sentences.get(0), limit);
        }
        return sb.toString();
    }

    /** 关键信息提取：AMOUNT（归一到元）/DATE（YYYY-MM-DD）/TERM（月/期）/GUARANTY/PARTY/MATTER（≤20 字） */
    private List<SummaryDtos.KeyInfo> extractKeyInfo(String text, List<String> sentences) {
        Map<String, SummaryDtos.KeyInfo> found = new LinkedHashMap<>();
        Matcher m = P_AMOUNT.matcher(text);
        while (m.find()) {
            double v = Double.parseDouble(m.group(1));
            String unit = m.group(2);
            if (unit.contains("万")) {
                v *= 1e4;
            } else if (unit.contains("亿")) {
                v *= 1e8;
            }
            found.putIfAbsent("AMOUNT:" + m.group(),
                    new SummaryDtos.KeyInfo("AMOUNT", m.group(), String.format("%.2f", v), "元"));
        }
        m = P_DATE.matcher(text);
        while (m.find()) {
            String d = m.group().replace('年', '-').replace('月', '-').replace('/', '-').replace("日", "");
            String[] p = d.split("-");
            if (p.length == 3) {
                String normalized = String.format("%04d-%02d-%02d",
                        Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                found.putIfAbsent("DATE:" + m.group(),
                        new SummaryDtos.KeyInfo("DATE", m.group(), normalized, "date"));
            }
        }
        m = P_TERM.matcher(text);
        while (m.find()) {
            int n = Integer.parseInt(m.group().replaceAll("\\D", ""));
            String unit = m.group().contains("年") ? "month" : m.group().contains("个月") ? "month" : "installment";
            String normalized = String.valueOf(m.group().contains("年") ? n * 12 : n);
            found.putIfAbsent("TERM:" + m.group(), new SummaryDtos.KeyInfo("TERM", m.group(), normalized, unit));
        }
        m = P_GUARANTY.matcher(text);
        while (m.find()) {
            String g = m.group().endsWith("担保") ? m.group() : m.group() + "担保";
            found.putIfAbsent("GUARANTY:" + g, new SummaryDtos.KeyInfo("GUARANTY", g, g, "way"));
        }
        m = P_PARTY.matcher(text);
        while (m.find()) {
            found.putIfAbsent("PARTY:" + m.group(1),
                    new SummaryDtos.KeyInfo("PARTY", m.group(1), m.group(1), "party"));
        }
        if (!sentences.isEmpty()) {
            String matter = truncate(sentences.get(0), 20);
            found.putIfAbsent("MATTER:" + matter, new SummaryDtos.KeyInfo("MATTER", matter, matter, "matter"));
        }
        return List.copyOf(found.values());
    }

    /** 分块仿真：按句边界组织 ≤2000 字块（重叠 200 字由句序保留实现） */
    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        for (String s : text.split("(?<=[。！？；!?;\n])")) {
            String t = s.strip();
            if (!t.isEmpty()) {
                sentences.add(t);
            }
        }
        if (sentences.isEmpty() && !text.isBlank()) {
            sentences.add(text.strip());
        }
        return sentences;
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
