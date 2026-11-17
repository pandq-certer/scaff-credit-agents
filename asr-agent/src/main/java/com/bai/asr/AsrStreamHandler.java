package com.bai.asr;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式转写 WebSocket（/api/bai/asr/stream，设计文档 4.3 节）。
 * <p>事件序列（服务端 → 客户端文本帧，每帧 JSON）：
 * <pre>
 * 逐句：{ "type":"partial", "index":1, "text":"张先生您", "startMs":1000, "endMs":2300 }
 *      → { "type":"final", "index":1, "text":"张先生您好，…", "confidence":0.97, "startMs":1000, "endMs":5400 }
 * 收尾：{ "type":"ended", "reason":"CLIENT_END|IDLE_TIMEOUT|ERROR" }
 * </pre>
 * 客户端 → 服务端控制帧：{"type":"config","scene":"..."} / {"type":"end"} / {"type":"cancel"}；
 * 识别帧：{"audioRef":"...","scene":"...","lang":"...","speakerDiarization":false,"maskSensitive":true}
 * （无 audioRef 时可用 {"text":"..."} 文本片段，仿真合成指纹）。
 * <p>仿真说明（用于联调）：真实引擎按 PCM 分片增量解码，本仿真复用 AsrService.transcribeRef 确定性输出，
 * 按句切分 partial/final 逐帧推送；单实例并发上限 100，超限以 ASR_1007 语义关闭（POLICY_VIOLATION）。
 */
public class AsrStreamHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AsrStreamHandler.class);
    private static final int MAX_SESSIONS = 100;
    /** 仿真帧间隔（ms），模拟半句级增量推送 */
    private static final long FRAME_DELAY_MS = 150L;
    /** 结束原因枚举（设计文档 4.3.1）：CLIENT_END|IDLE_TIMEOUT|ERROR */
    private static final String END_REASON_CLIENT_END = "CLIENT_END";

    private final ObjectMapper mapper;
    private final AsrService service;
    private final AtomicInteger active = new AtomicInteger();

    public AsrStreamHandler(ObjectMapper mapper, AsrService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (active.incrementAndGet() > MAX_SESSIONS) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("ASR_1007 流式并发超限（100/实例）"));
            return;
        }
        super.afterConnectionEstablished(session);
        log.info("流式连接建立: sessionId={}, active={}", session.getId(), active.get());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        active.decrementAndGet();
        log.info("流式连接关闭: sessionId={}, active={}, closeStatus={}", session.getId(), active.get(), status);
        super.afterConnectionClosed(session, status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String requestId = "WS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            Map<String, Object> payload = mapper.readValue(message.getPayload(),
                    new TypeReference<Map<String, Object>>() { });
            String type = str(payload.get("type"));
            if ("config".equals(type)) {
                log.info("流式配置: requestId={}, sessionId={}, scene={}, lang={}",
                        requestId, session.getId(), str(payload.get("scene")), str(payload.get("lang")));
                return;
            }
            if ("end".equals(type)) {
                sendEnded(session, END_REASON_CLIENT_END);
                log.info("流式结束: requestId={}, sessionId={}, reason={}", requestId, session.getId(), END_REASON_CLIENT_END);
                return;
            }
            if ("cancel".equals(type)) {
                sendEnded(session, END_REASON_CLIENT_END);
                log.info("流式放弃: requestId={}, sessionId={}, reason={}", requestId, session.getId(), END_REASON_CLIENT_END);
                return;
            }
            // 识别帧：audioRef（或 text 片段）→ 复用 AsrService.transcribeRef 确定性转写
            Map<String, Object> body = new LinkedHashMap<>();
            if (!isBlank(payload.get("audioRef"))) {
                body.put("audioRef", payload.get("audioRef"));
            } else if (!isBlank(payload.get("text"))) {
                body.put("audioRef", synthesizeAudioRef(str(payload.get("text"))));
            }
            body.put("scene", payload.getOrDefault("scene", "general"));
            body.put("lang", payload.get("lang"));
            body.put("speakerDiarization", payload.getOrDefault("speakerDiarization", false));
            body.put("maskSensitive", payload.getOrDefault("maskSensitive", true));
            AsrDtos.AsrResult result = service.transcribeRef(body, requestId);
            for (AsrDtos.Segment segment : result.segments()) {
                sendPartial(session, segment);
                delay();
                sendFinal(session, segment);
                delay();
            }
            sendEnded(session, END_REASON_CLIENT_END);
            log.info("流式转写: requestId={}, sessionId={}, asrId={}, scene={}, lang={}, status={}, segments={}, active={}",
                    requestId, session.getId(), result.asrId(), result.scene(), result.lang(),
                    result.status(), result.segments().size(), active.get());
        } catch (BaiException e) {
            sendFail(session, requestId, e.getCode(), e.getMessage());
        } catch (IOException e) {
            sendFail(session, requestId, AsrErrorCodes.PARAM_INVALID,
                    "ASR_1001 消息格式非法：需为 {\"audioRef\":\"...\"} 或 {\"type\":\"end\"} JSON");
        } catch (Exception e) {
            sendFail(session, requestId, AsrErrorCodes.INTERNAL_ERROR,
                    "ASR_2000 服务内部错误：" + e.getMessage());
        }
        log.debug("流式转写(仿真): sessionId={}, active={}", session.getId(), active.get());
    }

    /** 增量帧：句文本前缀（仿真半句级中间结果），partial.endMs 取句内前 40% 时间点 */
    private void sendPartial(WebSocketSession session, AsrDtos.Segment segment) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "partial");
        frame.put("index", segment.index());
        frame.put("text", prefix(segment.text()));
        long partialEnd = segment.startMs() + (long) ((segment.endMs() - segment.startMs()) * 0.4);
        frame.put("startMs", segment.startMs());
        frame.put("endMs", partialEnd);
        session.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
    }

    /** 句终帧：完整句文本 + 置信度 + 时间戳 */
    private void sendFinal(WebSocketSession session, AsrDtos.Segment segment) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "final");
        frame.put("index", segment.index());
        frame.put("text", segment.text());
        frame.put("confidence", segment.confidence());
        frame.put("startMs", segment.startMs());
        frame.put("endMs", segment.endMs());
        session.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
    }

    /** 收尾帧：CLIENT_END/IDLE_TIMEOUT/ERROR（设计文档 4.3.1） */
    private void sendEnded(WebSocketSession session, String reason) throws IOException {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "ended");
        frame.put("reason", reason);
        session.sendMessage(new TextMessage(mapper.writeValueAsString(frame)));
    }

    /** 异常帧：统一 BaiResponse 失败结构（BaiException → code/message；格式非法 → ASR_1001；兜底 → ASR_2000） */
    private void sendFail(WebSocketSession session, String requestId, String code, String message) throws IOException {
        session.sendMessage(new TextMessage(mapper.writeValueAsString(
                BaiResponse.fail(requestId, code, message))));
    }

    /** 文本片段合成确定性音频指纹（无 audioRef 时，text+scene 驱动与 transcribeRef 一致的重现） */
    private static String synthesizeAudioRef(String text) {
        return "text:" + UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 仿真增量文本：取句文本前 40% 字符（至少 1 个） */
    private static String prefix(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int len = Math.max(1, text.length() * 2 / 5);
        return text.substring(0, Math.min(len, text.length()));
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

    private static boolean isBlank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
