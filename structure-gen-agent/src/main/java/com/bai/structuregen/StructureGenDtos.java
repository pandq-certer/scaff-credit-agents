package com.bai.structuregen;

import java.util.List;

/**
 * 结构化生成输出统一结构（提示词库 A-36：SchemaInfo/GenerateResult/ValidationResult/HistoryItem）
 */
public final class StructureGenDtos {

    private StructureGenDtos() {}

    /** Schema 概要（字段清单按注册顺序） */
    public record SchemaInfo(String schemaId, String name, String version, String status, List<String> fields) {}

    /** 字段级校验结果 */
    public record ValidationResult(boolean passed, List<String> issues) {}

    /** 结构化生成结果 */
    public record GenerateResult(String genId, String outputType, Object result, ValidationResult validation,
                                 int entityMapped, String model) {}

    /** 生成历史条目 */
    public record HistoryItem(String genId, String requestId, String schemaId, String outputType,
                              boolean passed, int entityMapped, String createdAt) {}
}
