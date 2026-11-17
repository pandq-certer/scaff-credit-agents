package com.bai.asr;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RealAsrProvider 降级行为：推理网关不可达时抛 ASR_2001（MODEL_UNAVAILABLE），错误码契约不变。
 */
class RealAsrProviderTest {

    @Test
    void 网关不可达_降级ASR2001() {
        RealAsrProvider provider = new RealAsrProvider("http://127.0.0.1:1", Duration.ofSeconds(2));
        BaiException e = assertThrows(BaiException.class,
                () -> provider.recognize(new byte[2048], "a.wav", "general", null, false, true, "real-1"));
        assertEquals("ASR_2001", e.getCode());
    }
}
