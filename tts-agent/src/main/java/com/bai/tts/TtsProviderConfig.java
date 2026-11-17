package com.bai.tts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * TTS 引擎装配：bai.tts.provider=mock（默认，确定性仿真，联调回归基线）| real（统一 AI 推理网关）。
 */
@Configuration
public class TtsProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "bai.tts.provider", havingValue = "mock", matchIfMissing = true)
    public TtsProvider mockTtsProvider() {
        return new MockTtsProvider();
    }

    @Bean
    @ConditionalOnProperty(name = "bai.tts.provider", havingValue = "real")
    public TtsProvider realTtsProvider(
            @Value("${bai.tts.gateway.base-url:http://localhost:9100}") String baseUrl,
            @Value("${bai.tts.gateway.timeout-ms:30000}") long timeoutMs) {
        return new RealTtsProvider(baseUrl, Duration.ofMillis(timeoutMs));
    }
}
