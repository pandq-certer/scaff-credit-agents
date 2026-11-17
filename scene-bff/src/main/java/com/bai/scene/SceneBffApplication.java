package com.bai.scene;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 场景编排层 BFF 入口：剧本注册表 + 编排执行 + SSE 滚动日志 + credit-web 最小前端
 */
@SpringBootApplication
public class SceneBffApplication {

    public static void main(String[] args) {
        SpringApplication.run(SceneBffApplication.class, args);
    }
}
