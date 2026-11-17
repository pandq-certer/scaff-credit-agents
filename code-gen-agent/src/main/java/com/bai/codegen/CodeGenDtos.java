package com.bai.codegen;

import java.util.List;

/**
 * 代码生成输出统一结构（提示词库 F-3：GenerateResult）
 * <p>安全扫描 HIGH 零容忍：passed=false 时 code 不返回；语法校验零错误；模板占位符 {{var}}
 */
public final class CodeGenDtos {

    private CodeGenDtos() {}

    /** 语法校验结果 */
    public record SyntaxCheck(boolean passed, List<String> issues) {}

    /** 安全扫描结果（high&gt;0 时 passed=false，code 不予返回） */
    public record SecurityScan(boolean passed, int high, List<String> warnings) {}

    /** 代码评审建议 */
    public record Review(double score, List<String> suggestions) {}

    /** 生成结果（model=code-model-v2 确定性模板模式） */
    public record GenerateResult(String genId, String codeType, String code, String language,
                                 SyntaxCheck syntaxCheck, SecurityScan securityScan, Review review, String model) {}

    /** 模板变量定义 */
    public record Variable(String name, String type, boolean required) {}

    /** 代码模板信息（{{var}} 占位符，PUBLISHED 后不可变） */
    public record TemplateInfo(String templateId, String name, String codeType, String language, String content,
                               List<Variable> variables, int version, String status, String updatedAt) {}

    /** 模板管理结果 */
    public record TemplateManageResult(String templateId, int version, String status) {}

    /** 模板列表 */
    public record TemplateList(int total, List<TemplateInfo> templates) {}

    /** 生成历史记录（append-only 留痕保留≥3 年） */
    public record HistoryRecord(String genId, String bizKey, String codeType, String language, String requirement,
                                String code, SyntaxCheck syntaxCheck, SecurityScan securityScan, Review review,
                                String model, Integer score, String createdAt) {}

    /** 历史列表 */
    public record HistoryResult(int total, List<HistoryRecord> records) {}
}
