package com.bai.callexec;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 * 调用执行服务（BIA-INT-037）。智能体业务动作的统一工具执行器。
 * <p>处理链：入参校验 → 白名单校验（targetService 三级，非白名单拒绝）→ 参数组装（必填/类型/格式契约校验）
 * → 鉴权透传 → 发起调用（P0 资金类 8s/3 次、P1 业务类 5s/2 次、P2 辅助类 3s/1 次可降级）
 * → 超时/5xx 指数退避重试（1s/2s/4s 上限 3 次，4xx 不重试）→ 按响应 schema 标准化解析 → 写调用历史。
 * <p>MVP 为确定性仿真（内置定义注册表 + 目标哈希驱动结果），真实 HTTP/RPC 调用与 HMAC 签名由统一服务中台承担；
 * Service 内按 targetService（账务/放款/短信/签约/通知）做确定性分支产出标准化回执。
 */
@Service
public class CallExecService {

    private static final Logger log = LoggerFactory.getLogger(CallExecService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_PARAM_FIELDS = 100;          // 参数个数上限，超长 CEX_1006
    private static final int MAX_PARAM_VALUE_LEN = 256;       // 单参数值上限，超长 CEX_1006
    /** 目标服务白名单（非白名单 CEX_1003） */
    private static final Set<String> WHITELIST = Set.of(
            "disbursement-api", "accounting-api", "esign-api", "sms-api", "notify-api", "credit-system-api");

    /** 内置接口定义（P0 资金类不降级；P2 辅助类可降级） */
    private record Def(String name, String targetService, String method, String path, String priority,
                       int timeoutMs, int maxRetries, List<String> requiredParams, String status) {}
    private static final Map<String, Def> BUILT_INS = buildDefs();

    private final IdempotencyStore idempotency = new IdempotencyStore();
    /** 定义注册表：definitionId → 定义（含运行时新增） */
    private final Map<String, Def> definitions = new ConcurrentHashMap<>(BUILT_INS);
    /** 调用历史：callId → 记录 */
    private final Map<String, CallExecDtos.HistoryRecord> history = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    private static Map<String, Def> buildDefs() {
        Map<String, Def> m = new LinkedHashMap<>();
        m.put("DEF_DISBURSE_001", new Def("放款指令", "disbursement-api", "POST", "/disburse",
                "P0", 8000, 3, List.of("loanNo", "amount", "accountNo"), "PUBLISHED"));
        m.put("DEF_ACCT_DEDUCT_001", new Def("还款扣款", "accounting-api", "POST", "/deduct",
                "P0", 8000, 3, List.of("loanNo", "amount"), "PUBLISHED"));
        m.put("DEF_ESIGN_001", new Def("电子签约", "esign-api", "POST", "/sign/flows",
                "P1", 5000, 2, List.of("contractNo", "signerId"), "PUBLISHED"));
        m.put("DEF_SMS_001", new Def("短信下发", "sms-api", "POST", "/messages",
                "P1", 5000, 2, List.of("phone", "templateId"), "PUBLISHED"));
        m.put("DEF_NOTIFY_001", new Def("站内通知", "notify-api", "POST", "/notify",
                "P2", 3000, 1, List.of("templateCode", "receiver"), "PUBLISHED"));
        return Map.copyOf(m);
    }

    /** 执行调用（幂等键 requestId+definitionId+params 指纹，24h 命中返回原结果，零重复执行） */
    public CallExecDtos.CallResult execute(Map<String, Object> body, String requestId, String traceId,
                                           String authorization) {
        if (body == null || isBlank(body.get("definitionId")) || body.get("params") == null) {
            throw new BaiException(CallExecErrorCodes.PARAM_INVALID,
                    "CEX_1001 参数缺失：definitionId 与 params 必填");
        }
        if (isBlank(traceId)) {
            throw new BaiException(CallExecErrorCodes.PARAM_INVALID, "CEX_1001 参数缺失：X-Trace-Id 必填");
        }
        if (isBlank(authorization)) {
            throw new BaiException(CallExecErrorCodes.TOKEN_INVALID,
                    "CEX_1002 Token 缺失：Authorization Bearer Token 必填（统一权限平台签发）");
        }
        Map<String, Object> params = cast(body.get("params"));
        if (params == null) {
            throw new BaiException(CallExecErrorCodes.PARAM_INVALID, "CEX_1001 参数非法：params 须为对象");
        }
        String definitionId = str(body.get("definitionId"));
        String fingerprint = Integer.toHexString(shaKey(definitionId + "|" + params));
        CallExecDtos.CallResult cached = idempotency.get("execute", requestId + ":" + definitionId + ":" + fingerprint);
        if (cached != null) {
            return cached;
        }
        Def def = definitions.get(definitionId);
        if (def == null) {
            throw new BaiException(CallExecErrorCodes.NOT_FOUND,
                    "CEX_1004 接口定义不存在：" + definitionId);
        }
        if (!"PUBLISHED".equals(def.status())) {
            throw new BaiException(CallExecErrorCodes.NOT_FOUND,
                    "CEX_1004 接口定义未发布：" + definitionId + "（当前 " + def.status() + "）");
        }
        if (!WHITELIST.contains(def.targetService())) {
            throw new BaiException(CallExecErrorCodes.NO_PERMISSION,
                    "CEX_1003 目标服务不在白名单：" + def.targetService());
        }
        // 参数契约校验：必填/类型/格式/超长
        for (String required : def.requiredParams()) {
            if (isBlank(params.get(required))) {
                throw new BaiException(CallExecErrorCodes.CONTRACT_INVALID,
                        "CEX_1007 参数契约不符：缺少必填参数 " + required + "（定义 " + definitionId + "）");
            }
        }
        if (params.size() > MAX_PARAM_FIELDS) {
            throw new BaiException(CallExecErrorCodes.TOO_LONG,
                    "CEX_1006 参数超长：" + params.size() + " 个字段（≤" + MAX_PARAM_FIELDS + "），请缩减输入");
        }
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (String.valueOf(e.getValue()).length() > MAX_PARAM_VALUE_LEN) {
                throw new BaiException(CallExecErrorCodes.TOO_LONG,
                        "CEX_1006 参数超长：" + e.getKey() + " 值超过 " + MAX_PARAM_VALUE_LEN + " 字符，请缩减输入");
            }
        }
        long begin = System.currentTimeMillis();
        // 发起调用（确定性仿真）：哈希驱动 SUCCESS/RETRIED/TIMEOUT/FAILED/DEGRADED
        int h = Math.abs(shaKey(requestId + "|" + definitionId + "|" + fingerprint));
        String status;
        int retryCount = 0;
        String errorCode = null;
        String firstError = null;
        if (h % 97 == 0) {
            status = "TIMEOUT";
            retryCount = def.maxRetries();
            errorCode = CallExecErrorCodes.TARGET_DEGRADED;
        } else if (h % 53 == 0) {
            status = "FAILED";
            errorCode = CallExecErrorCodes.CONTRACT_INVALID;    // 目标 4xx 业务拒绝，不重试
        } else if (h % 25 == 7) {
            status = "RETRIED";
            retryCount = 1;
            firstError = "首次调用超时，指数退避重试成功";
        } else if ("P2".equals(def.priority()) && h % 23 == 0) {
            status = "DEGRADED";                                // P2 辅助类降级，P0/P1 不降级
            errorCode = CallExecErrorCodes.TARGET_DEGRADED;
        } else {
            status = "SUCCESS";
        }
        Map<String, Object> result = buildResult(def, params, status, errorCode, firstError, requestId);
        long elapsed = System.currentTimeMillis() - begin;
        String callId = "CL" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        CallExecDtos.CallResult call = new CallExecDtos.CallResult(callId, definitionId, status,
                result, elapsed, retryCount, def.targetService());
        history.put(callId, new CallExecDtos.HistoryRecord(callId, definitionId, def.targetService(),
                status, elapsed, retryCount, LocalDateTime.now().toString()));
        idempotency.put("execute", requestId + ":" + definitionId + ":" + fingerprint, call);
        log.info("调用执行: requestId={}, traceId={}, callId={}, definitionId={}, targetService={}, status={}, retryCount={}",
                requestId, traceId, callId, definitionId, def.targetService(), status, retryCount);
        return call;
    }

