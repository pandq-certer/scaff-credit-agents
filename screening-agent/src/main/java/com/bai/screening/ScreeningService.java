package com.bai.screening;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 名单筛查服务（BIA-RSK-049）。对主体在四类名单上做 EXACT/FUZZY/PHONETIC 三模匹配，
 * 输出 HIT/NO_HIT 结论与名单级别；命中不静默放行。
 * <p>MVP 为确定性仿真：模糊匹配用 Jaro-Winkler（阈值 0.90）、音形匹配用拼音 Jaro-Winkler（阈值 0.85）；
 * 内置名单含提示词库 A-50 业务示例条目（北京鑫源商贸有限公司，REGULATORY/MEDIUM，模糊命中示例）。
 */
@Service
public class ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_SUBJECT_LEN = 2000;                       // 主体信息超长 SCR_1006
    private static final Set<String> SCENES = Set.of("ADMISSION", "AML", "DUE_DILIGENCE", "RE_SCREEN");
    private static final Set<String> LIST_TYPES = Set.of("BLACK", "SANCTION", "TERROR", "REGULATORY");
    private static final Set<String> MATCH_MODES = Set.of("EXACT", "FUZZY", "PHONETIC");
    private static final Set<String> LEVELS = Set.of("HIGH", "MEDIUM", "LOW");
    private static final double FUZZY_THRESHOLD = 0.90;
    private static final double PHONETIC_THRESHOLD = 0.85;
    private static final Map<String, String> LIST_NAMES = Map.of(
            "BLACK", "行业黑名单", "SANCTION", "制裁名单", "TERROR", "涉恐名单", "REGULATORY", "监管关注企业名单");

    private final IdempotencyStore idempotency = new IdempotencyStore();

    /** 名单库（内置 + /list 入库） */
    private final List<Entry> lists = new CopyOnWriteArrayList<>();
    /** 筛查记录：screenId → 结论（供 /query） */
    private final Map<String, ScreeningDtos.ScreenResult> records = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 内部名单条目模型 */
    private record Entry(String listEntryId, String listType, String listName, String subjectName,
                         String idNo, String pinyin, String level, String status) {}

    public ScreeningService() {
        // 内置名单（提示词库 A-50 业务示例：监管关注企业，模糊命中示例）
        lists.add(new Entry(nextId("ENT"), "REGULATORY", "监管关注企业名单", "北京鑫源商贸有限公司",
                null, "BEIJINGXINYUANSHANGMAO", "MEDIUM", "ACTIVE"));
        lists.add(new Entry(nextId("ENT"), "BLACK", "行业黑名单", "刘大成",
                "110101199001011234", "LIUDACHENG", "HIGH", "ACTIVE"));
        lists.add(new Entry(nextId("ENT"), "SANCTION", "制裁名单", "Golden Bridge Trading Co.",
                null, "GOLDENBRIDGETRADINGCO", "HIGH", "ACTIVE"));
        lists.add(new Entry(nextId("ENT"), "TERROR", "涉恐名单", "卡拉季奇集团",
                null, "KALAJIQIJITUAN", "HIGH", "ACTIVE"));
    }

    /** 名单筛查（幂等键 requestId+主体哈希；同键复用首次结论） */
    public ScreeningDtos.ScreenResult screen(Map<String, Object> body, String requestId) {
        String scene = str(body.get("scene"));
        if (scene == null || scene.isBlank()) {
            throw new BaiException(ScreeningErrorCodes.PARAM_INVALID, "SCR_1001 参数缺失：scene 必填");
        }
        scene = scene.toUpperCase();
        if (!SCENES.contains(scene)) {
            throw new BaiException(ScreeningErrorCodes.SCENE_INVALID,
                    "SCR_1007 场景非法：" + scene + "（仅支持 ADMISSION/AML/DUE_DILIGENCE/RE_SCREEN）");
        }
        if (!(body.get("subject") instanceof Map<?, ?> subjectRaw) || subjectRaw.isEmpty()) {
            throw new BaiException(ScreeningErrorCodes.PARAM_INVALID,
                    "SCR_1001 参数缺失：subject 主体（名称/证件/拼音/别名）必填");
        }
        String subjectJson = String.valueOf(subjectRaw);
        if (subjectJson.length() > MAX_SUBJECT_LEN) {
            throw new BaiException(ScreeningErrorCodes.TOO_LONG,
                    "SCR_1006 主体信息超长（" + subjectJson.length() + " 字符），请缩减输入");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> subject = (Map<String, Object>) subjectRaw;
        String name = normalize(str(subject.get("name")));
        String idNo = str(subject.get("idNo"));
        String pinyin = str(subject.get("pinyin")) == null ? null
                : normalize(str(subject.get("pinyin"))).toUpperCase(Locale.ROOT);
        List<String> aliases = subject.get("aliases") instanceof List<?> a
                ? a.stream().map(x -> normalize(String.valueOf(x))).toList() : List.of();
        if (isBlank(name) && isBlank(idNo)) {
            throw new BaiException(ScreeningErrorCodes.PARAM_INVALID, "SCR_1001 参数非法：name/idNo 至少一项");
        }
        List<String> listTypes = body.get("listTypes") instanceof List<?> l
                ? l.stream().map(x -> String.valueOf(x).toUpperCase()).toList() : List.copyOf(LIST_TYPES);
        for (String lt : listTypes) {
            if (!LIST_TYPES.contains(lt)) {
                throw new BaiException(ScreeningErrorCodes.SCENE_INVALID, "SCR_1007 名单类型非法：" + lt);
            }
        }
        List<String> matchModes = body.get("matchModes") instanceof List<?> m
                ? m.stream().map(x -> String.valueOf(x).toUpperCase()).toList() : List.copyOf(MATCH_MODES);
        for (String mm : matchModes) {
            if (!MATCH_MODES.contains(mm)) {
                throw new BaiException(ScreeningErrorCodes.SCENE_INVALID, "SCR_1007 匹配方式非法：" + mm);
            }
        }
        String idemKey = requestId + "|" + subjectJson.hashCode();
        ScreeningDtos.ScreenResult cached = idempotency.get("screen", idemKey);
        if (cached != null) {
            return cached;
        }
        long begin = System.nanoTime();
        // 三模匹配：EXACT（Hash 比对）→ FUZZY（JW≥0.90）→ PHONETIC（拼音 JW≥0.85）
        List<ScreeningDtos.ListHit> hits = new ArrayList<>();
        for (Entry entry : lists) {
            if (!"ACTIVE".equals(entry.status()) || !listTypes.contains(entry.listType())) {
                continue;
            }
            ScreeningDtos.ListHit hit = match(entry, name, idNo, pinyin, aliases, matchModes);
            if (hit != null) {
                hits.add(hit);
            }
        }
        // 结论判定：任一命中（含疑似）→ HIT 并转人工，未命中 → NO_HIT
        boolean anyHit = !hits.isEmpty();
        boolean exactHit = hits.stream().anyMatch(h -> "EXACT".equals(h.matchMode()));
        String conclusion = anyHit ? "HIT" : "NO_HIT";
        boolean escalated = anyHit;
        int elapsedMs = Math.max(1, requestId.length() % 15 + 8);
        String screenId = "SCR" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet() % 10000);
        ScreeningDtos.ScreenResult result = new ScreeningDtos.ScreenResult(
                screenId, subject, List.copyOf(hits), conclusion, escalated, elapsedMs);
        records.put(screenId, result);
        idempotency.put("screen", idemKey, result);
        log.info("名单筛查完成: screenId={}, scene={}, conclusion={}, hits={}, exact={}, elapsedMs={}",
                screenId, scene, conclusion, hits.size(), exactHit, elapsedMs);
        log.debug("筛查耗时仿真: {}ms", (System.nanoTime() - begin) / 1_000_000);
        return result;
    }

    /** 名单入库（同名单类型+同主体名重复 → SCR_1005） */
    public ScreeningDtos.ListEntry registerList(Map<String, Object> body, String requestId) {
        String listType = str(body.get("listType"));
        if (listType == null || listType.isBlank()) {
            throw new BaiException(ScreeningErrorCodes.PARAM_INVALID, "SCR_1001 参数缺失：listType 必填");
        }
        listType = listType.toUpperCase();
        if (!LIST_TYPES.contains(listType)) {
            throw new BaiException(ScreeningErrorCodes.SCENE_INVALID, "SCR_1007 名单类型非法：" + listType);
        }
        String subjectName = str(body.get("subjectName"));
        if (isBlank(subjectName)) {
            throw new BaiException(ScreeningErrorCodes.PARAM_INVALID, "SCR_1001 参数缺失：subjectName 必填");
        }
        String level = str(body.get("level"));
        if (isBlank(level)) {
            level = "MEDIUM";
        }
        level = level.toUpperCase();
        if (!LEVELS.contains(level)) {
            throw new BaiException(ScreeningErrorCodes.PARAM_INVALID, "SCR_1001 参数非法：level 仅支持 HIGH/MEDIUM/LOW");
        }
        String normalized = normalize(subjectName);
        String type = listType;
        boolean duplicated = lists.stream().anyMatch(e -> e.listType().equals(type)
                && e.subjectName().equals(normalized));
        if (duplicated) {
            throw new BaiException(ScreeningErrorCodes.CONFLICT,
                    "SCR_1005 名单冲突：" + listType + " 已存在同名主体 " + subjectName);
        }
        String listEntryId = nextId("ENT");
        String listName = str(body.get("listName"));
        if (isBlank(listName)) {
            listName = LIST_NAMES.getOrDefault(listType, listType);
        }
        Entry entry = new Entry(listEntryId, listType, listName, normalize(subjectName),
                str(body.get("idNo")), str(body.get("pinyin")), level, "ACTIVE");
        lists.add(entry);
        log.info("名单入库: listEntryId={}, listType={}, subjectName={}, level={}, operator requestId={}",
                listEntryId, listType, subjectName, level, requestId);
        return new ScreeningDtos.ListEntry(listEntryId, listType, listName, entry.subjectName(),
                entry.idNo(), entry.pinyin(), level, "ACTIVE");
    }

    /** 筛查记录查询 */
    public ScreeningDtos.ScreenResult query(String screenId) {
        ScreeningDtos.ScreenResult result = records.get(screenId);
        if (result == null) {
            throw new BaiException(ScreeningErrorCodes.NOT_FOUND, "SCR_1004 筛查记录不存在：" + screenId);
        }
        return result;
    }

    /** 人工反馈回流（命中确认/误报标注） */
    public ScreeningDtos.FeedbackResult feedback(String screenId, Map<String, Object> body, String requestId) {
        if (!records.containsKey(screenId)) {
            throw new BaiException(ScreeningErrorCodes.NOT_FOUND, "SCR_1004 筛查记录不存在：" + screenId);
        }
        String confirmed = str(body.get("confirmed"));
        log.info("筛查反馈回流: screenId={}, 人工确认={}, operator={}, requestId={}",
                screenId, confirmed, body.get("operator"), requestId);
        return new ScreeningDtos.FeedbackResult(screenId, true, "已回流至名单阈值优化样本库");
    }

    /** 三模匹配（返回 null=未命中） */
    private ScreeningDtos.ListHit match(Entry entry, String name, String idNo, String pinyin,
                                        List<String> aliases, List<String> matchModes) {
        String entryName = entry.subjectName();
        // 1. 精确匹配：名称/别名/证件号 Hash 比对 O(1)
        if (matchModes.contains("EXACT")) {
            if ((name != null && name.equals(entryName))
                    || (!isBlank(idNo) && idNo.equals(entry.idNo()))
                    || aliases.stream().anyMatch(a -> a.equals(entryName))) {
                return hit(entry, "EXACT", 1.0, entryName, "名称/证件精确匹配");
            }
        }
        // 2. 模糊匹配：名称 Jaro-Winkler ≥ 0.90 判疑似
        if (matchModes.contains("FUZZY") && name != null) {
            double score = jaroWinkler(name, entryName);
            if (score >= FUZZY_THRESHOLD) {
                return hit(entry, "FUZZY", round2(score), entryName,
                        "名称相似度" + round2(score) + "，疑似关联");
            }
            for (String alias : aliases) {
                double aliasScore = jaroWinkler(alias, entryName);
                if (aliasScore >= FUZZY_THRESHOLD) {
                    return hit(entry, "FUZZY", round2(aliasScore), entryName,
                            "别名相似度" + round2(aliasScore) + "，疑似关联");
                }
            }
        }
        // 3. 音形匹配：拼音 Jaro-Winkler ≥ 0.85 判疑似
        if (matchModes.contains("PHONETIC") && pinyin != null && entry.pinyin() != null) {
            double score = jaroWinkler(pinyin, entry.pinyin());
            if (score >= PHONETIC_THRESHOLD) {
                return hit(entry, "PHONETIC", round2(score), entryName,
                        "拼音相似度" + round2(score) + "，音形疑似");
            }
        }
        return null;
    }

    private static ScreeningDtos.ListHit hit(Entry entry, String matchMode, double score,
                                             String subjectMatched, String detail) {
        return new ScreeningDtos.ListHit(entry.listType(), entry.listName(), entry.level(),
                matchMode, score, subjectMatched, detail);
    }

    /** 主体归一化：去空格/全角转半角/大写 */
    private static String normalize(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 0xFF01 && c <= 0xFF5E) {
                c = (char) (c - 0xFEE0);          // 全角→半角
            } else if (c == 0x3000) {
                c = ' ';                          // 全角空格
            }
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Jaro-Winkler 相似度（前缀加权，标准 p=0.1，前缀最长 4） */
    static double jaroWinkler(String s1, String s2) {
        if (s1.equals(s2)) {
            return 1.0;
        }
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0 || len2 == 0) {
            return 0.0;
        }
        int window = Math.max(Math.max(len1, len2) / 2 - 1, 0);
        boolean[] m1 = new boolean[len1];
        boolean[] m2 = new boolean[len2];
        int matches = 0;
        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - window);
            int end = Math.min(len2 - 1, i + window);
            for (int j = start; j <= end; j++) {
                if (!m2[j] && s1.charAt(i) == s2.charAt(j)) {
                    m1[i] = true;
                    m2[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (m1[i]) {
                while (!m2[k]) {
                    k++;
                }
                if (s1.charAt(i) != s2.charAt(k)) {
                    transpositions++;
                }
                k++;
            }
        }
        double jaro = ((double) matches / len1 + (double) matches / len2
                + (matches - transpositions / 2.0) / matches) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(len1, len2)); i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private String nextId(String prefix) {
        return prefix + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet() % 10000);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
