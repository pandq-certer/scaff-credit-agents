package com.bai.screening;

import java.util.List;

/**
 * 名单筛查输出统一结构（提示词库 A-50：ScreenResult/ListHit/ListEntry/FeedbackResult）
 */
public final class ScreeningDtos {

    private ScreeningDtos() {}

    /** 命中明细 */
    public record ListHit(String listType, String listName, String listLevel, String matchMode,
                          double score, String subjectMatched, String detail) {}

    /** 筛查结果 */
    public record ScreenResult(String screenId, Object subject, List<ListHit> hits, String conclusion,
                               boolean escalated, int elapsedMs) {}

    /** 名单条目 */
    public record ListEntry(String listEntryId, String listType, String listName, String subjectName,
                            String idNo, String pinyin, String level, String status) {}

    /** 人工反馈回流结果 */
    public record FeedbackResult(String screenId, boolean accepted, String message) {}
}
