package com.bai.image;

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

/**
 * 图像处理原子接口（权限点 IMG_PROCESS/IMG_QUALITY/IMG_COMPARE/IMG_TASKS/IMG_ADMIN，Bearer Token 由网关统一鉴权）
 */
@RestController
public class ImageController {

    private final ImageService service;
    /** 限流：process 单调用方 30 QPS、quality/compare 60 QPS */
    private final TokenBuckets processLimiter = new TokenBuckets(30, 30);
    private final TokenBuckets qualityLimiter = new TokenBuckets(60, 60);

    public ImageController(ImageService service) {
        this.service = service;
    }

    /** 处理管道（增强/校正/标准化/压缩/打码，>5MB 或超分自动转异步并附 taskId） */
    @PostMapping("/process")
    public BaiResponse<ImageDtos.ProcessResult> process(
            @RequestParam("file") MultipartFile file,
            @RequestParam("steps") String steps,
            @RequestParam(value = "scene", required = false) String scene,
            @RequestParam(value = "mask", required = false) String mask,
            @RequestParam(value = "targetSize", required = false) Integer targetSize,
            @RequestHeader("X-Request-Id") String requestId) {
        limit(processLimiter, requestId, "IMG_1008 触发限流（process 单调用方 30 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.process(file, steps, scene, mask, targetSize, requestId));
    }

    /** 质量评估（五维评分 + ACCEPT/RETRY/REJECT 判定） */
    @PostMapping("/quality")
    public BaiResponse<ImageDtos.QualityResult> quality(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "scene", required = false) String scene,
            @RequestHeader("X-Request-Id") String requestId) {
        limit(qualityLimiter, requestId, "IMG_1008 触发限流（quality 单调用方 60 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.quality(file, scene, requestId));
    }

    /** 相似度比对（感知哈希汉明距离≤10 判相似；两侧各传文件或 imageId） */
    @PostMapping("/compare")
    public BaiResponse<ImageDtos.CompareResult> compare(
            @RequestParam(value = "left", required = false) MultipartFile left,
            @RequestParam(value = "right", required = false) MultipartFile right,
            @RequestParam(value = "leftId", required = false) String leftId,
            @RequestParam(value = "rightId", required = false) String rightId,
            @RequestHeader("X-Request-Id") String requestId) {
        limit(qualityLimiter, requestId, "IMG_1008 触发限流（compare 单调用方 60 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.compare(left, right, leftId, rightId, requestId));
    }

    /** 处理管道（JSON 通道：编排层 scene-bff 调用；与 multipart /process 同路径，按 Content-Type 分流） */
    @PostMapping(value = "/process", consumes = "application/json")
    public BaiResponse<ImageDtos.ProcessResult> processJson(@RequestBody java.util.Map<String, Object> body,
            @RequestHeader("X-Request-Id") String requestId) {
        limit(processLimiter, requestId, "IMG_1008 触发限流（process 单调用方 30 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.processJson(body, requestId));
    }

    /** 质量评估（JSON 通道：编排层 scene-bff 调用；与 multipart /quality 同路径，按 Content-Type 分流） */
    @PostMapping(value = "/quality", consumes = "application/json")
    public BaiResponse<ImageDtos.QualityResult> qualityJson(@RequestBody java.util.Map<String, Object> body,
            @RequestHeader("X-Request-Id") String requestId) {
        limit(qualityLimiter, requestId, "IMG_1008 触发限流（quality 单调用方 60 QPS），请按 Retry-After 退避重试");
        return BaiResponse.ok(requestId, service.qualityJson(body, requestId));
    }

    /** 异步任务查询 */
    @GetMapping("/tasks/{taskId}")
    public BaiResponse<ImageDtos.TaskInfo> task(@PathVariable String taskId,
                                                @RequestHeader("X-Request-Id") String requestId) {
        return BaiResponse.ok(requestId, service.task(taskId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    private void limit(TokenBuckets bucket, String requestId, String message) {
        if (!bucket.allow(requestId == null ? "anonymous" : requestId)) {
            throw new BaiException(ImageErrorCodes.RATE_LIMITED, message);
        }
    }
}
