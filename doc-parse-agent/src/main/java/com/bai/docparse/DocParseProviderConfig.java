package com.bai.docparse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 文档解析引擎装配：bai.docparse.provider=mock（默认，确定性仿真，联调回归基线）
 * | real（图像类复用统一 AI 推理网关 OCR，正文喂本地分类/KV 抽取链路）。
 */
@Configuration
public class DocParseProviderConfig {

    @Bean
    @ConditionalOnProperty(name = "bai.docparse.provider", havingValue = "mock", matchIfMissing = true)
    public DocParseProvider mockDocParseProvider() {
        return new MockDocParseProvider();
    }

    @Bean
    @ConditionalOnProperty(name = "bai.docparse.provider", havingValue = "real")
    public DocParseProvider realDocParseProvider(
            @Value("${bai.docparse.gateway.base-url:http://localhost:9100}") String baseUrl,
            @Value("${bai.docparse.gateway.timeout-ms:30000}") long timeoutMs) {
        return new RealDocParseProvider(baseUrl, Duration.ofMillis(timeoutMs));
    }
}
