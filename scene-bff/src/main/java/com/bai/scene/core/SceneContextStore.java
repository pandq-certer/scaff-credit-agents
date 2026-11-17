package com.bai.scene.core;

import com.bai.scene.script.SceneScript;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 场景会话上下文存储：剧本执行进度之外的会话级状态（槽位、结果分块、待确认步骤）
 */
@Component
public class SceneContextStore {

    public enum Status { RUNNING, AWAITING_CONFIRM, COMPLETED, CANCELLED }

    public static class SceneContext {
        public final String sessionId;
        public final SceneScript script;
        public final String userId;
        public final Map<String, Object> slots;
        public volatile Status status = Status.RUNNING;
        public volatile String pendingConfirmStepId;
        public final Map<String, String> summaries = new ConcurrentHashMap<>();
        /** 各步骤响应 data（跨步骤传值，如 authorize 的 authId 传给 report） */
        public final Map<String, JsonNode> stepData = new ConcurrentHashMap<>();
        public volatile List<Map<String, Object>> blocks = List.of();

        public SceneContext(String sessionId, SceneScript script, String userId, Map<String, Object> slots) {
            this.sessionId = sessionId;
            this.script = script;
            this.userId = userId;
            this.slots = slots;
        }
    }

    private final Map<String, SceneContext> contexts = new ConcurrentHashMap<>();

    public SceneContext create(String sessionId, SceneScript script, String userId, Map<String, Object> slots) {
        SceneContext ctx = new SceneContext(sessionId, script, userId, slots);
        contexts.put(sessionId, ctx);
        return ctx;
    }

    public SceneContext find(String sessionId) { return contexts.get(sessionId); }
}
