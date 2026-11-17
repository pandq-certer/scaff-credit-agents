package com.bai.callexec;

import java.util.List;
import java.util.Map;

/**
 * 调用执行输出统一结构（提示词库 A-38：CallResult/DefinitionInfo/DefinitionResult/HistoryRecord/HistoryQuery）
 * <p>status 为 RETRIED/TIMEOUT/FAILED/DEGRADED 时 result 必须带错误码；P0 资金类服务不降级
 */
public final class CallExecDtos {

    private CallExecDtos() {}

    /** 执行结果（标准化：业务码/数据/错误结构） */
    public record CallResult(String callId, String definitionId, String status, Map<String, Object> result,
                             long elapsedMs, int retryCount, String targetService) {}

    /** 接口定义（端点/方法/参数契约/优先级） */
    public record DefinitionInfo(String definitionId, String name, String targetService, String method,
                                 String path, int timeoutMs, int maxRetries, String priority,
                                 List<String> requiredParams, String status) {}

    /** 定义管理结果（CREATE/UPDATE/PUBLISH/OFFLINE） */
    public record DefinitionResult(String definitionId, String name, String targetService, String op,
                                   String status) {}

    /** 调用历史记录 */
    public record HistoryRecord(String callId, String definitionId, String targetService, String status,
                                long elapsedMs, int retryCount, String calledAt) {}

    /** 调用历史查询 */
    public record HistoryQuery(int total, List<HistoryRecord> records) {}
}
