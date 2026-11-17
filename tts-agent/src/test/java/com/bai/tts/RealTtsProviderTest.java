package com.bai.tts;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RealTtsProvider 降级行为测试：推理网关不可达时必须抛 TTS_2001（MODEL_UNAVAILABLE）。
 */
class RealTtsProviderTest {

    @Test
    void gatewayUnreachableShouldThrowModelUnavailable() {
        RealTtsProvider provider = new RealTtsProvider("http://127.0.0.1:1", Duration.ofSeconds(2));
        BaiException ex = assertThrows(BaiException.class, () ->
                provider.synthesize("你好世界", "v_general_f", "general", "PROFESSIONAL",
                        1.0, "wav", 24000, "real-1"));
        assertEquals(TtsErrorCodes.MODEL_UNAVAILABLE, ex.getCode());
    }
}
