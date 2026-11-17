package com.bai.dialog;

import java.util.List;
import java.util.Map;

/**
 * 对话管理输出统一结构（提示词库 A-39：DialogReply）
 * <p>action.type 必须取自五种策略动作 ANSWER/CONFIRM/CLARIFY/COLLECT/HANDOFF；reply 必须通过合规检查
 */
public final class DialogDtos {

    private DialogDtos() {}

    /** 对话回复 */
    public record DialogReply(String sessionId, String reply, String intent, Map<String, Object> slots,
                              Action action, Handoff handoff, long elapsedMs) {}

    /** 策略动作（type：ANSWER/CONFIRM/CLARIFY/COLLECT/HANDOFF） */
    public record Action(String type, Map<String, Object> detail) {}

    /** 转人工标记 */
    public record Handoff(boolean required, String reason) {}

    /** 会话信息（列表查询） */
    public record SessionInfo(String sessionId, String userId, String channel, String status,
                              String intent, Map<String, Object> slots, String createdAt, String updatedAt) {}

    /** 对话历史条目（滑动窗口最近 20 轮 + 状态快照） */
    public record HistoryItem(String role, String content, String intent, Map<String, Object> slots, String at) {}

    /** 对话历史响应 */
    public record DialogHistory(String sessionId, String userId, String status, List<HistoryItem> messages) {}
}
