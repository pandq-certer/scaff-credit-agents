package com.bai.report;

import java.util.List;
import java.util.Map;

/**
 * 报表生成输出统一结构（BIA-DTA-023：GenerateResult）
 * <p>处理链：模板解析 → 口径解析（关联指标清单）→ 数据绑定（仿真，用于联调：调不到真实
 * data-query/metric，直接确定性生成绑定数据）→ 渲染（仿真生成 fileId=OBJ+日期+序号）
 * → 三校验 checks{caliber,required,balance}（勾稽不平可用 flag=UNBALANCE 仿真触发 RPG_1006）→ 输出
 */
public final class ReportGenDtos {

    private ReportGenDtos() {}

    /** generate 请求体（params.orgId/date/granularity 必填；format 缺省 EXCEL） */
    public record GenerateRequest(String templateId, Map<String, Object> params, String format) {}

    /** 三校验（caliber 口径/required 必填/balance 勾稽，PASS/FAIL） */
    public record Checks(String caliber, String required, String balance) {}

    /** 生成结果（submissionId 报送登记前为 null） */
    public record GenerateResult(String reportId, String templateId, String templateVersion, Map<String, Object> params,
                                 String format, String fileId, Checks checks, String submissionId, long elapsedMs) {}

    /** 报送登记请求体 */
    public record SubmissionRequest(String reportId, String channel) {}

    /** 报送登记结果（内存模拟，重报同 reportId 不新增） */
    public record SubmissionResult(String submissionId, String reportId, String channel, String status, long elapsedMs) {}

    /** batch 请求体（≤200 张） */
    public record BatchRequest(List<GenerateRequest> items) {}

    /** batch 受理结果 */
    public record BatchResult(String batchId, String status, int total) {}

    /** 模板定义（GET /templates） */
    public record TemplateInfo(String templateId, String templateName, String version, String status,
                               String period, List<String> metrics) {}

    /** 模板库（GET /templates） */
    public record TemplatesResult(String version, List<TemplateInfo> templates) {}
}
