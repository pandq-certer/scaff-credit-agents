package com.bai.notify;

import java.util.List;

/**
 * 消息通知输出统一结构（提示词库 A-30：SendResult）
 * <p>接收人手机/邮箱日志脱敏；回执幂等（messageId+channel 唯一）
 */
public final class NotifyDtos {

    private NotifyDtos() {}

    /** 发送受理结果 */
    public record SendResult(String messageId, String status, List<ChannelStatus> channels, boolean optedOut) {}

    /** 渠道下发状态 */
    public record ChannelStatus(String channel, String status) {}

    /** 模板注册信息 */
    public record TemplateInfo(String templateCode, String name, String category,
                               List<String> allowedChannels, String content) {}

    /** 发送记录（接收人摘要脱敏，全量留痕 append-only 保留≥3 年） */
    public record SendRecord(String messageId, String requestId, String templateCode,
                             String receiverMasked, String priority, String status,
                             List<ChannelStatus> channels, boolean optedOut, String createdAt) {}

    /** 退订登记 */
    public record OptOutEntry(String optoutId, String receiverMasked, String scope, String createdAt) {}
}
