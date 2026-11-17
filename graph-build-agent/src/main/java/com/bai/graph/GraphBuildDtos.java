package com.bai.graph;

import java.util.List;
import java.util.Map;

/**
 * 图谱构建输出统一结构（BIA-DTA-026：BuildResult/JobView）
 * <p>构建为异步任务仿真（PENDING→RUNNING→DONE，约 200ms，用于联调）；
 * stats.goldenLinked 按 0.99×edgesAdded 取整（确定性）；
 * 节点/边为确定性仿真列表（含 validFrom/validTo/source 属性）
 */
public final class GraphBuildDtos {

    private GraphBuildDtos() {}

    /** 构建范围（orgId=机构；relationTypes⊆关系类型字典） */
    public record Scope(String orgId, List<String> relationTypes) {}

    /** build 请求体（mode 须为 FULL/INCREMENTAL；INCREMENTAL 必填 dataVersion，格式 yyyyMMdd-HHmm） */
    public record BuildRequest(String mode, Scope scope, String dataVersion) {}

    /** 构建统计（goldenLinked=0.99×edgesAdded 取整，确定性） */
    public record Stats(long nodesAdded, long nodesUpdated, long edgesAdded, long edgesUpdated, long goldenLinked) {}

    /** 构建受理结果（异步任务，status 初始 PENDING；stats 完成后经 GET /jobs/{jobId} 查询） */
    public record BuildResult(String jobId, String mode, String status, Stats stats) {}

    /** increment 请求体（events≤10000 条；dataVersion 必填） */
    public record IncrementRequest(List<Map<String, Object>> events, String dataVersion) {}

    /** 任务状态（GET /jobs/{jobId}） */
    public record JobView(String jobId, String mode, String status, String dataVersion, Stats stats,
                          String createdAt, long elapsedMs) {}

    /** 节点视图（GET /nodes，确定性仿真） */
    public record NodeView(String nodeId, String nodeType, String name, String validFrom, String validTo, String source) {}

    /** 边视图（GET /edges，确定性仿真） */
    public record EdgeView(String edgeId, String fromNode, String toNode, String relationType,
                           String validFrom, String validTo, String source) {}

    /** 节点列表 */
    public record NodesResult(int total, List<NodeView> nodes) {}

    /** 边列表 */
    public record EdgesResult(int total, List<EdgeView> edges) {}
}
