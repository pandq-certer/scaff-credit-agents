package com.bai.sentiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 流式情绪 WebSocket 注册：context-path 拼接后即 /api/bai/sentiment/stream
 */
@Configuration
@EnableWebSocket
public class SentimentWebSocketConfig implements WebSocketConfigurer {

    private final ObjectMapper mapper;
    private final SentimentService service;

    public SentimentWebSocketConfig(ObjectMapper mapper, SentimentService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new SentimentStreamHandler(mapper, service), "/stream")
                .setAllowedOriginPatterns("*");
    }
}
