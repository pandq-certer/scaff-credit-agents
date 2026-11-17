package com.bai.ner;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实体抽取服务。MVP 阶段为确定性仿真引擎（标注：仿真，用于联调），以规则/词典/正则近似模拟 BERT+CRF 序列标注；
 * NER_2001 模型不可用降级走的词典+规则兜底即本实现。
 * <p>处理链：长文本 512 token 窗口分窗 → 模型 BIO 序列合并（仿真为整段正则）→ 领域词典最长匹配融合
 * → 规则增强（敏感实体规则优先；金额/日期/利率/期限归一）→ 置信度过滤 → 最长匹配去重与落库（明文不落库，存哈希+脱敏文本）
 */
@Service
public class NerService {

    private static final Logger log = LoggerFactory.getLogger(NerService.class);

    private static final int MAX_TEXT = 20000;
    private static final int MAX_BATCH = 100000;

    /** 实体类型体系（仿真内置快照；敏感类型识别即脱敏） */
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "PERSON", "ORG", "AMOUNT", "DATE", "RATE", "TERM",
            "ID_NO", "ACCOUNT", "PHONE", "ADDRESS", "PRODUCT", "COLLATERAL");

    /** 敏感实体规则（规则优先于模型）；产品/担保物走领域词典（DICT）；其余模拟模型序列标注（MODEL） */
    private static final Pattern P_ID_NO = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern P_ACCOUNT = Pattern.compile("(?<!\\d)62\\d{14,17}(?!\\d)");
    private static final Pattern P_PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern P_ADDRESS = Pattern.compile(
            "[\\u4e00-\\u9fa5]{2,8}(?:省|市)[\\u4e00-\\u9fa5]{0,8}(?:市|区|县)[\\u4e00-\\u9fa5\\d]{0,20}(?:路|街|道|巷)[\\u4e00-\\u9fa5\\d]{0,10}号?");
    private static final Pattern P_AMOUNT = Pattern.compile("\\d+(?:\\.\\d+)?(?:万|亿)?元");
    private static final Pattern P_DATE = Pattern.compile("\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?");
    private static final Pattern P_RATE = Pattern.compile("\\d+(?:\\.\\d+)?%");
    private static final Pattern P_TERM = Pattern.compile("\\d+\\s*(?:个月|年|期)");
    private static final Pattern P_PRODUCT = Pattern.compile("经营贷|消费贷|住房贷款|房贷|车贷|信用贷|抵押贷款");
    private static final Pattern P_COLLATERAL = Pattern.compile("住宅|商品房|商铺|厂房|车辆|土地|存单");
    private static final Pattern P_ORG = Pattern.compile("[\\u4e00-\\u9fa5]{2,10}(?:银行|公司)");
    private static final Pattern P_PERSON = Pattern.compile(
            "(?:借款人|申请人|客户)([\\u4e00-\\u9fa5]{2,3}?)(?=[，。！？、\\s]|向|在|于|与|和|已|将|因|申请|提供)");
    private static final Pattern P_AMOUNT_NUM = Pattern.compile("(\\d+(?:\\.\\d+)?)(万|亿)?");
    private static final Pattern P_TERM_NUM = Pattern.compile("(\\d+)\\s*(个月|年|期)");

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final Map<String, NerDtos.BatchTask> tasks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 同步单条抽取（幂等：requestId 相同返回首次结果，缓存 24h） */
    public NerDtos.NerResult extract(NerDtos.ExtractRequest req, String requestId) {
        NerDtos.NerResult cached = idempotency.get("extract", requestId);
        if (cached != null) {
            return cached;
        }
        NerDtos.NerResult result = doExtract(req, requestId);
        idempotency.put("extract", requestId, result);
        return result;
    }

    /** 批量抽取（≤100000 条，异步；MVP 仿真为同步完成并落任务存储供 GET /tasks/{taskId} 查询） */
    public NerDtos.BatchTask batch(NerDtos.BatchRequest req, String requestId) {
        NerDtos.BatchTask cached = idempotency.get("batch", requestId);
        if (cached != null) {
            return cached;
        }
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BaiException(NerErrorCodes.PARAM_INVALID, "NER_1001 参数缺失：items 批量项必填");
        }
        if (req.items().size() > MAX_BATCH) {
            throw new BaiException(NerErrorCodes.TEXT_TOO_LONG, "NER_1005 批量超 100000 条，请截断或分批");
        }
        List<NerDtos.NerResult> results = new ArrayList<>();
        for (int i = 0; i < req.items().size(); i++) {
            try {
                results.add(doExtract(req.items().get(i), requestId + "-" + (i + 1)));
            } catch (BaiException e) {
                throw new BaiException(e.getCode(), e.getMessage() + "（第 " + (i + 1) + " 条）");
            }
        }
        NerDtos.BatchTask task = new NerDtos.BatchTask(nextId("NERB"), "DONE", req.items().size(), results);
        idempotency.put("batch", requestId, task);
        tasks.put(task.taskId(), task);
        log.info("实体批量任务完成(仿真): requestId={}, taskId={}, total={}", requestId, task.taskId(), task.total());
        return task;
    }

    /** 异步任务查询 */
    public NerDtos.BatchTask getTask(String taskId) {
        NerDtos.BatchTask task = tasks.get(taskId);
        if (task == null) {
            throw new BaiException(NerErrorCodes.NOT_FOUND, "NER_1004 taskId 不存在，请校验任务号");
        }
        return task;
    }

    /** 实体类型体系（仿真内置快照） */
    public NerDtos.NerTypes types() {
        return new NerDtos.NerTypes("v2026.09", List.of(
                new NerDtos.TypeDef("PERSON", "客户/人员", false),
                new NerDtos.TypeDef("ORG", "机构", false),
                new NerDtos.TypeDef("AMOUNT", "金额", false),
                new NerDtos.TypeDef("DATE", "日期", false),
                new NerDtos.TypeDef("RATE", "利率", false),
                new NerDtos.TypeDef("TERM", "期限", false),
                new NerDtos.TypeDef("ID_NO", "证件号", true),
                new NerDtos.TypeDef("ACCOUNT", "账号", true),
                new NerDtos.TypeDef("PHONE", "手机号", true),
                new NerDtos.TypeDef("ADDRESS", "地址", true),
                new NerDtos.TypeDef("PRODUCT", "产品", false),
                new NerDtos.TypeDef("COLLATERAL", "担保物", false)));
    }

    // ---------------- 内部处理链 ----------------

    private NerDtos.NerResult doExtract(NerDtos.ExtractRequest req, String requestId) {
        if (req == null || req.text() == null) {
            throw new BaiException(NerErrorCodes.PARAM_INVALID, "NER_1001 参数缺失：text 必填");
        }
        String text = req.text();
        if (text.isBlank() || isPureSymbol(text)) {
            throw new BaiException(NerErrorCodes.EMPTY_TEXT, "NER_1006 空文本/纯符号，请修正输入");
        }
        if (text.length() > MAX_TEXT) {
            throw new BaiException(NerErrorCodes.TEXT_TOO_LONG, "NER_1005 文本超过 20000 字，请截断或分批");
        }
        if (req.types() != null) {
            for (String t : req.types()) {
                if (!SUPPORTED_TYPES.contains(t)) {
                    throw new BaiException(NerErrorCodes.NOT_FOUND, "NER_1004 实体类型不存在：" + t);
                }
            }
        }

        long start = System.currentTimeMillis();
        boolean normalize = req.enableNormalize() == null || req.enableNormalize();
        boolean sensitive = req.enableSensitive() == null || req.enableSensitive();
        List<String> types = req.types() == null ? List.of() : req.types();

        List<NerDtos.Entity> entities = simulate(text, types, normalize, sensitive);
        Map<String, Integer> stats = new LinkedHashMap<>();
        for (NerDtos.Entity e : entities) {
            stats.merge(e.type(), 1, Integer::sum);
        }
        long elapsed = System.currentTimeMillis() - start;
        String scene = req.scene() == null || req.scene().isBlank() ? "default" : req.scene();
        NerDtos.NerResult result = new NerDtos.NerResult(nextId("NER"), scene, sha256Hex(text), entities, stats, elapsed);

        long sensitiveCount = entities.stream().filter(NerDtos.Entity::sensitive).count();
        log.info("实体抽取审计(仿真): requestId={}, scene={}, textHash={}, textLen={}, stats={}, sensitiveCount={}, elapsed={}ms",
                requestId, scene, result.textHash(), text.length(), stats, sensitiveCount, elapsed);
        return result;
    }

    /**
     * 仿真抽取引擎（确定性）：敏感实体规则 → 词典最长匹配 → 模型序列标注仿真（正则近似）；
     * 跨窗口/嵌套实体按最长匹配优先去重（先扫到的优先级高，重叠跳过）
     */
    private List<NerDtos.Entity> simulate(String text, List<String> types, boolean normalize, boolean sensitive) {
        List<NerDtos.Entity> found = new ArrayList<>();
        if (sensitive) {
            scan(text, P_ID_NO, "ID_NO", 0.99, "RULE", true, normalize, found);
            scan(text, P_ACCOUNT, "ACCOUNT", 0.98, "RULE", true, normalize, found);
            scan(text, P_PHONE, "PHONE", 0.98, "RULE", true, normalize, found);
            scan(text, P_ADDRESS, "ADDRESS", 0.90, "MODEL", true, normalize, found);
        }
        scan(text, P_AMOUNT, "AMOUNT", 0.97, "MODEL", false, normalize, found);
        scan(text, P_DATE, "DATE", 0.96, "MODEL", false, normalize, found);
        scan(text, P_RATE, "RATE", 0.99, "RULE", false, normalize, found);
        scan(text, P_TERM, "TERM", 0.96, "MODEL", false, normalize, found);
        scan(text, P_PRODUCT, "PRODUCT", 0.95, "DICT", false, normalize, found);
        scan(text, P_COLLATERAL, "COLLATERAL", 0.92, "DICT", false, normalize, found);
        scan(text, P_ORG, "ORG", 0.96, "MODEL", false, normalize, found);
        scanPerson(text, found);

        List<NerDtos.Entity> out = new ArrayList<>();
        for (NerDtos.Entity e : found) {
            // 类型过滤（缺省全部）＋ 置信度过滤（≥0.85 直接输出；0.60—0.85 词典命中保留；<0.60 丢弃；敏感强制保留）
            if (!types.isEmpty() && !types.contains(e.type())) {
                continue;
            }
            boolean pass = e.confidence() >= 0.85
                    || ("DICT".equals(e.source()) && e.confidence() >= 0.60)
                    || e.sensitive();
            if (pass) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparingInt(NerDtos.Entity::start));
        return out;
    }

    private void scan(String text, Pattern pattern, String type, double confidence, String source,
                      boolean mask, boolean normalize, List<NerDtos.Entity> found) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            if (overlaps(found, m.start(), m.end())) {
                continue;
            }
            String raw = m.group();
            found.add(new NerDtos.Entity(type, mask ? maskSensitive(raw) : raw, m.start(), m.end(),
                    confidence, source, normalize ? normalizeValue(type, raw) : null, mask));
        }
    }

    private void scanPerson(String text, List<NerDtos.Entity> found) {
        Matcher m = P_PERSON.matcher(text);
        while (m.find()) {
            int s = m.start(1);
            int e = m.end(1);
            if (overlaps(found, s, e)) {
                continue;
            }
            found.add(new NerDtos.Entity("PERSON", m.group(1), s, e, 0.93, "MODEL", null, false));
        }
    }

    private boolean overlaps(List<NerDtos.Entity> found, int start, int end) {
        for (NerDtos.Entity e : found) {
            if (start < e.end() && end > e.start()) {
                return true;
            }
        }
        return false;
    }

    /** 敏感实体脱敏：保留前 3 后 4，中间打码 */
    private String maskSensitive(String raw) {
        if (raw.length() <= 7) {
            return "*".repeat(raw.length());
        }
        return raw.substring(0, 3) + "*".repeat(raw.length() - 7) + raw.substring(raw.length() - 4);
    }

    /** 数字归一：金额万×1e4/亿×1e8（元）、百分比转小数、日期归 YYYY-MM-DD、期限归月/期；失败输出 raw 且 normalizable=false */
    private NerDtos.Normalized normalizeValue(String type, String raw) {
        try {
            switch (type) {
                case "AMOUNT" -> {
                    Matcher m = P_AMOUNT_NUM.matcher(raw);
                    if (m.find()) {
                        double v = Double.parseDouble(m.group(1));
                        if ("万".equals(m.group(2))) {
                            v *= 1e4;
                        } else if ("亿".equals(m.group(2))) {
                            v *= 1e8;
                        }
                        return new NerDtos.Normalized(v == Math.floor(v) ? (long) v : v, "yuan", true);
                    }
                }
                case "RATE" -> {
                    return new NerDtos.Normalized(Double.parseDouble(raw.replace("%", "")) / 100, "ratio", true);
                }
                case "DATE" -> {
                    String d = raw.replace('年', '-').replace('月', '-').replace('/', '-').replace("日", "");
                    String[] p = d.split("-");
                    return new NerDtos.Normalized(String.format("%04d-%02d-%02d", Integer.parseInt(p[0]),
                            Integer.parseInt(p[1]), Integer.parseInt(p[2])), "date", true);
                }
                case "TERM" -> {
                    Matcher m = P_TERM_NUM.matcher(raw);
                    if (m.find()) {
                        int n = Integer.parseInt(m.group(1));
                        if ("年".equals(m.group(2))) {
                            return new NerDtos.Normalized(n * 12, "month", true);
                        }
                        return new NerDtos.Normalized(n, "个月".equals(m.group(2)) ? "month" : "installment", true);
                    }
                }
                default -> {
                    return null; // 非数值型实体无归一值
                }
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
            // 归一失败：输出 raw 并标记 normalizable=false
        }
        return new NerDtos.Normalized(raw, null, false);
    }

    private boolean isPureSymbol(String text) {
        return text.codePoints().noneMatch(Character::isLetterOrDigit);
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
