package com.bai.eventsubscribe;

import java.util.List;
import java.util.Map;

/**
 * 事件订阅输出统一结构（提示词库 A-33：PublishResult/SubscribeResult/TypeResult/SubscriptionInfo）
 * <p>发布不丢失（生产 ack+消费重试）；死信保留 90 天可重投；留痕保留≥3 年
 */
public final class EventSubscribeDtos {

    private EventSubscribeDtos() {}

    /** 事件发布受理结果 */
    public record PublishResult(String eventId, String status, int matchedSubscriptions, int dispatched) {}

    /** 订阅管理结果（CREATE/CANCEL） */
    public record SubscribeResult(String subscriptionId, String eventType, String action, String status, String createdAt) {}

    /** 事件类型注册结果 */
    public record TypeResult(String name, String status, List<String> requiredFields, String createdAt) {}

    /** 订阅条目（事件类型+条件过滤+白名单端点） */
    public record SubscriptionInfo(String subscriptionId, String subscriberId, String eventType,
                                   Map<String, Object> condition, Map<String, Object> endpoint,
                                   String status, String createdAt) {}

    /** 订阅查询结果 */
    public record SubscriptionQuery(int total, List<SubscriptionInfo> subscriptions) {}

    /** 分发记录（留痕：发布→分发→ack 全链，内存仿真） */
    public record DispatchRecord(String eventId, String subscriptionId, String endpoint,
                                 String status, int attempts, String deadLetter, String dispatchedAt) {}
}
