package com.bai.docparse;

import java.util.List;

/**
 * 文档解析内容抽取提供者接口：抽象"图像类/扫描件真实内容获取"能力。
 * <p>doc-parse 自身的 Word/Excel/PDF 正文抽取为本地 POI/PDFBox 确定性实现（非 AI 仿真），无需抽象；
 * 依赖外部引擎的是两类：图像类文件（jpg/jpeg/png/tif/tiff）的文字识别，以及无文本层的扫描 PDF。
 * mock 模式返回 null（走确定性仿真兜底，行为与历史版本一致）；
 * real 模式图像走 SYS-01 OCR 服务 8101 /v1/ocr，扫描 PDF 走 SYS-01 解析服务 8102 /v1/parse，
 * 正文喂给现有 classifyReal 分类与 FIELD_PATTERNS KV 正则白名单真实抽取链路（抽不到置低置信，不伪造值）。
 */
public interface DocParseProvider {

    /**
     * 图像类内容 OCR 抽取。
     *
     * @param content   图像字节（jpg/jpeg/png/tif/tiff）
     * @param filename  原始文件名（含扩展名，供网关识别格式）
     * @param requestId 幂等/追踪键
     * @return 按 block 顺序拼接的正文文本（\n 分隔）；无文字返回空串；mock 模式返回 null（仿真兜底）
     * @throws com.bai.common.BaiException real 模式网关不可达/超时 → DCP_2001；网关业务错误码原样透传
     */
    String ocrImage(byte[] content, String filename, String requestId);

    /** 扫描 PDF 解析结果：阅读顺序正文 + 表格 + 页数 */
    record PdfContent(String text, List<DocParseDtos.Table> tables, int pages) {}

    /**
     * 无文本层 PDF（扫描件）内容抽取。
     * <p>默认返回 null：mock 及未实现该能力的提供者保持既有仿真兜底行为。
     */
    default PdfContent parsePdf(byte[] content, String filename, String requestId) {
        return null;
    }
}
