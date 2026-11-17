package com.bai.ner;

import java.util.List;
import java.util.Map;

/**
 * 实体抽取输出统一结构（提示词库 A-08：NerResult）
 * <p>实体 source=MODEL/DICT/RULE；敏感实体（证件号/账号/手机号/地址）识别即脱敏（前 3 后 4），明文不返回；
 * 无法归一输出 raw 并标记 normalizable=false；嵌套实体按最长匹配优先
 */
public final class NerDtos {

    private NerDtos() {}

    /** extract 请求体（text 必填 ≤20000 字；scene 客服/外呼/合同/尽调/申请；types 缺省全部） */
    public record ExtractRequest(String text, String scene, List<String> types,
                                 Boolean enableNormalize, Boolean enableSensitive) {}

    /** 数字归一值（万×1e4、亿×1e8、百分比转小数、日期归 YYYY-MM-DD、期限归月/期） */
    public record Normalized(Object value, String unit, boolean normalizable) {}

    /** 实体：边界 start/end（含头不含尾）、类型、置信度、来源与归一值；敏感实体 text 为掩码 */
    public record Entity(String type, String text, int start, int end, double confidence,
                         String source, Normalized normalized, boolean sensitive) {}

    /** 抽取结果 */
    public record NerResult(String nerId, String scene, String textHash,
                            List<Entity> entities, Map<String, Integer> stats, long elapsedMs) {}

    /** batch 请求体（≤100000 条，异步） */
    public record BatchRequest(List<ExtractRequest> items) {}

    /** 异步批量任务（MVP 仿真为同步完成，落任务存储供 tasks 查询） */
    public record BatchTask(String taskId, String status, int total, List<NerResult> results) {}

    /** 类型体系条目（sensitive=敏感类型，识别即脱敏） */
    public record TypeDef(String type, String name, boolean sensitive) {}

    /** 类型体系（仿真内置快照） */
    public record NerTypes(String version, List<TypeDef> types) {}
}
