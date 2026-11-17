package com.bai.tts;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文字转语音原子接口（权限点 TTS_SYNTHESIZE/TTS_STREAM/TTS_VOICES/TTS_TASKS，Bearer Token 由网关统一鉴权）
 */
@RestController
public class TtsController {

    private final TtsService service;
    /** 限流：合成单调用方 100 QPS（设计 4.6） */
    private final TokenBuckets synthLimiter = new TokenBuckets(100, 100);
    /** 限流：音色列表/任务查询 50 QPS */
    private final TokenBuckets queryLimiter = new TokenBuckets(50, 50);

    public TtsController(TtsService service) {
        this.service = service;
    }

    /**
     * 语音合成（同步；&gt;5000 字或 texts 批量自动转异步返回 {taskId, status:PENDING}；
     * 幂等：同 requestId 重复提交返回首次结果，缓存 24h）
     */
    @PostMapping(value = "/synthesize", consumes = "multipart/form-data")
    public BaiResponse<Object> synthesize(@RequestParam(value = "text", required = false) String text,
                                          @RequestParam(value = "texts", required = false) String texts,
                                          @RequestParam(value = "voiceId", required = false) String voiceId,
                                          @RequestParam(value = "scene", required = false) String scene,
                                          @RequestParam(value = "style", required = false) String style,
                                          @RequestParam(value = "rate", required = false) Double rate,
                                          @RequestParam(value = "format", required = false) String format,
                                          @RequestParam(value = "sampleRate", required = false) Integer sampleRate,
                                          @RequestHeader("X-Request-Id") String requestId) {
        limit(synthLimiter, requestId, "TTS_1008 触发限流（合成单调用方 100 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.synthesize(text, texts, voiceId, scene, style,
                rate, format, sampleRate, requestId));
    }

    /** 异步任务查询（&gt;5000 字/批量转异步后按 taskId 查询） */
    @GetMapping("/tasks/{taskId}")
    public BaiResponse<TtsDtos.TtsTask> getTask(@PathVariable String taskId,
                                                @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        limit(queryLimiter, requestId, "TTS_1008 触发限流（任务查询单调用方 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.getTask(taskId));
    }

    /** 音色列表（设计 4.4：voiceId/name/gender/style/scene/sampleRates） */
    @GetMapping("/voices")
    public BaiResponse<List<TtsDtos.Voice>> voices(@RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        limit(queryLimiter, requestId, "TTS_1008 触发限流（音色列表单调用方 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.listVoices());
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(TtsErrorCodes.RATE_LIMITED, message);
        }
    }
}
