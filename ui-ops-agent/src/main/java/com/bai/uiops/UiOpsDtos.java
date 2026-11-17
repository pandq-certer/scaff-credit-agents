package com.bai.uiops;

/**
 * 界面操作输出统一结构（提示词库 A-42：OpResult/PageSnapshot/LocateResult/SyncResult/RollbackResult）
 * <p>result 为 VALIDATION_FAILED/NOT_FOUND/BLOCKED 时必须带原因；sync 必含 consistency（MATCHED/DIFF）
 */
public final class UiOpsDtos {

    private UiOpsDtos() {}

    /** 页面快照（url/hash） */
    public record PageSnapshot(String url, String hash) {}

    /** 页面操作结果 */
    public record OpResult(String opId, String sessionId, String element, String action, String result,
                           String reason, PageSnapshot pageSnapshot, long elapsedMs) {}

    /** 元素定位结果（三级策略 ID→XPATH→视觉兜底，置信度≥0.9 才执行操作） */
    public record LocateResult(String sessionId, String element, String strategy, double confidence,
                               boolean located, String reason, long elapsedMs) {}

    /** 交易同步结果（页面提交→后台调用→状态回读比对） */
    public record SyncResult(String opId, String txnRef, String consistency, String status,
                             PageSnapshot pageSnapshot, String backendTxnNo, boolean confirmRequired,
                             long elapsedMs) {}

    /** 回滚处理结果（BUSINESS 逆向交易 / DATA 快照恢复） */
    public record RollbackResult(String rollbackId, String rollbackType, String refId, String status,
                                 String reason, long elapsedMs) {}
}
