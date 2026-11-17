package com.bai.face;

import java.util.List;

/**
 * 人脸核验输出统一结构（提示词库 A-06：VerifyResult）
 * <p>原因码：Q_LOW/R_LOW/A_LOW/C_LOW/N_HIT/ATTACK；结论 PASS/REVIEW/REJECT（任一关键项 REJECT 即整体 REJECT）
 */
public final class FaceVerifyDtos {

    private FaceVerifyDtos() {}

    /** 五维质量（清晰度/角度/遮挡/光照/模糊/是否通过） */
    public record Quality(double clarity, double angle, double occlusion, double lighting, double blur, boolean pass) {}

    /** 活体（得分/攻击类型/方式：STATIC 或 BLINK/OPEN_MOUTH/NOD/SHAKE） */
    public record Liveness(double score, String attack, String type) {}

    /** 1:1 人证比对（分数/阈值 0.75/是否通过/底片标识） */
    public record Compare(double score, double threshold, boolean pass, String idPhoto) {}

    /** 1:N 检索候选（库内记录 ID 与距离） */
    public record Candidate(String id, double distance) {}

    /** 检索命中与候选（命中判定仅作提示，业务判定归调用方） */
    public record SearchOut(boolean hit, List<Candidate> top) {}

    /** 核身结果（verify/compare/search 统一输出结构） */
    public record VerifyResult(String verifyId, String scene, String conclusion, List<String> reasons,
                               Quality quality, Liveness liveness, Compare compare, SearchOut search,
                               long elapsedMs) {}

    /** 异步任务（视频输入自动转异步，taskId 即 verifyId） */
    public record VerifyTask(String taskId, String status, VerifyResult result) {}
}
