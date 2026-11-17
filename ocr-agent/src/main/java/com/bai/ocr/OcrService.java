package com.bai.ocr;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OCR 识别服务（编排层）：入参校验、幂等、任务管理、敏感脱敏与审计日志；
 * 引擎内核经 {@link OcrProvider} 注入（mock=确定性仿真 | real=统一推理网关，开关 bai.ocr.provider）。
 * <p>处理链：校验入参 → Provider 引擎识别 → 置信度评估 → 敏感脱敏与落库
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private static final long MIN_SIZE = 1024L;          // 1KB
    private static final long MAX_SIZE = 10 * 1024 * 1024L; // 10MB
    private static final Set<String> SUPPORTED_TYPES = Set.of("jpg", "jpeg", "png", "pdf", "tiff", "bmp");

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final Map<String, OcrDtos.OcrTask> tasks = new ConcurrentHashMap<>();
    /** JSON 直连识别 docId 序号 */
    private final AtomicLong jsonSeq = new AtomicLong();
    private final OcrProvider provider;

    @Autowired
    public OcrService(OcrProvider provider) {
        this.provider = provider;
    }

    /** 测试与演示便捷构造：默认 Mock 引擎（确定性仿真，行为与改造前一致） */
    public OcrService() {
        this(new MockOcrProvider());
    }

    /** 同步识别（幂等：requestId 相同返回首次结果，缓存 24h） */
    public OcrDtos.OcrResult recognize(MultipartFile file, String docType, String requestId) {
        OcrDtos.OcrResult cached = idempotency.get("ocr", requestId);
        if (cached != null) {
            return cached;
        }
        validate(file);
        OcrDtos.OcrResult result = provider.recognize(bytes(file), file.getOriginalFilename(), docType, requestId);
        idempotency.put("ocr", requestId, result);
        log.info("OCR 识别完成: requestId={}, docType={}, elapsed={}ms", requestId, result.docType(), result.elapsedMs());
        return result;
    }

    /** 异步任务提交（大图/批量） */
    public OcrDtos.OcrTask submitTask(MultipartFile file, String docType, String requestId) {
        String cachedTaskId = null;
        OcrDtos.OcrResult cached = idempotency.get("ocr-task", requestId);
        if (cached != null) {
            cachedTaskId = cached.ocrId();
        }
        if (cachedTaskId != null) {
            OcrDtos.OcrTask existing = tasks.get(cachedTaskId);
            if (existing != null) {
                return existing;
            }
        }
        validate(file);
        String taskId = "OT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        OcrDtos.OcrTask task = new OcrDtos.OcrTask(taskId, "RUNNING", null);
        tasks.put(taskId, task);
        OcrDtos.OcrResult result = provider.recognize(bytes(file), file.getOriginalFilename(), docType, requestId);
        idempotency.put("ocr-task", requestId, result);
        tasks.put(taskId, new OcrDtos.OcrTask(taskId, "DONE", result));
        return tasks.get(taskId);
    }

    /** 任务查询 */
    public OcrDtos.OcrTask getTask(String taskId) {
        OcrDtos.OcrTask task = tasks.get(taskId);
        if (task == null) {
            throw new BaiException(OcrErrorCodes.TASK_NOT_FOUND, "OCR_1004 taskId 不存在，请校验任务号");
        }
        return task;
    }

    /**
     * JSON 直连识别（演示影像标识 imageRef，无需文件上传；场景 BFF JSON 直传时使用）。
     * <p>字段表由 Provider 生成（mock=确定性演示字段；real=推理网关按 imageRef 解析）；mask=true 时个人敏感字段（证件号/手机号）脱敏。
     */
    public OcrDtos.JsonRecognizeResult recognizeJson(Map<String, Object> body, String requestId) {
        long start = System.currentTimeMillis();
        Object refRaw = body == null ? null : body.get("imageRef");
        if (refRaw == null || String.valueOf(refRaw).isBlank()) {
            throw new BaiException(OcrErrorCodes.PARAM_INVALID, "OCR_1001 参数缺失：imageRef 必填");
        }
        String imageRef = String.valueOf(refRaw);
        Object typeRaw = body.get("docType");
        String docType = typeRaw == null || String.valueOf(typeRaw).isBlank()
                ? "AUTO" : String.valueOf(typeRaw).trim().toUpperCase();
        Object maskRaw = body.get("mask");
        boolean mask = maskRaw == null || Boolean.parseBoolean(String.valueOf(maskRaw));
        Collection<?> whitelist = body.get("fields") instanceof Collection<?> c && !c.isEmpty() ? c : null;

        OcrProvider.RefResult ref = provider.recognizeRef(imageRef, docType, requestId);
        Map<String, String> fields = ref.fields();
        if (mask) {
            fields.replaceAll((k, v) -> isSensitiveKey(k) ? maskValue(v) : v);
        }
        if (whitelist != null) {
            fields.keySet().retainAll(whitelist.stream().map(String::valueOf).toList());
        }
        String docId = "DOC" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%05d", jsonSeq.incrementAndGet());
        long elapsed = System.currentTimeMillis() - start;
        log.info("OCR JSON 直连识别: requestId={}, docId={}, docType={}, imageRef={}, fields={}, maskApplied={}",
                requestId, docId, docType, imageRef, fields.size(), mask);
        return new OcrDtos.JsonRecognizeResult(docId, docType, fields, ref.confidence(), mask, elapsed);
    }

    /** 个人敏感字段键（证件号/手机号） */
    private boolean isSensitiveKey(String key) {
        return key != null && (key.contains("证件号") || key.contains("手机号"));
    }

    /** 脱敏为 6222****1234 样式（保留前 4 后 4） */
    private String maskValue(String v) {
        if (v == null || v.length() <= 8) {
            return v == null ? null : v.charAt(0) + "****";
        }
        return v.substring(0, 4) + "****" + v.substring(v.length() - 4);
    }

    private byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BaiException(OcrErrorCodes.IMAGE_CORRUPTED, "OCR_1007 图像不可解析（读取失败），请重新上传");
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BaiException(OcrErrorCodes.PARAM_INVALID, "OCR_1001 参数缺失：image 文件必填");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BaiException(OcrErrorCodes.FILE_TOO_LARGE, "OCR_1005 图像超过 10MB，请压缩或裁剪后重试");
        }
        if (file.getSize() < MIN_SIZE) {
            throw new BaiException(OcrErrorCodes.IMAGE_CORRUPTED, "OCR_1007 图像不可解析（损坏/空），请重新上传");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        if (!SUPPORTED_TYPES.contains(ext)) {
            throw new BaiException(OcrErrorCodes.FORMAT_UNSUPPORTED, "OCR_1006 图像格式不支持，支持 jpg/jpeg/png/pdf/tiff/bmp");
        }
    }
}
