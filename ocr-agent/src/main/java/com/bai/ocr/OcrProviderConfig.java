package com.bai.ocr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * OCR 引擎装配：bai.ocr.provider=mock（确定性仿真，联调回归基线）| real（SYS-01 智能文档处理服务 8101）。
 */
@Configuration
public class OcrProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "bai.ocr.provider", havingValue = "mock", matchIfMissing = true)
    public OcrProvider mockOcrProvider() {
        return new MockOcrProvider();
    }

    @Bean
    @ConditionalOnProperty(name = "bai.ocr.provider", havingValue = "real")
    public OcrProvider realOcrProvider(
            @Value("${bai.ocr.sys01.base-url:http://localhost:8101}") String baseUrl,
            @Value("${bai.ocr.sys01.timeout-ms:180000}") long timeoutMs) {
        return new RealOcrProvider(baseUrl, Duration.ofMillis(timeoutMs));
    }
}
