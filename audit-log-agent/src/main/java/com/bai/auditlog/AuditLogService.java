package com.bai.auditlog;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计留痕服务（BIA-EPT-032）。全行审计与合规追责的统一日志底座。
 * <p>处理链：事件必填校验（who/what/when）→ 幂等去重（requestId+eventId 唯一键不重复落库）
 * → 哈希链生成 cur_hash=SHA-256(prev_hash+事件序列化+盐) → append-only 落库（库级禁 UPDATE/DELETE）
 * → 多维查询/导出（导出操作本身留痕）→ 重算链校验断链定位。
 * <p>MVP 为确定性内存仿真（List 存储模拟 append-only 存储），生产替换为 MQ 削峰+分表 append-only 库。
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private static final int MAX_BATCH = 1000;               // 批量 ≤1000 条/批
    private static final int MAX_QUERY_DAYS = 90;            // 单次查询 ≤90 天
    private static final int MAX_EXPORT_ROWS = 1_000_000;    // 导出 ≤100 万条/次
    private static final String SALT = "scaff-credit|BIA-EPT-032|"; // 哈希链盐（生产由 KMS 托管）
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final IdempotencyStore idempotency = new IdempotencyStore(300_000L); // 5 分钟内同请求返回首次结果

    /** 幂等索引：requestId:eventId → logId（同事件唯一键不重复落库） */
    private final Map<String, String> eventIndex = new ConcurrentHashMap<>();
    /** append-only 内存存储（生产为按时间分表 append-only 库，禁 UPDATE/DELETE） */
    private final List<AuditLogDtos.LogEntry> chain = new ArrayList<>();
    /** 哈希链头（genesis 初始化） */
    private final String genesisHead;
    private String chainHead;
    private final AtomicLong seq = new AtomicLong();

    public AuditLogService() {
        this.genesisHead = sha256(SALT + "GENESIS");
        this.chainHead = genesisHead;
    }

    /** 审计事件写入（单条/批量 ≤1000 条/批，幂等键 requestId+eventId，重复请求返回相同结果） */
    public AuditLogDtos.WriteResult write(List<?> events, String requestId, String caller) {
        if (events == null || events.isEmpty()) {
            throw new BaiException(AuditLogErrorCodes.PARAM_INVALID, "ALG_1001 参数缺失：events 必填（审计事件列表）");
        }
        if (events.size() > MAX_BATCH) {
            throw new BaiException(AuditLogErrorCodes.BATCH_LIMIT,
                    "ALG_1005 批量超限：" + events.size() + " 条超限（≤1000 条/批），请分批提交");
        }
        AuditLogDtos.WriteResult cached = idempotency.get("write", requestId);
        if (cached != null) {
            return cached;
        }
        List<String> logIds = new ArrayList<>();
        int accepted = 0;
        int rejected = 0;
        for (Object item : events) {
            Map<String, Object> event = cast(item);
            if (event == null || !validate(event)) {
                rejected++;
                continue;
            }
            String eventId = str(event.get("eventId"));
            if (eventId == null) {
                eventId = "EVT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            }
            String reused = eventIndex.get(requestId + ":" + eventId);
            if (reused != null) {
                // 同事件唯一键不重复落库，返回原 logId
                logIds.add(reused);
                accepted++;
                continue;
            }
            String logId = nextLogId();
            AuditLogDtos.LogEntry entry = append(event, requestId, logId);
            eventIndex.put(requestId + ":" + eventId, logId);
            logIds.add(logId);
            accepted++;
            log.info("审计事件受理: logId={}, action={}, userId={}, caller={}, sensitive={}",
                    logId, valueAt(entry.what(), "action"), valueAt(entry.who(), "userId"), caller, entry.sensitive());
        }
        AuditLogDtos.WriteResult result = new AuditLogDtos.WriteResult(accepted, rejected, List.copyOf(logIds));
        idempotency.put("write", requestId, result);
        return result;
    }

    /** 审计日志多维查询（操作人/对象/时间/类型，单次 ≤90 天，分页返回） */
    public AuditLogDtos.QueryResult query(Map<String, Object> criteria) {
        List<AuditLogDtos.LogEntry> matched = filter(criteria);
        int page = intOf(criteria.get("page"), 1);
        int pageSize = intOf(criteria.get("pageSize"), 20);
        int fromIdx = Math.min((page - 1) * pageSize, matched.size());
        int toIdx = Math.min(fromIdx + pageSize, matched.size());
        return new AuditLogDtos.QueryResult(matched.size(), page, pageSize, matched.subList(fromIdx, toIdx));
    }

    /** 审计日志导出（CSV，≤100 万条/次，导出操作本身留痕——审计的审计） */
    public AuditLogDtos.ExportResult export(Map<String, Object> criteria, String requestId, String caller) {
        List<AuditLogDtos.LogEntry> matched = filter(criteria);
        if (matched.size() > MAX_EXPORT_ROWS) {
            throw new BaiException(AuditLogErrorCodes.BATCH_LIMIT,
                    "ALG_1005 导出超限：" + matched.size() + " 条（≤100 万条/次），请缩小范围分段导出");
        }
        String exportId = "EXP" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        StringBuilder csv = new StringBuilder(
                "logId,requestId,when,userId,role,orgId,action,objectType,objectId,channel,ip,traceId,code,message,sensitive,masked,hash\n");
        for (AuditLogDtos.LogEntry e : matched) {
            csv.append(csv(e.logId())).append(',').append(csv(e.requestId())).append(',').append(csv(e.when())).append(',')
                    .append(csv(valueAt(e.who(), "userId"))).append(',').append(csv(valueAt(e.who(), "role"))).append(',')
                    .append(csv(valueAt(e.who(), "orgId"))).append(',').append(csv(valueAt(e.what(), "action"))).append(',')
                    .append(csv(valueAt(e.what(), "objectType"))).append(',').append(csv(valueAt(e.what(), "objectId"))).append(',')
                    .append(csv(valueAt(e.where(), "channel"))).append(',').append(csv(valueAt(e.where(), "ip"))).append(',')
                    .append(csv(valueAt(e.where(), "traceId"))).append(',').append(csv(valueAt(e.result(), "code"))).append(',')
                    .append(csv(valueAt(e.result(), "message"))).append(',').append(e.sensitive()).append(',')
                    .append(e.masked()).append(',').append(csv(e.hash())).append('\n');
        }
        // 导出操作本身留痕（审计的审计）
        Map<String, Object> exportEvent = new LinkedHashMap<>();
        exportEvent.put("who", linkedMap("userId", caller, "role", "SYSTEM", "orgId", "-"));
        exportEvent.put("what", linkedMap("action", "EXPORT", "objectType", "AUDIT_LOG", "objectId", exportId));
        exportEvent.put("when", LocalDateTime.now().toString());
        exportEvent.put("where", linkedMap("channel", "INTERNAL", "ip", "-", "traceId", requestId == null ? "-" : requestId));
        exportEvent.put("result", linkedMap("code", "0", "message", "导出 " + matched.size() + " 条"));
        exportEvent.put("sensitive", false);
        exportEvent.put("masked", true);
        append(exportEvent, requestId, nextLogId());
        log.info("审计日志导出: exportId={}, count={}, caller={}", exportId, matched.size(), caller);
        return new AuditLogDtos.ExportResult(exportId, "CSV", matched.size(), csv.toString(), LocalDateTime.now().toString());
    }

    /** 防篡改校验：重算链哈希比对，断链定位到具体事件（brokenAt） */
    public synchronized AuditLogDtos.TamperCheckResult tamperCheck() {
        String prev = genesisHead;
        for (int i = 0; i < chain.size(); i++) {
            AuditLogDtos.LogEntry e = chain.get(i);
            String expect = sha256(SALT + prev + "|" + canonical(e));
            if (!expect.equals(e.hash())) {
                log.warn("哈希链断链: logId={}, index={}", e.logId(), i);
                return new AuditLogDtos.TamperCheckResult(false, chainHead, i, e.logId());
            }
            prev = e.hash();
        }
        return new AuditLogDtos.TamperCheckResult(true, chainHead, chain.size(), null);
    }

    /** 过滤与分页参数校验（查询/导出共用，单次 ≤90 天） */
    private List<AuditLogDtos.LogEntry> filter(Map<String, Object> criteria) {
        Map<String, Object> c = criteria == null ? Map.of() : criteria;
        String userId = strOrNull(c.get("userId"));
        String action = strOrNull(c.get("action"));
        String objectType = strOrNull(c.get("objectType"));
        String objectId = strOrNull(c.get("objectId"));
        String from = strOrNull(c.get("from"));
        String to = strOrNull(c.get("to"));
        int page = intOf(c.get("page"), 1);
        int pageSize = intOf(c.get("pageSize"), 20);
        if (page < 1 || pageSize < 1 || pageSize > 200) {
            throw new BaiException(AuditLogErrorCodes.PARAM_INVALID, "ALG_1001 参数非法：page≥1 且 1≤pageSize≤200");
        }
        String fromDay = from == null ? null : from.length() < 10 ? from : from.substring(0, 10);
        String toDay = to == null ? null : to.length() < 10 ? to : to.substring(0, 10);
        if (fromDay != null && toDay != null) {
            try {
                long days = ChronoUnit.DAYS.between(LocalDate.parse(fromDay), LocalDate.parse(toDay));
                if (days > MAX_QUERY_DAYS) {
                    throw new BaiException(AuditLogErrorCodes.PARAM_INVALID,
                            "ALG_1001 参数非法：单次查询跨度 ≤90 天，请缩小时间范围");
                }
            } catch (java.time.format.DateTimeParseException ex) {
                throw new BaiException(AuditLogErrorCodes.PARAM_INVALID,
                        "ALG_1001 参数非法：from/to 须为 ISO 日期（yyyy-MM-dd 或含时间部分）");
            }
        }
        synchronized (this) {
            return chain.stream()
                    .filter(e -> matches(e, userId, action, objectType, objectId, fromDay, toDay))
                    .toList();
        }
    }

    private boolean matches(AuditLogDtos.LogEntry e, String userId, String action,
                            String objectType, String objectId, String fromDay, String toDay) {
        if (userId != null && !valueAt(e.who(), "userId").equals(userId)) {
            return false;
        }
        if (action != null && !valueAt(e.what(), "action").equals(action)) {
            return false;
        }
        if (objectType != null && !valueAt(e.what(), "objectType").equals(objectType)) {
            return false;
        }
        if (objectId != null && !valueAt(e.what(), "objectId").equals(objectId)) {
            return false;
        }
        if (fromDay != null || toDay != null) {
            String day = e.when() == null ? "" : e.when().substring(0, Math.min(10, e.when().length()));
            if (fromDay != null && day.compareTo(fromDay) < 0) {
                return false;
            }
            if (toDay != null && day.compareTo(toDay) > 0) {
                return false;
            }
        }
        return true;
    }

    /** 事件必填校验：who(userId)/what(action)/when 缺一即拒（计入 rejected） */
    private boolean validate(Map<String, Object> e) {
        Map<String, Object> who = cast(e.get("who"));
        Map<String, Object> what = cast(e.get("what"));
        if (who == null || isBlank(who.get("userId"))) {
            return false;
        }
        if (what == null || isBlank(what.get("action"))) {
            return false;
        }
        return !isBlank(e.get("when"));
    }

    /** 哈希链追加：cur_hash=SHA-256(prev_hash+事件序列化+盐)，append-only（synchronized 串行化保证链序） */
    private synchronized AuditLogDtos.LogEntry append(Map<String, Object> event, String requestId, String logId) {
        String prev = chainHead;
        Map<String, Object> who = orEmpty(cast(event.get("who")));
        Map<String, Object> what = orEmpty(cast(event.get("what")));
        Map<String, Object> where = orEmpty(cast(event.get("where")));
        Map<String, Object> result = orEmpty(cast(event.get("result")));
        String when = str(event.get("when"));
        boolean sensitive = Boolean.TRUE.equals(event.get("sensitive"));
        boolean masked = Boolean.TRUE.equals(event.get("masked"));
        String createdAt = LocalDateTime.now().toString();
        String hash = sha256(SALT + prev + "|" + who + "|" + what + "|" + when + "|" + where + "|" + result
                + "|" + sensitive + "|" + masked);
        AuditLogDtos.LogEntry entry = new AuditLogDtos.LogEntry(logId, requestId, who, what, when, where, result,
                sensitive, masked, prev, hash, createdAt);
        chain.add(entry);
        chainHead = hash;
        return entry;
    }

    /** 校验用规范化串化（与 append 保持同一公式） */
    private String canonical(AuditLogDtos.LogEntry e) {
        return e.who() + "|" + e.what() + "|" + e.when() + "|" + e.where() + "|" + e.result()
                + "|" + e.sensitive() + "|" + e.masked();
    }

    private String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new BaiException(AuditLogErrorCodes.INTERNAL_ERROR, "ALG_2000 哈希计算不可用：" + ex.getMessage());
        }
    }

    private synchronized String nextLogId() {
        return "LOG" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
    }

    private static Map<String, Object> linkedMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static Map<String, Object> orEmpty(Map<String, Object> m) {
        return m == null ? Map.of() : m;
    }

    private static String valueAt(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static boolean isBlank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String strOrNull(Object o) {
        String s = str(o);
        return s == null || s.isBlank() ? null : s;
    }

    private static int intOf(Object o, int defaultValue) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? defaultValue : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    /** CSV 字段转义（含逗号/引号/换行时加引号） */
    private static String csv(String v) {
        String s = v == null ? "" : v;
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
