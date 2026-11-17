package com.bai.ocr;

import com.bai.common.BaiResponse;
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
 * OCR 原子接口（权限点 OCR_RECOGNIZE / OCR_TASKS，Bearer Token，由网关统一鉴权）
 */
@RestController
public class OcrController {

    private final OcrService service;
    private final com.bai.common.TokenBuckets limiter = new com.bai.common.TokenBuckets(100, 200);

    public OcrController(OcrService service) {
        this.service = service;
    }

    /** 同步识别（单图/多图中的单图，大图或批量走异步） */
    @PostMapping("/recognize")
    public BaiResponse<OcrDtos.OcrResult> recognize(@RequestParam("image") MultipartFile image,
                                                    @RequestParam(value = "docType", required = false) String docType,
                                                    @RequestParam(value = "maskSensitive", defaultValue = "true") boolean maskSensitive,
                                                    @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId);
        return BaiResponse.ok(requestId, service.recognize(image, docType, requestId));
    }

    /** 同步识别（JSON 直连变体：演示影像标识 imageRef，consumes=application/json 与 multipart 区分） */
    @PostMapping(value = "/recognize", consumes = "application/json")
    public BaiResponse<OcrDtos.JsonRecognizeResult> recognizeJson(@RequestBody Map<String, Object> body,
                                                                  @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId);
        return BaiResponse.ok(requestId, service.recognizeJson(body, requestId));
    }

    /** 异步任务提交（大图/批量） */
    @PostMapping("/tasks")
    public BaiResponse<OcrDtos.OcrTask> submitTask(@RequestParam("image") MultipartFile image,
                                                   @RequestParam(value = "docType", required = false) String docType,
                                                   @RequestHeader("X-Request-Id") String requestId) {
        limit(requestId);
        return BaiResponse.ok(requestId, service.submitTask(image, docType, requestId));
    }

    /** 异步任务查询 */
    @GetMapping("/tasks/{taskId}")
    public BaiResponse<OcrDtos.OcrTask> getTask(@PathVariable String taskId,
                                                @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.getTask(taskId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(String requestId) {
        if (!limiter.allow(requestId == null ? "anonymous" : requestId)) {
            throw new com.bai.common.BaiException(OcrErrorCodes.RATE_LIMITED,
                    "OCR_1008 触发限流（QPS 100/突发 200），请按 Retry-After 退避重试");
        }
    }
}
