package com.bai.approvalroute;

import java.util.List;
import java.util.Map;

/**
 * 审批流转输出统一结构（提示词库 A-29：RouteResult/ApproveResult/TaskInfo/ChainResult）
 * <p>会签需全部同意才通过，或签任一同意即通过；终态不可逆；审批记录保留≥3 年
 */
public final class ApprovalRouteDtos {

    private ApprovalRouteDtos() {}

    /** 审批人（userId/mode/level） */
    public record Approver(String userId, String mode, int level) {}

    /** 时限与催办时点（剩余 25% 时限催办 1 次，超时升级上级） */
    public record TimeLimit(int hours, String escalateAt) {}

    /** 路由决策结果（规则按优先级升序命中即停） */
    public record RouteResult(String routeId, String nodeName, List<Approver> approvers,
                              TimeLimit timeLimit, List<String> rulesHit) {}

    /** 审批动作执行结果（PENDING→APPROVED/REJECTED/RETURNED/WITHDRAWN，版本乐观锁） */
    public record ApproveResult(String taskId, String action, String status, int version,
                                String operator, String opinion, String operatedAt) {}

    /** 审批任务（GET /tasks 返回，含版本号与投票状态） */
    public record TaskInfo(String taskId, String routeId, String bizType, String nodeName, String mode,
                           List<Approver> approvers, String status, int version, TimeLimit timeLimit,
                           Map<String, String> votes, String createdAt) {}

    /** 审批记录（审批人/动作/意见/时间，append-only） */
    public record ApprovalRecord(String operator, String action, String opinion, String operatedAt) {}

    /** 审批链查询结果（路由决策 + 任务 + 全量审批记录） */
    public record ChainResult(String routeId, String taskId, String bizType, String nodeName, String status,
                              int version, List<ApprovalRecord> records, String createdAt) {}
}
