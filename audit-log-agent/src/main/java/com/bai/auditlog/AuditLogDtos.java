package com.bai.auditlog;

import java.util.List;
import java.util.Map;

/**
 * 审计留痕输出统一结构（提示词库 A-32：WriteResult/TamperCheckResult）
 * <p>日志 append-only 落库（库级禁 UPDATE/DELETE），哈希链 cur_hash=SHA-256(prev_hash+事件+盐) 防篡改
 */
public final class AuditLogDtos {

    private AuditLogDtos() {}

    /** 写入受理结果 */
    public record WriteResult(int accepted, int rejected, List<String> logIds) {}

    /** 审计日志条目（who/what/when/where/result 五元组 + 哈希链指针） */
    public record LogEntry(String logId, String requestId, Map<String, Object> who, Map<String, Object> what,
                           String when, Map<String, Object> where, Map<String, Object> result,
                           boolean sensitive, boolean masked, String prevHash, String hash, String createdAt) {}

    /** 查询结果（多维过滤分页返回） */
    public record QueryResult(int total, int page, int pageSize, List<LogEntry> logs) {}

    /** 导出结果（CSV，导出操作本身留痕） */
    public record ExportResult(String exportId, String format, int count, String csv, String createdAt) {}

    /** 防篡改校验结果（断链时 brokenAt 定位到具体事件） */
    public record TamperCheckResult(boolean valid, String chainRoot, int checked, String brokenAt) {}
}
