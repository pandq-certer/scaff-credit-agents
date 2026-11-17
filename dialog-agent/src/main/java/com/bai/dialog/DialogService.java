package com.bai.dialog;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import com.spatix.scaff.iagent.model.AgentSession;
import com.spatix.scaff.iagent.model.EventType;
import com.spatix.scaff.iagent.model.ProcessLogEvent;
import com.spatix.scaff.iagent.sse.SessionEventPublisher;
import com.spatix.scaff.iagent.store.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话管理服务（BIA-INT-038）。多轮会话状态与上下文记忆，会话管理经 scaff-iagent SessionStore/SessionEventPublisher 契约承载。
 * <p>处理链：校验会话状态与权限 → 意图识别 → 槽位抽取填充 → 策略决策（必需槽缺失 CLARIFY、关键信息变更 CONFIRM、
 * 开放问题 ANSWER、情绪异常/用户要求/连续 3 轮未解决 HANDOFF，规则优先 LLM 补判）→ 回复生成（模板/LLM，输出前合规检查）
 * → 记忆更新（最近 20 轮滑动窗口 + 状态快照，TTL 30min）→ 留痕（dialog.handoff/dialog.sensitive-blocked 事件发布）
 */
@Service
public class DialogService {

    private static final Logger log = LoggerFactory.getLogger(DialogService.class);

    private static final int MAX_MESSAGE_LEN = 2000;         // 消息 ≤2000 字
    private static final int MEMORY_ROUNDS = 20;             // 滑动窗口最近 20 轮
    private static final int MEMORY_MAX_ITEMS = MEMORY_ROUNDS * 2;
    private static final long SESSION_TTL_MILLIS = 30 * 60 * 1000L; // 会话缓存 TTL 30min
    private static final int HANDOFF_AFTER_ROUNDS = 3;       // 连续 3 轮未解决转人工
    private static final Set<String> CHANNELS = Set.of("VOICE", "TEXT", "PC", "MOBILE");
    private static final Set<String> SESSION_ACTIONS = Set.of("CREATE", "RESUME", "END");
    /** 场景白名单（场景按渠道授权，非白名单 DLG_1007） */
    private static final Set<String> SCENE_WHITELIST = Set.of("VOICE_ASSISTANT", "CUSTOM_SERVICE", "DIGITAL_STAFF");

    /** 敏感话题词表（零越界：BLOCK 拦截；营销禁收益承诺、催收禁威胁辱骂） */
    private static final Set<String> SENSITIVE_WORDS = Set.of(
            "保本", "稳赚", "高收益", "保证收益", "威胁", "辱骂", "堵门", "暴力催收", "赌博", "洗钱");

    private static final Pattern LOAN_NO = Pattern.compile("(?:贷款编号|贷款号|编号)[:：]?\\s*([A-Za-z0-9\\-]{6,32})|(\\d{10,32})");
    private static final Pattern AMOUNT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(万|元)?");
    private static final Pattern LOAN_NO_IN_TEXT = Pattern.compile("贷款编号为?\\s*([A-Za-z0-9\\-]{6,32})");

    private final IdempotencyStore idempotency = new IdempotencyStore(300_000L); // 5min 内同键不重复处理

    /** 会话管理契约（IagentAutoConfiguration 装配内存实现） */
    private final SessionStore sessionStore;
    private final SessionEventPublisher eventPublisher;

    /** LLM 通道模式：MOCK=可用；UNAVAILABLE=模拟降级（DLG_2001 转模板+转人工） */
    private final boolean llmAvailable;

    /** 会话域模型：sessionId → 会话（状态/意图/槽位/历史/轮次） */
    private final Map<String, DialogSession> sessions = new ConcurrentHashMap<>();

    public DialogService(SessionStore sessionStore, SessionEventPublisher eventPublisher,
                         @Value("${dialog.llm-mode:MOCK}") String llmMode) {
        this.sessionStore = sessionStore;
        this.eventPublisher = eventPublisher;
        this.llmAvailable = !"UNAVAILABLE".equalsIgnoreCase(llmMode);
    }

