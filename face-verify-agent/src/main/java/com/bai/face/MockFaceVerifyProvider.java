package com.bai.face;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * 仿真引擎提供者（确定性：同 requestId 结果一致）。
 * <p>原 Service 内核仿真逻辑原样下沉，随机流顺序与阈值逐行保持一致，保证回归基线行为不变。
 * 1:N 检索（SEARCH）为二期能力，恒为本地仿真。
 */
public class MockFaceVerifyProvider implements FaceVerifyProvider {

    private static final double COMPARE_THRESHOLD = 0.75;

    @Override
    public FaceOutcome verifyFace(byte[] content, byte[] idPhoto, String idPhotoRef, String scene,
                                  Set<String> steps, String livenessAction, String requestId) {
        Random r = new Random(Objects.hash(requestId, scene, "verify"));

        // 1. 人脸检测与五维质量评估（多人脸取最大清晰脸并提示，仿真略）
        double clarity = round2(0.72 + r.nextDouble() * 0.27);
        double angle = round2(0.75 + r.nextDouble() * 0.24);
        double occlusion = round2(0.80 + r.nextDouble() * 0.19);
        double lighting = round2(0.75 + r.nextDouble() * 0.24);
        double blur = round2(0.02 + r.nextDouble() * 0.18);
        boolean qualityPass = clarity >= 0.80 && angle >= 0.80 && occlusion >= 0.80 && lighting >= 0.80 && blur <= 0.20;
        FaceVerifyDtos.Quality quality = new FaceVerifyDtos.Quality(clarity, angle, occlusion, lighting, blur, qualityPass);

        // 2. 活体检测（steps 未包含 liveness 时跳过；防攻击检测仿真恒为 NONE）
        FaceVerifyDtos.Liveness liveness = null;
        if (steps.contains("liveness")) {
            double score = round2(0.82 + r.nextDouble() * 0.17);
            liveness = new FaceVerifyDtos.Liveness(score, "NONE", livenessAction);
        }

        // 3. 特征提取与 1:1 比对（512 维特征，余弦相似度，阈值按场景固定 0.75）
        FaceVerifyDtos.Compare compare = null;
        if (steps.contains("compare")) {
            // 底片缺省按 scene 从 P10 影像库取（仿真恒成功）
            String ref = idPhotoRef != null ? idPhotoRef : "PHOTO_001";
            double score = round2(0.70 + r.nextDouble() * 0.29);
            compare = new FaceVerifyDtos.Compare(score, COMPARE_THRESHOLD, score >= COMPARE_THRESHOLD, ref);
        }

        // 4. 1:N 检索（topK=10，输出候选距离与命中标记；二期能力保留仿真）
        FaceVerifyDtos.SearchOut search = null;
        if (steps.contains("search")) {
            List<FaceVerifyDtos.Candidate> top = new ArrayList<>();
            double d = round2(0.25 + r.nextDouble() * 0.35);
            for (int i = 0; i < 3; i++) {
                top.add(new FaceVerifyDtos.Candidate("FAC_" + String.format("%08d", 20260000 + r.nextInt(999999)), d));
                d = round2(d + 0.05 + r.nextDouble() * 0.05);
            }
            search = new FaceVerifyDtos.SearchOut(top.get(0).distance() <= 0.40, top);
        }
        return new FaceOutcome(quality, liveness, compare, search);
    }

    @Override
    public FaceOutcome compareFace(byte[] content, byte[] idPhoto, String idPhotoRef, String scene, String requestId) {
        Random r = new Random(Objects.hash(requestId, "compare"));
        FaceVerifyDtos.Quality quality = passQuality(r);
        double score = round2(0.70 + r.nextDouble() * 0.29);
        FaceVerifyDtos.Compare compare = new FaceVerifyDtos.Compare(score, COMPARE_THRESHOLD,
                score >= COMPARE_THRESHOLD, idPhotoRef != null ? idPhotoRef : "PHOTO_001");
        return new FaceOutcome(quality, null, compare, null);
    }

    private FaceVerifyDtos.Quality passQuality(Random r) {
        return new FaceVerifyDtos.Quality(round2(0.82 + r.nextDouble() * 0.17), round2(0.82 + r.nextDouble() * 0.17),
                round2(0.85 + r.nextDouble() * 0.14), round2(0.82 + r.nextDouble() * 0.17),
                round2(0.02 + r.nextDouble() * 0.12), true);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
