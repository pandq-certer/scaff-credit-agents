package com.bai.entity;

import java.util.List;
import java.util.Map;

/**
 * 实体归一输出统一结构（BIA-DTA-025：ResolveResult）
 * <p>指纹为证件号/信用代码 SHA-256 哈希（不落明文）；决策：证件哈希精确命中 → score≥0.90 MATCH，
 * 同名不同证件 → REVIEW(0.75-0.90)，弱信号 → CANDIDATE(0.60-0.75)，无命中 → NEW（新建黄金记录
 * goldenId=GRD+序号、版本 1，响应中 goldenId 为 null，新记录可经 GET /entities 查看）
 */
public final class EntityResolutionDtos {

    private EntityResolutionDtos() {}

    /** resolve 请求体（record 必填且序列化≤256KB；source 须为 core/channel/credit-report/external） */
    public record ResolveRequest(Map<String, Object> record, String source, String entityType) {}

    /** 候选（matchedFields：name/idCard/creditCode/phone/entityType） */
    public record Candidate(String goldenId, double score, List<String> matchedFields) {}

    /** 匹配决策（decision: MATCH/CANDIDATE/NEW/REVIEW；score 2 位小数） */
    public record Match(String decision, double score, List<Candidate> candidates) {}

    /** 归一结果（goldenId 仅 MATCH 决策返回；NEW 决策为 null） */
    public record ResolveResult(String resolveId, String entityType, String fingerprint, String goldenId,
                                Match match, long elapsedMs) {}

    /** match 请求体（候选检索 topK≤50，缺省 10） */
    public record MatchRequest(Map<String, Object> record, Integer topK) {}

    /** 检索结果 */
    public record MatchResult(String matchId, String entityType, List<Candidate> candidates, long elapsedMs) {}

    /** merge 请求体（goldenId/record/reason/operator 必填；合并版本递增） */
    public record MergeRequest(String goldenId, Map<String, Object> record, String reason, String operator) {}

    /** 合并结果 */
    public record MergeResult(String mergeId, String goldenId, int version, String status, long elapsedMs) {}

    /** 黄金记录视图（GET /entities，证件号脱敏前 4 后 4+****，仅存哈希不落明文） */
    public record GoldenRecordView(String goldenId, String name, String entityType, String idCardMasked,
                                   String creditCodeMasked, int version, String createdAt) {}

    /** 黄金记录列表（GET /entities） */
    public record EntitiesResult(int total, List<GoldenRecordView> entities) {}
}