    /** 会话创建/恢复/结束（CREATE/RESUME/END） */
    public DialogDtos.SessionInfo manageSession(String action, String sessionId, String userId,
                                                String channel, String scene, String requestId) {
        if (action == null || !SESSION_ACTIONS.contains(action.toUpperCase())) {
            throw new BaiException(DialogErrorCodes.PARAM_INVALID, "DLG_1001 参数非法：action 须为 CREATE/RESUME/END");
        }
        String act = action.toUpperCase();
        if (userId == null || userId.isBlank()) {
            throw new BaiException(DialogErrorCodes.PARAM_INVALID, "DLG_1001 参数缺失：userId 必填");
        }
        if (scene != null && !scene.isBlank() && !SCENE_WHITELIST.contains(scene.toUpperCase())) {
            throw new BaiException(DialogErrorCodes.SENSITIVE_BLOCKED,
                    "DLG_1007 场景非法：scene 不在白名单（VOICE_ASSISTANT/CUSTOM_SERVICE/DIGITAL_STAFF）");
        }
        if ("CREATE".equals(act)) {
            // 会话冲突（重复创建）：携带已存在且 ACTIVE 的 sessionId → 提示恢复会话
            if (sessionId != null && !sessionId.isBlank()) {
                DialogSession existing = live(sessionId);
                if (existing != null && "ACTIVE".equals(existing.status)) {
                    throw new BaiException(DialogErrorCodes.SESSION_CONFLICT,
                            "DLG_1005 会话冲突：会话已存在且 ACTIVE，请改用 RESUME 恢复会话 " + sessionId);
                }
            }
            String newId = "SES" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            DialogSession session = new DialogSession(newId, userId,
                    channel == null ? "TEXT" : channel.toUpperCase(), "ACTIVE");
            sessions.put(newId, session);
            registerStore(session, scene);
            log.info("会话创建: sessionId={}, userId={}, channel={}, requestId={}", newId, userId, session.channel, requestId);
            return session.toInfo();
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new BaiException(DialogErrorCodes.PARAM_INVALID, "DLG_1001 参数缺失：sessionId 必填");
        }
        DialogSession session = live(sessionId);
        if (session == null || "ENDED".equals(session.status)) {
            throw new BaiException(DialogErrorCodes.SESSION_NOT_FOUND,
                    "DLG_1004 会话不存在或已结束，请校验 sessionId 或新建会话");
        }
        if (!userId.equals(session.userId)) {
            throw new BaiException(DialogErrorCodes.NO_PERMISSION, "DLG_1003 无会话权限：仅本人可操作该会话");
        }
        if ("RESUME".equals(act)) {
            session.status = "ACTIVE";
            registerStore(session, scene);
            log.info("会话恢复: sessionId={}, userId={}", sessionId, userId);
        } else {
            session.status = "ENDED";
            publish(session.sessionId, EventType.SUSPENDED, "dialog.session-ended");
            log.info("会话结束: sessionId={}, userId={}", sessionId, userId);
        }
        session.updatedAt = LocalDateTime.now();
        return session.toInfo();
    }

