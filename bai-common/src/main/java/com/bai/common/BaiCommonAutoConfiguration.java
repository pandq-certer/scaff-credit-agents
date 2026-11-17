package com.bai.common;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * bai-common 自动装配：统一异常出口对所有原子智能体生效
 * （原子应用主类包名为 com.bai.<atom>，扫描不到 com.bai.common，故经 AutoConfiguration 注册）
 */
@AutoConfiguration
public class BaiCommonAutoConfiguration {

    @Bean
    public BaiExceptionHandler baiExceptionHandler() {
        return new BaiExceptionHandler();
    }
}
