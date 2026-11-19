package com.bai.docparse;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DocParseService 单元测试（感知类）：JSON 通道解析（fileName 确定性仿真）、格式校验、Schema 版本、幂等。
 */
class DocParseServiceTest {

    private final DocParseService service = new DocParseService();

    @Test
    void 解析pdf_返回docType与KV() {
        DocParseDtos.ParseResult r = service.parseJson(
                Map.of("fileName", "合同.pdf", "docType", "CONTRACT", "schemaVersion", "1.0"), "dp-1");
        assertNotNull(r.docId());
        assertNotNull(r.docType());
        assertTrue(r.pages() >= 1);
    }

    @Test
    void 解析xlsx_识别表格文档() {
        DocParseDtos.ParseResult r = service.parseJson(
                Map.of("fileName", "流水.xlsx", "docType", "BANK_STATEMENT"), "dp-2");
        assertNotNull(r.docId());
    }

    @Test
    void 缺fileName_抛DCP1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.parseJson(Map.of(), "dp-x"));
        assertEquals("DCP_1001", e.getCode());
    }

    @Test
    void 不支持的扩展名_抛DCP1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.parseJson(Map.of("fileName", "文档.exe"), "dp-y"));
        assertEquals("DCP_1006", e.getCode());
    }

    @Test
    void 非法Schema版本_抛DCP1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.parseJson(Map.of("fileName", "合同.pdf", "schemaVersion", "9.9"), "dp-z"));
        assertEquals("DCP_1004", e.getCode());
    }

    @Test
    void 加密文件_抛DCP1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.parseJson(Map.of("fileName", "报告_加密.pdf"), "dp-e"));
        assertEquals("DCP_1006", e.getCode());
    }

    @Test
    void 同requestId_幂等返回首次结果() {
        Map<String, Object> body = Map.of("fileName", "合同.pdf", "docType", "CONTRACT");
        DocParseDtos.ParseResult a = service.parseJson(body, "dp-idem");
        DocParseDtos.ParseResult b = service.parseJson(body, "dp-idem");
        assertEquals(a.docId(), b.docId());
    }

    @Test
    void schema查询_非空() {
        assertFalse(service.schemas().isEmpty());
    }

    @Test
    void 结构化图像OCR_版面页数docType与KV真实还原() {
        List<DocParseProvider.OcrBlock> blocks = List.of(
                new DocParseProvider.OcrBlock(1, "个人经营性贷款申请表", 0.999, 0.34, 0.07, 0.65, 0.09),
                new DocParseProvider.OcrBlock(1, "申请人姓名", 0.99, 0.09, 0.12, 0.17, 0.14),
                new DocParseProvider.OcrBlock(1, "张*", 0.995, 0.20, 0.12, 0.23, 0.14),
                new DocParseProvider.OcrBlock(1, "申请金额：人民币500,000元", 0.99, 0.09, 0.30, 0.60, 0.33),
                new DocParseProvider.OcrBlock(1, "申请期限：36个月", 0.99, 0.09, 0.35, 0.45, 0.38));
        DocParseProvider.ImageContent ic = new DocParseProvider.ImageContent(
                "个人经营性贷款申请表\n申请人姓名\n张*\n申请金额：人民币500,000元\n申请期限：36个月", blocks, 1);
        DocParseService structured = new DocParseService(new DocParseProvider() {
            @Override
            public String ocrImage(byte[] content, String filename, String requestId) {
                return ic.text();
            }

            @Override
            public DocParseProvider.ImageContent ocrImageBlocks(byte[] content, String filename, String requestId) {
                return ic;
            }
        });
        MockMultipartFile file = new MockMultipartFile("file", "real-001.jpg", "image/jpeg", new byte[]{1, 2, 3});
        DocParseDtos.ParseResult r = structured.parse(file, null, null, null, null, "dp-st1");

        assertEquals(1, r.pages());
        assertEquals("application_form", r.docType());
        assertEquals(0.95, r.docTypeConfidence(), 1e-9);
        assertEquals(blocks.size(), r.layout().size());
        assertEquals("PARAGRAPH", r.layout().get(0).type());
        assertEquals(0.34, r.layout().get(0).x(), 1e-9);
        assertEquals(0.65 - 0.34, r.layout().get(0).w(), 1e-9);
        assertEquals(0.999, r.layout().get(0).confidence(), 1e-9);
        assertEquals(5, r.readingOrder().size());
        assertEquals("张*", r.readingOrder().get(2).text());
        @SuppressWarnings("unchecked")
        Map<String, Object> kv = (Map<String, Object>) r.kv();
        assertEquals("张*", ((DocParseDtos.KvField) kv.get("applicant_name")).value());
        assertEquals("500000", ((DocParseDtos.KvField) kv.get("loan_amount")).value());
        assertEquals("36个月", ((DocParseDtos.KvField) kv.get("loan_term")).value());
        // 文本中缺失的字段置低置信（不伪造值）
        assertTrue(r.validation().lowConfidenceFields().contains("id_no"));
        assertTrue(r.validation().needReview());
    }

    @Test
    void mock提供者_图像仍走仿真兜底() {
        MockMultipartFile file = new MockMultipartFile("file", "样张.jpg", "image/jpeg", new byte[]{1});
        DocParseDtos.ParseResult r = service.parse(file, null, null, null, null, "dp-st2");
        // mock 兜底：页数按大小估算、版面含仿真 TITLE
        assertTrue(r.pages() >= 1);
        assertTrue(r.layout().stream().anyMatch(l -> "TITLE".equals(l.type())));
    }
}
