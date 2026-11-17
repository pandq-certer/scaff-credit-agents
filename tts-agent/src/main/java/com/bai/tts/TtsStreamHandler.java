package com.bai.tts;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式合成 WebSocket（/api/bai/tts/stream，设计文档 4.3 节）。
 * <p>事件序列（服务端 → 客户端）：
 * <pre>
 * 连接后：{ "type":"meta", "voiceId":"...", "format":"opus", "sampleRate":24000, "durationMs":8450 }
 * 每段：二进制帧（PCM 24kHz/16bit，100ms 分片）+ { "type":"segment","index":1,"text":"...","startMs":0,"endMs":4200 }
 * 收尾：{ "type":"ended","reason":"FINISHED|CANCELED|ERROR|IDLE_TIMEOUT" }
 * </pre>
 * 客户端 → 服务端文本帧：{"type":"text","text":"...","seq":1} / {"type":"end"} / {"type":"cancel"}；
 * 连接 Query 参数：voiceId/scene/format/requestId/token。
 * <p>约束（设计 4.3）：连接空闲 60s 超时；单连接最长 30min；单实例并发 100（超限以 TTS_1008 语义关闭）。
 * 仿真说明：真实引擎按 100ms 音频分片增量推送，本仿真复用 TtsService 确定性合成结果逐段推送。
 */
