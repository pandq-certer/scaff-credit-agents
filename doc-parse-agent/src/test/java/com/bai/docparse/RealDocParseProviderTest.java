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
}
