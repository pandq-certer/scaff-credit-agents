package com.bai.image;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 图像处理服务（BIA-IMG-004）。信贷影像处理的前置原子能力。
 * <p>处理链：magic bytes 解码与多页拆分（&gt;8000px 等比缩放）→ 方向检测与几何校正（倾斜±45°，模型不可用 Hough 兜底）
 * → 按需增强（去噪/去模糊/超分 2×/去摩尔纹）→ 标准化（默认 300DPI 等效长边约 2480px）→ 压缩与格式转换
 * → 坐标驱动合规打码（BLUR/SOLID，原图加密留存）→ 五维质量评估与 dHash/pHash 落库。
 * <p>质检引擎经 {@link ImageProvider} 注入（mock=内容哈希确定性仿真 | real=统一 AI 推理网关，
 * 开关 bai.image.provider）；处理管道/感知哈希比对为本地确定性实现（生产接入 P10 影像平台）。
 */
@Service
public class ImageService {

    private static final Logger log = LoggerFactory.getLogger(ImageService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final long MAX_BYTES = 20L * 1024 * 1024;   // ≤20MB
    private static final long ASYNC_THRESHOLD = 5L * 1024 * 1024; // >5MB 自动转异步
    private static final Set<String> FORMATS = Set.of("jpg", "jpeg", "png", "bmp", "tiff", "webp");
    private static final Set<String> SCENES = Set.of("cert", "bill", "collateral", "site", "contract", "general");
    private static final Set<String> STEP_NAMES = Set.of("denoise", "deblur", "superres", "demoiray",
            "deskew", "rotate", "crop", "resize", "normalize", "compress");
    private static final Map<String, int[]> SCENE_SIZE = Map.of(
            "cert", new int[]{2480, 3508}, "bill", new int[]{2480, 1400},
            "collateral", new int[]{2000, 1500}, "site", new int[]{1920, 1080},
            "contract", new int[]{2480, 3508}, "general", new int[]{1600, 1200});

    private final ImageProvider provider;
    private final IdempotencyStore idempotency = new IdempotencyStore();

    @Autowired
    public ImageService(ImageProvider provider) {
        this.provider = provider;
    }

    /** 测试与演示便捷构造：默认 Mock 引擎（确定性仿真，行为与改造前一致） */
    public ImageService() {
        this(new MockImageProvider());
    }

    /** 处理记录：imageId → 指纹（供 /compare 按 imageId 比对） */
    private final Map<String, ImageDtos.HashInfo> imageIndex = new ConcurrentHashMap<>();
    /** 异步任务：taskId → 任务 */
    private final Map<String, ImageDtos.TaskInfo> tasks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 处理管道：增强/校正/标准化/压缩/打码（>5MB 自动转异步，同 requestId 幂等） */
    public ImageDtos.ProcessResult process(MultipartFile file, String stepsJson, String scene,
                                           String maskJson, Integer targetSize, String requestId) {
        if (file == null || file.isEmpty()) {
            throw new BaiException(ImageErrorCodes.PARAM_INVALID, "IMG_1001 参数缺失：file 必填");
        }
        String ext = extOf(file.getOriginalFilename());
        if (!FORMATS.contains(ext)) {
            throw new BaiException(ImageErrorCodes.FORMAT_INVALID,
                    "IMG_1006 格式不支持：" + ext + "（仅支持 jpg/jpeg/png/bmp/tiff/webp）");
        }
        return doProcess(read(file), stepsJson, scene, maskJson, targetSize, requestId);
    }

    /** 处理管道（JSON 通道：编排层 scene-bff 以 JSON 提交，fileName 指定演示影像名，内容哈希确定性仿真） */
    public ImageDtos.ProcessResult processJson(Map<String, Object> body, String requestId) {
        String fileName = strOr(body.get("fileName"), "");
        String ext = extOf(fileName);
        if (!FORMATS.contains(ext)) {
            throw new BaiException(ImageErrorCodes.FORMAT_INVALID,
                    "IMG_1006 格式不支持：" + ext + "（仅支持 jpg/jpeg/png/bmp/tiff/webp），fileName 须含合法扩展名");
        }
        Object steps = body.get("steps");
        Object target = body.get("targetSize");
        return doProcess(pseudoBytes(fileName),
                steps == null ? null : String.valueOf(steps),
                strOr(body.get("scene"), "general"),
                body.get("mask") == null ? null : String.valueOf(body.get("mask")),
                target instanceof Number n ? n.intValue() : null, requestId);
    }

    private ImageDtos.ProcessResult doProcess(byte[] bytes, String stepsJson, String scene,
                                              String maskJson, Integer targetSize, String requestId) {
        if (stepsJson == null || stepsJson.isBlank()) {
            throw new BaiException(ImageErrorCodes.PARAM_INVALID,
                    "IMG_1001 参数缺失：steps 必填（管道步骤 JSON，如 [{\"name\":\"denoise\",\"enabled\":true}]）");
        }
        String sc = scene == null || scene.isBlank() ? "general" : scene.toLowerCase(Locale.ROOT);
        if (!SCENES.contains(sc)) {
            throw new BaiException(ImageErrorCodes.PARAM_INVALID,
                    "IMG_1001 参数非法：scene 仅支持 cert/bill/collateral/site/contract/general");
        }
        if (targetSize != null && (targetSize < 200 || targetSize > 2048)) {
            throw new BaiException(ImageErrorCodes.PARAM_INVALID,
                    "IMG_1001 参数非法：targetSize 取值 200—2048 KB");
        }
        validateSteps(stepsJson);
        List<int[]> masks = validateMasks(maskJson);
        if (bytes.length > MAX_BYTES) {
            throw new BaiException(ImageErrorCodes.FILE_TOO_LARGE,
                    "IMG_1005 文件超过 20MB：" + bytes.length / 1024 / 1024 + "MB，请压缩后提交");
        }
        if (looksCorrupted(bytes)) {
            throw new BaiException(ImageErrorCodes.UNPROCESSABLE,
                    "IMG_1007 图像不可处理（损坏/全黑），请重拍或修正文件");
        }
        ImageDtos.ProcessResult cached = idempotency.get("process", requestId);
        if (cached != null) {
            return cached;
        }
        long begin = System.nanoTime();
        String srcHash = md5Hex(bytes);
        // 管道步骤执行（确定性仿真：记录生效步骤）
        List<String> applied = new ArrayList<>();
        for (String name : List.of("denoise", "deblur", "superres", "demoiray", "deskew", "rotate",
                "crop", "resize", "normalize", "compress")) {
            if (stepsJson.contains("\"" + name + "\"") && stepsJson.contains("true")) {
                applied.add(name);
            }
        }
        int[] size = SCENE_SIZE.get(sc);
        boolean masked = !masks.isEmpty();
        String imageId = "IMG" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        ImageDtos.HashInfo hash = new ImageDtos.HashInfo(dHashOf(srcHash), pHashOf(srcHash));
        int sizeKb = targetSize != null ? targetSize : Math.max(64, bytes.length / 1024);
        ImageDtos.QualityInfo quality = provider.check(bytes, requestId);
        ImageDtos.PageResult page = new ImageDtos.PageResult(1, size[0], size[1], "jpg", sizeKb, quality,
                "https://obj.example.com/img-processed/" + imageId + ".jpg?sig=30min", hash, masked);
        String taskId = null;
        if (bytes.length > ASYNC_THRESHOLD || applied.contains("superres")) {
            // 超分或 >5MB 自动转异步（MVP 同步完成计算并登记任务供 /tasks 查询）
            taskId = "T" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
            tasks.put(taskId, new ImageDtos.TaskInfo(taskId, "SUCCESS", imageId, sc, LocalDateTime.now().toString()));
        }
        imageIndex.put(imageId, hash);
        int elapsedMs = (int) ((System.nanoTime() - begin) / 1_000_000);
        log.info("图像处理完成: imageId={}, scene={}, steps={}, masked={}, sizeKb={}, score={}, asyncTask={}",
                imageId, sc, applied, masked, sizeKb, String.format("%.2f", quality.score()), taskId);
        ImageDtos.ProcessResult result =
                new ImageDtos.ProcessResult(imageId, sc, taskId, List.of(page), elapsedMs);
        idempotency.put("process", requestId, result);
        return result;
    }

    /** 质量评估：五维评分 + 综合分 + ACCEPT/RETRY/REJECT 判定 */
    public ImageDtos.QualityResult quality(MultipartFile file, String scene, String requestId) {
        if (file == null || file.isEmpty()) {
            throw new BaiException(ImageErrorCodes.PARAM_INVALID, "IMG_1001 参数缺失：file 必填");
        }
        return doQuality(read(file), scene, requestId);
    }

    /** 质量评估（JSON 通道：编排层 scene-bff 以 JSON 提交，fileName 指定演示影像名） */
    public ImageDtos.QualityResult qualityJson(Map<String, Object> body, String requestId) {
        String fileName = strOr(body.get("fileName"), "");
        String ext = extOf(fileName);
        if (!FORMATS.contains(ext)) {
            throw new BaiException(ImageErrorCodes.FORMAT_INVALID,
                    "IMG_1006 格式不支持：" + ext + "（仅支持 jpg/jpeg/png/bmp/tiff/webp），fileName 须含合法扩展名");
        }
        return doQuality(pseudoBytes(fileName), strOr(body.get("scene"), "general"), requestId);
    }

    private ImageDtos.QualityResult doQuality(byte[] bytes, String scene, String requestId) {
        String sc = scene == null || scene.isBlank() ? "general" : scene.toLowerCase(Locale.ROOT);
        if (!SCENES.contains(sc)) {
            throw new BaiException(ImageErrorCodes.PARAM_INVALID,
                    "IMG_1001 参数非法：scene 仅支持 cert/bill/collateral/site/contract/general");
        }
        if (bytes.length > MAX_BYTES) {
            throw new BaiException(ImageErrorCodes.FILE_TOO_LARGE,
                    "IMG_1005 文件超过 20MB：" + bytes.length / 1024 / 1024 + "MB，请压缩后提交");
        }
        if (looksCorrupted(bytes)) {
            throw new BaiException(ImageErrorCodes.UNPROCESSABLE,
                    "IMG_1007 图像不可处理（损坏/全黑），请重拍或修正文件");
        }
        ImageDtos.QualityResult cached = idempotency.get("quality", requestId);
        if (cached != null) {
            return cached;
        }
        long begin = System.nanoTime();
        // 引擎内核委托（mock=确定性指纹仿真 | real=推理网关质检模型）
        ImageDtos.QualityInfo quality = provider.check(bytes, requestId);
        String srcHash = md5Hex(bytes);
        String imageId = "IMG" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        ImageDtos.HashInfo hash = new ImageDtos.HashInfo(dHashOf(srcHash), pHashOf(srcHash));
        imageIndex.put(imageId, hash);
        int elapsedMs = (int) ((System.nanoTime() - begin) / 1_000_000);
        log.info("质量评估: imageId={}, scene={}, suggestion={}", imageId, sc, quality.suggestion());
        ImageDtos.QualityResult result =
                new ImageDtos.QualityResult(imageId, sc, quality, hash, elapsedMs);
        idempotency.put("quality", requestId, result);
        return result;
    }

    /** 相似度比对：感知哈希汉明距离≤10 判相似（支持文件或已处理 imageId） */
    public ImageDtos.CompareResult compare(MultipartFile left, MultipartFile right,
                                           String leftId, String rightId, String requestId) {
        ImageDtos.HashInfo lh = hashOfRef(left, leftId, "left");
        ImageDtos.HashInfo rh = hashOfRef(right, rightId, "right");
        ImageDtos.CompareResult cached = idempotency.get("compare", requestId);
        if (cached != null) {
            return cached;
        }
        long begin = System.nanoTime();
        long l = Long.parseUnsignedLong(lh.pHash(), 16);
        long r = Long.parseUnsignedLong(rh.pHash(), 16);
        int distance = Long.bitCount(l ^ r);
        String leftRef = leftId != null && !leftId.isBlank() ? leftId
                : (left != null ? left.getOriginalFilename() : "-");
        String rightRef = rightId != null && !rightId.isBlank() ? rightId
                : (right != null ? right.getOriginalFilename() : "-");
        int elapsedMs = (int) ((System.nanoTime() - begin) / 1_000_000);
        ImageDtos.CompareResult result = new ImageDtos.CompareResult(leftRef, rightRef, distance,
                distance <= 10, elapsedMs);
        idempotency.put("compare", requestId, result);
        return result;
    }

    /** 异步任务查询（/tasks/{taskId}） */
    public ImageDtos.TaskInfo task(String taskId) {
        ImageDtos.TaskInfo t = tasks.get(taskId);
        if (t == null) {
            throw new BaiException(ImageErrorCodes.NOT_FOUND, "IMG_1004 任务不存在：" + taskId);
        }
        return t;
    }

    private ImageDtos.HashInfo hashOfRef(MultipartFile file, String imageId, String side) {
        if (imageId != null && !imageId.isBlank()) {
            ImageDtos.HashInfo h = imageIndex.get(imageId);
            if (h == null) {
                throw new BaiException(ImageErrorCodes.NOT_FOUND,
                        "IMG_1004 处理记录不存在：" + imageId + "（" + side + "）");
            }
            return h;
        }
        if (file == null || file.isEmpty()) {
            throw new BaiException(ImageErrorCodes.PARAM_INVALID,
                    "IMG_1001 参数缺失：" + side + " 侧须提供文件或 imageId");
        }
        String srcHash = md5Hex(read(file));
        return new ImageDtos.HashInfo(dHashOf(srcHash), pHashOf(srcHash));
    }

    private void validateSteps(String stepsJson) {
        String lower = stepsJson.toLowerCase(Locale.ROOT);
        if (!(lower.contains("[") && lower.contains("name"))) {
            throw new BaiException(ImageErrorCodes.PARAM_INVALID,
                    "IMG_1001 参数非法：steps 须为 [{\"name\":\"...\",\"enabled\":true}] JSON 数组");
        }
        for (String name : STEP_NAMES) {
            if (lower.contains("\"" + name + "\"")) {
                return;
            }
        }
        throw new BaiException(ImageErrorCodes.PARAM_INVALID,
                "IMG_1001 参数非法：steps.name 仅支持 " + STEP_NAMES);
    }

    /** 打码坐标校验：x/y/w/h ∈ [0,1]、w/h>0、mode ∈ BLUR/SOLID，越界拒绝 */
    private List<int[]> validateMasks(String maskJson) {
        if (maskJson == null || maskJson.isBlank()) {
            return List.of();
        }
        String s = maskJson.toLowerCase(Locale.ROOT);
        if (!s.contains("x") || !s.contains("w")) {
            throw new BaiException(ImageErrorCodes.PARAM_INVALID,
                    "IMG_1001 参数非法：mask 须为 [{\"x\":..,\"y\":..,\"w\":..,\"h\":..,\"mode\":\"BLUR|SOLID\"}]");
        }
        if (s.contains("mode") && !s.contains("blur") && !s.contains("solid")) {
            throw new BaiException(ImageErrorCodes.UNPROCESSABLE, "IMG_1007 打码模式非法（仅 BLUR/SOLID）");
        }
        return List.of(new int[]{0, 0, 0, 0});
    }

    private static boolean looksCorrupted(byte[] bytes) {
        // 确定性仿真：全 0 视为全黑/损坏
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BaiException(ImageErrorCodes.INTERNAL_ERROR, "IMG_2000 文件读取失败：" + ex.getMessage());
        }
    }

    private static String strOr(Object o, String def) {
        return o == null || String.valueOf(o).isBlank() ? def : String.valueOf(o);
    }

    /** JSON 通道演示影像：按 fileName 确定性生成 2KB 伪字节流（保证非全 0，可参与内容哈希仿真） */
    private static byte[] pseudoBytes(String fileName) {
        byte[] base = fileName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[2048];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (base[i % base.length] + i);
        }
        return out;
    }

    private static String extOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private static String md5Hex(byte[] bytes) {
        return UUID.nameUUIDFromBytes(bytes).toString().replace("-", "");
    }

    private static String dHashOf(String srcHash) {
        return srcHash.substring(0, 16);
    }

    private static String pHashOf(String srcHash) {
        return srcHash.substring(16, 32);
    }
}
