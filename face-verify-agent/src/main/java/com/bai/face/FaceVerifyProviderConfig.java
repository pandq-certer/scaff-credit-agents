package com.bai.face;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 人脸核验引擎装配：bai.faceverify.provider=mock（默认，确定性仿真，联调回归基线）| real（统一 AI 推理网关）。
 */
@Configuration
public class FaceVerifyProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "bai.faceverify.provider", havingValue = "mock", matchIfMissing = true)
    public FaceVerifyProvider mockFaceVerifyProvider() {
        return new MockFaceVerifyProvider();
    }

    @Bean
    @ConditionalOnProperty(name = "bai.faceverify.provider", havingValue = "real")
    public FaceVerifyProvider realFaceVerifyProvider(
            @Value("${bai.faceverify.gateway.base-url:http://localhost:9100}") String baseUrl,
            @Value("${bai.faceverify.gateway.timeout-ms:30000}") long timeoutMs) {
        return new RealFaceVerifyProvider(baseUrl, Duration.ofMillis(timeoutMs));
    }
}