    /** 按 targetService 确定性分支产出标准化回执（账务/放款/签约/短信/通知） */
    private Map<String, Object> buildResult(Def def, Map<String, Object> params, String status,
                                            String errorCode, String firstError, String requestId) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (!"SUCCESS".equals(status)) {
            r.put("code", errorCode);
        }
        if (firstError != null) {
            r.put("firstError", firstError);
        }
        String now = LocalDateTime.now().toString();
        switch (def.targetService()) {
            case "disbursement-api" -> {
                r.put("txnNo", "TXN" + TS.format(LocalDateTime.now()) + String.format("%05d", seq.incrementAndGet()));
                r.put("loanNo", str(params.get("loanNo")));
                r.put("amount", params.get("amount"));
                r.put("disburseTime", now);
            }
            case "accounting-api" -> {
                r.put("voucherNo", "VCH" + TS.format(LocalDateTime.now()) + String.format("%05d", seq.incrementAndGet()));
                r.put("loanNo", str(params.get("loanNo")));
                r.put("amount", params.get("amount"));
                r.put("accountTime", now);
            }
            case "esign-api" -> {
                r.put("signFlowId", "SF" + UUID.nameUUIDFromBytes((requestId + "|esign").getBytes())
                        .toString().replace("-", "").substring(0, 16).toUpperCase());
                r.put("contractNo", str(params.get("contractNo")));
                r.put("signerId", mask(str(params.get("signerId"))));
                r.put("signStatus", "DEGRADED".equals(status) ? "PROCESSING" : "SIGNED");
            }
            case "sms-api" -> {
                r.put("msgId", "MSG" + UUID.nameUUIDFromBytes((requestId + "|sms").getBytes())
                        .toString().replace("-", "").substring(0, 16).toUpperCase());
                r.put("phone", mask(str(params.get("phone"))));
                r.put("templateId", str(params.get("templateId")));
                r.put("sendStatus", "DEGRADED".equals(status) ? "QUEUED" : "SENT");
                r.put("sendTime", now);
            }
            case "notify-api" -> {
                r.put("notifyId", "NTF" + UUID.nameUUIDFromBytes((requestId + "|notify").getBytes())
                        .toString().replace("-", "").substring(0, 16).toUpperCase());
                r.put("channel", "IN_APP");
                r.put("receiver", mask(str(params.get("receiver"))));
                r.put("sendStatus", "DEGRADED".equals(status) ? "DEGRADED_FALLBACK" : "SENT");
            }
            default -> {
                r.put("targetService", def.targetService());
                r.put("echo", Map.copyOf(params));
            }
        }
        if ("TIMEOUT".equals(status) || "DEGRADED".equals(status)) {
            r.put("degraded", true);
        }
        return r;
    }

    /** 定义管理（CREATE/UPDATE/PUBLISH/OFFLINE；定义名冲突 CEX_1005） */
    public CallExecDtos.DefinitionResult manageDefinition(Map<String, Object> body) {
        if (body == null || isBlank(body.get("op"))) {
            throw new BaiException(CallExecErrorCodes.PARAM_INVALID, "CEX_1001 参数缺失：op 必填");
        }
        String op = str(body.get("op")).toUpperCase();
        String definitionId = strOr(body.get("definitionId"), null);
        switch (op) {
            case "CREATE" -> {
                if (definitionId == null || isBlank(body.get("name")) || isBlank(body.get("targetService"))) {
                    throw new BaiException(CallExecErrorCodes.PARAM_INVALID,
                            "CEX_1001 参数缺失：CREATE 须 definitionId/name/targetService");
                }
                if (definitions.containsKey(definitionId)) {
                    throw new BaiException(CallExecErrorCodes.CONFLICT,
                            "CEX_1005 定义名冲突：" + definitionId + " 已存在");
                }
                String targetService = str(body.get("targetService"));
                if (!WHITELIST.contains(targetService)) {
                    throw new BaiException(CallExecErrorCodes.NO_PERMISSION,
                            "CEX_1003 目标服务不在白名单：" + targetService);
                }
                List<String> requiredParams = strings(body.get("requiredParams"));
                Def def = new Def(str(body.get("name")), targetService,
                        strOr(body.get("method"), "POST"), strOr(body.get("path"), "/"),
                        strOr(body.get("priority"), "P1"),
                        (int) longOr(body.get("timeoutMs"), 5000),
                        (int) longOr(body.get("maxRetries"), 2), requiredParams, "DRAFT");
                definitions.put(definitionId, def);
                log.info("定义创建: definitionId={}, targetService={}, priority={}", definitionId, targetService, def.priority());
                return new CallExecDtos.DefinitionResult(definitionId, def.name(), targetService, op, def.status());
            }
            case "UPDATE" -> {
                Def prev = definitionOf(definitionId);
                if (prev != null && !"DRAFT".equals(prev.status()) && !"PUBLISHED".equals(prev.status())) {
                    throw new BaiException(CallExecErrorCodes.CONFLICT,
                            "CEX_1005 版本冲突：" + definitionId + " 状态 " + prev.status() + " 不可更新");
                }
                Def updated = new Def(strOr(body.get("name"), prev.name()), prev.targetService(),
                        strOr(body.get("method"), prev.method()), strOr(body.get("path"), prev.path()),
                        prev.priority(), (int) longOr(body.get("timeoutMs"), prev.timeoutMs()),
                        (int) longOr(body.get("maxRetries"), prev.maxRetries()),
                        body.get("requiredParams") instanceof List<?> l ? strings(l) : prev.requiredParams(),
                        prev.status());
                definitions.put(definitionId, updated);
                log.info("定义更新: definitionId={}", definitionId);
                return new CallExecDtos.DefinitionResult(definitionId, updated.name(), updated.targetService(),
                        op, updated.status());
            }
            case "PUBLISH" -> {
                Def prev = definitionOf(definitionId);
                Def published = new Def(prev.name(), prev.targetService(), prev.method(), prev.path(),
                        prev.priority(), prev.timeoutMs(), prev.maxRetries(), prev.requiredParams(), "PUBLISHED");
                definitions.put(definitionId, published);
                log.info("定义发布: definitionId={}", definitionId);
                return new CallExecDtos.DefinitionResult(definitionId, published.name(),
                        published.targetService(), op, published.status());
            }
            case "OFFLINE" -> {
                Def prev = definitionOf(definitionId);
                Def offline = new Def(prev.name(), prev.targetService(), prev.method(), prev.path(),
                        prev.priority(), prev.timeoutMs(), prev.maxRetries(), prev.requiredParams(), "OFFLINE");
                definitions.put(definitionId, offline);
                log.info("定义停用: definitionId={}", definitionId);
                return new CallExecDtos.DefinitionResult(definitionId, offline.name(),
                        offline.targetService(), op, offline.status());
            }
            default -> throw new BaiException(CallExecErrorCodes.PARAM_INVALID,
                    "CEX_1001 参数非法：op 仅支持 CREATE/UPDATE/PUBLISH/OFFLINE");
        }
    }

    /** 定义列表查询（targetService/status 过滤） */
    public List<CallExecDtos.DefinitionInfo> queryDefinitions(String targetService, String status) {
        return definitions.entrySet().stream()
                .filter(e -> targetService == null || targetService.isBlank() || e.getValue().targetService().equals(targetService))
                .filter(e -> status == null || status.isBlank() || e.getValue().status().equals(status))
                .map(e -> new CallExecDtos.DefinitionInfo(e.getKey(), e.getValue().name(),
                        e.getValue().targetService(), e.getValue().method(), e.getValue().path(),
                        e.getValue().timeoutMs(), e.getValue().maxRetries(), e.getValue().priority(),
                        e.getValue().requiredParams(), e.getValue().status()))
                .toList();
    }

    /** 调用历史查询（callId/definitionId/status 过滤；callId 指定且不存在 CEX_1004） */
    public CallExecDtos.HistoryQuery queryHistory(String callId, String definitionId, String status) {
        List<CallExecDtos.HistoryRecord> records = history.values().stream()
                .filter(r -> callId == null || callId.isBlank() || r.callId().equals(callId))
                .filter(r -> definitionId == null || definitionId.isBlank() || r.definitionId().equals(definitionId))
                .filter(r -> status == null || status.isBlank() || r.status().equals(status))
                .toList();
        if (callId != null && !callId.isBlank() && records.isEmpty()) {
            throw new BaiException(CallExecErrorCodes.NOT_FOUND, "CEX_1004 调用历史不存在：" + callId);
        }
        return new CallExecDtos.HistoryQuery(records.size(), records);
    }

    private Def definitionOf(String definitionId) {
        Def def = definitionId == null ? null : definitions.get(definitionId);
        if (def == null) {
            throw new BaiException(CallExecErrorCodes.NOT_FOUND,
                    "CEX_1004 接口定义不存在：" + definitionId);
        }
        return def;
    }

    /** 手机号/账号类脱敏（保留前 3 后 4） */
    private static String mask(String v) {
        if (v == null) {
            return null;
        }
        if (v.length() <= 7) {
            return v.charAt(0) + "****";
        }
        return v.substring(0, 3) + "****" + v.substring(v.length() - 4);
    }

    private static int shaKey(String s) {
        return UUID.nameUUIDFromBytes(s.getBytes()).hashCode();
    }

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            out.add(String.valueOf(item));
        }
        return List.copyOf(out);
    }

    private static long longOr(Object o, long defaultValue) {
        try {
            return o == null ? defaultValue : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
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

    private static String strOr(Object o, String defaultValue) {
        String s = str(o);
        return s == null || s.isBlank() ? defaultValue : s;
    }
}
