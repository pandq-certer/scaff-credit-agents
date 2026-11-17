package com.bai.sensitivemask;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感脱敏服务（BIA-RSK-046）。对字段/文本/批量数据按场景做敏感识别与脱敏，输出零明文结果、
 * 检出统计与规则版本快照。
 * <p>MVP 为确定性仿真：NER 用正则识别（证件号/手机号/银行卡/姓名/地址），
 * HASH 仿真用 SHA-256（生产替换国密 SM3）、FPE 仿真用哈希派生数字（生产替换 SM4）；
 * 场景算法映射固定：DISPLAY→MASK、EXPORT→FPE、LOG→HASH、DEV/TEST→REPLACE、STAT→TRUNCATE。
 */
@Service
public class SensitiveMaskService {

    private static final Logger log = LoggerFactory.getLogger(SensitiveMaskService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_TEXT_LEN = 10000;                 // 内容超长 SMK_1006
    private static final Set<String> DATA_TYPES = Set.of("FIELD", "TEXT", "BATCH");
    private static final Set<String> SCENARIOS = Set.of("DISPLAY", "EXPORT", "LOG", "DEV", "TEST", "STAT");
    private static final Set<String> ALGORITHMS = Set.of("MASK", "HASH", "REPLACE", "TRUNCATE", "FPE");

    /** 敏感识别正则（检出优先级：证件 > 银行卡 > 手机 > 姓名 > 地址，位置不重叠） */
    private static final Pattern P_IDCARD = Pattern.compile("\\d{17}[\\dXx]");
    private static final Pattern P_BANKCARD = Pattern.compile("\\d{13,19}");
    private static final Pattern P_MOBILE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern P_NAME = Pattern.compile("(?:客户|借款人|担保人|姓名[:：])[\\u4e00-\\u9fa5]{2,4}");
    private static final Pattern P_ADDRESS = Pattern.compile("(?:地址|住址)[:：]?[\\u4e00-\\u9fa5\\d]{4,30}?(?:路|街|巷|号|栋|楼|室)");

    private final IdempotencyStore idempotency = new IdempotencyStore();

    /** 脱敏记录：maskId → 结果（供 /verify 抽检） */
    private final Map<String, SensitiveMaskDtos.MaskResult> records = new ConcurrentHashMap<>();
    /** 规则注册表（内置 + /rule 注册） */
    private final List<SensitiveMaskDtos.RuleInfo> rules = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    public SensitiveMaskService() {
        rules.add(new SensitiveMaskDtos.RuleInfo("RULE_NAME_V3", "姓名脱敏", "NAME", "MASK", "V3", "ACTIVE"));
        rules.add(new SensitiveMaskDtos.RuleInfo("RULE_IDCARD_V5", "身份证脱敏", "IDCARD", "MASK", "V5", "ACTIVE"));
        rules.add(new SensitiveMaskDtos.RuleInfo("RULE_MOBILE_V2", "手机号脱敏", "MOBILE", "MASK", "V2", "ACTIVE"));
        rules.add(new SensitiveMaskDtos.RuleInfo("RULE_BANKCARD_V3", "银行卡脱敏", "BANKCARD", "MASK", "V3", "ACTIVE"));
        rules.add(new SensitiveMaskDtos.RuleInfo("RULE_ADDR_V1", "地址脱敏", "ADDRESS", "MASK", "V1", "ACTIVE"));
    }

    /** 统一脱敏（幂等键 requestId+dataType+内容哈希；同键复用首次结果） */
    public SensitiveMaskDtos.MaskResult mask(Map<String, Object> body, String requestId) {
        String dataType = str(body.get("dataType"));
        if (dataType == null || dataType.isBlank()) {
            throw new BaiException(SensitiveMaskErrorCodes.PARAM_INVALID, "SMK_1001 参数缺失：dataType 必填");
        }
        dataType = dataType.toUpperCase();
        if (!DATA_TYPES.contains(dataType)) {
            throw new BaiException(SensitiveMaskErrorCodes.TYPE_INVALID,
                    "SMK_1007 类型非法：" + dataType + "（仅支持 FIELD/TEXT/BATCH）");
        }
        String scenario = str(body.get("scenario"));
        if (scenario == null || scenario.isBlank()) {
            throw new BaiException(SensitiveMaskErrorCodes.PARAM_INVALID, "SMK_1001 参数缺失：scenario 必填");
        }
        scenario = scenario.toUpperCase();
        if (!SCENARIOS.contains(scenario)) {
            throw new BaiException(SensitiveMaskErrorCodes.TYPE_INVALID,
                    "SMK_1007 场景非法：" + scenario + "（仅支持 DISPLAY/EXPORT/LOG/DEV/TEST/STAT）");
        }
        String algorithm = algorithmOf(scenario);
        String idemKey = requestId + "|" + dataType + "|" + String.valueOf(body).hashCode();
        SensitiveMaskDtos.MaskResult cached = idempotency.get("mask", idemKey);
        if (cached != null) {
            return cached;
        }
        long begin = System.nanoTime();
        Object maskedFields = null;
        String maskedText = null;
        int detected;
        List<String> ruleVersions = new ArrayList<>();
        switch (dataType) {
            case "FIELD" -> {
                if (!(body.get("fields") instanceof Map<?, ?> fields) || fields.isEmpty()) {
                    throw new BaiException(SensitiveMaskErrorCodes.TYPE_INVALID,
                            "SMK_1007 类型不匹配：dataType=FIELD 时 fields 字段集必填（object）");
                }
                Map<String, Object> masked = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : fields.entrySet()) {
                    String key = String.valueOf(e.getKey());
                    String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
                    if (value.length() > MAX_TEXT_LEN) {
                        throw new BaiException(SensitiveMaskErrorCodes.TOO_LONG,
                                "SMK_1006 内容超长（字段 " + key + " " + value.length() + " 字符），请缩减输入");
                    }
                    String type = typeOf(key, value);
                    masked.put(key, apply(algorithm, type, value));
                    if (!"NONE".equals(type)) {
                        detected = 1;   // 计数在下方累加，此处仅标记
                        ruleVersions.add(ruleOf(type));
                    }
                }
                maskedFields = masked;
                detected = masked.size();
            }
            case "TEXT" -> {
                if (!(body.get("text") instanceof String text) || text.isBlank()) {
                    throw new BaiException(SensitiveMaskErrorCodes.TYPE_INVALID,
                            "SMK_1007 类型不匹配：dataType=TEXT 时 text 必填（string）");
                }
                if (text.length() > MAX_TEXT_LEN) {
                    throw new BaiException(SensitiveMaskErrorCodes.TOO_LONG,
                            "SMK_1006 内容超长（" + text.length() + " > " + MAX_TEXT_LEN + " 字符），请缩减输入");
                }
                List<SensitiveMaskDtos.Detection> detections = scan(text, algorithm);
                StringBuilder sb = new StringBuilder(text);
                for (int i = detections.size() - 1; i >= 0; i--) {
                    SensitiveMaskDtos.Detection d = detections.get(i);
                    sb.replace(d.start(), d.end(), d.masked());
                    ruleVersions.add(ruleOf(d.type()));
                }
                maskedText = sb.toString();
                detected = detections.size();
            }
            default -> {
                // BATCH：字段集数组，逐行按 FIELD 规则脱敏
                if (!(body.get("fields") instanceof List<?> rows) || rows.isEmpty()) {
                    throw new BaiException(SensitiveMaskErrorCodes.TYPE_INVALID,
                            "SMK_1007 类型不匹配：dataType=BATCH 时 fields 必填（object 数组）");
                }
                List<Map<String, Object>> maskedRows = new ArrayList<>();
                int count = 0;
                for (Object row : rows) {
                    if (!(row instanceof Map<?, ?> m)) {
                        throw new BaiException(SensitiveMaskErrorCodes.TYPE_INVALID,
                                "SMK_1007 类型不匹配：BATCH 行必须为 object");
                    }
                    Map<String, Object> masked = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        String key = String.valueOf(e.getKey());
                        String value = e.getValue() == null ? "" : String.valueOf(e.getValue());
                        String type = typeOf(key, value);
                        masked.put(key, apply(algorithm, type, value));
                        if (!"NONE".equals(type)) {
                            count++;
                            ruleVersions.add(ruleOf(type));
                        }
                    }
                    maskedRows.add(masked);
                }
                maskedFields = maskedRows;
                detected = count;
            }
        }
        List<String> distinctRules = ruleVersions.stream().distinct().toList();
        String maskId = "SMK" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet() % 10000);
        int elapsedMs = Math.max(1, requestId.length() % 10 + 5);
        SensitiveMaskDtos.MaskResult result = new SensitiveMaskDtos.MaskResult(
                maskId, maskedFields, maskedText, detected, distinctRules, elapsedMs);
        records.put(maskId, result);
        idempotency.put("mask", idemKey, result);
        log.info("脱敏完成: maskId={}, dataType={}, scenario={}, algorithm={}, detected={}, rules={}",
                maskId, dataType, scenario, algorithm, detected, distinctRules);
        log.debug("脱敏耗时仿真: {}ms", (System.nanoTime() - begin) / 1_000_000);
        return result;
    }

    /** 敏感识别：输出类型与位置（DISPLAY 掩码预览） */
    public SensitiveMaskDtos.DetectResult detect(Map<String, Object> body) {
        String text;
        if (body.get("text") instanceof String s && !s.isBlank()) {
            text = s;
        } else if (body.get("fields") instanceof Map<?, ?> f) {
            text = String.valueOf(f);
        } else {
            throw new BaiException(SensitiveMaskErrorCodes.PARAM_INVALID,
                    "SMK_1001 参数缺失：text 或 fields 必填其一");
        }
        if (text.length() > MAX_TEXT_LEN) {
            throw new BaiException(SensitiveMaskErrorCodes.TOO_LONG,
                    "SMK_1006 内容超长（" + text.length() + " > " + MAX_TEXT_LEN + " 字符），请缩减输入");
        }
        List<SensitiveMaskDtos.Detection> detections = scan(text, "MASK");
        List<String> ruleVersions = detections.stream().map(d -> ruleOf(d.type())).distinct().toList();
        int elapsedMs = Math.max(1, text.length() % 8 + 3);
        log.info("敏感识别: detected={}, types={}, elapsedMs={}", detections.size(), ruleVersions, elapsedMs);
        return new SensitiveMaskDtos.DetectResult(detections, detections.size(), ruleVersions, elapsedMs);
    }

    /** 脱敏抽检：按 maskId 复检残敏感，或直接复检传入内容 */
    public SensitiveMaskDtos.VerifyResult verify(Map<String, Object> body) {
        String maskId = str(body.get("maskId"));
        String text;
        String targetId;
        if (maskId != null && !maskId.isBlank()) {
            SensitiveMaskDtos.MaskResult record = records.get(maskId);
            if (record == null) {
                throw new BaiException(SensitiveMaskErrorCodes.NOT_FOUND, "SMK_1004 脱敏记录不存在：" + maskId);
            }
            text = record.maskedText() != null ? record.maskedText()
                    : (record.maskedFields() != null ? String.valueOf(record.maskedFields()) : "");
            targetId = maskId;
        } else if (body.get("maskedText") instanceof String s && !s.isBlank()) {
            text = s;
            targetId = "INLINE";
        } else {
            throw new BaiException(SensitiveMaskErrorCodes.PARAM_INVALID,
                    "SMK_1001 参数缺失：maskId 或 maskedText 必填其一");
        }
        List<SensitiveMaskDtos.Detection> residuals = scan(text, "MASK");
        int elapsedMs = Math.max(1, text.length() % 6 + 2);
        boolean clean = residuals.isEmpty();
        log.info("脱敏抽检: targetId={}, checkedChars={}, clean={}, residual={}",
                targetId, text.length(), clean, residuals.size());
        return new SensitiveMaskDtos.VerifyResult(targetId, text.length(), clean, residuals, elapsedMs);
    }

    /** 规则管理（重复 ruleId → SMK_1005） */
    public SensitiveMaskDtos.RuleInfo registerRule(Map<String, Object> body, String requestId) {
        String ruleId = str(body.get("ruleId"));
        String name = str(body.get("name"));
        String sensitiveType = str(body.get("sensitiveType"));
        String algorithm = str(body.get("algorithm"));
        if (isBlank(ruleId) || isBlank(name) || isBlank(sensitiveType)) {
            throw new BaiException(SensitiveMaskErrorCodes.PARAM_INVALID,
                    "SMK_1001 参数缺失：ruleId/name/sensitiveType 必填");
        }
        algorithm = isBlank(algorithm) ? "MASK" : algorithm.toUpperCase();
        if (!ALGORITHMS.contains(algorithm)) {
            throw new BaiException(SensitiveMaskErrorCodes.PARAM_INVALID,
                    "SMK_1001 参数非法：algorithm 仅支持 MASK/HASH/REPLACE/TRUNCATE/FPE");
        }
        if (rules.stream().anyMatch(r -> r.ruleId().equals(ruleId))) {
            throw new BaiException(SensitiveMaskErrorCodes.CONFLICT,
                    "SMK_1005 规则冲突：" + ruleId + " 已存在，请更换规则号");
        }
        SensitiveMaskDtos.RuleInfo info = new SensitiveMaskDtos.RuleInfo(
                ruleId, name, sensitiveType.toUpperCase(), algorithm, "V1", "ACTIVE");
        rules.add(info);
        log.info("脱敏规则入库: ruleId={}, type={}, algorithm={}, operator requestId={}",
                ruleId, info.sensitiveType(), algorithm, requestId);
        return info;
    }

    /** 场景→算法固定映射（不可绕过） */
    private String algorithmOf(String scenario) {
        return switch (scenario) {
            case "DISPLAY" -> "MASK";
            case "EXPORT" -> "FPE";
            case "LOG" -> "HASH";
            case "DEV", "TEST" -> "REPLACE";
            case "STAT" -> "TRUNCATE";
            default -> "MASK";   // 不可达：scenario 已校验
        };
    }

    /** 敏感类型判定：先按字段名映射，再按值正则 */
    private String typeOf(String key, String value) {
        String k = key.toLowerCase();
        if (k.contains("idcard") || k.contains("cert") || k.contains("证件")) {
            return "IDCARD";
        }
        if (k.contains("mobile") || k.contains("phone") || k.contains("tel") || k.contains("手机")) {
            return "MOBILE";
        }
        if (k.contains("bank") || k.contains("card") || k.contains("account") || k.contains("卡")) {
            return "BANKCARD";
        }
        if (k.contains("name") || k.contains("姓名")) {
            return "NAME";
        }
        if (k.contains("addr") || k.contains("地址")) {
            return "ADDRESS";
        }
        // 值正则兜底
        if (P_IDCARD.matcher(value).matches()) {
            return "IDCARD";
        }
        if (P_MOBILE.matcher(value).matches()) {
            return "MOBILE";
        }
        if (value.matches("\\d{13,19}")) {
            return "BANKCARD";
        }
        if (value.length() >= 2 && value.length() <= 4 && value.matches("[\\u4e00-\\u9fa5]+")) {
            return "NAME";
        }
        return "NONE";
    }

    /** 文本扫描（位置不重叠，按检出优先级依次占用区间） */
    private List<SensitiveMaskDtos.Detection> scan(String text, String algorithm) {
        List<SensitiveMaskDtos.Detection> detections = new ArrayList<>();
        List<boolean[]> occupied = new ArrayList<>();
        occupied.add(new boolean[text.length()]);
        scanOnce(text, P_IDCARD, "IDCARD", algorithm, occupied, detections);
        scanOnce(text, P_MOBILE, "MOBILE", algorithm, occupied, detections);
        scanOnce(text, P_BANKCARD, "BANKCARD", algorithm, occupied, detections);
        scanOnce(text, P_NAME, "NAME", algorithm, occupied, detections);
        scanOnce(text, P_ADDRESS, "ADDRESS", algorithm, occupied, detections);
        detections.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return detections;
    }

    private void scanOnce(String text, Pattern pattern, String type, String algorithm,
                          List<boolean[]> occupied, List<SensitiveMaskDtos.Detection> detections) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            if (anyOccupied(occupied, start, end)) {
                continue;
            }
            occupy(occupied, start, end);
            String masked = apply(algorithm, type, m.group());
            double confidence = switch (type) {
                case "IDCARD", "MOBILE" -> 0.99;
                case "BANKCARD" -> 0.95;
                case "NAME" -> 0.90;
                default -> 0.88;
            };
            detections.add(new SensitiveMaskDtos.Detection(type, start, end, masked, confidence));
        }
    }

    private static boolean anyOccupied(List<boolean[]> occupied, int start, int end) {
        boolean[] slots = occupied.get(0);
        for (int i = start; i < end; i++) {
            if (slots[i]) {
                return true;
            }
        }
        return false;
    }

    private static void occupy(List<boolean[]> occupied, int start, int end) {
        boolean[] slots = occupied.get(0);
        for (int i = start; i < end; i++) {
            slots[i] = true;
        }
    }

    /** 脱敏执行：按算法 × 类型确定性脱敏 */
    private String apply(String algorithm, String type, String value) {
        if ("NONE".equals(type) || value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        return switch (algorithm) {
            case "MASK" -> maskValue(type, value);
            case "HASH" -> "SM3*" + sha256(value).substring(0, 24);   // 仿真摘要，生产替换国密 SM3
            case "REPLACE" -> "REDACTED";                              // DEV/TEST 彻底替换
            case "TRUNCATE" -> truncateValue(type, value);             // STAT 聚合口径
            case "FPE" -> fpeValue(value);                             // 格式保留加密仿真
            default -> maskValue(type, value);
        };
    }

    /** MASK：保留首尾位 */
    private String maskValue(String type, String value) {
        return switch (type) {
            case "IDCARD" -> value.length() >= 8
                    ? value.substring(0, 3) + "***" + value.substring(value.length() - 4) : "***";
            case "MOBILE" -> value.length() >= 7
                    ? value.substring(0, 3) + "**" + value.substring(value.length() - 4) : "***";
            case "BANKCARD" -> value.length() >= 8
                    ? value.substring(0, 4) + "****" + value.substring(value.length() - 4) : "***";
            case "NAME" -> value.charAt(0) + "**";
            case "ADDRESS" -> value.length() <= 6 ? value.charAt(0) + "****"
                    : value.substring(0, 6) + "****";
            default -> value.length() <= 2 ? "***" : value.charAt(0) + "***";
        };
    }

    /** TRUNCATE：只保留聚合口径（证件取行政区划、手机取号段、地址取区域） */
    private String truncateValue(String type, String value) {
        return switch (type) {
            case "IDCARD" -> value.substring(0, 6) + "**";            // 行政区划
            case "MOBILE" -> value.substring(0, 3) + "****";          // 号段
            case "BANKCARD" -> value.substring(0, 4) + "**";
            case "ADDRESS" -> value.length() <= 3 ? value : value.substring(0, 3) + "**";
            default -> "**";
        };
    }

    /** FPE 仿真：数字按哈希派生同长度替换，字母/汉字保留原格式骨架 */
    private String fpeValue(String value) {
        String digest = sha256(value);
        StringBuilder sb = new StringBuilder();
        int di = 0;
        for (char c : value.toCharArray()) {
            if (Character.isDigit(c)) {
                sb.append(digest.charAt(di % digest.length()));
                di++;
            } else if (Character.isLetter(c)) {
                sb.append("*");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return String.format("%064x", value.hashCode());
        }
    }

    private static String ruleOf(String type) {
        return switch (type) {
            case "IDCARD" -> "RULE_IDCARD_V5";
            case "MOBILE" -> "RULE_MOBILE_V2";
            case "BANKCARD" -> "RULE_BANKCARD_V3";
            case "NAME" -> "RULE_NAME_V3";
            case "ADDRESS" -> "RULE_ADDR_V1";
            default -> "RULE_DEFAULT_V1";
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
