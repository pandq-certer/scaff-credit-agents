package com.bai.tasksched;

/**
 * 任务调度原子错误码段（提示词库 A-28 条目，原文档码表逐条保留）
 */
public final class TaskScheduleErrorCodes {

    private TaskScheduleErrorCodes() {}

    public static final String PARAM_INVALID = "TSK_1001";       // 参数缺失/cron 非法；校验后重试
    public static final String TOKEN_INVALID = "TSK_1002";       // Token 缺失/过期；重新获取 Token
    public static final String NO_PERMISSION = "TSK_1003";       // 无调用权限（含跨机构调度）；申请权限
    public static final String NOT_FOUND = "TSK_1004";           // 调度/实例不存在；校验参数
    public static final String STATE_CONFLICT = "TSK_1005";      // 依赖环/状态冲突；修正后重试
    public static final String TARGET_UNREACHABLE = "TSK_1006";  // 目标智能体不可达/日历跳过；检查目标
    public static final String RATE_LIMITED = "TSK_1007";        // 触发限流；按 Retry-After 退避
    public static final String INTERNAL_ERROR = "TSK_2000";      // 调度服务内部错误；记录 requestId 上报
    public static final String QUEUE_DEGRADED = "TSK_2001";      // 执行队列不可用（降级中）；稍后重试
}
