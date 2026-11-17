package com.bai.ocr;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RealOcrProvider 降级行为：推理网关不可达时抛 OCR_2001（MODEL_UNAVAILABLE），错误码契约不变。
 */
class RealOcrProviderTest {

    @Test
    void 网关不可达_降级OCR2001() {
        RealOcrProvider provider = new RealOcrProvider("http://127.0.0.1:1", Duration.ofSeconds(2));
        BaiException e = assertThrows(BaiException.class,
                () -> provider.recognize(new byte[2048], "a.jpg", "ID_CARD", "real-1"));
        assertEquals("OCR_2001", e.getCode());
    }

    @Test
    void 直连识别_网关不可达_降级OCR2001() {
        RealOcrProvider provider = new RealOcrProvider("http://127.0.0.1:1", Duration.ofSeconds(2));
        BaiException e = assertThrows(BaiException.class,
                () -> provider.recognizeRef("IMG-REF-1", "ID_CARD", "real-2"));
        assertEquals("OCR_2001", e.getCode());
    }
}
