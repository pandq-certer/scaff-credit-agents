package com.bai.face;

import java.util.Set;

/**
 * 人脸核验引擎提供者（质量/活体/比对内核抽象）：mock=确定性仿真（默认，联调回归基线）| real=统一 AI 推理网关。
 * <p>网关契约（AI推理网关API契约.md）：POST /v1/face/verify
 * 请求 {content_base64, content_base64_ref, scene, verify_type} → 响应 {similarity, passed, liveness_score}，
 * 比对阈值 0.75；1:N 检索（SEARCH）为二期能力，保留 mock 仿真不接网关。
 */
public interface FaceVerifyProvider {

    /** 组合核身：五维质量 + 活体 + 1:1 比对 + 1:N 检索（步骤由 steps 决定，未列出的跳过） */
    FaceOutcome verifyFace(byte[] content, byte[] idPhoto, String idPhotoRef, String scene,
                           Set<String> steps, String livenessAction, String requestId);

    /** 纯 1:1 人证比对（底片必填，由 Service 校验后调用） */
    FaceOutcome compareFace(byte[] content, byte[] idPhoto, String idPhotoRef, String scene, String requestId);

    /** 审计留痕用模型版本标识（mock=sim-v1.0.0，real=网关版本） */
    default String modelVersion() {
        return "sim-v1.0.0";
    }

    /** 单次评估输出：quality 恒非空；liveness/compare/search 按执行步骤可为 null */
    record FaceOutcome(FaceVerifyDtos.Quality quality, FaceVerifyDtos.Liveness liveness,
                       FaceVerifyDtos.Compare compare, FaceVerifyDtos.SearchOut search) {}
}
