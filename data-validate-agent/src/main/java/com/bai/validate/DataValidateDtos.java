package com.bai.validate;

import java.util.List;
import java.util.Map;

/**
 * 数据校验输出统一结构（BIA-DTA-021：ValidateResult）
 * <p>模板与规则库内置（确定性仿真，用于联调）；规则覆盖必填/格式/范围/枚举/一致性/完整性/阈值/跨系统八类算子；
 * 任一 ERROR → FAIL，仅 WARN → WARN，否则 PASS；幂等键=requestId+数据 SHA-256 摘要
 */
public final class DataValidateDtos {

    private DataValidateDtos() {}

    /** 校验上下文（orgId=机构；target=跨系统比对对端，非空时触发跨系统仿真通道） */
    public record Context(String orgId, String target) {}

    /** validate 请求体（templateId 与 rules 二选一；data 必填，序列化≤512KB） */
    public record ValidateRequest(String templateId, List<String> rules, Map<String, Object> data, Context context) {}

    /** 校验明细（level：ERROR/WARN；actual=实际值） */
    public record Detail(String ruleId, String ruleName, String field, String level, String message, Object actual) {}

    /** 汇总（passed/warned/failed 计数） */
    public record Summary(int passed, int warned, int failed) {}

    /** 校验结果 */
    public record ValidateResult(String validateId, String templateId, String result, Summary summary,
                                 List<Detail> details, boolean crossSystemSkipped, long elapsedMs) {}

    /** batch 请求体（≤5000 条） */
    public record BatchRequest(List<ValidateRequest> items) {}

    /** batch 受理结果 */
    public record BatchResult(String batchId, String status, int total) {}

    /** 规则定义（GET /rules） */
    public record RuleDef(String ruleId, String ruleName, String operator, String field, String level,
                          String message, Object param) {}

    /** 规则库（GET /rules） */
    public record RulesResult(String version, List<RuleDef> rules) {}

    /** 模板定义（GET /templates） */
    public record TemplateDef(String templateId, String templateName, String version, String status, List<String> ruleIds) {}

    /** 模板库（GET /templates） */
    public record TemplatesResult(String version, List<TemplateDef> templates) {}
}
