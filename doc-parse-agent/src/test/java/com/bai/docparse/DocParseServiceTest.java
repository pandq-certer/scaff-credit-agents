package com.bai.docparse;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

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
}
