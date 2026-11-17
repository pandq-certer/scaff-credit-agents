package com.bai.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 流式合成 WebSocket 注册：context-path 拼接后即 /api/bai/tts/stream（设计文档 4.3 节）
 */
@Configuration
@EnableWebSocket
public class TtsWebSocketConfig implements WebSocketConfigurer {

    private final ObjectMapper mapper;
    private final TtsService service;

    public TtsWebSocketConfig(ObjectMapper mapper, TtsService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new TtsStreamHandler(mapper, service), "/stream")
                .setAllowedOriginPatterns("*");
    }
}
