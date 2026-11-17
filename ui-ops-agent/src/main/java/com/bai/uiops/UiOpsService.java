package com.bai.uiops;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 界面操作服务（BIA-INT-041）。智能体操作 PC 页面的执行通道：元素定位、表单填充、点击提交、交易同步与回滚。
 * <p>处理链：沙箱校验（元素/指令白名单）→ 元素定位（三级策略 ID→XPATH→视觉兜底，置信度≥0.9 才操作）
 * → 表单操作（必填/类型校验、操作后回读）→ 交易同步（页面提交→后台调用→状态轮询≤30s）→ 状态回读（快照 hash 比对）
 * → 一致性处置（差异人工介入）→ 留痕。
 * <p>MVP 为确定性仿真（内置元素白名单 + 哈希驱动定位/比对结果），页面桥与后台联动由前端工程/call-exec-agent 承担。
 */
@Service
public class UiOpsService {

    private static final Logger log = LoggerFactory.getLogger(UiOpsService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> ACTIONS = Set.of("FILL", "SELECT", "CLICK", "SUBMIT", "UPLOAD");
    private static final Set<String> LOCATE_STRATEGIES = Set.of("ID", "XPATH", "VISUAL", "AUTO");
    private static final Set<String> ROLLBACK_TYPES = Set.of("BUSINESS", "DATA");
    private static final int MAX_FORM_FIELDS = 100;           // 表单字段上限，超长 UIO_1006
    private static final double LOCATE_THRESHOLD = 0.90;      // 定位置信度阈值

    /** 元素白名单（沙箱：未注册元素拒绝）。p0=资金类须双人确认 */
    private record ElementDef(String page, String locatorType, boolean p0) {}
    private static final Map<String, ElementDef> ELEMENTS = buildElements();

    private final IdempotencyStore idempotency = new IdempotencyStore();
    /** 操作留痕：opId → 操作记录（供 rollback 引用） */
    private final Map<String, UiOpsDtos.OpResult> ops = new ConcurrentHashMap<>();
    /** 交易同步留痕：txnRef → 同步记录（txnRef 唯一约束防重复提交） */
    private final Map<String, UiOpsDtos.SyncResult> syncs = new ConcurrentHashMap<>();
    /** 已回滚引用：opId/txnRef → rollbackId（幂等防重复回滚） */
    private final Map<String, String> rollbacks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    private static Map<String, ElementDef> buildElements() {
        Map<String, ElementDef> m = new LinkedHashMap<>();
        m.put("loanNo", new ElementDef("LOAN_EARLY_REPAY", "ID", false));
        m.put("repayAmount", new ElementDef("LOAN_EARLY_REPAY", "ID", false));
        m.put("repayDate", new ElementDef("LOAN_EARLY_REPAY", "ID", false));
        m.put("remark", new ElementDef("LOAN_EARLY_REPAY", "XPATH", false));
        m.put("uploadFile", new ElementDef("LOAN_EARLY_REPAY", "XPATH", false));
        m.put("submitBtn", new ElementDef("LOAN_EARLY_REPAY", "ID", true));
        m.put("confirmBtn", new ElementDef("LOAN_EARLY_REPAY", "ID", true));
        m.put("exportBtn", new ElementDef("LOAN_EXPORT", "XPATH", false));
        m.put("queryBtn", new ElementDef("LOAN_QUERY", "ID", false));
        return Map.copyOf(m);
    }

    /** 元素定位（ID→XPATH→视觉兜底三级策略；AUTO 按注册定位器优先，视觉兜底置信度不足则 located=false） */
    public UiOpsDtos.LocateResult locate(Map<String, Object> body, String requestId) {
        if (body == null || isBlank(body.get("sessionId")) || isBlank(body.get("element"))) {
            throw new BaiException(UiOpsErrorCodes.PARAM_INVALID, "UIO_1001 参数缺失：sessionId 与 element 必填");
        }
        String sessionId = str(body.get("sessionId"));
        String element = str(body.get("element"));
        String strategy = strOr(body.get("strategy"), "AUTO").toUpperCase();
        if (!LOCATE_STRATEGIES.contains(strategy)) {
            throw new BaiException(UiOpsErrorCodes.PARAM_INVALID,
                    "UIO_1001 参数非法：strategy 仅支持 ID/XPATH/VISUAL/AUTO");
        }
        requireSession(sessionId);
        ElementDef def = elementOf(element);
        long begin = System.currentTimeMillis();
        // 确定性仿真：AUTO 下注册定位器未命中（sessionId+element 哈希驱动）时降级视觉兜底
        boolean fallback = "AUTO".equals(strategy) && Math.abs((sessionId + element).hashCode()) % 17 == 0;
        // 三级策略：AUTO 时按 ID→XPATH→视觉兜底逐级尝试；显式 strategy 直接采用
        String used = fallback || "VISUAL".equals(strategy) ? "VISUAL" : "AUTO".equals(strategy) ? def.locatorType() : strategy;
        double confidence = switch (used) {
            case "ID" -> 0.99;
            case "XPATH" -> 0.95;
            default -> 0.88;    // 视觉兜底
        };
        boolean located = confidence >= LOCATE_THRESHOLD;
        String reason = located ? null : "定位置信度 " + confidence + "＜" + LOCATE_THRESHOLD + "（视觉兜底），不盲执行";
        long elapsed = System.currentTimeMillis() - begin;
        log.info("元素定位: requestId={}, sessionId={}, element={}, strategy={}, confidence={}, located={}",
                requestId, sessionId, element, used, confidence, located);
        return new UiOpsDtos.LocateResult(sessionId, element, used, confidence, located, reason, elapsed);
    }

    /** 页面操作（幂等键 requestId+sessionId+action 不重复操作） */
    public UiOpsDtos.OpResult operate(Map<String, Object> body, String requestId) {
        if (body == null || isBlank(body.get("sessionId")) || isBlank(body.get("element"))
                || isBlank(body.get("action"))) {
            throw new BaiException(UiOpsErrorCodes.PARAM_INVALID,
                    "UIO_1001 参数缺失：sessionId/element/action 必填");
        }
        String sessionId = str(body.get("sessionId"));
        String element = str(body.get("element"));
        String action = str(body.get("action")).toUpperCase();
        if (!ACTIONS.contains(action)) {
            throw new BaiException(UiOpsErrorCodes.PARAM_INVALID,
                    "UIO_1001 参数非法：action 仅支持 FILL/SELECT/CLICK/SUBMIT/UPLOAD");
        }
        Map<String, Object> value = cast(body.get("value"));
        if (("FILL".equals(action) || "SELECT".equals(action)) && (value == null || isBlank(value.get("value")))) {
            throw new BaiException(UiOpsErrorCodes.PARAM_INVALID,
                    "UIO_1001 参数缺失：action=" + action + " 时 value{value} 必填");
        }
        requireSession(sessionId);
        ElementDef def = elementOf(element);
        String idemKey = requestId + ":" + sessionId + ":" + action + ":" + element;
        UiOpsDtos.OpResult cached = idempotency.get("operate", idemKey);
        if (cached != null) {
            return cached;
        }
        long begin = System.currentTimeMillis();
        String result;
        String reason;
        // 一致性定位失败不盲执行：视觉兜底置信度不足 → NOT_FOUND
        boolean locateFail = Math.abs((sessionId + element).hashCode()) % 17 == 0;
        if (locateFail) {
            result = "NOT_FOUND";
            reason = "元素定位失败：置信度 0.88＜0.90（视觉兜底），请重试或人工介入";
        } else if (("FILL".equals(action) || "SELECT".equals(action)) && !formValid(element, value)) {
            result = "VALIDATION_FAILED";
            reason = "表单校验未过：填充值类型/范围非法（操作后回读确认失败）";
        } else if (def.p0() && ("CLICK".equals(action) || "SUBMIT".equals(action))
                && (value == null || !Boolean.parseBoolean(String.valueOf(value.get("confirm"))))) {
            result = "BLOCKED";
            reason = "P0 资金类操作须双人确认（value.confirm=true）";
        } else {
            result = "SUCCESS";
            reason = null;
        }
        long elapsed = System.currentTimeMillis() - begin;
        String opId = "OP" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        UiOpsDtos.OpResult op = new UiOpsDtos.OpResult(opId, sessionId, element, action, result,
                reason, snapshot(sessionId, def.page()), elapsed);
        ops.put(opId, op);
        idempotency.put("operate", idemKey, op);
        log.info("页面操作: requestId={}, opId={}, sessionId={}, element={}, action={}, result={}",
                requestId, opId, sessionId, element, action, result);
        return op;
    }

    /** 交易同步（页面提交→后台调用→状态回读比对；txnRef 唯一约束防重复提交） */
    public UiOpsDtos.SyncResult sync(Map<String, Object> body, String requestId) {
        if (body == null || isBlank(body.get("txnRef"))) {
            throw new BaiException(UiOpsErrorCodes.PARAM_INVALID, "UIO_1001 参数缺失：txnRef 必填");
        }
        String txnRef = str(body.get("txnRef"));
        Map<String, Object> formData = cast(body.get("formData"));
        if (formData == null || formData.isEmpty()) {
            throw new BaiException(UiOpsErrorCodes.PARAM_INVALID, "UIO_1001 参数缺失：formData 必填");
        }
        if (formData.size() > MAX_FORM_FIELDS) {
            throw new BaiException(UiOpsErrorCodes.TOO_LONG,
                    "UIO_1006 表单超长：" + formData.size() + " 个字段（≤" + MAX_FORM_FIELDS + "），请缩减输入");
        }
        boolean confirmRequired = Boolean.parseBoolean(String.valueOf(body.get("confirmRequired")));
        // 同 requestId 幂等返回首次结果；同 txnRef 不同请求视为重复提交
        UiOpsDtos.SyncResult cached = idempotency.get("sync", requestId + ":" + txnRef);
        if (cached != null) {
            return cached;
        }
        if (syncs.containsKey(txnRef)) {
            throw new BaiException(UiOpsErrorCodes.CONFLICT,
                    "UIO_1005 重复提交：交易 " + txnRef + " 已同步，txnRef 幂等拦截");
        }
        if (confirmRequired && !Boolean.parseBoolean(String.valueOf(formData.get("confirm")))) {
            throw new BaiException(UiOpsErrorCodes.NO_PERMISSION,
                    "UIO_1003 P0 资金类操作须双人确认：confirmRequired=true 时 formData.confirm 必填");
        }
        long begin = System.currentTimeMillis();
        // 状态回读：页面快照与后台结果 hash/字段级比对（确定性仿真：formData.forceDiff=true 触发差异）
        boolean diff = Boolean.parseBoolean(String.valueOf(formData.get("forceDiff")));
        if (diff) {
            throw new BaiException(UiOpsErrorCodes.VALIDATION_DIFF,
                    "UIO_1007 一致性差异：页面与后台状态比对不一致（consistency=DIFF），已暂停转人工介入");
        }
        long elapsed = System.currentTimeMillis() - begin;
        String opId = "OP" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        String backendTxnNo = "BK" + Integer.toHexString(Math.abs(txnRef.hashCode())) + String.format("%04d", seq.incrementAndGet());
        UiOpsDtos.SyncResult sync = new UiOpsDtos.SyncResult(opId, txnRef, "MATCHED", "SYNCED",
                snapshot(txnRef, "LOAN_EARLY_REPAY"), backendTxnNo, confirmRequired, elapsed);
        syncs.put(txnRef, sync);
        idempotency.put("sync", requestId + ":" + txnRef, sync);
        log.info("交易同步: requestId={}, opId={}, txnRef={}, consistency={}, backendTxnNo={}",
                requestId, opId, txnRef, sync.consistency(), backendTxnNo);
        return sync;
    }

    /** 回滚处理（BUSINESS 逆向交易 / DATA 快照恢复；P0 回滚须双人授权，幂等防重复回滚） */
    public UiOpsDtos.RollbackResult rollback(Map<String, Object> body, String requestId, String authorization) {
        if (body == null || isBlank(body.get("rollbackType"))) {
            throw new BaiException(UiOpsErrorCodes.PARAM_INVALID, "UIO_1001 参数缺失：rollbackType 必填");
        }
        String rollbackType = str(body.get("rollbackType")).toUpperCase();
        if (!ROLLBACK_TYPES.contains(rollbackType)) {
            throw new BaiException(UiOpsErrorCodes.PARAM_INVALID,
                    "UIO_1001 参数非法：rollbackType 仅支持 BUSINESS/DATA");
        }
        String refId = strOr(body.get("opId"), strOr(body.get("txnRef"), null));
        if (refId == null) {
            throw new BaiException(UiOpsErrorCodes.PARAM_INVALID, "UIO_1001 参数缺失：opId 或 txnRef 至少一个");
        }
        if (authorization == null || authorization.isBlank()) {
            throw new BaiException(UiOpsErrorCodes.NO_PERMISSION,
                    "UIO_1003 回滚需 P0 双人授权：Authorization Bearer Token 缺失");
        }
        if (ops.get(refId) == null && syncs.get(refId) == null) {
            throw new BaiException(UiOpsErrorCodes.NOT_FOUND, "UIO_1004 操作/交易记录不存在：" + refId);
        }
        if (rollbacks.containsKey(refId)) {
            throw new BaiException(UiOpsErrorCodes.CONFLICT,
                    "UIO_1005 重复回滚：" + refId + " 已回滚（" + rollbacks.get(refId) + "）");
        }
        long begin = System.currentTimeMillis();
        String rollbackId = "RB" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        rollbacks.put(refId, rollbackId);
        long elapsed = System.currentTimeMillis() - begin;
        String reason = "BUSINESS".equals(rollbackType) ? "逆向交易补偿完成（幂等防重）" : "按页面快照恢复数据完成";
        log.info("回滚处理: requestId={}, rollbackId={}, rollbackType={}, refId={}, status=ROLLED_BACK",
                requestId, rollbackId, rollbackType, refId);
        return new UiOpsDtos.RollbackResult(rollbackId, rollbackType, refId, "ROLLED_BACK", reason, elapsed);
    }

    /** 会话有效性（确定性仿真：PC 端会话号须 UIO_/SES 前缀且≥8 位） */
    private void requireSession(String sessionId) {
        boolean valid = sessionId.length() >= 8 && (sessionId.startsWith("UIO_") || sessionId.startsWith("SES"));
        if (!valid) {
            throw new BaiException(UiOpsErrorCodes.NOT_FOUND,
                    "UIO_1004 sessionId 无效：" + sessionId + "（须为 PC 端有效页面会话）");
        }
    }

    private ElementDef elementOf(String element) {
        ElementDef def = ELEMENTS.get(element);
        if (def == null) {
            throw new BaiException(UiOpsErrorCodes.NOT_FOUND,
                    "UIO_1004 元素未注册（沙箱白名单拒绝）：" + element);
        }
        return def;
    }

    /** 表单校验（确定性）：金额类须为正数，value.invalid=true 模拟校验未过 */
    private boolean formValid(String element, Map<String, Object> value) {
        if (Boolean.parseBoolean(String.valueOf(value.get("invalid")))) {
            return false;
        }
        String v = str(value.get("value"));
        if ("repayAmount".equals(element) || element.toLowerCase().contains("amount")) {
            try {
                return Double.parseDouble(v.replace(",", "")) > 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return !v.isBlank();
    }

    /** 页面快照（url 确定性 + 内容 hash 8 位） */
    private UiOpsDtos.PageSnapshot snapshot(String sessionKey, String page) {
        String hash = UUID.nameUUIDFromBytes((sessionKey + "|" + page).getBytes()).toString()
                .replace("-", "").substring(0, 8);
        return new UiOpsDtos.PageSnapshot("https://credit.internal/page/" + page, hash);
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
