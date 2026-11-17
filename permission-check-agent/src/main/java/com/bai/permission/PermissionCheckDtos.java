package com.bai.permission;

import java.util.List;

/**
 * 权限校验统一出入参结构（设计文档 46-H 4.2~4.5 节）。
 * <p>check 结论 ALLOW/DENY，Deny-Override 合并；data-check 输出数据范围表达式与列级可见性；
 * grant 异步按级别审批（L1 自助 / L2 直属上级 / L3 部门负责人 / L4 双人+分管）。
 */
public final class PermissionCheckDtos {

    private PermissionCheckDtos() {}

    /** 身份上下文：userId/roleIds/orgId/deptId */
    public record Identity(String userId, List<String> roleIds, String orgId, String deptId) {}

    /** 资源标识：type/id */
    public record Resource(String type, String id) {}

    /** 操作权限校验入参 */
    public record OperationCheckRequest(Identity identity, String operation, Resource resource, String requestId) {}

    /** 命中的策略明细 */
    public record MatchedPolicy(String policyId, String effect, String source) {}

    /** 操作权限校验结论 */
    public record CheckResult(String checkId, String decision, List<MatchedPolicy> matchedPolicies,
                              String evaluateChain, String reason, long elapsedMs) {}

    /** 数据权限校验入参：dataType=CUSTOMER|LOAN|ACCOUNT|REPORT */
    public record DataCheckRequest(Identity identity, String dataType, List<String> filters,
                                   List<String> needColumns, String requestId) {}

    /** 数据范围表达式：机构/客户/产品/自定义规则 */
    public record ScopeExpression(List<String> orgs, List<String> customers, List<String> products, String customRule) {}

    /** 数据权限校验结论 */
    public record DataCheckResult(String checkId, boolean allowed, ScopeExpression scopeExpression,
                                  List<String> columns, List<String> deniedColumns) {}

    /** 权限查询条目 */
    public record QueryItem(String userId, List<String> roleIds, String operation, String resource,
                            String effect, String source, String effectiveFrom, String effectiveTo) {}

    /** 权限查询结果（分页） */
    public record QueryResult(int total, List<QueryItem> items) {}

    /** 授权申请入参（异步，按 applyId 幂等） */
    public record GrantRequest(String applyId, String applicant, String targetUserId, String operation,
                               String resource, String dataScope, String reason, String approver,
                               String validFrom, String validTo) {}

    /** 审批链步骤 */
    public record ApprovalStep(String approver, String level, String status, String time) {}

    /** 授权申请结论：status=PENDING|APPROVED|REJECTED */
    public record GrantResult(String applyId, String status, List<ApprovalStep> approvalChain) {}
}
