package com.bai.face;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RealFaceVerifyProvider 降级路径测试：网关不可达 → FAC_2001 MODEL_UNAVAILABLE
 */
class RealFaceVerifyProviderTest {

    private final RealFaceVerifyProvider provider =
            new RealFaceVerifyProvider("http://127.0.0.1:1", Duration.ofSeconds(2));

    @Test
    void verifyFaceGatewayDownThrowsModelUnavailable() {
        BaiException e = assertThrows(BaiException.class, () -> provider.verifyFace(
                new byte[2048], null, null, "general", Set.of("liveness", "compare"), "STATIC", "real-1"));
        assertEquals(FaceVerifyErrorCodes.MODEL_UNAVAILABLE, e.getCode());
    }

    @Test
    void compareFaceGatewayDownThrowsModelUnavailable() {
        BaiException e = assertThrows(BaiException.class, () -> provider.compareFace(
                new byte[2048], new byte[2048], "id-front.jpg", "general", "real-2"));
        assertEquals(FaceVerifyErrorCodes.MODEL_UNAVAILABLE, e.getCode());
    }
}
