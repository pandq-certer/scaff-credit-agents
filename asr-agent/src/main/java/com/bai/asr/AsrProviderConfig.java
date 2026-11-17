package com.bai.asr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * ASR 引擎提供者装配：按 bai.asr.provider 切换 mock/real，默认 mock（matchIfMissing）。
 * real 模式读 bai.asr.gateway.base-url（默认 http://localhost:9100，可用环境变量 BAI_AI_GATEWAY_URL 覆盖）
 * 与 bai.asr.gateway.timeout-ms（默认 30000）。
 */
@Configuration
public class AsrProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "bai.asr.provider", havingValue = "mock", matchIfMissing = true)
    public AsrProvider mockAsrProvider() {
        return new MockAsrProvider();
    }

    @Bean
    @ConditionalOnProperty(name = "bai.asr.provider", havingValue = "real")
    public AsrProvider realAsrProvider(
            @Value("${bai.asr.gateway.base-url:http://localhost:9100}") String baseUrl,
            @Value("${bai.asr.gateway.timeout-ms:30000}") long timeoutMs) {
        return new RealAsrProvider(baseUrl, Duration.ofMillis(timeoutMs));
    }
}
