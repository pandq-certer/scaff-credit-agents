package com.bai.scene.script;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 场景剧本：元信息 + 开场引导 + 一次问全复述 + 步骤 DAG + 确认提示 + 结果分块生成
 */
public record SceneScript(
        String sceneId,          // 如 S1-1
        String agentId,          // 主智能体标识
        String title,            // 场景名
        List<String> keywords,   // 意图路由关键词
        String opening,          // 开场引导
        String restatement,      // 任务清单复述（多场景协同确认）
        List<SceneStep> steps,   // 处理链步骤（顺序即编号，DAG 由 dependsOn 声明）
        String confirmPrompt,    // 确认节点提示（如「回复 1 确认发送…」）
        BlocksFn blocksFn        // 分块生成：可引用槽位、步骤摘要与步骤响应 data
) {

    /** 三参构造函数式接口：分块生成 */
    @FunctionalInterface
    public interface BlocksFn {
        List<Map<String, Object>> apply(Map<String, Object> slots,
                                        Map<String, String> summaries,
                                        Map<String, JsonNode> stepData);
    }
}
