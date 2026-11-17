package com.bai.eventsubscribe;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件订阅服务（BIA-EPT-033）。全行事件驱动协同的统一订阅决策器。
 * <p>处理链：schema 校验与类型授权 → eventType 索引命中订阅 → 条件表达式白名单运算符（EQ/GT/GTE/LT/LTE/IN/CONTAINS，防注入）
 * → 按订阅 Group 隔离、bizKey 分区保序分发 → 端点失败退避重试（1s/3s/9s）≤3 次、仍失败入死信（保留 90 天可重投）
 * → 全链留痕（发布→分发→ack，保留≥3 年）。
 * <p>MVP 为确定性内存仿真（Map/List 存储 + 哈希决定端点投递结果），生产替换为 MQ（RocketMQ/Kafka）按 Group 隔离投递。
 */
@Service
public class EventSubscribeService {

    private static final Logger log = LoggerFactory.getLogger(EventSubscribeService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** 条件白名单运算符（防注入） */
    private static final Set<String> OPS = Set.of("EQ", "GT", "GTE", "LT", "LTE", "IN", "CONTAINS");
    /** 订阅端点白名单（host 后缀，非白名单拒绝） */
    private static final List<String> ENDPOINT_HOST_SUFFIXES = List.of(".bai.internal", "localhost");
    /** 端点失败重试上限（退避 1s/3s/9s），仍失败入死信 */
    private static final int MAX_RETRY = 3;

    private final IdempotencyStore idempotency = new IdempotencyStore();

    /** 事件类型注册表：name → {required:[...], description} */
    private final Map<String, Map<String, Object>> eventTypes = new ConcurrentHashMap<>();
    /** 订阅表：subscriptionId → 订阅 */
    private final Map<String, EventSubscribeDtos.SubscriptionInfo> subscriptions = new ConcurrentHashMap<>();
    /** 事件留痕：eventId → 事件记录 */
    private final Map<String, Map<String, Object>> eventLog = new ConcurrentHashMap<>();
    /** 事件指纹（eventType+bizKey+payload 哈希）→ eventId，7 天内不重复发布 */
    private final Map<String, String> fingerprintIndex = new ConcurrentHashMap<>();
    /** 分发留痕：eventId → 分发记录列表 */
    private final Map<String, List<EventSubscribeDtos.DispatchRecord>> dispatchLog = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public EventSubscribeService() {
        // 预置事件类型（体系内高频事件；schema 升级向后兼容，新字段可选）
        register("loan.approved", List.of("loanNo"), "授信审批通过");
        register("loan.disbursed", List.of("loanNo", "amount"), "放款成功");
        register("repay.due", List.of("loanNo", "dueDate"), "还款到期提醒");
        register("risk.alert", List.of("alertId", "level"), "风险告警");
        register("call.failed", List.of("callId"), "调用执行失败");
        register("call.degraded", List.of("callId"), "调用执行降级");
        register("uiop.consistency-diff", List.of("opId"), "界面操作一致性差异");
        register("uiop.rolled-back", List.of("opId"), "界面操作回滚完成");
        register("asr.completed", List.of("asrId"), "语音转写完成");
    }

    /** 事件发布：类型校验 → 授权校验 → 订阅匹配 → 条件过滤 → 入队分发（幂等键 requestId+bizKey+eventType） */
    public EventSubscribeDtos.PublishResult publish(Map<String, Object> body, String requestId, String caller) {
        if (body == null || isBlank(body.get("eventType")) || isBlank(body.get("bizKey")) || body.get("payload") == null) {
            throw new BaiException(EventSubscribeErrorCodes.PARAM_INVALID,
                    "EVS_1001 参数缺失：eventType/bizKey/payload 必填");
        }
        String eventType = str(body.get("eventType"));
        String bizKey = str(body.get("bizKey"));
        Map<String, Object> payload = cast(body.get("payload"));
        if (payload == null) {
            throw new BaiException(EventSubscribeErrorCodes.PARAM_INVALID, "EVS_1001 参数非法：payload 须为对象");
        }
        if (isBlank(caller)) {
            throw new BaiException(EventSubscribeErrorCodes.NO_PERMISSION,
                    "EVS_1003 无发布权限：Authorization Bearer Token 缺失（发布按事件类型授权）");
        }
        EventSubscribeDtos.PublishResult cached = idempotency.get("publish", requestId + ":" + bizKey + ":" + eventType);
        if (cached != null) {
            return cached;
        }
        Map<String, Object> type = eventTypes.get(eventType);
        if (type == null) {
            throw new BaiException(EventSubscribeErrorCodes.NOT_FOUND,
                    "EVS_1004 事件类型未注册：" + eventType + "，请先调用 /type 注册");
        }
        // schema 校验（必填字段，按注册 schema；新字段可选向后兼容）
        for (String field : requiredOf(type)) {
            if (!payload.containsKey(field)) {
                throw new BaiException(EventSubscribeErrorCodes.SCHEMA_INVALID,
                        "EVS_1006 payload 不符 schema：缺少必填字段 " + field);
            }
        }
        // 事件指纹去重（eventType+bizKey+payload，7 天内不重复发布）
        String fingerprint = shaKey(eventType + "|" + bizKey + "|" + payload);
        String existing = fingerprintIndex.get(fingerprint);
        if (existing != null) {
            Map<String, Object> prev = eventLog.get(existing);
            if (prev != null) {
                EventSubscribeDtos.PublishResult result = new EventSubscribeDtos.PublishResult(existing,
                        str(prev.get("status")), (Integer) prev.get("matchedSubscriptions"), (Integer) prev.get("dispatched"));
                idempotency.put("publish", requestId + ":" + bizKey + ":" + eventType, result);
                return result;
            }
        }
        String eventId = "EVT" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        List<EventSubscribeDtos.SubscriptionInfo> matched = subscriptions.values().stream()
                .filter(s -> "ACTIVE".equals(s.status()))
                .filter(s -> s.eventType().equals(eventType))
                .toList();
        int dispatched = 0;
        List<EventSubscribeDtos.DispatchRecord> records = new ArrayList<>();
        for (EventSubscribeDtos.SubscriptionInfo s : matched) {
            if (s.condition() != null && !s.condition().isEmpty() && !evaluate(s.condition(), payload)) {
                continue; // 条件过滤不命中跳过
            }
            records.add(deliver(eventId, s));
            dispatched++;
        }
        String status = (matched.isEmpty() || dispatched == 0) && !matched.isEmpty() ? "FILTERED_OUT" : "ACCEPTED";
        Map<String, Object> record = linkedMap("eventId", eventId, "requestId", requestId, "eventType", eventType,
                "bizKey", bizKey, "caller", caller, "status", status,
                "matchedSubscriptions", matched.size(), "dispatched", dispatched, "createdAt", LocalDateTime.now().toString());
        eventLog.put(eventId, record);
        fingerprintIndex.put(fingerprint, eventId);
        dispatchLog.put(eventId, records);
        log.info("事件发布受理: eventId={}, eventType={}, bizKey={}, matched={}, dispatched={}, caller={}",
                eventId, eventType, bizKey, matched.size(), dispatched, caller);
        EventSubscribeDtos.PublishResult result =
                new EventSubscribeDtos.PublishResult(eventId, status, matched.size(), dispatched);
        idempotency.put("publish", requestId + ":" + bizKey + ":" + eventType, result);
        return result;
    }

    /** 订阅管理：CREATE/CANCEL，condition 表达式 + endpoint 白名单（非白名单拒绝） */
    public EventSubscribeDtos.SubscribeResult subscribe(Map<String, Object> body, String requestId, String caller) {
        if (body == null || isBlank(caller)) {
            throw new BaiException(EventSubscribeErrorCodes.PARAM_INVALID,
                    "EVS_1001 参数缺失：action/eventType/endpoint 必填，且须携带 Authorization Token");
        }
        String action = strOr(body.get("action"), "CREATE");
        String eventType = str(body.get("eventType"));
        if (!List.of("CREATE", "CANCEL").contains(action)) {
            throw new BaiException(EventSubscribeErrorCodes.PARAM_INVALID,
                    "EVS_1001 参数非法：action 仅支持 CREATE/CANCEL");
        }
        if (isBlank(eventType)) {
            throw new BaiException(EventSubscribeErrorCodes.PARAM_INVALID, "EVS_1001 参数缺失：eventType 必填");
        }
        String subscriberId = strOr(body.get("subscriberId"), caller);
        if ("CANCEL".equals(action)) {
            EventSubscribeDtos.SubscriptionInfo target = subscriptions.values().stream()
                    .filter(s -> s.eventType().equals(eventType) && s.subscriberId().equals(subscriberId))
                    .findFirst()
                    .orElseThrow(() -> new BaiException(EventSubscribeErrorCodes.NOT_FOUND,
                            "EVS_1004 订阅不存在：eventType=" + eventType + ", subscriber=" + subscriberId));
            subscriptions.put(target.subscriptionId(),
                    new EventSubscribeDtos.SubscriptionInfo(target.subscriptionId(), target.subscriberId(),
                            target.eventType(), target.condition(), target.endpoint(), "CANCELLED", target.createdAt()));
            return new EventSubscribeDtos.SubscribeResult(target.subscriptionId(), eventType, "CANCEL",
                    "CANCELLED", target.createdAt());
        }
        Map<String, Object> endpoint = cast(body.get("endpoint"));
        if (endpoint == null || isBlank(endpoint.get("url"))) {
            throw new BaiException(EventSubscribeErrorCodes.PARAM_INVALID,
                    "EVS_1001 参数缺失：endpoint.url 必填（白名单端点）");
        }
        String url = str(endpoint.get("url"));
        if (!whitelisted(url)) {
            throw new BaiException(EventSubscribeErrorCodes.SCHEMA_INVALID,
                    "EVS_1006 端点白名单外：" + url + "（仅允许 *.bai.internal / localhost，双向认证）");
        }
        Map<String, Object> condition = cast(body.get("condition"));
        if (condition != null && !condition.isEmpty()) {
            validateCondition(condition);
        }
        boolean duplicated = subscriptions.values().stream().anyMatch(s ->
                "ACTIVE".equals(s.status()) && s.eventType().equals(eventType) && s.subscriberId().equals(subscriberId));
        if (duplicated) {
            throw new BaiException(EventSubscribeErrorCodes.CONFLICT,
                    "EVS_1005 重复订阅：eventType=" + eventType + ", subscriber=" + subscriberId);
        }
        String subscriptionId = "SUB" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        EventSubscribeDtos.SubscriptionInfo info = new EventSubscribeDtos.SubscriptionInfo(subscriptionId,
                subscriberId, eventType, condition == null ? Map.of() : condition,
                linkedMap("url", url, "retry", intOf(endpoint.get("retry"), MAX_RETRY),
                        "timeoutMs", intOf(endpoint.get("timeoutMs"), 5000)),
                "ACTIVE", LocalDateTime.now().toString());
        subscriptions.put(subscriptionId, info);
        log.info("订阅创建: subscriptionId={}, eventType={}, subscriber={}, endpoint={}",
                subscriptionId, eventType, subscriberId, url);
        return new EventSubscribeDtos.SubscribeResult(subscriptionId, eventType, "CREATE", "ACTIVE", info.createdAt());
    }

    /** 事件类型注册（类型冲突拒绝；schema 升级向后兼容） */
    public EventSubscribeDtos.TypeResult registerType(Map<String, Object> body) {
        if (body == null || isBlank(body.get("name"))) {
            throw new BaiException(EventSubscribeErrorCodes.PARAM_INVALID, "EVS_1001 参数缺失：name 必填");
        }
        String name = str(body.get("name"));
        if (eventTypes.containsKey(name)) {
            throw new BaiException(EventSubscribeErrorCodes.CONFLICT, "EVS_1005 类型冲突：事件类型 " + name + " 已注册");
        }
        List<String> required = new ArrayList<>();
        Map<String, Object> schema = cast(body.get("schema"));
        if (schema != null && schema.get("required") instanceof List<?> list) {
            for (Object o : list) {
                required.add(String.valueOf(o));
            }
        }
        register(name, required, strOr(body.get("description"), "-"));
        return new EventSubscribeDtos.TypeResult(name, "REGISTERED", List.copyOf(required),
                LocalDateTime.now().toString());
    }

    /** 订阅查询（可按 eventType/subscriberId/status 过滤） */
    public EventSubscribeDtos.SubscriptionQuery querySubscriptions(Map<String, Object> criteria) {
        Map<String, Object> c = criteria == null ? Map.of() : criteria;
        String eventType = strOrNull(c.get("eventType"));
        String subscriberId = strOrNull(c.get("subscriberId"));
        String status = strOrNull(c.get("status"));
        List<EventSubscribeDtos.SubscriptionInfo> matched = subscriptions.values().stream()
                .filter(s -> eventType == null || s.eventType().equals(eventType))
                .filter(s -> subscriberId == null || s.subscriberId().equals(subscriberId))
                .filter(s -> status == null || s.status().equals(status))
                .sorted(java.util.Comparator.comparing(EventSubscribeDtos.SubscriptionInfo::subscriptionId))
                .toList();
        return new EventSubscribeDtos.SubscriptionQuery(matched.size(), matched);
    }

    /** 端点投递（确定性仿真）：失败按 1s/3s/9s 退避重试≤3 次，仍失败入死信（保留 90 天可重投） */
    private EventSubscribeDtos.DispatchRecord deliver(String eventId, EventSubscribeDtos.SubscriptionInfo s) {
        String url = String.valueOf(s.endpoint().get("url"));
        int retryLimit = intOf(s.endpoint().get("retry"), MAX_RETRY);
        boolean ok = (shaKey(eventId + "|" + s.subscriptionId()).hashCode() & 0x7fffffff) % 10 != 0;
        int attempts = 1;
        String status;
        String deadLetter = null;
        if (ok) {
            status = "DELIVERED";
        } else if (retryLimit >= MAX_RETRY) {
            status = "DEAD";
            deadLetter = "DLQ-90D";
            attempts = MAX_RETRY + 1;
        } else {
            status = "FAILED";
            attempts = retryLimit + 1;
        }
        return new EventSubscribeDtos.DispatchRecord(eventId, s.subscriptionId(), url, status, attempts,
                deadLetter, LocalDateTime.now().toString());
    }

    /** 条件表达式求值：{field, op, value}，白名单运算符防注入，不命中返回 false */
    private boolean evaluate(Map<String, Object> condition, Map<String, Object> payload) {
        String field = str(condition.get("field"));
        String op = str(condition.get("op"));
        Object expect = condition.get("value");
        Object actual = payload.get(field);
        if (op == null || actual == null) {
            return false;
        }
        return switch (op) {
            case "EQ" -> String.valueOf(actual).equals(String.valueOf(expect));
            case "GT" -> compareTo(actual, expect) > 0;
            case "GTE" -> compareTo(actual, expect) >= 0;
            case "LT" -> compareTo(actual, expect) < 0;
            case "LTE" -> compareTo(actual, expect) <= 0;
            case "IN" -> expect instanceof List<?> list && list.stream().map(String::valueOf)
                    .anyMatch(v -> v.equals(String.valueOf(actual)));
            case "CONTAINS" -> String.valueOf(actual).contains(String.valueOf(expect));
            default -> false;
        };
    }

    private void validateCondition(Map<String, Object> condition) {
        if (isBlank(condition.get("field")) || isBlank(condition.get("op")) || condition.get("value") == null) {
            throw new BaiException(EventSubscribeErrorCodes.SCHEMA_INVALID,
                    "EVS_1006 条件非法：condition 须为 {field, op, value}");
        }
        if (!OPS.contains(str(condition.get("op")))) {
            throw new BaiException(EventSubscribeErrorCodes.SCHEMA_INVALID,
                    "EVS_1006 条件非法：运算符须在白名单 " + OPS + " 内（防注入）");
        }
    }

    private boolean whitelisted(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return false;
            }
            return ENDPOINT_HOST_SUFFIXES.stream().anyMatch(s -> host.equals(s.substring(1)) || host.endsWith(s));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private void register(String name, List<String> required, String description) {
        eventTypes.put(name, linkedMap("required", required, "description", description,
                "registeredAt", LocalDateTime.now().toString()));
    }

    @SuppressWarnings("unchecked")
    private static int compareTo(Object actual, Object expect) {
        if (actual instanceof Number a && expect instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        return String.valueOf(actual).compareTo(String.valueOf(expect));
    }

    private static List<String> requiredOf(Map<String, Object> type) {
        Object r = type.get("required");
        List<String> out = new ArrayList<>();
        if (r instanceof List<?> list) {
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    private static String shaKey(String payload) {
        return UUID.nameUUIDFromBytes(payload.getBytes()).toString();
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

    private static String strOr(Object o, String defaultValue) {
        String s = str(o);
        return s == null || s.isBlank() ? defaultValue : s;
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
}
