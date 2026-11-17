package com.bai.asr;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 语音识别原子接口（权限点 ASR_TRANSCRIBE/ASR_STREAM/ASR_TASKS/ASR_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class AsrController {

    private final AsrService service;
    /** 限流：离线转写/语种探测单调用方 20 QPS */
    private final TokenBuckets transcribeLimiter = new TokenBuckets(20, 20);
    /** 限流：任务查询管理类 50 QPS */
    private final TokenBuckets taskLimiter = new TokenBuckets(50, 50);

    public AsrController(AsrService service) {
        this.service = service;
    }

    /** 离线转写（同步；>20MB 自动转异步返回任务；幂等：同 requestId 返回首次结果） */
    @PostMapping(value = "/transcribe", consumes = "multipart/form-data")
    public BaiResponse<Object> transcribe(@RequestParam("audio") MultipartFile audio,
                                          @RequestParam(value = "scene", required = false) String scene,
                                          @RequestParam(value = "lang", required = false) String lang,
                                          @RequestParam(value = "speakerDiarization", defaultValue = "false") boolean speakerDiarization,
                                          @RequestParam(value = "hotwords", required = false) String hotwords,
                                          @RequestParam(value = "maskSensitive", defaultValue = "true") boolean maskSensitive,
                                          @RequestHeader("X-Request-Id") String requestId) {
        limit(transcribeLimiter, requestId, "ASR_1008 触发限流（离线转写单调用方 20 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.transcribe(audio, scene, lang, speakerDiarization,
                hotwords, maskSensitive, requestId));
    }

    /** 离线转写（JSON 直连变体：audioRef 音频标识，consumes=application/json 与 multipart 区分） */
    @PostMapping(value = "/transcribe", consumes = "application/json")
    public BaiResponse<AsrDtos.AsrResult> transcribeRef(@RequestBody Map<String, Object> body,
                                                        @RequestHeader("X-Request-Id") String requestId) {
        limit(transcribeLimiter, requestId, "ASR_1008 触发限流（离线转写单调用方 20 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.transcribeRef(body, requestId));
    }

    /** 异步任务查询（>20MB 转异步后按 taskId 查询） */
    @GetMapping("/tasks/{taskId}")
    public BaiResponse<AsrDtos.AsrTask> getTask(@PathVariable String taskId,
                                                @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        limit(taskLimiter, requestId, "ASR_1008 触发限流（任务查询单调用方 50 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.getTask(taskId));
    }

    /** 轻量语种探测 */
    @PostMapping(value = "/detect-lang", consumes = "multipart/form-data")
    public BaiResponse<AsrDtos.LangDetectResult> detectLang(@RequestParam("audio") MultipartFile audio,
                                                            @RequestHeader("X-Request-Id") String requestId) {
        limit(transcribeLimiter, requestId, "ASR_1008 触发限流（语种探测单调用方 20 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.detectLang(audio, requestId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(AsrErrorCodes.RATE_LIMITED, message);
        }
    }
}
