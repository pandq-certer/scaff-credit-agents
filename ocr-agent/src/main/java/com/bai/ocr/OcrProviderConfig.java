package com.bai.ocr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * OCR 引擎装配：bai.ocr.provider=mock（默认，确定性仿真，联调回归基线）| real（统一 AI 推理网关）。
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
            @Value("${bai.ocr.gateway.base-url:http://localhost:9100}") String baseUrl,
            @Value("${bai.ocr.gateway.timeout-ms:30000}") long timeoutMs) {
        return new RealOcrProvider(baseUrl, Duration.ofMillis(timeoutMs));
    }
}