public class TtsStreamHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TtsStreamHandler.class);
    private static final int MAX_SESSIONS = 100;
    private static final long IDLE_TIMEOUT_MS = 60_000L;       // 连接空闲超时 60s
    private static final long MAX_LIFETIME_MS = 30 * 60_000L;  // 单连接最大 30min
    private static final long FRAME_DELAY_MS = 150L;           // 仿真分片间隔（设计：≤500ms）
    private static final int MAX_CHUNKS_PER_SEGMENT = 30;      // 每段音频分片上限（100ms/片）

    private final ObjectMapper mapper;
    private final TtsService service;
    private final AtomicInteger active = new AtomicInteger();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> lastActivity = new ConcurrentHashMap<>();
    private final Map<String, Long> connectedAt = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tts-stream-idle");
        t.setDaemon(true);
        return t;
    });

    public TtsStreamHandler(ObjectMapper mapper, TtsService service) {
        this.mapper = mapper;
        this.service = service;
        this.sweeper.scheduleAtFixedRate(this::sweep, 20, 20, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (active.incrementAndGet() > MAX_SESSIONS) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("TTS_1008 流式并发超限（100/实例）"));
            return;
        }
        super.afterConnectionEstablished(session);
        String sid = session.getId();
        String voiceId = queryParam(session, "voiceId");
        String scene = queryParam(session, "scene");
        String format = queryParam(session, "format");
        Integer sampleRate = queryInt(session, "sampleRate", 24000);
        String requestId = queryParam(session, "requestId");
        try {
            service.validateStreamParams(voiceId, scene, format, sampleRate);
        } catch (BaiException e) {
            sendFail(session, requestId, e.getCode(), e.getMessage());
            session.close(CloseStatus.POLICY_VIOLATION.withReason(e.getCode() + " 连接参数非法"));
            return;
        }
        sessions.put(sid, session);
        lastActivity.put(sid, System.currentTimeMillis());
        connectedAt.put(sid, System.currentTimeMillis());
        session.getAttributes().put("buffer", new StringBuilder());
        session.getAttributes().put("voiceId", voiceId);
        session.getAttributes().put("scene", scene == null || scene.isBlank() ? "general" : scene);
        session.getAttributes().put("format", format == null || format.isBlank() ? "wav" : format);
        session.getAttributes().put("sampleRate", sampleRate);
        session.getAttributes().put("requestId", requestId == null ? "WS-" + sid : requestId);
        log.info("流式合成连接建立: sessionId={}, voiceId={}, scene={}, format={}, sampleRate={}, active={}",
                sid, voiceId, scene, format, sampleRate, active.get());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        active.decrementAndGet();
        sessions.remove(session.getId());
        lastActivity.remove(session.getId());
        connectedAt.remove(session.getId());
        log.info("流式合成连接关闭: sessionId={}, active={}, closeStatus={}",
                session.getId(), active.get(), status);
        super.afterConnectionClosed(session, status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        touch(session);
        String requestId = str(session.getAttributes().get("requestId"));
        try {
            Map<String, Object> payload = mapper.readValue(message.getPayload(),
                    new TypeReference<Map<String, Object>>() { });
            String type = str(payload.get("type"));
            if ("text".equals(type)) {
                String text = str(payload.get("text"));
                if (text == null) {
                    sendFail(session, requestId, TtsErrorCodes.PARAM_INVALID,
                            "TTS_1001 参数缺失：text 帧 text 必填");
                    return;
                }
                StringBuilder buffer = (StringBuilder) session.getAttributes().get("buffer");
                buffer.append(text);
                log.info("流式文本帧: sessionId={}, seq={}, textLen={}", session.getId(),
                        payload.get("seq"), text.length());
                return;
            }
            if ("end".equals(type)) {
                StringBuilder buffer = (StringBuilder) session.getAttributes().get("buffer");
                String text = buffer.toString();
                if (text.isBlank()) {
                    sendFail(session, requestId, TtsErrorCodes.TEXT_UNSYNTHESIZABLE,
                            "TTS_1007 文本不可合成（流式未收到文本）");
                    session.close(CloseStatus.POLICY_VIOLATION.withReason("TTS_1007"));
                    return;
                }
                TtsDtos.TtsResult result = service.synthesizeStream(text,
                        str(session.getAttributes().get("voiceId")),
                        str(session.getAttributes().get("scene")),
                        str(session.getAttributes().get("format")),
                        (Integer) session.getAttributes().get("sampleRate"), requestId);
                sendMeta(session, result);
                long seed = Math.abs(result.ttsId().hashCode());
                for (TtsDtos.Segment seg : result.segments()) {
                    sendAudioChunks(session, seg, (Integer) session.getAttributes().get("sampleRate"), seed);
                    sendSegment(session, seg);
                    delay();
                }
                sendEnded(session, "FINISHED");
                session.close(CloseStatus.NORMAL);
                log.info("流式合成结束: sessionId={}, requestId={}, ttsId={}, durationMs={}, segments={}",
                        session.getId(), requestId, result.ttsId(), result.durationMs(), result.segments().size());
                return;
            }
            if ("cancel".equals(type)) {
                sendEnded(session, "CANCELED");
                session.close(CloseStatus.NORMAL);
                log.info("流式合成取消: sessionId={}, requestId={}", session.getId(), requestId);
                return;
            }
            sendFail(session, requestId, TtsErrorCodes.PARAM_INVALID,
                    "TTS_1001 消息类型非法：仅支持 text/end/cancel");
        } catch (BaiException e) {
            sendFail(session, requestId, e.getCode(), e.getMessage());
            session.close(CloseStatus.POLICY_VIOLATION.withReason(e.getCode()));
        } catch (IOException e) {
            sendFail(session, requestId, TtsErrorCodes.PARAM_INVALID,
                    "TTS_1001 消息格式非法：需为 {\"type\":\"text\",\"text\":\"...\"} 等 JSON 帧");
        } catch (Exception e) {
            sendFail(session, requestId, TtsErrorCodes.INTERNAL_ERROR,
                    "TTS_2000 服务内部错误：" + e.getMessage());
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    /** 元信息帧：voiceId/format/sampleRate/durationMs（设计 4.3） */
    private void sendMeta(WebSocketSession session, TtsDtos.TtsResult result) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "meta");
        frame.put("voiceId", result.voiceId());
        frame.put("format", result.format());
        frame.put("sampleRate", result.sampleRate());
        frame.put("durationMs", result.durationMs());
        session.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
    }

    /** 分段帧：index/text/startMs/endMs */
    private void sendSegment(WebSocketSession session, TtsDtos.Segment segment) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "segment");
        frame.put("index", segment.index());
        frame.put("text", segment.text());
        frame.put("startMs", segment.startMs());
        frame.put("endMs", segment.endMs());
        session.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
    }

    /** 音频分片（二进制帧，PCM 16bit 单声道，按 100ms 片，确定性内容） */
    private void sendAudioChunks(WebSocketSession session, TtsDtos.Segment segment, int sampleRate, long seed)
            throws IOException {
        int chunks = Math.toIntExact(Math.max(1L, Math.min(segment.endMs() - segment.startMs(), MAX_CHUNKS_PER_SEGMENT * 100L) / 100L));
        byte[] chunk = pcmChunk(sampleRate, seed);
        for (int i = 0; i < chunks; i++) {
            session.sendMessage(new BinaryMessage(chunk));
        }
    }

    /** 收尾帧：FINISHED/CANCELED/ERROR/IDLE_TIMEOUT（设计 4.3） */
    private void sendEnded(WebSocketSession session, String reason) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "ended");
        frame.put("reason", reason);
        session.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
    }

    /** 异常帧：统一 BaiResponse 失败结构（BaiException → code/message；格式非法 → TTS_1001；兜底 → TTS_2000） */
    private void sendFail(WebSocketSession session, String requestId, String code, String message) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(
                    BaiResponse.fail(requestId, code, message))));
        }
    }

    /** 空闲/超时巡检：空闲 60s → ended(IDLE_TIMEOUT) 关闭；连接超 30min 关闭 */
    private void sweep() {
        long now = System.currentTimeMillis();
        for (WebSocketSession s : sessions.values()) {
            if (!s.isOpen()) {
                continue;
            }
            Long last = lastActivity.get(s.getId());
            if (last != null && now - last > IDLE_TIMEOUT_MS) {
                try {
                    sendEnded(s, "IDLE_TIMEOUT");
                } catch (IOException ignored) {
                    // 关闭前尽力发送收尾帧
                }
                closeQuietly(s);
                continue;
            }
            Long at = connectedAt.get(s.getId());
            if (at != null && now - at > MAX_LIFETIME_MS) {
                closeQuietly(s);
            }
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        } catch (IOException ignored) {
            // 已断开忽略
        }
    }

    private void touch(WebSocketSession session) {
        lastActivity.put(session.getId(), System.currentTimeMillis());
    }

    /** 确定性 PCM 分片内容（100ms，16bit 单声道） */
    private static byte[] pcmChunk(int sampleRate, long seed) {
        int bytes = sampleRate * 2 / 10;
        byte[] data = new byte[bytes];
        double phase = seed % 7;
        for (int i = 0; i < bytes; i += 2) {
            short v = (short) (Math.sin(i / 2 * 0.08 + phase) * 1800);
            data[i] = (byte) (v & 0xFF);
            data[i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return data;
    }

    private static void delay() {
        if (FRAME_DELAY_MS <= 0) {
            return;
        }
        try {
            Thread.sleep(FRAME_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String queryParam(WebSocketSession session, String name) {
        String query = session.getUri() == null || session.getUri().getQuery() == null
                ? null : session.getUri().getQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static Integer queryInt(WebSocketSession session, String name, int defaultValue) {
        String v = queryParam(session, name);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
