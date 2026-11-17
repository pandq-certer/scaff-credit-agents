package com.bai.entity;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
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
 * 实体归一服务（BIA-DTA-025）。黄金记录库为内存仿真（内置 7 条种子记录，标注：仿真，用于联调）；
 * 证件号/信用代码/手机号仅存 SHA-256 哈希与脱敏掩码（前 4 后 4+****），不落明文。
 * <p>处理链：规范化 → 指纹（主标识 SHA-256）→ 黄金库匹配 → 决策：
 * 证件哈希精确命中 → score≥0.90 MATCH（命中记录姓名与入参姓名不一致 → 证件冲突 ENT_1006）；
 * 同名不同证件 → REVIEW(0.75-0.90)；弱信号（仅手机号等）→ CANDIDATE(0.60-0.75)；
 * 无命中 → NEW 新建黄金记录（goldenId=GRD+序号，版本 1，响应 goldenId 为 null）。
 * <p>幂等：requestId+指纹，缓存 24h；限流 resolve 50 QPS / match 50 QPS / merge 10 QPS
 */
@Service
public class EntityResolutionService {

    private static final Logger log = LoggerFactory.getLogger(EntityResolutionService.class);

    private static final int MAX_RECORD_BYTES = 256 * 1024;
    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 50;
    private static final Set<String> SOURCES = Set.of("core", "channel", "credit-report", "external");
    private static final Set<String> ENTITY_TYPES = Set.of("PERSON", "COMPANY", "COLLATERAL");

    /** 黄金记录库（内存仿真）：goldenId → 记录（仅哈希与掩码，不落明文） */
    private final Map<String, GoldenRecord> store = new ConcurrentHashMap<>(seedRecords());
    /** goldenId 序列（种子 7 条后自增） */
    private final AtomicLong goldenSeq = new AtomicLong(store.size());
    private final AtomicLong resolveSeq = new AtomicLong();
    private final AtomicLong matchSeq = new AtomicLong();
    private final AtomicLong mergeSeq = new AtomicLong();
    private final ObjectMapper mapper;
    private final IdempotencyStore idempotency = new IdempotencyStore();

    public EntityResolutionService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 实体归一（幂等：requestId+指纹相同返回首次结果，缓存 24h） */
    public EntityResolutionDtos.ResolveResult resolve(EntityResolutionDtos.ResolveRequest req, String requestId) {
        long start = System.currentTimeMillis();
        Normalized n = validate(req == null ? null : req.record(), req == null ? null : req.source(),
                req == null ? null : req.entityType());
        String fingerprint = fingerprint(n);
        String key = requestId + ":" + fingerprint;
        EntityResolutionDtos.ResolveResult cached = idempotency.get("resolve", key);
        if (cached != null) {
            return cached;
        }
        // 证件哈希精确命中 → MATCH（命中记录姓名不一致 → 证件冲突 ENT_1006，同人不同证）
        GoldenRecord idHit = n.idCardHash() == null ? null : findByIdHash(n.idCardHash());
        GoldenRecord ccHit = n.creditCodeHash() == null ? null : findByCreditCodeHash(n.creditCodeHash());
        EntityResolutionDtos.ResolveResult result;
        if (idHit != null || ccHit != null) {
            GoldenRecord hit = idHit != null ? idHit : ccHit;
            String hitField = idHit != null ? "idCard" : "creditCode";
            if (n.name() != null && !n.name().equals(hit.name)) {
                throw new BaiException(EntityResolutionErrorCodes.CONFLICT,
                        "ENT_1006 证件号与已命中记录冲突（同人不同证）：证件 " + hitField + " 哈希已绑定主体 "
                                + hit.goldenId + "（姓名不一致），请人工核实");
            }
            double score = n.phoneHash() != null && n.phoneHash().equals(hit.phoneHash) ? 0.99 : 0.95;
            List<String> fields = new ArrayList<>(List.of(hitField));
            if (score > 0.95) {
                fields.add("phone");
            }
            EntityResolutionDtos.Match match = new EntityResolutionDtos.Match("MATCH", score,
                    List.of(new EntityResolutionDtos.Candidate(hit.goldenId, score, fields)));
            result = done(start, requestId, n.entityType(), fingerprint, hit.goldenId, match);
        } else {
            // 无证件哈希命中：按姓名/手机号/实体类型弱信号评分（姓名精确 +0.78、手机号 +0.60、同类型 +0.03，封顶 0.87）
            List<EntityResolutionDtos.Candidate> candidates = new ArrayList<>();
            for (GoldenRecord g : store.values()) {
                double score = 0;
                List<String> fields = new ArrayList<>();
                if (n.name() != null && n.name().equals(g.name)) {
                    score += 0.78;
                    fields.add("name");
                }
                if (n.phoneHash() != null && n.phoneHash().equals(g.phoneHash)) {
                    score += 0.60;
                    fields.add("phone");
                }
                if (n.entityType().equals(g.entityType)) {
                    score += 0.03;
                    fields.add("entityType");
                }
                if (score > 0) {
                    candidates.add(new EntityResolutionDtos.Candidate(g.goldenId, round2(Math.min(score, 0.87)), fields));
                }
            }
            candidates.sort(Comparator.comparingDouble(EntityResolutionDtos.Candidate::score).reversed());
            double top = candidates.isEmpty() ? 0 : candidates.get(0).score();
            String decision = top >= 0.90 ? "MATCH" : (top >= 0.75 ? "REVIEW" : (top >= 0.60 ? "CANDIDATE" : "NEW"));
            if ("NEW".equals(decision)) {
                // NEW：新建黄金记录（goldenId=GRD+序号，版本 1）；响应 goldenId 保持 null，新记录可经 GET /entities 查看
                createGolden(n, req.source());
            }
            EntityResolutionDtos.Match match = new EntityResolutionDtos.Match(decision,
                    "NEW".equals(decision) ? 0.0 : top, candidates);
            result = done(start, requestId, n.entityType(), fingerprint, null, match);
        }
        idempotency.put("resolve", key, result);
        return result;
    }

