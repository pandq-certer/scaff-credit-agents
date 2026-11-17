package com.bai.face;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 人脸核验服务。核身引擎（质量/活体/比对内核）经 {@link FaceVerifyProvider} 注入
 * （mock=确定性仿真 | real=统一 AI 推理网关，开关 bai.faceverify.provider）；1:N 检索（SEARCH）
 * 为二期能力保留本地仿真；P10 影像平台（底片缺省按 scene 获取）与 P12 esign-provider（意愿核验联动）经 scaff-iprov 预留接线。
 * <p>处理链：人脸检测与关键点对齐 → 五维质量评估 → 静默/动作活体（BLINK/OPEN_MOUTH/NOD/SHAKE）
 * → 防攻击检测（翻拍/纸片/面具/换脸）→ 512 维特征提取与 1:1 比对（阈值 0.75）
 * → 1:N 检索（命中 d≤0.30 判 REJECT 提示、0.30&lt;d≤0.40 判 REVIEW）→ 分级判定 → 加密留痕落库
 */
@Service
public class FaceVerifyService {

    private static final Logger log = LoggerFactory.getLogger(FaceVerifyService.class);

    private static final long MAX_SIZE = 50L * 1024 * 1024;  // 50MB
    private static final long MIN_SIZE = 1024L;              // 1KB（过小视为无法检出人脸）
    private static final Set<String> IMAGE_TYPES = Set.of("jpg", "jpeg", "png");
    private static final Set<String> VIDEO_TYPES = Set.of("mp4", "webm");
    private static final Set<String> SCENES = Set.of("remote_account", "counter", "mobile", "post_loan", "general");
    private static final String THRESHOLD_VERSION = "thr-2026.09";

    private final FaceVerifyProvider provider;
    private final ObjectMapper mapper;
    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final Map<String, FaceVerifyDtos.VerifyTask> tasks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    @Autowired
    public FaceVerifyService(FaceVerifyProvider provider, ObjectMapper mapper) {
        this.provider = provider;
        this.mapper = mapper;
    }

    /** 测试与演示便捷构造：默认 Mock 引擎（确定性仿真，行为与改造前一致） */
    public FaceVerifyService(ObjectMapper mapper) {
        this(new MockFaceVerifyProvider(), mapper);
    }

    /** 组合主流程：检测+活体+比对+检索（steps 可配；视频输入自动转异步，taskId 即 verifyId） */
    public FaceVerifyDtos.VerifyResult verify(MultipartFile image, String scene, MultipartFile idPhoto,
                                              String steps, boolean search, String callbackUrl, String requestId) {
        FaceVerifyDtos.VerifyResult cached = idempotency.get("verify", requestId);
        if (cached != null) {
            return cached;
        }
        validateImage(image);
        validateScene(scene);
        StepPlan plan = parseSteps(steps, search);
        boolean video = isVideo(image);
        byte[] content = readFile(image);
        byte[] idPhotoBytes = idPhoto != null && !idPhoto.isEmpty() ? readFile(idPhoto) : null;
        String idPhotoRef = idPhoto != null && idPhoto.getOriginalFilename() != null
                ? idPhoto.getOriginalFilename() : null;
        long start = System.currentTimeMillis();
        // 引擎内核委托（mock=确定性仿真 | real=统一 AI 推理网关，SEARCH 检索仅 mock 仿真）
        FaceVerifyProvider.FaceOutcome outcome = provider.verifyFace(content, idPhotoBytes, idPhotoRef,
                scene, plan.names(), plan.livenessAction(), requestId);
        List<String> reasons = new ArrayList<>();
        if (!outcome.quality().pass()) {
            reasons.add("Q_LOW");
        }
        if (outcome.liveness() != null && outcome.liveness().score() < 0.85) {
            reasons.add("A_LOW");
        }
        if (outcome.compare() != null && !outcome.compare().pass()) {
            reasons.add("R_LOW");
        }
        if (outcome.search() != null && outcome.search().hit()) {
            reasons.add("N_HIT");
        }
        String conclusion = resolveConclusion(reasons, outcome.search());
        long elapsed = System.currentTimeMillis() - start;
        FaceVerifyDtos.VerifyResult result = new FaceVerifyDtos.VerifyResult(nextId("FAC"), scene,
                conclusion, reasons, outcome.quality(), outcome.liveness(), outcome.compare(),
                outcome.search(), elapsed);
        idempotency.put("verify", requestId, result);
        if (video) {
            // 视频输入自动转异步：登记任务（仿真：同步完成），taskId 即 verifyId，可经 GET /tasks/{taskId} 查询
            tasks.put(result.verifyId(), new FaceVerifyDtos.VerifyTask(result.verifyId(), "DONE", result));
        }
        log.info("核身审计: requestId={}, scene={}, conclusion={}, reasons={}, mediaHash={}, featureHash={}, "
                        + "modelVersion={}, thresholdVersion={}, callbackUrl={}, elapsed={}ms",
                requestId, scene, result.conclusion(), result.reasons(), sha256Hex(content),
                featureHash(requestId), provider.modelVersion(), THRESHOLD_VERSION, callbackUrl, elapsed);
        return result;
    }

