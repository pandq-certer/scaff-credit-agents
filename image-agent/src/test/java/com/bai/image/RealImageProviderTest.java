package com.bai.image;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RealImageProvider 降级行为测试：推理网关不可达时必须抛 IMG_2001（MODEL_DEGRADED）。
 */
class RealImageProviderTest {

    @Test
    void gatewayUnreachableShouldThrowModelDegraded() {
        RealImageProvider provider = new RealImageProvider("http://127.0.0.1:1", Duration.ofSeconds(2));
        BaiException ex = assertThrows(BaiException.class, () ->
                provider.check(new byte[2048], "real-1"));
        assertEquals(ImageErrorCodes.MODEL_DEGRADED, ex.getCode());
    }
}
