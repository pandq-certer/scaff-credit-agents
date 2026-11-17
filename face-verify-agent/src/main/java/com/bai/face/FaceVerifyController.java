package com.bai.face;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.common.TokenBuckets;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人脸核验原子接口（权限点 FAC_VERIFY/FAC_COMPARE/FAC_SEARCH/FAC_TASKS/FAC_ADMIN，
 * 现场照/视频查看 FAC_MEDIA_READ 双人复核；Bearer Token 由网关统一鉴权，Controller 不校验 Token）
 */
@RestController
public class FaceVerifyController {

    private final FaceVerifyService service;
    /** 限流（条目约定）：verify 30 QPS / compare 60 QPS；search 沿用 30 QPS 档，突发 2 倍 */
    private final TokenBuckets verifyLimiter = new TokenBuckets(30, 60);
    private final TokenBuckets compareLimiter = new TokenBuckets(60, 120);
    private final TokenBuckets searchLimiter = new TokenBuckets(30, 60);
    /** 单 IP 10 次/min 防爆破（仅 verify 主流程），异常频次触发风控事件 */
    private final Map<String, long[]> ipMinuteWindows = new ConcurrentHashMap<>();

    public FaceVerifyController(FaceVerifyService service) {
        this.service = service;
    }

    /** 检测+活体+比对+检索组合主流程（steps 可配；视频输入自动转异步） */
    @PostMapping("/verify")
    public BaiResponse<FaceVerifyDtos.VerifyResult> verify(
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "scene", required = false) String scene,
            @RequestParam(value = "idPhoto", required = false) MultipartFile idPhoto,
            @RequestParam(value = "steps", required = false) String steps,
            @RequestParam(value = "search", defaultValue = "true") boolean search,
            @RequestParam(value = "callbackUrl", required = false) String callbackUrl,
            @RequestHeader("X-Request-Id") String requestId,
            HttpServletRequest httpRequest) {
        if (!verifyLimiter.allow(requestId)) {
            throw new BaiException(FaceVerifyErrorCodes.RATE_LIMITED, "FAC_1008 触发限流（verify 30 QPS），请按 Retry-After 退避");
        }
        limitPerIp(httpRequest.getRemoteAddr());
        return BaiResponse.ok(requestId, service.verify(image, scene, idPhoto, steps, search, callbackUrl, requestId));
    }

    /** 纯 1:1 人证比对 */
    @PostMapping("/compare")
    public BaiResponse<FaceVerifyDtos.VerifyResult> compare(
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "idPhoto", required = false) MultipartFile idPhoto,
            @RequestParam(value = "scene", required = false) String scene,
            @RequestHeader("X-Request-Id") String requestId) {
        if (!compareLimiter.allow(requestId)) {
            throw new BaiException(FaceVerifyErrorCodes.RATE_LIMITED, "FAC_1008 触发限流（compare 60 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.compare(image, idPhoto, scene, requestId));
    }

    /** 1:N 人像检索（library=history/blacklist，topK=10） */
    @PostMapping("/search")
    public BaiResponse<FaceVerifyDtos.VerifyResult> search(
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "library", required = false) String library,
            @RequestParam(value = "topK", required = false) Integer topK,
            @RequestHeader("X-Request-Id") String requestId) {
        if (!searchLimiter.allow(requestId)) {
            throw new BaiException(FaceVerifyErrorCodes.RATE_LIMITED, "FAC_1008 触发限流（search 30 QPS），请按 Retry-After 退避");
        }
        return BaiResponse.ok(requestId, service.searchFace(image, library, topK, requestId));
    }

    /** 异步任务查询（视频核身，taskId 即 verifyId） */
    @GetMapping("/tasks/{taskId}")
    public BaiResponse<FaceVerifyDtos.VerifyTask> getTask(@PathVariable String taskId,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        return BaiResponse.ok(requestId, service.getTask(taskId));
    }

    /** 探活 */
    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    /** 单 IP 10 次/min 防爆破：key=ip，value=[分钟窗口, 计数] */
    private void limitPerIp(String ip) {
        long minute = System.currentTimeMillis() / 60_000L;
        long[] window = ipMinuteWindows.computeIfAbsent(ip, k -> new long[]{minute, 0});
        synchronized (window) {
            if (window[0] != minute) {
                window[0] = minute;
                window[1] = 0;
            }
            if (++window[1] > 10) {
                throw new BaiException(FaceVerifyErrorCodes.RATE_LIMITED,
                        "FAC_1008 单 IP 超过 10 次/min 防爆破限制，已触发风控事件，请稍后重试");
            }
        }
    }
}
