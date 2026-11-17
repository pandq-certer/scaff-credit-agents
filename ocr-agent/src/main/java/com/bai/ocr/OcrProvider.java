package com.bai.ocr;

import java.util.Map;

/**
 * OCR 引擎提供者抽象：隔离「引擎内核」与「服务编排」。
 * <p>mock = {@link MockOcrProvider} 确定性仿真（联调/回归专用，行为与仿真版完全一致）；
 * real = {@link RealOcrProvider} 统一 AI 推理网关（PaddleOCR 内核，HTTP 调用，数据不出行）。
 * <p>切换开关 {@code bai.ocr.provider=mock|real}，默认 mock。
 */
public interface OcrProvider {

    /**
     * 文件识别：图像/PDF 字节 → 识别结果。docType 可空，缺省语义由实现决定（mock=GENERIC，real=AUTO）。
     * <p>前置的文件大小/格式校验由服务层完成，实现只负责引擎识别。
     */
    OcrDtos.OcrResult recognize(byte[] content, String filename, String docType, String requestId);

    /**
     * imageRef 直连识别：业务侧影像标识 → 原始字段表与整体置信度。
     * <p>脱敏/字段白名单/docId 生成由服务层处理；返回的 fields 必须为可变 Map（服务层原地进行掩码/过滤）。
     */
    RefResult recognizeRef(String imageRef, String docType, String requestId);

    /** 直连识别原始结果（未脱敏） */
    record RefResult(Map<String, String> fields, double confidence) {}
}
