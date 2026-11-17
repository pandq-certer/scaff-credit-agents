package com.bai.graphpenetrate;

import java.util.List;

/**
 * 图谱穿透输出统一结构（提示词库 A-45：Path/EquityItem/Controller/PenetrateResult/ControlResult/Relation/RelationsResult/BatchResult）
 */
public final class GraphPenetrateDtos {

    private GraphPenetrateDtos() {}

    /** 穿透路径（hopRatios=沿路径自实体向外每条边真实持股比例，用于图谱化展示） */
    public record Path(String path, int hops, double ratio, List<Double> hopRatios) {}

    /** 持股汇总 */
    public record EquityItem(String holder, double direct, double indirect, double weighted) {}

    /** 实控人认定 */
    public record Controller(String person, double weightedRatio, String method, List<String> evidence) {}

    /** 股权穿透结果 */
    public record PenetrateResult(String penetrateId, List<Path> paths, List<EquityItem> equitySummary,
                                  List<Controller> controllers, boolean cycleDetected, int elapsedMs) {}

    /** 实控人认定结果（同步） */
    public record ControlResult(String penetrateId, Object entity, List<Controller> controllers, int elapsedMs) {}

    /** 关系明细 */
    public record Relation(String relType, String from, String to, Double ratio, String detail) {}

    /** 关系查询结果 */
    public record RelationsResult(Object entity, List<Relation> relations, int total, int elapsedMs) {}

    /** 批量穿透结果 */
    public record BatchResult(int total, List<PenetrateResult> results) {}
}
