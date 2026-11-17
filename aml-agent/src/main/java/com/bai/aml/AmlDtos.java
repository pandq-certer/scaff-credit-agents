package com.bai.aml;

import java.util.List;
import java.util.Map;

/**
 * 反洗钱输出统一结构（提示词库 A-51：MonitorResult/ReportResult/FeedbackResult/MonitorRecord）
 * <p>评分≥75 判可疑并建议上报 SAR、60-75 存疑转人工；大额交易零漏报；审计 append-only 保留≥5 年
 */
public final class AmlDtos {

    private AmlDtos() {}

    /** 触发明细（规则命中依据） */
    public record Trigger(String ruleId, String ruleType, String threshold, String actual, String detail) {}

    /** 窗口监测结果 */
    public record MonitorResult(String monitorId, String window, String scene, List<Trigger> triggers,
                                double modelScore, String riskLevel, boolean suspicious,
                                boolean reportRequired, boolean escalated, int txnCount, double totalAmount,
                                int elapsedMs) {}

    /** 报告（SAR/大额）生成结果（草稿，人工复核后上报） */
    public record ReportResult(String reportId, String monitorId, String reportType, String status,
                               String summary, String createdAt) {}

    /** 人工结论回流结果 */
    public record FeedbackResult(String monitorId, String conclusion, String status, String feedbackAt) {}

    /** 监测记录查询条目 */
    public record MonitorRecord(String monitorId, String requestId, String window, String scene,
                                String riskLevel, double modelScore, boolean suspicious,
                                boolean reportRequired, boolean escalated, String feedback,
                                List<Trigger> triggers, String createdAt) {}
}
