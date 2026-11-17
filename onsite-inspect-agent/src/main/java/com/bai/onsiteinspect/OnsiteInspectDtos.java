package com.bai.onsiteinspect;

import java.util.List;

/**
 * 现场尽调输出统一结构（设计文档 4.2-4.6 响应参数表）
 */
public final class OnsiteInspectDtos {

    private OnsiteInspectDtos() {}

    /** 调查提纲（提纲引用+要点，4.2 响应 outline） */
    public record Outline(String outlineRef, String version, List<String> points) {}

    /** 下户任务视图（4.2 响应：taskId/outline/status/inspectorsChecked） */
    public record TaskView(String taskId, String customerId, String loanNo, String inspectType,
                           List<String> inspectors, String status, boolean inspectorsChecked,
                           Outline outline, String createdAt) {}

    /** 查勘清单项（观察点/必采/指引，4.3 响应 items[]） */
    public record ChecklistItem(String code, String name, boolean required, String guide) {}

    /** 查勘清单（4.3 响应：items/version/outlineRef） */
    public record ChecklistResult(String version, String outlineRef, List<ChecklistItem> items) {}

    /** 现场证据采集结果（4.4 响应：evidenceId/indexed/quality/consistency） */
    public record EvidenceResult(String evidenceId, String taskId, boolean indexed, String quality,
                                 boolean consistency) {}

    /** 访谈采集结果（4.5 响应：interviewId/recorded/transcriptRef/notes） */
    public record InterviewResult(String interviewId, String taskId, String layer, boolean recorded,
                                  String transcriptRef, String notes) {}

    /** 现场异常（类型 AB01-AB06/等级 提示-警告-严重，7.2 异常识别规则） */
    public record Abnormal(String type, String level, String description) {}

    /** 现场结论结果（4.6 响应：summaryId/conclusion/materialRef/abnormals） */
    public record SummaryResult(String summaryId, String taskId, String conclusion, String materialRef,
                                List<Abnormal> abnormals, String templateVersion) {}
}
