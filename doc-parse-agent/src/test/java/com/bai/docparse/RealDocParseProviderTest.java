package com.bai.docparse;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RealDocParseProvider 降级测试：推理网关不可达时抛 DCP_2001 MODEL_DEGRADED（真实模式不伪造值）。
 */
class RealDocParseProviderTest {

    private final RealDocParseProvider provider =
            new RealDocParseProvider("http://127.0.0.1:1", Duration.ofSeconds(2));

    @Test
    void 网关不可达_抛DCP2001() {
        BaiException e = assertThrows(BaiException.class,
                () -> provider.ocrImage("凭证图".getBytes(StandardCharsets.UTF_8), "凭证.png", "rdp-1"));
        assertEquals("DCP_2001", e.getCode());
    }

    @Test
    void 结构化OCR_解析blocks文本坐标置信度页数() throws Exception {
        String body = "{\"blocks\":["
                + "{\"text\":\"个人经营性贷款申请表\",\"confidence\":0.999,\"bbox\":[0.34,0.07,0.65,0.09],\"page\":1},"
                + "{\"text\":\"张*\",\"confidence\":0.99,\"bbox\":[0.20,0.12,0.23,0.14]}"
                + "],\"page_count\":1}";
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        server.createContext("/v1/ocr", exchange -> {
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (var os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        RealDocParseProvider p = new RealDocParseProvider(
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5));
        try {
            DocParseProvider.ImageContent ic = p.ocrImageBlocks(new byte[]{1}, "real-001.jpg", "rdp-2");
            assertEquals(2, ic.blocks().size());
            assertEquals("个人经营性贷款申请表\n张*", ic.text());
            assertEquals(0.999, ic.blocks().get(0).confidence(), 1e-9);
            assertEquals(0.34, ic.blocks().get(0).x1(), 1e-9);
            assertEquals(0.65, ic.blocks().get(0).x2(), 1e-9);
            assertEquals(0.14, ic.blocks().get(1).y2(), 1e-9);
            assertEquals(1, ic.pages());
        } finally {
            server.stop(0);
        }
    }
}
