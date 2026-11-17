package com.bai.sentiment;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式情绪 WebSocket（/api/bai/sentiment/stream）。
 * <p>仿真说明（用于联调）：按设计文档 4.5 节事件序列逐帧发送 START→PARTIAL→STABLE→END
 * （对齐现有 SSE /stream 的 delta 帧结构 type=START|PARTIAL|STABLE、category、stableHits，
 * END 帧携带完整结果 BaiResponse&lt;SentimentResult&gt; 信封）；稳定窗口 2 次一致上报与
 * 空闲 60s 超时由真实引擎实现，本仿真为逐条即时上报；单实例并发上限 100，超限以 SEN_1007 关闭连接。
 */
public class SentimentStreamHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SentimentStreamHandler.class);
    private static final int MAX_SESSIONS = 100;

    private final ObjectMapper mapper;
    private final SentimentService service;
    private final AtomicInteger active = new AtomicInteger();

    public SentimentStreamHandler(ObjectMapper mapper, SentimentService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (active.incrementAndGet() > MAX_SESSIONS) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("SEN_1007 流式并发超限（100/实例）"));
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
            // 请求体 {text, sessionId?}（复用 analyze 请求体，scene/turnSeq 可选）
            SentimentDtos.AnalyzeRequest req = mapper.readValue(message.getPayload(), SentimentDtos.AnalyzeRequest.class);
            SentimentDtos.SentimentResult result = service.analyze(req, requestId);
            String sid = req.sessionId() == null ? "" : req.sessionId().replace("\"", "'");
            // 事件序列：START → PARTIAL（增量）→ STABLE（稳定窗口 2 次一致）→ END（携带完整结果）
            session.sendMessage(new TextMessage("{\"type\":\"START\",\"sessionId\":\"" + sid + "\"}"));
            session.sendMessage(new TextMessage("{\"type\":\"PARTIAL\",\"category\":\""
                    + result.emotion().category() + "\",\"stable\":false}"));
            session.sendMessage(new TextMessage("{\"type\":\"STABLE\",\"category\":\""
                    + result.emotion().category() + "\",\"stableHits\":2}"));
            Map<String, Object> end = new LinkedHashMap<>();
            end.put("type", "END");
            end.put("reason", "FINISHED");
            BaiResponse<SentimentDtos.SentimentResult> resp = BaiResponse.ok(requestId, result);
            end.put("code", resp.getCode());
            end.put("requestId", resp.getRequestId());
            end.put("message", resp.getMessage());
            end.put("data", resp.getData());
            session.sendMessage(new TextMessage(mapper.writeValueAsString(end)));
        } catch (BaiException e) {
            session.sendMessage(new TextMessage(
                    mapper.writeValueAsString(BaiResponse.fail(requestId, e.getCode(), e.getMessage()))));
        } catch (IOException e) {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(BaiResponse.fail(requestId,
                    SentimentErrorCodes.PARAM_INVALID, "SEN_1001 消息格式非法：需为 {\"text\":\"...\"} JSON"))));
        }
        log.debug("流式情绪(仿真): sessionId={}, active={}", session.getId(), active.get());
    }
}
