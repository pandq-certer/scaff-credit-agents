package com.bai.docparse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 文档解析引擎装配：bai.docparse.provider=mock（确定性仿真，联调回归基线）
 * | real（图像类走 SYS-01 OCR 服务 8101，正文喂本地分类/KV 抽取链路）。
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
            @Value("${bai.docparse.sys01.base-url:http://localhost:8101}") String baseUrl,
            @Value("${bai.docparse.sys01.parse-base-url:http://localhost:8102}") String parseBaseUrl,
            @Value("${bai.docparse.sys01.timeout-ms:180000}") long timeoutMs,
            @Value("${bai.docparse.sys01.pdf-timeout-ms:900000}") long pdfTimeoutMs) {
        return new RealDocParseProvider(baseUrl, parseBaseUrl,
                Duration.ofMillis(timeoutMs), Duration.ofMillis(pdfTimeoutMs));
    }
}
