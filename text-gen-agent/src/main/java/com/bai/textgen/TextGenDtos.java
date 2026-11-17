package com.bai.textgen;

import java.util.List;

/**
 * 文本生成输出统一结构（提示词库 A-35：GenerateResult）
 * <p>RED 红线零容忍：命中不得返回文本；facts 事实注入禁止改写；长度偏差 ±10%
 */
public final class TextGenDtos {

    private TextGenDtos() {}

    /** 生成结果（compliance.passed=false 时 text 不返回） */
    public record GenerateResult(String text, String genId, String mode, Compliance compliance,
                                 int length, String model) {}

    /** 合规检查结果 */
    public record Compliance(boolean passed, List<String> riskWords) {}

    /** 模板注册信息（{{var}} 占位符） */
    public record TemplateInfo(String templateId, String name, String scene, String content, String createdAt) {}

    /** 生成历史记录（全量留痕 append-only 保留≥3 年） */
    public record GenRecord(String genId, String requestId, String mode, String style, String templateId,
                            String text, int length, boolean passed, String createdAt) {}

    /** 模板列表 */
    public record TemplateList(int total, List<TemplateInfo> items) {}

    /** 历史列表 */
    public record HistoryResult(int total, List<GenRecord> items) {}
}
