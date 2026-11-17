package com.bai.image;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 图像质检引擎装配：bai.image.provider=mock（默认，确定性仿真，联调回归基线）| real（统一 AI 推理网关）。
 */
@Configuration
public class ImageProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "bai.image.provider", havingValue = "mock", matchIfMissing = true)
    public ImageProvider mockImageProvider() {
        return new MockImageProvider();
    }

    @Bean
    @ConditionalOnProperty(name = "bai.image.provider", havingValue = "real")
    public ImageProvider realImageProvider(
            @Value("${bai.image.gateway.base-url:http://localhost:9100}") String baseUrl,
            @Value("${bai.image.gateway.timeout-ms:30000}") long timeoutMs) {
        return new RealImageProvider(baseUrl, Duration.ofMillis(timeoutMs));
    }
}
