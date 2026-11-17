package com.bai.tasksched;

import java.util.List;
import java.util.Map;

/**
 * 任务调度输出统一结构（提示词库 A-28：ScheduleResult/InstanceInfo）
 * <p>实例状态机 PENDING→RUNNING→SUCCESS/FAILED/CANCELLED（终态不可逆，失败重建新实例）
 */
public final class TaskScheduleDtos {

    private TaskScheduleDtos() {}

    /** 调度注册/更新结果 */
    public record ScheduleResult(String scheduleId, String name, String cron, String status, String nextFireTime) {}

    /** 调度定义（内存存储，含目标与策略） */
    public record ScheduleDef(String scheduleId, String name, String cron, Map<String, Object> target,
                              Map<String, Object> policy, String status, String nextFireTime, String createdAt) {}

    /** 立即触发结果 */
    public record TriggerResult(String instanceId, String scheduleId, String status) {}

    /** 实例信息 */
    public record InstanceInfo(String instanceId, String scheduleId, String status,
                               String triggeredAt, String updatedAt) {}

    /** 实例列表 */
    public record InstanceList(int total, List<InstanceInfo> items) {}
}
