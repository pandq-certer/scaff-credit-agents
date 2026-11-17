package com.bai.todo;

import java.util.Map;

/**
 * 任务待办输出统一结构（提示词库 A-31：TodoView/Assignee/ActionResult/Stats）
 */
public final class TodoDtos {

    private TodoDtos() {}

    /** 受理人（type=USER/ROLE/ORG） */
    public record Assignee(String type, String id) {}

    /** 待办视图 */
    public record TodoView(String todoId, String source, String bizId, String title, Assignee assignee,
                           String priority, String dueTime, String status, String createdAt) {}

    /** 待办动作结果 */
    public record ActionResult(String todoId, String action, String status, String operator) {}

    /** 待办统计 */
    public record Stats(int total, Map<String, Long> byStatus, Map<String, Long> bySource) {}
}
