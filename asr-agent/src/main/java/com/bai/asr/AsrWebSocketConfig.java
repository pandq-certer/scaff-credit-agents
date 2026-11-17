package com.bai.asr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 流式转写 WebSocket 注册：context-path 拼接后即 /api/bai/asr/stream（设计文档 4.3 节）
 */
@Configuration
@EnableWebSocket
public class AsrWebSocketConfig implements WebSocketConfigurer {

    private final ObjectMapper mapper;
    private final AsrService service;

    public AsrWebSocketConfig(ObjectMapper mapper, AsrService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new AsrStreamHandler(mapper, service), "/stream")
                .setAllowedOriginPatterns("*");
    }
}
