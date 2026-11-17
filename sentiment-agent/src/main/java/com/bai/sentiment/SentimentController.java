package com.bai.sentiment;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 情绪分析原子接口（权限点 SEN_ANALYZE/SEN_BATCH/SEN_SCHEMAS/SEN_STREAM/SEN_ADMIN；
 * Bearer Token 由网关统一鉴权，Controller 不校验 Token）
 * <p>流式通道条目约定为 WS，MVP 仿真以 SSE（text/event-stream）承载同一路径 /stream：
 * 稳定窗口 2 次一致上报、信号即时上报、空闲 60s 超时，供联调验证事件序列
 */
@RestController
public class SentimentController {

    private final SentimentService service;
    /** 限流（条目约定）：analyze 200 QPS / batch 50 QPS；流式并发 100/实例 */
    private final TokenBuckets analyzeLimiter = new TokenBuckets(200, 400);
    private final TokenBuckets batchLimiter = new TokenBuckets(50, 100);
    private final AtomicInteger streaming = new AtomicInteger();

    public SentimentController(SentimentService service) {
        this.service = service;
    }

    /** 同步单条分析 */
    @PostMapping("/analyze")
    public BaiResponse<SentimentDtos.SentimentResult> analyze(@RequestBody SentimentDtos.AnalyzeRequest request,
                                                              @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId, analyzeLimiter, "analyze 200 QPS");
        return BaiResponse.ok(requestId, service.analyze(request, requestId));
    }

    /** 批量分析（≤100000 条，异步） */
    @PostMapping("/batch")
    public BaiResponse<SentimentDtos.BatchResult> batch(@RequestBody SentimentDtos.BatchRequest request,
                                                        @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId, batchLimiter, "batch 50 QPS");
        return BaiResponse.ok(requestId, service.batch(request, requestId));
    }

    /** 情绪体系与信号体系 */
    @GetMapping("/schemas")
    public BaiResponse<SentimentDtos.SentimentSchemas> schemas(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.schemas());
    }

    /** 流式情绪通道（条目为 WS；MVP 以 SSE 仿真：稳定窗口 2 次一致上报后完成，空闲 60s 超时，并发 100/实例） */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(value = "sessionId", required = false) String sessionId,
                             @RequestParam(value = "text", required = false) String text) {
        if (streaming.get() >= 100) {
            throw new BaiException(SentimentErrorCodes.RATE_LIMITED, "SEN_1007 流式并发超限（100/实例），请稍后重试");
        }
        streaming.incrementAndGet();
        SseEmitter emitter = new SseEmitter(60_000L);
        AtomicBoolean released = new AtomicBoolean(false);
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                streaming.decrementAndGet();
            }
        };
        emitter.onCompletion(release);
        emitter.onTimeout(release);
        try {
            SentimentDtos.Emotion emotion = service.emotionOf(text);
            String sid = sessionId == null ? "" : sessionId.replace("\"", "'");
            emitter.send(SseEmitter.event().name("delta")
                    .data("{\"type\":\"START\",\"sessionId\":\"" + sid + "\"}", MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().name("delta")
                    .data("{\"type\":\"PARTIAL\",\"category\":\"" + emotion.category() + "\",\"stable\":false}",
                            MediaType.APPLICATION_JSON));
            // 稳定窗口 2 次一致后上报 STABLE（信号命中即时上报，仿真合并入稳定帧）
            emitter.send(SseEmitter.event().name("delta")
                    .data("{\"type\":\"STABLE\",\"category\":\"" + emotion.category() + "\",\"stableHits\":2}",
                            MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(String requestId, TokenBuckets limiter, String detail) {
        if (!limiter.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(SentimentErrorCodes.RATE_LIMITED, "SEN_1007 触发限流（" + detail + "），请按 Retry-After 退避");
        }
    }
}