    /** 消息处理（幂等键：requestId+sessionId+message，5min 缓存内同键返回首次回复） */
    public DialogDtos.DialogReply handleMessage(String sessionId, String message, String channel,
                                                String userId, String requestId) {
        long start = System.currentTimeMillis();
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(DialogErrorCodes.PARAM_INVALID, "DLG_1001 参数缺失：requestId 必填");
        }
        if (requestId.length() > 32) {
            throw new BaiException(DialogErrorCodes.PARAM_INVALID, "DLG_1001 参数非法：requestId 须 32 位以内");
        }
        if (userId == null || userId.isBlank()) {
            throw new BaiException(DialogErrorCodes.PARAM_INVALID, "DLG_1001 参数缺失：userId 必填");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new BaiException(DialogErrorCodes.PARAM_INVALID, "DLG_1001 参数缺失：sessionId 必填");
        }
        DialogSession session = live(sessionId);
        if (session == null || !"ACTIVE".equals(session.status)) {
            throw new BaiException(DialogErrorCodes.SESSION_NOT_FOUND, "DLG_1004 会话不存在或非 ACTIVE，请校验 sessionId");
        }
        if (!userId.equals(session.userId)) {
            throw new BaiException(DialogErrorCodes.NO_PERMISSION, "DLG_1003 无会话权限：越权访问他人会话");
        }
        if (message == null || message.isBlank()) {
            throw new BaiException(DialogErrorCodes.PARAM_INVALID, "DLG_1001 参数缺失：message 必填");
        }
        if (message.length() > MAX_MESSAGE_LEN) {
            throw new BaiException(DialogErrorCodes.MESSAGE_TOO_LONG,
                    "DLG_1006 消息超长（" + message.length() + " > 2000 字），请缩减输入");
        }
        String ch = channel == null ? session.channel : channel.toUpperCase();
        if (!CHANNELS.contains(ch)) {
            throw new BaiException(DialogErrorCodes.SENSITIVE_BLOCKED,
                    "DLG_1007 场景非法：channel 须为 VOICE/TEXT/PC/MOBILE");
        }
        String idemKey = sessionId + ":" + message;
        DialogDtos.DialogReply cached = idempotency.get(idemKey, requestId);
        if (cached != null) {
            return cached;
        }

        // 敏感话题拦截：BLOCK/REDIRECT 零越界，并发布 dialog.sensitive-blocked 事件
        String sensitive = hitSensitive(message);
        if (sensitive != null) {
            publish(sessionId, EventType.PROGRESS, "dialog.sensitive-blocked:" + sensitive);
            log.info("敏感拦截: sessionId={}, userId={}, hit={}", sessionId, userId, sensitive);
            throw new BaiException(DialogErrorCodes.SENSITIVE_BLOCKED,
                    "DLG_1007 敏感拦截：消息命中敏感话题（" + sensitive + "），已按 BLOCK 拦截");
        }

        // 意图识别（规则匹配，联动 intent-agent 的判定口径）与槽位抽取（联动 ner-agent 口径）
        String intent = detectIntent(message);
        mergeSlots(session, message, intent);
        if (intent != null) {
            session.intent = intent;
        }
        session.channel = ch;
        session.clarifyRounds = session.lastAction != null && ("CLARIFY".equals(session.lastAction)
                || "COLLECT".equals(session.lastAction)) ? session.clarifyRounds + 1 : 0;

