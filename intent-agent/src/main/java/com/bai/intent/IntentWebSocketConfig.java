package com.bai.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 流式意图 WebSocket 注册：context-path 拼接后即 /api/bai/intent/stream
 */
@Configuration
@EnableWebSocket
public class IntentWebSocketConfig implements WebSocketConfigurer {

    private final ObjectMapper mapper;
    private final IntentService service;

    public IntentWebSocketConfig(ObjectMapper mapper, IntentService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new IntentStreamHandler(mapper, service), "/stream")
                .setAllowedOriginPatterns("*");
    }
}