    /** 纯 1:1 人证比对（60 QPS 档；底片必填，缺失按 FAC_1004 返回） */
    public FaceVerifyDtos.VerifyResult compare(MultipartFile image, MultipartFile idPhoto, String scene, String requestId) {
        FaceVerifyDtos.VerifyResult cached = idempotency.get("compare", requestId);
        if (cached != null) {
            return cached;
        }
        validateImage(image);
        if (idPhoto == null || idPhoto.isEmpty()) {
            throw new BaiException(FaceVerifyErrorCodes.NOT_FOUND, "FAC_1004 证件底片不存在：纯人证比对必须提供 idPhoto 底片");
        }
        String sceneVal = scene == null || scene.isBlank() ? "general" : scene;
        validateScene(sceneVal);
        long start = System.currentTimeMillis();
        // 引擎内核委托（mock=确定性仿真 | real=推理网关 1:1 比对）
        FaceVerifyProvider.FaceOutcome outcome = provider.compareFace(readFile(image), readFile(idPhoto),
                idPhoto.getOriginalFilename(), sceneVal, requestId);
        FaceVerifyDtos.Compare compare = outcome.compare();
        boolean ok = compare.pass();
        List<String> reasons = ok ? List.of() : List.of("R_LOW");
        long elapsed = System.currentTimeMillis() - start;
        FaceVerifyDtos.VerifyResult result = new FaceVerifyDtos.VerifyResult(nextId("FAC"), sceneVal,
                ok ? "PASS" : "REJECT", reasons, outcome.quality(), null, compare, null, elapsed);
        idempotency.put("compare", requestId, result);
        log.info("比对审计: requestId={}, scene={}, conclusion={}, reasons={}, featureHash={}, elapsed={}ms",
                requestId, sceneVal, result.conclusion(), reasons, featureHash(requestId), elapsed);
        return result;
    }

    /** 1:N 人像检索（library=history/blacklist，topK 1—50 默认 10；命中判定仅作提示，业务判定归调用方） */
    public FaceVerifyDtos.VerifyResult searchFace(MultipartFile image, String library, Integer topK, String requestId) {
        FaceVerifyDtos.VerifyResult cached = idempotency.get("search", requestId);
        if (cached != null) {
            return cached;
        }
        validateImage(image);
        String lib = library == null || library.isBlank() ? "history" : library;
        if (!"history".equals(lib) && !"blacklist".equals(lib)) {
            throw new BaiException(FaceVerifyErrorCodes.PARAM_INVALID, "FAC_1001 library 非法：仅支持 history/blacklist");
        }
        int k = topK == null ? 10 : topK;
        if (k < 1 || k > 50) {
            throw new BaiException(FaceVerifyErrorCodes.PARAM_INVALID, "FAC_1001 topK 非法：取值 1—50");
        }
        long start = System.currentTimeMillis();
        Random r = new Random(Objects.hash(requestId, lib, "search"));
        FaceVerifyDtos.Quality quality = passQuality(r);
        List<FaceVerifyDtos.Candidate> top = new ArrayList<>();
        double d = round2(0.20 + r.nextDouble() * 0.45);
        for (int i = 0; i < k; i++) {
            String id = "blacklist".equals(lib)
                    ? "BLK_" + String.format("%08d", 20260000 + r.nextInt(999999))
                    : "FAC_" + String.format("%08d", 20260000 + r.nextInt(999999));
            top.add(new FaceVerifyDtos.Candidate(id, d));
            d = round2(d + 0.02 + r.nextDouble() * 0.06);
        }
        boolean hit = top.get(0).distance() <= 0.40;
        List<String> reasons = hit ? List.of("N_HIT") : List.of();
        FaceVerifyDtos.SearchOut search = new FaceVerifyDtos.SearchOut(hit, top);
        String conclusion = resolveConclusion(reasons, search);
        long elapsed = System.currentTimeMillis() - start;
        FaceVerifyDtos.VerifyResult result = new FaceVerifyDtos.VerifyResult(nextId("FAC"), "general", conclusion,
                reasons, quality, null, null, search, elapsed);
        idempotency.put("search", requestId, result);
        log.info("检索审计(仿真): requestId={}, library={}, conclusion={}, hit={}, topK={}, elapsed={}ms",
                requestId, lib, conclusion, hit, k, elapsed);
        return result;
    }

    /** 异步任务查询（视频核身） */
    public FaceVerifyDtos.VerifyTask getTask(String taskId) {
        FaceVerifyDtos.VerifyTask task = tasks.get(taskId);
        if (task == null) {
            throw new BaiException(FaceVerifyErrorCodes.NOT_FOUND, "FAC_1004 taskId 不存在，请校验任务号");
        }
        return task;
    }