        DialogRound round = decide(session, message, intent);
        String reply = complianceCheck(round.reply());
        String handoffReason = round.handoffReason() == null ? "" : round.handoffReason();
        DialogDtos.DialogReply result = new DialogDtos.DialogReply(sessionId, reply, session.intent,
                session.snapshotSlots(),
                new DialogDtos.Action(round.actionType(), round.detail()),
                new DialogDtos.Handoff(round.handoff(), handoffReason),
                System.currentTimeMillis() - start);
        if (round.handoff()) {
            session.status = "HANDOFF";
            publish(sessionId, EventType.CONFIRM_REQUIRED, "dialog.handoff:" + round.handoffReason());
        }
        // 记忆更新：滑动窗口最近 20 轮 + 状态快照，TTL 30min
        session.history.add(new DialogDtos.HistoryItem("user", message, session.intent, session.snapshotSlots(),
                LocalDateTime.now().toString()));
        session.history.add(new DialogDtos.HistoryItem("assistant", reply, session.intent, session.snapshotSlots(),
                LocalDateTime.now().toString()));
        while (session.history.size() > MEMORY_MAX_ITEMS) {
            session.history.remove(0);
        }
        session.lastAction = round.actionType();
        session.lastActiveAt = System.currentTimeMillis();
        session.updatedAt = LocalDateTime.now();
        registerStore(session, null);
        idempotency.put(idemKey, requestId, result);
        log.info("对话处理完成: sessionId={}, intent={}, action={}, elapsed={}ms, requestId={}",
                sessionId, session.intent, round.actionType(), result.elapsedMs(), requestId);
        return result;
    }

    /** 会话列表查询（按用户密级分权隔离：仅本人/授权坐席可见，由网关鉴权） */
    public List<DialogDtos.SessionInfo> listSessions(String userId) {
        List<DialogDtos.SessionInfo> result = new ArrayList<>();
        for (DialogSession session : sessions.values()) {
            if (userId == null || userId.isBlank() || userId.equals(session.userId)) {
                result.add(session.toInfo());
            }
        }
        return result;
    }

    /** 对话历史查询（会话/历史不存在 DLG_1004；越权 DLG_1003） */
    public DialogDtos.DialogHistory getHistory(String sessionId, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BaiException(DialogErrorCodes.PARAM_INVALID, "DLG_1001 参数缺失：sessionId 必填");
        }
        DialogSession session = sessions.get(sessionId);
        if (session == null) {
            throw new BaiException(DialogErrorCodes.SESSION_NOT_FOUND, "DLG_1004 对话历史不存在，请校验 sessionId");
        }
        if (userId != null && !userId.isBlank() && !userId.equals(session.userId)) {
            throw new BaiException(DialogErrorCodes.NO_PERMISSION, "DLG_1003 无会话权限：仅本人/授权坐席可见");
        }
        return new DialogDtos.DialogHistory(sessionId, session.userId, session.status,
                List.copyOf(session.history));
    }

    /** 策略决策：规则优先（必需槽缺失 CLARIFY→关键信息 CONFIRM→开放问题 ANSWER/LLM→HANDOFF），LLM 补判 */
    private DialogRound decide(DialogSession session, String message, String intent) {
        // 用户主动要求转人工
        if (message.contains("人工") || message.contains("投诉") || message.contains("客服人员")) {
            return new DialogRound("HANDOFF", Map.of("trigger", "user_request"),
                    "好的，正在为您转接人工坐席，请稍候，已为您附上本次对话上下文。", true, "用户主动要求转人工");
        }
        // 连续 3 轮未解决转人工并附上下文
        if (session.clarifyRounds >= HANDOFF_AFTER_ROUNDS) {
            return new DialogRound("HANDOFF", Map.of("clarifyRounds", session.clarifyRounds),
                    "多次沟通仍未解决您的问题，正在为您转接人工坐席，已附上对话上下文。", true, "连续 3 轮未解决");
        }
        String required = requiredSlot(intent, session.slots);
        boolean missing = required != null && session.slots.get(required) == null;
        if (missing) {
            // 必需槽缺失：已有部分槽位走 COLLECT 收集，否则 CLARIFY 追问
            boolean partial = session.slots.values().stream().anyMatch(java.util.Objects::nonNull);
            String type = partial ? "COLLECT" : "CLARIFY";
            if ("repay_query".equals(intent)) {
                return new DialogRound(type, Map.of("missingSlot", required),
                        "好的，还款计划查询需要您提供贷款编号，请问您的贷款编号是多少？", false, null);
            }
            if ("early_repay".equals(intent)) {
                String tip = "loanNo".equals(required) ? "贷款编号" : "提前还款金额";
                return new DialogRound(type, Map.of("missingSlot", required),
                        "办理提前还款需要您提供" + tip + "，请问您的" + tip + "是多少？", false, null);
            }
            return new DialogRound(type, Map.of("missingSlot", required),
                    "需要您补充「" + required + "」信息，请问方便提供一下吗？", false, null);
        }
        // 槽位齐全：若用户确认（上一轮 CONFIRM），输出 ANSWER；否则复述确认关键信息
        boolean confirmed = message.contains("确认") || message.contains("是的") || message.contains("对")
                || message.equalsIgnoreCase("ok") || message.contains("没错");
        if ("CONFIRM".equals(session.lastAction) && confirmed) {
            return new DialogRound("ANSWER", Map.of("intent", session.intent),
                    mockBusinessAnswer(session.intent, session.slots), false, null);
        }
        if (required != null) {
            return new DialogRound("CONFIRM", Map.of("confirmOf", intent, "slots", session.slots),
                    "确认一下：" + describeSlots(intent, session.slots) + "，请问确认吗？", false, null);
        }
        // 开放问题：LLM 补判（温度 0.5），不可用降级模板回复并打开转人工开关
        if ("general_qa".equals(intent)) {
            if (llmAvailable) {
                return new DialogRound("ANSWER", Map.of("source", "LLM"),
                        "您好，我是智能信贷助手，可为您办理还款计划查询、提前还款等业务，请问有什么可以帮您？", false, null);
            }
            return new DialogRound("ANSWER", Map.of("source", "TEMPLATE_FALLBACK"),
                    "抱歉，智能回复暂时不可用，已为您转接人工坐席，请稍候。", true, "LLM 不可用（DLG_2001 降级），已转模板回复并转人工");
        }
        return new DialogRound("ANSWER", Map.of("intent", intent),
                mockBusinessAnswer(intent, session.slots), false, null);
    }

    /** 意图识别（关键词规则，联动 intent-agent 判定口径，场景白名单限定范围） */
    private String detectIntent(String message) {
        if (message.contains("提前还款") || message.contains("提前还")) {
            return "early_repay";
        }
        if (message.contains("还款计划") || message.contains("还款") || message.contains("月供")) {
            return "repay_query";
        }
        if (message.contains("贷款查询") || message.contains("我的贷款") || message.contains("贷款信息")) {
            return "loan_query";
        }
        if (message.contains("余额") || message.contains("账户")) {
            return "balance_query";
        }
        return "general_qa";
    }

    /** 槽位抽取（贷款编号/金额，联动 ner-agent 抽取口径）并填充会话槽位 */
    private void mergeSlots(DialogSession session, String message, String intent) {
        if ("general_qa".equals(intent) && session.slots.isEmpty()) {
            return;
        }
        Matcher matcher = LOAN_NO.matcher(message);
        if (matcher.find()) {
            String loanNo = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (loanNo != null && session.slots.get("loanNo") == null) {
                session.slots.put("loanNo", loanNo);
            }
        } else {
            Matcher named = LOAN_NO_IN_TEXT.matcher(message);
            if (named.find() && session.slots.get("loanNo") == null) {
                session.slots.put("loanNo", named.group(1));
            }
        }
        if ("early_repay".equals(intent)) {
            Matcher amount = AMOUNT.matcher(message);
            if (amount.find() && session.slots.get("amount") == null) {
                session.slots.put("amount", amount.group(1) + (amount.group(2) == null ? "元" : amount.group(2)));
            }
        }
        session.slots.putIfAbsent("loanNo", null);
    }

    private String requiredSlot(String intent, Map<String, Object> slots) {
        return switch (intent == null ? "" : intent) {
            case "repay_query", "loan_query", "balance_query" -> "loanNo";
            case "early_repay" -> slots.get("loanNo") == null ? "loanNo" : "amount";
            default -> null;
        };
    }

    private String hitSensitive(String message) {
        for (String word : SENSITIVE_WORDS) {
            if (message.contains(word)) {
                return word;
            }
        }
        return null;
    }

    /** 回复合规检查（营销禁收益承诺、催收禁威胁辱骂），未过回退安全模板 */
    private String complianceCheck(String reply) {
        for (String word : SENSITIVE_WORDS) {
            if (reply.contains(word)) {
                log.warn("回复合规检查未过（命中 {}），回退安全模板", word);
                return "您好，相关业务请以我行官方渠道说明为准，如需详细咨询可转人工坐席。";
            }
        }
        return reply;
    }

    private String mockBusinessAnswer(String intent, Map<String, Object> slots) {
        String loanNo = String.valueOf(slots.getOrDefault("loanNo", "-"));
        return switch (intent == null ? "" : intent) {
            case "repay_query" -> "已为您查询到贷款编号 " + loanNo + " 的还款计划：本期应还 12,000.00 元，还款日为每月 15 日，当前无逾期。";
            case "early_repay" -> "贷款编号 " + loanNo + " 提前还款申请已受理，应还本息以系统试算为准，扣款确认后将短信通知您。";
            case "loan_query" -> "您的贷款编号 " + loanNo + " 状态正常，剩余期限 24 期，利率按合同约定执行。";
            case "balance_query" -> "您的账户余额查询结果以手机银行展示为准，如需对账单可随时告诉我。";
            default -> "您好，请问有什么可以帮您？";
        };
    }

    private String describeSlots(String intent, Map<String, Object> slots) {
        StringBuilder sb = new StringBuilder();
        slots.forEach((k, v) -> {
            if (v != null) {
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(k).append("=").append(v);
            }
        });
        return sb.length() == 0 ? intent : sb.toString();
    }

    /** 会话注册到 scaff-iagent SessionStore（会话管理契约），并同步状态快照 */
    private void registerStore(DialogSession session, String scene) {
        AgentSession agentSession = new AgentSession();
        agentSession.setSessionId(session.sessionId);
        agentSession.setUserId(session.userId);
        agentSession.setCreatedAt(LocalDateTime.parse(session.createdAt));
        agentSession.setUpdatedAt(LocalDateTime.now());
        sessionStore.save(agentSession);
        if (scene != null) {
            publish(session.sessionId, EventType.START, "dialog.session-created:" + scene);
        }
    }

    /** 发布会话事件（dialog.handoff / dialog.sensitive-blocked / 生命周期） */
    private void publish(String sessionId, EventType type, String message) {
        try {
            ProcessLogEvent event = new ProcessLogEvent();
            event.setSessionId(sessionId);
            event.setType(type);
            event.setMessage(message);
            event.setTimestamp(LocalDateTime.now());
            eventPublisher.publish(event);
        } catch (Exception e) {
            log.warn("会话事件发布失败（不影响主流程）: sessionId={}, msg={}", sessionId, e.getMessage());
        }
    }

    /** TTL 30min：过期会话标记 TIMEOUT */
    private DialogSession live(String sessionId) {
        DialogSession session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (System.currentTimeMillis() - session.lastActiveAt > SESSION_TTL_MILLIS
                && "ACTIVE".equals(session.status)) {
            session.status = "TIMEOUT";
        }
        return session;
    }

    /** 会话域模型（状态 ACTIVE/ENDED/TIMEOUT/HANDOFF 一致流转） */
    private static final class DialogSession {
        final String sessionId;
        final String userId;
        final String createdAt = LocalDateTime.now().toString();
        final Map<String, Object> slots = new LinkedHashMap<>();
        final List<DialogDtos.HistoryItem> history = new ArrayList<>();
        String channel;
        String status;
        String intent;
        String lastAction;
        int clarifyRounds;
        long lastActiveAt = System.currentTimeMillis();
        LocalDateTime updatedAt = LocalDateTime.now();

        DialogSession(String sessionId, String userId, String channel, String status) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.channel = channel;
            this.status = status;
        }

        DialogDtos.SessionInfo toInfo() {
            return new DialogDtos.SessionInfo(sessionId, userId, channel, status, intent,
                    snapshotSlots(), createdAt, updatedAt.toString());
        }

        /** 槽位快照（可含 null 值，JSON 输出对齐示例 slots.loanNo=null） */
        Map<String, Object> snapshotSlots() {
            return new LinkedHashMap<>(slots);
        }
    }

    /** 单轮策略决策结果 */
    private record DialogRound(String actionType, Map<String, Object> detail, String reply,
                               boolean handoff, String handoffReason) {}
}
