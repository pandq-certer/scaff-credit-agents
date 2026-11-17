package com.bai.intent;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式意图识别 WebSocket（/api/bai/intent/stream）。
 * <p>仿真说明（用于联调）：稳定窗口 2 次一致上报与空闲 60s 超时由真实引擎实现，本仿真为逐条即时上报；
 * 单实例并发上限 100，超限以 INT_1007 关闭连接。
 */
public class IntentStreamHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(IntentStreamHandler.class);
    private static final int MAX_SESSIONS = 100;

    private final ObjectMapper mapper;
    private final IntentService service;
    private final AtomicInteger active = new AtomicInteger();

    public IntentStreamHandler(ObjectMapper mapper, IntentService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (active.incrementAndGet() > MAX_SESSIONS) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("INT_1007 流式并发超限（100/实例）"));
            return;
        }
        super.afterConnectionEstablished(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        active.decrementAndGet();
        super.afterConnectionClosed(session, status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String requestId = "WS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            IntentDtos.ClassifyRequest req = mapper.readValue(message.getPayload(), IntentDtos.ClassifyRequest.class);
            BaiResponse<IntentDtos.IntentResult> resp = BaiResponse.ok(requestId, service.classify(req, requestId));
            session.sendMessage(new TextMessage(mapper.writeValueAsString(resp)));
        } catch (BaiException e) {
            session.sendMessage(new TextMessage(
                    mapper.writeValueAsString(BaiResponse.fail(requestId, e.getCode(), e.getMessage()))));
        } catch (IOException e) {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(BaiResponse.fail(requestId,
                    IntentErrorCodes.PARAM_INVALID, "INT_1001 消息格式非法：需为 {\"text\":\"...\"} JSON"))));
        }
        log.debug("流式意图(仿真): sessionId={}, active={}", session.getId(), active.get());
    }
}
