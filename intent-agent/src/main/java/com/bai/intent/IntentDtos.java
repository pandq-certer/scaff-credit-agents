package com.bai.intent;

import java.util.List;

/**
 * 意图识别输出统一结构（提示词库 A-07：IntentResult）
 * <p>阈值处置：置信度 ≥0.85 直接路由、0.70—0.85 澄清确认、&lt;0.70 且候选差 &lt;0.05 列候选追问；
 * 二级意图置信度 &lt;0.55 仅返回一级；OOD 命中不进入业务路由
 */
public final class IntentDtos {

    private IntentDtos() {}

    /** 对话历史项 {role,text} */
    public record HistoryItem(String role, String text) {}

    /** classify 请求体 */
    public record ClassifyRequest(String text, String scene, String sessionId, List<HistoryItem> history,
                                  Boolean enableOod, Integer topK) {}

    /** 一级/二级意图+置信度+归一文本（二级置信度 &lt;0.55 时 level2 为 null） */
    public record IntentInfo(String level1, String level2, double confidence, String normalizedText) {}

    /** TopK 候选（intent 形如 level1/level2） */
    public record Candidate(String intent, double confidence) {}

    /** 轻量槽位（name/value/confidence；置信度 &lt;0.80 标记缺失待追问） */
    public record Slot(String name, String value, double confidence) {}

    /** 拒识标记与分级 NONE/LOW/HIGH（LOW 澄清追问一次、HIGH 转人工） */
    public record Ood(boolean flagged, String level) {}

    /** 多轮使用标记 */
    public record MultiTurn(boolean used, boolean revised, int historyTurns) {}

    /** 判定结果 */
    public record IntentResult(String intentId, IntentInfo intent, List<Candidate> candidates, List<Slot> slots,
                               Ood ood, MultiTurn multiTurn, long elapsedMs) {}

    /** 批量请求（≤10000 条，异步） */
    public record BatchRequest(List<ClassifyRequest> items) {}

    /** 批量受理结果 */
    public record BatchResult(String batchId, String status, int total) {}

    /** 意图树配置（版本化、热更新 &lt;30s、新旧并存可回滚；仿真为内置快照） */
    public record SchemaLevel2(String code, String name) {}
    public record SchemaLevel1(String code, String name, List<SchemaLevel2> level2) {}
    public record IntentSchema(String version, String updatedAt, List<SchemaLevel1> intents) {}
}