    /** 候选检索（topK≤50，缺省 10） */
    public EntityResolutionDtos.MatchResult match(EntityResolutionDtos.MatchRequest req, String requestId) {
        long start = System.currentTimeMillis();
        if (req == null || req.record() == null || req.record().isEmpty()) {
            throw new BaiException(EntityResolutionErrorCodes.PARAM_INVALID, "ENT_1001 参数缺失：record 必填");
        }
        int topK = req.topK() == null ? DEFAULT_TOP_K : req.topK();
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new BaiException(EntityResolutionErrorCodes.PARAM_INVALID,
                    "ENT_1001 参数非法：topK 须在 1-" + MAX_TOP_K + "（实际 " + topK + "）");
        }
        Normalized n = normalize(req.record(), null);
        // 候选评分：证件哈希命中 0.95/信用代码 0.95（强信号）+ 姓名/手机号/类型弱信号，封顶 0.87
        List<EntityResolutionDtos.Candidate> candidates = new ArrayList<>();
        for (GoldenRecord g : store.values()) {
            double score = 0;
            List<String> fields = new ArrayList<>();
            if (n.idCardHash() != null && n.idCardHash().equals(g.idCardHash)) {
                score = 0.95;
                fields.add("idCard");
            }
            if (n.creditCodeHash() != null && n.creditCodeHash().equals(g.creditCodeHash)) {
                score = Math.max(score, 0.95);
                fields.add("creditCode");
            }
            if (n.name() != null && n.name().equals(g.name)) {
                score += 0.78;
                fields.add("name");
            }
            if (n.phoneHash() != null && n.phoneHash().equals(g.phoneHash)) {
                score += 0.60;
                fields.add("phone");
            }
            if (n.entityType() != null && n.entityType().equals(g.entityType)) {
                score += 0.03;
                fields.add("entityType");
            }
            if (score > 0) {
                candidates.add(new EntityResolutionDtos.Candidate(g.goldenId, round2(Math.min(score, n.idCardHash() != null
                        || n.creditCodeHash() != null ? 0.99 : 0.87)), fields));
            }
        }
        candidates.sort(Comparator.comparingDouble(EntityResolutionDtos.Candidate::score).reversed());
        List<EntityResolutionDtos.Candidate> top = candidates.stream().limit(topK).toList();
        String matchId = "ERM" + dayStamp() + String.format("%07d", matchSeq.incrementAndGet());
        log.info("候选检索: requestId={}, matchId={}, entityType={}, 候选数={}", requestId, matchId, n.entityType(), top.size());
        return new EntityResolutionDtos.MatchResult(matchId, n.entityType(), top, System.currentTimeMillis() - start);
    }

    /** 黄金记录合并（版本递增；goldenId 不存在 → ENT_1004） */
    public EntityResolutionDtos.MergeResult merge(EntityResolutionDtos.MergeRequest req, String requestId) {
        long start = System.currentTimeMillis();
        if (req == null || req.goldenId() == null || req.goldenId().isBlank()) {
            throw new BaiException(EntityResolutionErrorCodes.PARAM_INVALID, "ENT_1001 参数缺失：goldenId 必填");
        }
        if (req.record() == null || req.record().isEmpty()) {
            throw new BaiException(EntityResolutionErrorCodes.PARAM_INVALID, "ENT_1001 参数缺失：record 必填");
        }
        if (req.reason() == null || req.reason().isBlank() || req.operator() == null || req.operator().isBlank()) {
            throw new BaiException(EntityResolutionErrorCodes.PARAM_INVALID, "ENT_1001 参数缺失：reason/operator 必填");
        }
        Normalized n = normalize(req.record(), null);
        int version;
        synchronized (store) {
            GoldenRecord g = store.get(req.goldenId());
            if (g == null) {
                throw new BaiException(EntityResolutionErrorCodes.GOLDEN_NOT_FOUND, "ENT_1004 goldenId 不存在：" + req.goldenId());
            }
            if (n.name() != null) {
                g.name = n.name();
            }
            if (n.idCardHash() != null && !n.idCardHash().equals(g.idCardHash)) {
                g.idCardHash = n.idCardHash();
                g.idCardMasked = n.idCardMasked();
            }
            if (n.creditCodeHash() != null && !n.creditCodeHash().equals(g.creditCodeHash)) {
                g.creditCodeHash = n.creditCodeHash();
                g.creditCodeMasked = n.creditCodeMasked();
            }
            if (n.phoneHash() != null) {
                g.phoneHash = n.phoneHash();
            }
            g.version++;
            version = g.version;
        }
        String mergeId = "EMG" + dayStamp() + String.format("%07d", mergeSeq.incrementAndGet());
        log.info("黄金记录合并: requestId={}, mergeId={}, goldenId={}, version={}, operator={}, reason={}",
                requestId, mergeId, req.goldenId(), version, req.operator(), req.reason());
        return new EntityResolutionDtos.MergeResult(mergeId, req.goldenId(), version, "MERGED",
                System.currentTimeMillis() - start);
    }

    /** 黄金记录列表（GET /entities，证件号脱敏前 4 后 4+****） */
    public EntityResolutionDtos.EntitiesResult entities() {
        List<EntityResolutionDtos.GoldenRecordView> views = store.values().stream()
                .sorted(Comparator.comparing(g -> g.goldenId))
                .map(g -> new EntityResolutionDtos.GoldenRecordView(g.goldenId, g.name, g.entityType,
                        g.idCardMasked, g.creditCodeMasked, g.version, g.createdAt))
                .toList();
        return new EntityResolutionDtos.EntitiesResult(views.size(), views);
    }

    // ---------- 内部逻辑 ----------

    private EntityResolutionDtos.ResolveResult done(long start, String requestId, String entityType,
                                                    String fingerprint, String goldenId,
                                                    EntityResolutionDtos.Match match) {
        String resolveId = "ERS" + dayStamp() + String.format("%07d", resolveSeq.incrementAndGet());
        long elapsed = System.currentTimeMillis() - start;
        log.info("实体归一: requestId={}, resolveId={}, entityType={}, decision={}, score={}, goldenId={}(仿真库，用于联调)",
                requestId, resolveId, entityType, match.decision(), match.score(), goldenId);
        return new EntityResolutionDtos.ResolveResult(resolveId, entityType, fingerprint, goldenId, match, elapsed);
    }

    /** 入参校验：record/序列化大小/source 白名单/entityType；返回规范化结果（含自动实体类型识别） */
    private Normalized validate(Map<String, Object> record, String source, String entityType) {
        if (record == null || record.isEmpty()) {
            throw new BaiException(EntityResolutionErrorCodes.PARAM_INVALID, "ENT_1001 参数缺失：record 必填");
        }
        if (source == null || !SOURCES.contains(source)) {
            throw new BaiException(EntityResolutionErrorCodes.PARAM_INVALID,
                    "ENT_1001 参数非法：source 须为 core/channel/credit-report/external");
        }
        try {
            if (mapper.writeValueAsBytes(record).length > MAX_RECORD_BYTES) {
                throw new BaiException(EntityResolutionErrorCodes.PARAM_INVALID,
                        "ENT_1001 参数非法：record 序列化超 256KB");
            }
        } catch (BaiException e) {
            throw e;
        } catch (Exception e) {
            throw new BaiException(EntityResolutionErrorCodes.PARAM_INVALID, "ENT_1001 参数非法：record 不可序列化为 JSON");
        }
        return normalize(record, entityType);
    }

    /** 规范化 + 哈希 + 掩码（不落明文）；entityType 缺省自动识别：有 idCard→PERSON，有 creditCode→COMPANY，否则 COLLATERAL */
    private Normalized normalize(Map<String, Object> record, String entityType) {
        String name = str(record.get("name"));
        String idCard = upper(str(record.get("idCard")));
        String creditCode = upper(str(record.get("creditCode")));
        String phone = str(record.get("phone"));
        String type = entityType == null || entityType.isBlank() || "AUTO".equalsIgnoreCase(entityType)
                ? (idCard != null ? "PERSON" : (creditCode != null ? "COMPANY" : "COLLATERAL"))
                : entityType.toUpperCase();
        if (!ENTITY_TYPES.contains(type)) {
            throw new BaiException(EntityResolutionErrorCodes.PARAM_INVALID,
                    "ENT_1001 参数非法：entityType 须为 PERSON/COMPANY/COLLATERAL");
        }
        return new Normalized(name, sha256("IDCARD|" + idCard), mask(idCard), sha256("USCC|" + creditCode),
                mask(creditCode), sha256("PHONE|" + phone), type);
    }

    /** 指纹：主标识 SHA-256（证件号 > 信用代码 > 姓名+手机号），不落明文 */
    private String fingerprint(Normalized n) {
        if (n.idCardHash() != null) {
            return n.idCardHash();
        }
        if (n.creditCodeHash() != null) {
            return n.creditCodeHash();
        }
        return sha256("NAME|" + n.name() + "|PHONE|" + n.phoneHash());
    }

    private String createGolden(Normalized n, String source) {
        GoldenRecord g = new GoldenRecord("GRD" + String.format("%05d", goldenSeq.incrementAndGet()), n.name(),
                n.entityType(), n.idCardMasked(), n.idCardHash(), n.creditCodeMasked(), n.creditCodeHash(),
                n.phoneHash(), 1, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        store.put(g.goldenId, g);
        log.info("新建黄金记录: goldenId={}, entityType={}, source={}(仿真库，用于联调)", g.goldenId, g.entityType, source);
        return g.goldenId;
    }

    private GoldenRecord findByIdHash(String hash) {
        return store.values().stream().filter(g -> hash.equals(g.idCardHash)).findFirst().orElse(null);
    }

    private GoldenRecord findByCreditCodeHash(String hash) {
        return store.values().stream().filter(g -> hash.equals(g.creditCodeHash)).findFirst().orElse(null);
    }

    /** 脱敏：前 4 后 4+**** */
    private static String mask(String s) {
        return s == null ? null : (s.length() > 8 ? s.substring(0, 4) + "****" + s.substring(s.length() - 4) : "****");
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase();
    }

    private String sha256(String s) {
        if (s == null || s.endsWith("|null")) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(s.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String dayStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    /** 规范化结果（哈希+掩码，不落明文） */
    private record Normalized(String name, String idCardHash, String idCardMasked, String creditCodeHash,
                              String creditCodeMasked, String phoneHash, String entityType) {}

    /** 黄金记录（内存仿真；仅哈希与掩码，不落明文） */
    private static final class GoldenRecord {
        final String goldenId;
        String name;
        final String entityType;
        String idCardMasked;
        String idCardHash;
        String creditCodeMasked;
        String creditCodeHash;
        String phoneHash;
        int version;
        final String createdAt;

        private GoldenRecord(String goldenId, String name, String entityType, String idCardMasked, String idCardHash,
                             String creditCodeMasked, String creditCodeHash, String phoneHash, int version, String createdAt) {
            this.goldenId = goldenId;
            this.name = name;
            this.entityType = entityType;
            this.idCardMasked = idCardMasked;
            this.idCardHash = idCardHash;
            this.creditCodeMasked = creditCodeMasked;
            this.creditCodeHash = creditCodeHash;
            this.phoneHash = phoneHash;
            this.version = version;
            this.createdAt = createdAt;
        }
    }

    /** 种子黄金记录（4 个人 + 3 户企业，仿真数据，用于联调） */
    private static Map<String, GoldenRecord> seedRecords() {
        Map<String, GoldenRecord> m = new LinkedHashMap<>();
        put(m, "GRD00001", "张伟", "PERSON", "110101199001011234", null, "13801010101");
        put(m, "GRD00002", "李娜", "PERSON", "310101198805054321", null, "13901020202");
        put(m, "GRD00003", "王强", "PERSON", "440101199203056789", null, "13601030303");
        put(m, "GRD00004", "赵敏", "PERSON", "510101199510102345", null, "13701040404");
        put(m, "GRD00005", "北京华信科技有限公司", "COMPANY", null, "91110108MA01ABCX5A", null);
        put(m, "GRD00006", "上海润泽实业有限公司", "COMPANY", null, "91310115MA1KDEFY2B", null);
        put(m, "GRD00007", "深圳市恒益贸易发展有限公司", "COMPANY", null, "91440300MA5KGHIJ3C", null);
        return m;
    }

    private static void put(Map<String, GoldenRecord> m, String goldenId, String name, String entityType,
                            String idCard, String creditCode, String phone) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        m.put(goldenId, new GoldenRecord(goldenId, name, entityType, mask(idCard),
                idCard == null ? null : sha256s("IDCARD|" + idCard), mask(creditCode),
                creditCode == null ? null : sha256s("USCC|" + creditCode),
                phone == null ? null : sha256s("PHONE|" + phone), 1, now));
    }

    private static String sha256s(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(s.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
