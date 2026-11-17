package com.bai.scene.llm;

import com.bai.scene.core.SceneContextStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 场景对话配置：某个场景接入「全对话大模型化」（意图识别 + 多轮槽位收集 + 结果解读 + 完成后追问）
 * 所需的场景专属信息。
 * <p>对话框架（多轮状态机、会话状态感知、降级策略）由 {@link SceneLlmDialogService} 统一实现，
 * 新场景接入只需在 {@link SceneDialogSpecs} 注册一份配置，无需改动对话框架与场景剧本。
 * <p>硬约束：数值计算/业务执行一律由后台原子服务完成，LLM 仅负责意图识别、槽位收集与结果解读，
 * 任何 LLM 失败均静默降级回关键词路由+模板链路。
 */
public class SceneDialogSpec {

    /** 场景编号，如 S6-2 */
    public final String sceneId;

    /** 专用意图路由提示词中的场景描述（场景职责 + 典型表述 + 排除说明） */
    public final String routerDesc;

    /** 槽位收集业务规则：必需要素、缺省值、取数说明（注入收集态系统提示词） */
    public final String collectRules;

    /** READY 动作输出的 slots JSON 字段模板（如 "applyNo":"借据编号","amount":金额元数字） */
    public final String slotSchema;

    /** 完成后追问示例（用于完成提示文案） */
    public final String followupHint;

    /** 槽位规范化：LLM 输出 slots → 剧本执行槽位（缺省回填、宽松解析）；返回 null 表示要素仍不足 */
    public final SlotNormalizer normalizer;

    /** 结果事实抽取：从会话上下文提取已执行的真实结果数据（追问模式与结果解读的数据来源） */
    public final FactsExtractor facts;

    /** 结果解读提示词（基于实算结果生成个性化解读；null 表示本场景无解读块） */
    public final String interpretPrompt;

    public SceneDialogSpec(String sceneId, String routerDesc, String collectRules, String slotSchema,
                           String followupHint, SlotNormalizer normalizer,
                           FactsExtractor facts, String interpretPrompt) {
        this.sceneId = sceneId;
        this.routerDesc = routerDesc;
        this.collectRules = collectRules;
        this.slotSchema = slotSchema;
        this.followupHint = followupHint;
        this.normalizer = normalizer;
        this.facts = facts;
        this.interpretPrompt = interpretPrompt;
    }

    /** 槽位规范化函数 */
    @FunctionalInterface
    public interface SlotNormalizer {
        /** 返回 null 表示要素仍不足，由 LLM 继续追问 */
        Map<String, Object> apply(JsonNode slots);
    }

    /** 结果事实抽取函数 */
    @FunctionalInterface
    public interface FactsExtractor {
        Map<String, Object> apply(SceneContextStore.SceneContext ctx);
    }
}
