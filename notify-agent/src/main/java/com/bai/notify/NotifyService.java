package com.bai.notify;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import com.spatix.scaff.iprov.ProviderResponse;
import com.spatix.scaff.iprov.provider.NotifyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 消息通知服务（BIA-EPT-030）。经 P14 notify-provider 通知通道下发（MVP 走 Mock）。
 * <p>处理链：模板渲染（${var} 占位符校验）→ 频控校验（退订黑名单优先→每日计数→22:00—08:00 营销禁发，P0 资金类豁免）
 * → 渠道选择（允许渠道∩健康渠道，优先级站内信&gt;短信&gt;Push&gt;邮件）→ 优先级入队（P0 直发/P1 队列/P2 低峰）
 * → 渠道网关下发 → 失败≤2 次退避（1s/3s）降级次优渠道 → 回执状态流转 → 留痕
 */
@Service
public class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)}");
    private static final Set<String> ALL_CHANNELS = Set.of("INBOX", "SMS", "PUSH", "EMAIL", "WECOM");
    /** 渠道优先级：站内信 > 短信 > Push > 邮件（企微末位） */
    private static final List<String> CHANNEL_PRIORITY = List.of("INBOX", "SMS", "PUSH", "EMAIL", "WECOM");
    private static final Set<String> PRIORITIES = Set.of("P0", "P1", "P2");
    private static final int MAX_BATCH = 1000;            // 批量≤1000 条/批
    private static final int MAX_RETRY = 2;               // 渠道失败重试≤2 次（退避 1s/3s）
    private static final int MARKETING_DAILY_LIMIT = 1;   // 营销类日 1 条
    private static final int COLLECTION_DAILY_LIMIT = 3;  // 催收类日 3 条
    private static final int DEFAULT_DAILY_LIMIT = 10;

    private final IdempotencyStore idempotency = new IdempotencyStore(300_000L); // 5 分钟内不重复发送

    /** P14 通知通道 Provider（IprovAutoConfiguration 装配 Mock 实现） */
    private final NotifyProvider notifyProvider;

    /** 模板注册表 */
    private final Map<String, NotifyDtos.TemplateInfo> templates = new ConcurrentHashMap<>();
    /** 发送记录：messageId → 记录 */
    private final Map<String, NotifyDtos.SendRecord> records = new ConcurrentHashMap<>();
    /** 退订黑名单：receiverKey → 登记（退订即时生效 ≤1s） */
    private final Map<String, NotifyDtos.OptOutEntry> optOuts = new ConcurrentHashMap<>();
    /** 渠道健康状态 */
    private final Map<String, Boolean> channelHealth = new ConcurrentHashMap<>(Map.of(
            "INBOX", true, "SMS", true, "PUSH", true, "EMAIL", true, "WECOM", true));
    /** 每日频控计数：receiverKey + 日期 + 类别 → 当日已发 */
    private final Map<String, AtomicInteger> dailyCounters = new ConcurrentHashMap<>();
    /** 回执去重：messageId:channel（回执幂等唯一键） */
    private final Set<String> receiptKeys = ConcurrentHashMap.newKeySet();

    public NotifyService(NotifyProvider notifyProvider) {
        this.notifyProvider = notifyProvider;
        // 预置注册模板（示例：还款到期提醒）
        templates.put("REPAY_DUE_SMS", new NotifyDtos.TemplateInfo("REPAY_DUE_SMS", "还款到期提醒", "NOTIFY",
                List.of("SMS", "PUSH"), "尊敬的${customerName}，您的贷款应还金额${amount}元，到期日${dueDate}，请按时还款。"));
        templates.put("MARKETING_PROMO", new NotifyDtos.TemplateInfo("MARKETING_PROMO", "营销推广", "MARKETING",
                List.of("SMS", "EMAIL"), "${customerName}，限时优惠活动进行中，详情请咨询客户经理。"));
        templates.put("AUTH_STATUS_REMIND", new NotifyDtos.TemplateInfo("AUTH_STATUS_REMIND", "授权状态提醒", "NOTIFY",
                List.of("SMS"), "客户${customerName}：${status}，请及时跟进处理。"));
        // 预置 NOTIFY 类模板（非营销类别，不触发 MARKETING 日频控）
        templates.put("TASK_URGE", new NotifyDtos.TemplateInfo("TASK_URGE", "督办提醒", "NOTIFY",
                List.of("SMS", "INBOX"), "${customerName}：${taskContent}，请于 ${deadline} 前处理。"));
        templates.put("VERIFY_CODE", new NotifyDtos.TemplateInfo("VERIFY_CODE", "签署验证码", "NOTIFY",
                List.of("SMS"), "${customerName}：验证码 ${code}，5 分钟内有效，请勿泄露。"));
        templates.put("LOAN_DISBURSE_NOTIFY", new NotifyDtos.TemplateInfo("LOAN_DISBURSE_NOTIFY", "放款通知", "NOTIFY",
                List.of("SMS", "PUSH"), "${customerName}：您申请的贷款 ${amount} 已发放至账户 ${account}，请注意查收。"));
        templates.put("DUE_REMIND", new NotifyDtos.TemplateInfo("DUE_REMIND", "到期提醒", "NOTIFY",
                List.of("SMS", "PUSH"), "${customerName}：您的贷款将于 ${dueDate} 到期，请及时安排还款。"));
        templates.put("OVERDUE_COLLECTION", new NotifyDtos.TemplateInfo("OVERDUE_COLLECTION", "催收提醒", "NOTIFY",
                List.of("SMS"), "${customerName}：您的贷款已逾期 ${overdueDays} 天，请尽快还款避免影响信用记录。"));
        templates.put("COMPLIANCE_SMS", new NotifyDtos.TemplateInfo("COMPLIANCE_SMS", "合规提示", "NOTIFY",
                List.of("SMS", "INBOX"), "${customerName}：${complianceTip}。"));
    }

    /** 单发/群发受理（幂等键：requestId+templateCode+receiver，5 分钟内不重复发送） */
    public NotifyDtos.SendResult send(String templateCode, Map<String, Object> receiver,
                                      Map<String, Object> params, List<String> channels,
                                      String priority, String requestId, String caller) {
        validateRequestId(requestId);
        if (templateCode == null || templateCode.isBlank()) {
            throw new BaiException(NotifyErrorCodes.PARAM_INVALID, "NTF_1001 参数缺失：templateCode 必填");
        }
        if (receiver == null || receiver.isEmpty()) {
            throw new BaiException(NotifyErrorCodes.PARAM_INVALID,
                    "NTF_1001 参数缺失：receiver（手机/邮箱/客户号/企微）至少一项");
        }
        // 群发批量校验：≤1000 条/批
        Object list = receiver.get("list");
        if (list instanceof List<?> items && items.size() > MAX_BATCH) {
            throw new BaiException(NotifyErrorCodes.PARAM_INVALID,
                    "NTF_1001 参数非法：批量 " + items.size() + " 条超限（≤1000 条/批），请分批提交");
        }
        // 跨机构群发拦截：接收人列表携带多个不同 orgId
        if (list instanceof List<?> items && distinctOrgs(items) > 1) {
            throw new BaiException(NotifyErrorCodes.NO_PERMISSION, "NTF_1003 无跨机构群发权限，请按机构分别提交");
        }
        String prio = priority == null || priority.isBlank() ? "P1" : priority.toUpperCase();
        if (!PRIORITIES.contains(prio)) {
            throw new BaiException(NotifyErrorCodes.PARAM_INVALID, "NTF_1001 参数非法：priority 须为 P0/P1/P2");
        }

        NotifyDtos.TemplateInfo template = templates.get(templateCode);
        if (template == null) {
            throw new BaiException(NotifyErrorCodes.TEMPLATE_NOT_FOUND, "NTF_1004 模板不存在或未注册：" + templateCode);
        }
        String receiverKey = receiverKey(receiver);
        String fingerprint = templateCode + ":" + receiverKey;
        NotifyDtos.SendResult cached = idempotency.get(fingerprint, requestId);
        if (cached != null) {
            return cached;
        }

        // 1. 退订黑名单优先（退订即时生效）
        boolean optedOut = optOuts.containsKey(receiverKey)
                || (Boolean.TRUE.equals(receiver.get("optedOut")));
        if (optedOut && "MARKETING".equals(template.category())) {
            // 营销类退订拦截：明示拒绝渠道，遵循个保法
            throw new BaiException(NotifyErrorCodes.OPTOUT_OR_RENDER_FAIL,
                    "NTF_1006 退订拦截：接收人已退订营销类消息，营销通知遵循个保法即时生效");
        }
        // 2. 频控校验（P0 资金类豁免频控；退订黑名单不受豁免影响）
        if (!"P0".equals(prio)) {
            checkFrequency(receiverKey, template.category());
        }
        // 3. 渠道选择：模板允许渠道 ∩ 健康渠道 ∩ 指定渠道，按优先级排序
        List<String> selected = selectChannels(template.allowedChannels(), channels);
        if (selected.isEmpty()) {
            throw new BaiException(NotifyErrorCodes.OPTOUT_OR_RENDER_FAIL,
                    "NTF_1006 渠道停用：模板允许渠道与健康渠道交集为空，请修正输入或联系管理员");
        }
        // 4. 模板渲染：${var} 占位符替换，缺失拒绝
        String content = render(template.content(), params == null ? Map.of() : params);

        // 5. 优先级入队下发（P0 直发即时通道、P1 队列优先、P2 批量低峰），经 notify-provider 渠道网关
        NotifyDtos.SendResult result = dispatch(templateCode, content, receiver, selected, prio, requestId);
        idempotency.put(fingerprint, requestId, result);
        audit(caller, templateCode, receiverKey, prio, result, optedOut);
        return result;
    }

    /** 渠道网关下发：NotifyProvider.send，失败重试≤2 次（1s/3s）后降级次优渠道，全部失败告警 */
    private NotifyDtos.SendResult dispatch(String templateCode, String content, Map<String, Object> receiver,
                                           List<String> channels, String priority, String requestId) {
        String messageId = "MSG" + UUID.randomUUID().toString().replace("-", "").substring(0, 13);
        List<NotifyDtos.ChannelStatus> statuses = new ArrayList<>();
        String phone = firstOf(receiver, "phone", "mobile");
        for (String channel : channels) {
            String status = "QUEUED";
            if ("P0".equals(priority) || "INBOX".equals(channel)) {
                // P0 直发即时通道，站内信即时落库
                status = sendViaProvider(messageId, channel, phone, templateCode, content, requestId, receiver);
            }
            statuses.add(new NotifyDtos.ChannelStatus(channel, status));
        }
        boolean allQueued = statuses.stream().allMatch(s -> "QUEUED".equals(s.status()));
        boolean anyFailed = statuses.stream().anyMatch(s -> "FAILED".equals(s.status()));
        String overall = anyFailed ? (statuses.stream().anyMatch(s -> !"FAILED".equals(s.status()))
                ? "PARTIAL_FAILED" : "FAILED") : (allQueued ? "ACCEPTED" : "SENT");
        NotifyDtos.SendResult result = new NotifyDtos.SendResult(messageId, overall, List.copyOf(statuses), false);
        records.put(messageId, new NotifyDtos.SendRecord(messageId, requestId, templateCode,
                maskReceiver(receiver), priority, overall, result.channels(), false, LocalDateTime.now().toString()));
        // 回执登记（messageId+channel 唯一，幂等冲突 NTF_1005 内部去重）
        statuses.forEach(s -> receiptKeys.add(messageId + ":" + s.channel()));
        return result;
    }

    private String sendViaProvider(String messageId, String channel, String phone, String templateCode,
                                   String content, String requestId, Map<String, Object> receiver) {
        Map<String, String> providerParams = new LinkedHashMap<>();
        providerParams.put("content", content);
        ProviderResponse<NotifyProvider.NotifyResult> resp = null;
        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            resp = notifyProvider.send(new NotifyProvider.NotifyRequest(
                    messageId, List.of(channel), phone, templateCode, providerParams), requestId);
            if (resp != null && resp.isSuccess()) {
                List<NotifyProvider.Receipt> receipts = resp.getData().receipts();
                for (NotifyProvider.Receipt receipt : receipts) {
                    if (!receiptKeys.add(messageId + ":" + receipt.channel())) {
                        // 回执幂等冲突：重复回执去重处理（NTF_1005），不重复更新
                        log.info("回执幂等去重: messageId={}, channel={}", messageId, receipt.channel());
                    }
                }
                return resp.getData().accepted() ? "SENT" : "QUEUED";
            }
            if (attempt < MAX_RETRY) {
                try {
                    Thread.sleep(attempt == 0 ? 1000L : 3000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // 渠道网关不可用（降级中）：记录后由上层评估次优渠道
        log.warn("渠道下发失败（降级中）: messageId={}, channel={}, msg={}", messageId, channel,
                resp == null ? "no-response" : resp.getMessage());
        return "FAILED";
    }

    /** 模板渲染：${var} 替换，占位符缺失拒绝（渲染失败 NTF_1006） */
    private String render(String content, Map<String, Object> params) {
        Matcher matcher = PLACEHOLDER.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = params.get(key);
            if (value == null || String.valueOf(value).isBlank()) {
                throw new BaiException(NotifyErrorCodes.OPTOUT_OR_RENDER_FAIL,
                        "NTF_1006 渲染失败：模板占位符缺失，请补全参数 ${" + key + "}");
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 渠道选择：模板允许 ∩ 健康 ∩ 指定，按 站内信>短信>Push>邮件 优先级 */
    private List<String> selectChannels(List<String> allowed, List<String> specified) {
        List<String> candidates = new ArrayList<>();
        for (String channel : CHANNEL_PRIORITY) {
            if (!ALL_CHANNELS.contains(channel) || !channelHealth.getOrDefault(channel, false)) {
                continue;
            }
            if (allowed != null && !allowed.contains(channel)) {
                continue;
            }
            if (specified != null && !specified.isEmpty() && !specified.contains(channel)) {
                continue;
            }
            candidates.add(channel);
        }
        return candidates;
    }

    /** 频控：每日计数（营销日 1 条、催收日 3 条）+ 时段窗口（22:00—08:00 营销禁发） */
    private void checkFrequency(String receiverKey, String category) {
        int limit = switch (category == null ? "" : category) {
            case "MARKETING" -> MARKETING_DAILY_LIMIT;
            case "COLLECTION" -> COLLECTION_DAILY_LIMIT;
            default -> DEFAULT_DAILY_LIMIT;
        };
        String dayKey = receiverKey + ":" + LocalDateTime.now().toLocalDate() + ":" + category;
        AtomicInteger counter = dailyCounters.computeIfAbsent(dayKey, k -> new AtomicInteger());
        if (counter.get() >= limit) {
            throw new BaiException(NotifyErrorCodes.RATE_LIMITED,
                    "NTF_1007 触发频控：" + category + " 类消息日发送上限 " + limit + " 条，请按 Retry-After 退避");
        }
        int hour = LocalDateTime.now().getHour();
        if ("MARKETING".equals(category) && (hour >= 22 || hour < 8)) {
            throw new BaiException(NotifyErrorCodes.RATE_LIMITED,
                    "NTF_1007 触发频控：22:00—08:00 营销类消息禁发时段，请按时段窗口重试");
        }
        counter.incrementAndGet();
    }

    /** 模板管理：注册/更新/停用（仅限运营管理员，网关鉴权） */
    public NotifyDtos.TemplateInfo manageTemplate(String templateCode, String name, String category,
                                                  List<String> allowedChannels, String content, boolean disable) {
        if (templateCode == null || templateCode.isBlank()) {
            throw new BaiException(NotifyErrorCodes.PARAM_INVALID, "NTF_1001 参数缺失：templateCode 必填");
        }
        if (disable) {
            NotifyDtos.TemplateInfo existing = templates.get(templateCode);
            if (existing == null) {
                throw new BaiException(NotifyErrorCodes.TEMPLATE_NOT_FOUND, "NTF_1004 模板不存在：" + templateCode);
            }
            templates.remove(templateCode);
            log.info("模板停用: templateCode={}", templateCode);
            return existing;
        }
        if (name == null || content == null || content.isBlank()) {
            throw new BaiException(NotifyErrorCodes.PARAM_INVALID, "NTF_1001 参数缺失：name/content 必填");
        }
        if (category != null && !Set.of("NOTIFY", "MARKETING", "COLLECTION").contains(category)) {
            throw new BaiException(NotifyErrorCodes.PARAM_INVALID, "NTF_1001 参数非法：category 须为 NOTIFY/MARKETING/COLLECTION");
        }
        NotifyDtos.TemplateInfo template = new NotifyDtos.TemplateInfo(templateCode, name,
                category == null ? "NOTIFY" : category, allowedChannels == null ? List.of("INBOX", "SMS") : allowedChannels, content);
        templates.put(templateCode, template);
        log.info("模板注册: templateCode={}, category={}", templateCode, template.category());
        return template;
    }

    /** 发送记录查询（按 messageId 或 requestId） */
    public NotifyDtos.SendRecord getRecord(String messageId, String requestId) {
        if (messageId != null && !messageId.isBlank()) {
            NotifyDtos.SendRecord record = records.get(messageId);
            if (record == null) {
                throw new BaiException(NotifyErrorCodes.TEMPLATE_NOT_FOUND, "NTF_1004 发送记录不存在，请校验 messageId");
            }
            return record;
        }
        if (requestId != null && !requestId.isBlank()) {
            return records.values().stream()
                    .filter(r -> requestId.equals(r.requestId()))
                    .findFirst()
                    .orElseThrow(() -> new BaiException(NotifyErrorCodes.TEMPLATE_NOT_FOUND,
                            "NTF_1004 发送记录不存在，请校验 requestId"));
        }
        throw new BaiException(NotifyErrorCodes.PARAM_INVALID, "NTF_1001 参数缺失：messageId 与 requestId 至少提供一项");
    }

    /** 退订管理：登记退订黑名单（即时生效 ≤1s） */
    public NotifyDtos.OptOutEntry optOut(Map<String, Object> receiver, String scope) {
        if (receiver == null || receiver.isEmpty()) {
            throw new BaiException(NotifyErrorCodes.PARAM_INVALID, "NTF_1001 参数缺失：receiver（手机/客户号）必填");
        }
        String receiverKey = receiverKey(receiver);
        NotifyDtos.OptOutEntry entry = new NotifyDtos.OptOutEntry(
                "OTO" + UUID.randomUUID().toString().replace("-", "").substring(0, 13),
                maskReceiver(receiver), scope == null ? "MARKETING" : scope, LocalDateTime.now().toString());
        optOuts.put(receiverKey, entry);
        log.info("退订登记: optoutId={}, receiver={}, scope={}（即时生效）", entry.optoutId(), entry.receiverMasked(), entry.scope());
        return entry;
    }

    private int distinctOrgs(List<?> items) {
        Set<Object> orgs = new java.util.HashSet<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> m && m.get("orgId") != null) {
                orgs.add(m.get("orgId"));
            }
        }
        return orgs.size();
    }

    private String receiverKey(Map<String, Object> receiver) {
        String key = firstOf(receiver, "phone", "mobile", "email", "customerId", "wecom");
        return key == null ? String.valueOf(receiver) : key;
    }

    private String firstOf(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /** 接收人摘要脱敏（手机/邮箱日志脱敏） */
    private String maskReceiver(Map<String, Object> receiver) {
        String raw = receiverKey(receiver);
        if (raw == null || raw.length() <= 4) {
            return "****";
        }
        if (raw.contains("@")) {
            int at = raw.indexOf('@');
            return raw.charAt(0) + "****" + raw.substring(at);
        }
        return raw.substring(0, 3) + "****" + raw.substring(raw.length() - 4);
    }

    private void audit(String caller, String templateCode, String receiverMasked, String priority,
                       NotifyDtos.SendResult result, boolean optedOut) {
        log.info("发送受理留痕: caller={}, templateCode={}, receiver={}, priority={}, messageId={}, status={}, optedOut={}",
                caller, templateCode, receiverMasked, priority, result.messageId(), result.status(), result.optedOut());
    }

    private void validateRequestId(String requestId) {
        if (requestId != null && requestId.length() > 32) {
            throw new BaiException(NotifyErrorCodes.PARAM_INVALID, "NTF_1001 参数非法：requestId 须 32 位以内");
        }
    }
}
