package com.bai.creditreport;

import java.util.List;

/**
 * 征信管理输出统一结构（提示词库 A-52）。
 * <p>授权三重校验：有效期（默认 365 天）+ 用途白名单 + 客户要素一致；
 * 无有效授权一律拒绝查询；异议 20 日时限强制跟踪；报告要素脱敏展示（sensitive-mask-agent 联动）。
 */
public final class CreditReportDtos {

    private CreditReportDtos() {}

    /** 客户主体：证件/姓名 */
    public record Customer(String certNo, String name) {}

    /** 授权书影像/要素：docNo + 客户要素（与 customer 做一致性校验） */
    public record AuthDoc(String docNo, String certNo, String name, String signedAt) {}

    /** 授权管理入参：action=CREATE/VALIDATE/RENEW/EXPIRE；purpose 须在白名单；requestId body 优先、header 兜底 */
    public record AuthorizeRequest(String action, String authId, Customer customer, AuthDoc authDoc,
                                   String purpose, Integer validDays, String requestId) {}

    /** 授权管理结果：authStatus=ACTIVE/EXPIRED/REVOKED/INVALID */
    public record AuthorizeResult(String authId, String authStatus, boolean purposeValid,
                                  String effectiveFrom, String effectiveTo, int validDays,
                                  String warning, long elapsedMs) {}

    /** 同步报告查询入参：凭有效 authId 查询；requestId body 优先、header 兜底 */
    public record ReportQueryRequest(String authId, String reportType, String requestId) {}

    /** 异步报告生成/解析入参；requestId body 优先、header 兜底 */
    public record ReportTaskRequest(String authId, String reportType, String requestId) {}

    /** 异议受理入参（异步）；requestId body 优先、header 兜底 */
    public record DisputeRequest(String authId, Customer customer, String content, List<String> items, String requestId) {}

    /** 逾期要素 */
    public record Overdue(int max30, int max90, int currentOverdue) {}

    /** 负债要素 */
    public record Liability(Long total, int creditCards, int loans) {}

    /** 查询记录要素 */
    public record Queries(int last3Month) {}

    /** 报告结构化要素（报文组解结果，脱敏展示） */
    public record ReportResult(String authId, String reportNo, Overdue overdue, Liability liability,
                               Queries queries, String sign, long elapsedMs) {}

    /** 异步报告任务 */
    public record ReportTask(String taskId, String status, ReportResult report) {}

    /** 异议受理结果：20 日反馈时限跟踪 */
    public record DisputeResult(String disputeId, String status, String feedbackDueDate,
                                int limitDays, String warning, long elapsedMs) {}
}
