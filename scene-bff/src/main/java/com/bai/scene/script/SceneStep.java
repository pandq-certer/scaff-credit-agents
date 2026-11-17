package com.bai.scene.script;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 场景剧本步骤：原子调用 / 占位（E/F/G/H 类原子未上线）/ 人工确认节点
 *
 * @param stepId      步骤编号（会话内唯一）
 * @param name        步骤名（滚动日志动作描述，如「① 归并客户身份」）
 * @param agentId     被调原子标识（confirm 步骤固定 human-confirm）
 * @param dependsOn   依赖的 stepId（DAG）
 * @param confirm     人工确认节点（⚑）
 * @param placeholder true=占位步骤（原子属 E/F/G/H 类后批开发，输出演示结果并标注）
 * @param url         真实调用 URL（占位/确认步骤为 null）
 * @param payloadFn   真实调用请求体构造（slots=会话槽位、summaries=各步骤日志摘要、stepData=各步骤响应 data；占位/确认步骤为 null）
 * @param summaryFn   调用结果摘要提取（BaiResponse.data → 日志摘要；null 则用通用摘要）
 */
public record SceneStep(
        String stepId,
        String name,
        String agentId,
        List<String> dependsOn,
        boolean confirm,
        boolean placeholder,
        String url,
        PayloadFn payloadFn,
        BiFn summaryFn) {

    /** 三参构造函数式接口：payload 可引用此前步骤的响应数据（跨步骤传值） */
    @FunctionalInterface
    public interface PayloadFn {
        Object apply(Map<String, Object> slots, Map<String, String> summaries, Map<String, JsonNode> stepData);
    }

    /** 两参构造函数式接口：步骤日志摘要 */
    @FunctionalInterface
    public interface BiFn {
        String apply(Map<String, Object> slots, JsonNode data);
    }

    /** 占位/确认步骤快捷构造 */
    public static SceneStep of(String stepId, String name, String agentId, List<String> dependsOn,
                               boolean confirm, boolean placeholder) {
        return new SceneStep(stepId, name, agentId, dependsOn, confirm, placeholder, null, null, null);
    }
}
