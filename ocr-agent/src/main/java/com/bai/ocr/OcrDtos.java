package com.bai.ocr;

import java.util.List;
import java.util.Map;

/**
 * OCR 输出统一结构（提示词库 A-01：OcrResult）
 * <p>bbox 一律为相对原图比例 0-1；低置信字段标记 REJECT 并给出原因（BLUR/TILT/TRUNCATED/LOW_CONF），不得静默输出
 */
public final class OcrDtos {

    private OcrDtos() {}

    /** 文本块：整行/整段识别结果 */
    public record Block(String text, double confidence, List<Double> bbox, String flag, String flagReason) {}

    /** 字段级 KV（证件/票据结构化） */
    public record Field(String value, double confidence, String flag, String flagReason) {}

    /** 识别结果 */
    public record OcrResult(String ocrId, String docType, String status, double qualityScore,
                            List<Block> blocks, Map<String, Field> fields, long elapsedMs) {}

    /** 异步任务 */
    public record OcrTask(String taskId, String status, OcrResult result) {}

    /** JSON 直连识别结果（演示影像标识 imageRef → 确定性演示字段表） */
    public record JsonRecognizeResult(String docId, String docType, Map<String, String> fields,
                                      double confidence, boolean maskApplied, long elapsedMs) {}
}