    // ---------------- 编排与判定（引擎内核已下沉 Provider） ----------------

    /** steps 步骤计划：liveness/compare/search 未列出的步骤跳过 */
    private record StepPlan(Set<String> names, String livenessAction) {}

    /** 分级判定：任一关键项 REJECT 即整体 REJECT，出现 REVIEW 且无 REJECT 为 REVIEW，全部 PASS 为 PASS */
    private String resolveConclusion(List<String> reasons, FaceVerifyDtos.SearchOut search) {
        boolean reject = false;
        boolean review = false;
        for (String reason : reasons) {
            switch (reason) {
                case "ATTACK", "R_LOW", "A_LOW" -> reject = true;
                case "Q_LOW", "C_LOW" -> review = true;
                case "N_HIT" -> {
                    double d = search != null && !search.top().isEmpty() ? search.top().get(0).distance() : 1.0;
                    if (d <= 0.30) {
                        reject = true;
                    } else {
                        review = true;
                    }
                }
                default -> review = true;
            }
        }
        return reject ? "REJECT" : review ? "REVIEW" : "PASS";
    }

    private StepPlan parseSteps(String steps, boolean searchEnabled) {
        if (steps == null || steps.isBlank()) {
            Set<String> def = new LinkedHashSet<>(List.of("liveness", "compare", "search"));
            if (!searchEnabled) {
                def.remove("search");
            }
            return new StepPlan(def, "STATIC");
        }
        try {
            List<Map<String, Object>> raw = mapper.readValue(steps, new TypeReference<List<Map<String, Object>>>() {});
            Set<String> names = new LinkedHashSet<>();
            String action = "STATIC";
            for (Map<String, Object> s : raw) {
                Object name = s.get("name");
                if (name == null || name.toString().isBlank()) {
                    throw new BaiException(FaceVerifyErrorCodes.PARAM_INVALID, "FAC_1001 steps 非法：步骤名不能为空");
                }
                names.add(name.toString());
                if ("liveness".equals(name.toString()) && s.get("action") != null) {
                    action = s.get("action").toString();
                }
            }
            if (!searchEnabled) {
                names.remove("search");
            }
            return new StepPlan(names, action);
        } catch (BaiException e) {
            throw e;
        } catch (Exception e) {
            throw new BaiException(FaceVerifyErrorCodes.PARAM_INVALID, "FAC_1001 steps 非法：无法解析 JSON 步骤");
        }
    }

    // ---------------- 校验与工具 ----------------

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BaiException(FaceVerifyErrorCodes.PARAM_INVALID, "FAC_1001 参数缺失：image 文件必填");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BaiException(FaceVerifyErrorCodes.FILE_TOO_LARGE, "FAC_1005 文件超过 50MB，请压缩后提交");
        }
        if (file.getSize() < MIN_SIZE) {
            throw new BaiException(FaceVerifyErrorCodes.NO_FACE, "FAC_1007 图像无人脸或质量过差，请重新采集");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        if (!IMAGE_TYPES.contains(ext) && !VIDEO_TYPES.contains(ext)) {
            throw new BaiException(FaceVerifyErrorCodes.FORMAT_UNSUPPORTED, "FAC_1006 格式不支持：支持 jpg/png 图片与 mp4/webm 视频");
        }
    }

    private void validateScene(String scene) {
        if (scene == null || scene.isBlank()) {
            throw new BaiException(FaceVerifyErrorCodes.PARAM_INVALID, "FAC_1001 参数缺失：scene 必填");
        }
        if (!SCENES.contains(scene)) {
            throw new BaiException(FaceVerifyErrorCodes.PARAM_INVALID,
                    "FAC_1001 scene 非法：remote_account/counter/mobile/post_loan/general");
        }
    }

    private boolean isVideo(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        return VIDEO_TYPES.stream().anyMatch(name::endsWith);
    }

    private byte[] readFile(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BaiException(FaceVerifyErrorCodes.FORMAT_UNSUPPORTED, "FAC_1006 文件读取/解码失败，请转换格式后重试");
        }
    }

    private FaceVerifyDtos.Quality passQuality(Random r) {
        return new FaceVerifyDtos.Quality(round2(0.82 + r.nextDouble() * 0.17), round2(0.82 + r.nextDouble() * 0.17),
                round2(0.85 + r.nextDouble() * 0.14), round2(0.82 + r.nextDouble() * 0.17),
                round2(0.02 + r.nextDouble() * 0.12), true);
    }

    private String nextId(String prefix) {
        return prefix + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%05d", seq.incrementAndGet());
    }

    private String featureHash(String requestId) {
        // 特征向量不可逆加密存储（仿真：仅输出特征哈希前 32 位）
        return sha256Hex((requestId + "|feature").getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    private String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
