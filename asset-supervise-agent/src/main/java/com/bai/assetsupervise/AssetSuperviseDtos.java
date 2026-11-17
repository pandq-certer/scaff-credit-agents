package com.bai.assetsupervise;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 押品监管输入/输出结构（提示词库 A-53，设计文档 4.2~4.7 接口字段表逐条对齐）
 */
public final class AssetSuperviseDtos {

    private AssetSuperviseDtos() {}

    // ------------------------------------------------------------------ 4.2 监管对象登记

    /** 登记入参：action=CREATE|UPDATE|TERMINATE；objectType=REAL_ESTATE|MOVABLE|WAREHOUSE_RECEIPT|REGULATED_WAREHOUSE */
    public record ObjectRequest(String objectId, String action, String objectType, String collateralId,
                                String loanNo, Map<String, Object> details, String requestId) {}

    /** 登记结果：status=ACTIVE|CHANGED|TERMINATED，registerNo 登记流水号 */
    public record ObjectResult(String code, String message, String objectId, String status, String registerNo) {}

    /** 监管对象台账视图（query type=OBJECT 条目） */
    public record SupervisedObjectView(String objectId, String objectType, String collateralId, String loanNo,
                                       String status, String registerNo, String updatedAt) {}

    // ------------------------------------------------------------------ 4.3 监测数据采集

    /** 采集入参：dataSource=IOT|WAREHOUSE|REGISTRY|MANUAL；metrics 指标编码/值/时间 */
    public record CollectMetric(String code, BigDecimal value, String time) {}

    public record CollectRequest(String objectId, String dataSource, List<CollectMetric> metrics,
                                 String rawRef, String requestId) {}

    /** 质量报告：总条数/有效条数/异常占比/问题明细 */
    public record QualityReport(int total, int valid, BigDecimal abnormalRate, List<String> issues) {}

    /** 采集结果：accepted 接收条数；disconnected 断连标记（REGULATORY_LOSS） */
    public record CollectResult(String collectNo, String objectId, int accepted, QualityReport quality,
                                String disconnected) {}

    // ------------------------------------------------------------------ 4.4 实时监控

    /** 监控入参：monitorType=MOVABLE|REAL_ESTATE */
    public record MonitorRequest(String objectId, String monitorType, String period, String ruleVersion,
                                 String requestId) {}

    /** 异常明细：类型/指标/偏差/证据/建议 */
    public record Abnormal(String type, String metric, BigDecimal deviation, String evidence, String suggestion) {}

    /** 监控结果：conclusion=NORMAL|ABNORMAL */
    public record MonitorResult(String monitorNo, String objectId, String conclusion, List<Abnormal> abnormals) {}

    // ------------------------------------------------------------------ 4.5 质押率盯市

    /** 盯市分档线（可显式覆盖，缺省按押品类型：动产 0.7/0.8、不动产 0.75/0.85、权利 0.8/0.9） */
    public record LtvLines(BigDecimal warningLine, BigDecimal disposalLine) {}

    /** 盯市入参：valuation 实时估值、loanBalance 融资余额、frequency=DAILY|MONTHLY|EVENT、timestamp 幂等键 */
    public record LtvRequest(String objectId, BigDecimal valuation, BigDecimal loanBalance, LtvLines lines,
                             String frequency, String timestamp, String requestId) {}

    /** 盯市结果：status=NORMAL|WARNING|DISPOSAL，trend=NONE|UP|DOWN|FLAT */
    public record LtvResult(String ltvNo, String objectId, BigDecimal ltv, BigDecimal valuation, BigDecimal loanBalance,
                            String status, String trend, BigDecimal warningLine, BigDecimal disposalLine,
                            String frequency, String timestamp) {}

    // ------------------------------------------------------------------ 4.6 预警触发与处置

    /** 预警入参：alertType=VALUE_BREACH|QUANTITY_ABNORMAL|REGULATORY_LOSS|LTV_OVER|STATUS_CHANGE */
    public record AlertRequest(String objectId, String alertType, String evidence, String strategyVersion,
                               String requestId) {}

    /** 处置任务：taskId/taskType/责任方/时限/状态 */
    public record AlertTask(String taskId, String taskType, String assignee, String deadline, String status) {}

    /** 预警结果：level=INFO|WARNING|SEVERE，tasks 处置任务清单 */
    public record AlertResult(String alertNo, String alertId, String objectId, String alertType, String level,
                              List<AlertTask> tasks, String evidence, String strategyVersion) {}

    // ------------------------------------------------------------------ 4.7 押品重估联动

    /** 重估入参：triggerType=PERIODIC|MARKET_CHANGE|ALERT|REQUIRED；valuationRequest 含新估值（valuation） */
    public record RevalueRequest(String objectId, String triggerType, Map<String, Object> valuationRequest,
                                 String requestId) {}

    /** 重估结果：revalueTaskId、valuationResult 重估结果、benchmarkUpdated 是否回流盯市基准 */
    public record RevalueResult(String revalueNo, String revalueTaskId, String objectId, String triggerType,
                                BigDecimal valuationResult, boolean benchmarkUpdated, String message) {}

    // ------------------------------------------------------------------ 查询

    /** 查询结果：type=OBJECT|MONITOR|LTV|ALERT 分页返回 items */
    public record QueryResult(int total, int page, int pageSize, List<Object> items) {}
}
