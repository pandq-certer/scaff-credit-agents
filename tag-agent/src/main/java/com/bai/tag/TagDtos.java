package com.bai.tag;

import java.util.List;

/**
 * 智能打标输出统一结构（提示词库 A-13：TagResult）
 * <p>标签为「一级.二级」两级结构、tagId 全局唯一；单条 ≤5 标签；风险标签命中即输出并附解释（每标签 ≤1 条、≤50 字）；
 * 风险标签（逾期/还款困难/投诉威胁/关联风险）命中即触发预警事件
 */
public final class TagDtos {

    private TagDtos() {}

    /** generate 请求体（text 必填 ≤5000 字；taxonomyVersion 默认最新；groups=business/risk/content 默认全部） */
    public record GenerateRequest(String text, String taxonomyVersion, List<String> groups,
                                  Boolean enableExplain) {}

    /** 命中标签（group/tagId/level/path/confidence/explain；explain 为 ≤50 字命中依据） */
    public record TagHit(String group, String tagId, int level, String path, double confidence, String explain) {}

    /** 打标结果 */
    public record TagResult(String tagId, String taxonomyVersion, List<TagHit> tags, long elapsedMs) {}

    /** batch 请求体（≤100000 条，异步） */
    public record BatchRequest(List<GenerateRequest> items) {}

    /** 批量受理结果（条目未定义批量任务查询接口，结果由调度器落库） */
    public record BatchResult(String batchId, String status, int total) {}

    /** 标签体系条目（keywords 为规则命中词，仿真内置快照） */
    public record TagDef(String tagId, String group, String path, int level, String keywords) {}

    /** 标签体系（版本化，治理归运营平台） */
    public record Taxonomy(String version, List<TagDef> tags) {}

    /** 模型信息（仿真内置快照） */
    public record TagModels(String model, String version, List<String> groups) {}
}
