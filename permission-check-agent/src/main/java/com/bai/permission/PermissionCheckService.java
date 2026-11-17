package com.bai.permission;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 权限校验服务（设计文档 46-H，BIA-RSK-045）。
 * <p>流水线：身份上下文 → 策略加载（RBAC 角色策略 + 继承 + DIRECT 直接授权）→ 策略评估 →
 * 合并决策（Deny-Override，任一 DENY 即 DENY）→ 留痕；数据范围按 机构树（本级+下级）/归属客户/产品 计算，最小授权取交集。
 * <p>幂等：check 同 requestId+identity+operation 不重复校验；授权按 applyId 幂等；重复申请 PMC_1005。
 * 限流（Controller 侧）：check 200 QPS/用户、data-check 50 QPS/用户。角色-权限/机构-范围映射为确定性仿真，用于联调。
 */
@Service
public class PermissionCheckService {

    private static final Logger log = LoggerFactory.getLogger(PermissionCheckService.class);

    /** 已注册权限点（operation 非法 → PMC_1007） */
    private static final Set<String> REGISTERED_OPERATIONS = Set.copyOf(List.of(
            "CUST_VIEW", "LOAN_QUERY", "LOAN_APPROVE", "UIO_OP",
            "FRD_CHECK", "AML_CHECK", "RISK_REPORT", "REPORT_VIEW",
            "GRANT_MGMT", "PMC_ADMIN", "ALL_QUERY"));
    /** 数据类型枚举（非法 → PMC_1007） */
    private static final Set<String> DATA_TYPES = Set.of("CUSTOMER", "LOAN", "ACCOUNT", "REPORT");

    /** 角色 → 直配权限点（RBAC） */
    private static final Map<String, List<String>> ROLE_OPS = Map.of(
            "ROLE_CUSTOMER_MANAGER", List.of("CUST_VIEW", "LOAN_QUERY", "UIO_OP"),
            "ROLE_DEPT_HEAD", List.of("LOAN_APPROVE", "REPORT_VIEW"),
            "ROLE_RISK_HEAD", List.of("FRD_CHECK", "AML_CHECK", "RISK_REPORT", "REPORT_VIEW"),
            "ROLE_BRANCH_ADMIN", List.of("GRANT_MGMT", "PMC_ADMIN", "ALL_QUERY", "REPORT_VIEW"));
    /** 角色继承：子角色 → 父角色（父角色权限以 INHERIT 源生效） */
    private static final Map<String, String> ROLE_PARENT = Map.of("ROLE_DEPT_HEAD", "ROLE_CUSTOMER_MANAGER");

    /** 用户档案（userId → 角色/机构，仿真） */
    private static final Map<String, UserProfile> USERS = Map.of(
            "u001", new UserProfile("u001", List.of("ROLE_CUSTOMER_MANAGER"), "ORG_CREDIT_001"),
            "u002", new UserProfile("u002", List.of("ROLE_DEPT_HEAD"), "ORG_CREDIT_001"),
            "u003", new UserProfile("u003", List.of("ROLE_RISK_HEAD"), "ORG_CREDIT_001"),
            "u004", new UserProfile("u004", List.of("ROLE_BRANCH_ADMIN"), "ORG_BRANCH_001"));

    /** 用户直接授权（DIRECT，用于 Deny-Override / 跨级拒绝联调） */
    private static final Map<String, List<DirectGrant>> DIRECT_GRANTS = Map.of(
            "u002", List.of(new DirectGrant("DG_002_01", "LOAN_APPROVE", "DENY")),
            "u004", List.of(new DirectGrant("DG_004_01", "CUST_VIEW", "DENY")));

    /** 角色 → 数据范围类型（SELF 归属 / ORG 机构级 / ALL 全量） */
    private static final Map<String, String> ROLE_SCOPE = Map.of(
            "ROLE_CUSTOMER_MANAGER", "SELF",
            "ROLE_DEPT_HEAD", "ORG",
            "ROLE_RISK_HEAD", "ORG",
            "ROLE_BRANCH_ADMIN", "ALL");

    /** 机构树：机构 → 下级机构（数据范围本级+下级） */
    private static final Map<String, List<String>> ORG_CHILDREN = Map.of(
            "ORG_BRANCH_001", List.of("ORG_CREDIT_001", "ORG_CREDIT_002"),
            "ORG_CREDIT_001", List.of(),
            "ORG_CREDIT_002", List.of());

    /** 机构 → 归属客户（数据范围计算） */
    private static final Map<String, List<String>> ORG_CUSTOMERS = Map.of(
            "ORG_CREDIT_001", List.of("CUST_001", "CUST_002", "CUST_003"),
            "ORG_CREDIT_002", List.of("CUST_004"),
            "ORG_BRANCH_001", List.of("CUST_001", "CUST_002", "CUST_003", "CUST_004", "CUST_005"));

    /** 客户经理归属客户（SELF 范围） */
    private static final Map<String, List<String>> CM_CUSTOMERS = Map.of(
            "u001", List.of("CUST_001", "CUST_002"),
            "u002", List.of("CUST_003"));

    /** dataType → 全列清单 */
    private static final Map<String, List<String>> COLUMNS = Map.of(
            "CUSTOMER", List.of("CUST_ID", "CUST_NAME", "CUST_TYPE", "CERT_NO", "MOBILE", "ADDRESS", "RISK_LEVEL", "CONTACT_LIST", "INCOME", "DEPOSIT_BALANCE"),
            "LOAN", List.of("LOAN_ID", "CUST_ID", "PRODUCT_ID", "AMOUNT", "RATE", "STATUS", "INTEREST", "OVERDUE_DAYS"),
            "ACCOUNT", List.of("ACCT_ID", "CUST_ID", "BALANCE", "CURRENCY", "STATUS", "OPEN_DATE"),
            "REPORT", List.of("REPORT_ID", "CUST_ID", "REPORT_TYPE", "SUMMARY", "DETAIL", "CONCLUSION"));

    /** dataType → 敏感受限列 */
    private static final Map<String, List<String>> SENSITIVE_COLUMNS = Map.of(
            "CUSTOMER", List.of("CERT_NO", "MOBILE", "CONTACT_LIST", "INCOME", "DEPOSIT_BALANCE"),
            "LOAN", List.of("INTEREST", "OVERDUE_DAYS"),
            "ACCOUNT", List.of("BALANCE"),
            "REPORT", List.of("DETAIL"));

    /** ORG 范围下仍受限的列（机构级可见证件/手机，但敏感财务字段仍受限；ALL 无受限） */
    private static final Map<String, List<String>> ORG_DENIED = Map.of(
            "CUSTOMER", List.of("CONTACT_LIST", "INCOME", "DEPOSIT_BALANCE"),
            "LOAN", List.of("OVERDUE_DAYS"),
            "ACCOUNT", List.of("BALANCE"),
            "REPORT", List.of("DETAIL"));

    /** 权限点 → 资源类型（query 条目展示） */
    private static final Map<String, String> OP_RESOURCE = Map.ofEntries(
            Map.entry("CUST_VIEW", "CUST"), Map.entry("LOAN_QUERY", "LOAN"),
            Map.entry("LOAN_APPROVE", "LOAN"), Map.entry("UIO_OP", "CUST"),
            Map.entry("FRD_CHECK", "FRAUD"), Map.entry("AML_CHECK", "AML"),
            Map.entry("RISK_REPORT", "RISK"), Map.entry("REPORT_VIEW", "REPORT"),
            Map.entry("GRANT_MGMT", "PMC"), Map.entry("PMC_ADMIN", "PMC"),
            Map.entry("ALL_QUERY", "ALL"));

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String EFFECTIVE_FROM = "2026-01-01";

    private final IdempotencyStore idempotency = new IdempotencyStore();
    /** PENDING 授权申请：target|op|resource|dataScope → applyId（重复申请 PMC_1005） */
    private final Map<String, String> pendingGrants = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    // --------------------------------------------------------------- check

    /** 操作权限校验（同步）：策略加载 → Deny-Override 合并；DENY 抛 PMC_1003（403） */
    public PermissionCheckDtos.CheckResult check(PermissionCheckDtos.OperationCheckRequest request, String requestId) {
        long start = System.currentTimeMillis();
        PermissionCheckDtos.Identity identity = request == null ? null : request.identity();
        if (identity == null || isBlank(identity.userId())) {
            throw new BaiException(PermissionCheckErrorCodes.PARAM_INVALID, "PMC_1001 参数缺失：identity.userId 必填");
        }
        UserProfile user = USERS.get(identity.userId());
        if (user == null) {
            throw new BaiException(PermissionCheckErrorCodes.NOT_FOUND, "PMC_1004 用户不存在：" + identity.userId());
        }
        String operation = request.operation();
        if (isBlank(operation)) {
            throw new BaiException(PermissionCheckErrorCodes.PARAM_INVALID, "PMC_1001 参数缺失：operation 必填");
        }
        if (!REGISTERED_OPERATIONS.contains(operation)) {
            throw new BaiException(PermissionCheckErrorCodes.SCOPE_INVALID,
                    "PMC_1007 权限点非法：" + operation + "，仅允许已注册权限点（如 CUST_VIEW/LOAN_QUERY/PMC_ADMIN）");
        }
        // 幂等：requestId+identity+operation 同键返回首次结论
        String idemKey = requestId + ":" + identity.userId() + ":" + operation;
        PermissionCheckDtos.CheckResult cached = idempotency.get("check", idemKey);
        if (cached != null) {
            if ("DENY".equals(cached.decision())) {
                throw new BaiException(PermissionCheckErrorCodes.PERMISSION_DENIED,
                        "PMC_1003 校验结论 DENY（checkId=" + cached.checkId() + "）：" + cached.reason());
            }
            return cached;
        }
        // 策略加载：角色直配（ROLE）+ 角色继承（INHERIT）+ 直接授权（DIRECT）
        List<PermissionCheckDtos.MatchedPolicy> matched = new ArrayList<>();
        for (String roleId : user.roleIds()) {
            loadRolePolicies(roleId, "ROLE", matched, operation);
        }
        for (DirectGrant g : DIRECT_GRANTS.getOrDefault(identity.userId(), List.of())) {
            if (g.operation().equals(operation)) {
                matched.add(new PermissionCheckDtos.MatchedPolicy(g.policyId(), g.effect(), "DIRECT"));
            }
        }
        // 合并决策：Deny-Override（任一 DENY 即 DENY；同 effect 按策略清单序保留）
        boolean deny = matched.stream().anyMatch(p -> "DENY".equals(p.effect()));
        boolean allow = matched.stream().anyMatch(p -> "ALLOW".equals(p.effect()));
        String checkId = nextId("PMC");
        long elapsed = System.currentTimeMillis() - start;
        PermissionCheckDtos.CheckResult result;
        if (deny) {
            result = new PermissionCheckDtos.CheckResult(checkId, "DENY", matched, "DENY_OVERRIDE",
                    "Deny-Override：命中拒绝策略（DIRECT 拒绝优先于角色授权）", elapsed);
        } else if (allow) {
            result = new PermissionCheckDtos.CheckResult(checkId, "ALLOW", matched, "DENY_OVERRIDE",
                    "命中授权策略（ROLE/INHERIT 源）", elapsed);
        } else {
            result = new PermissionCheckDtos.CheckResult(checkId, "DENY", matched, "DENY_OVERRIDE",
                    "默认拒绝：无匹配策略", elapsed);
        }
        idempotency.put("check", idemKey, result);
        // 校验留痕（含 DENY 全量记录）
        log.info("操作权限校验: requestId={}, userId={}, operation={}, decision={}, checkId={}",
                requestId, identity.userId(), operation, result.decision(), checkId);
        if ("DENY".equals(result.decision())) {
            throw new BaiException(PermissionCheckErrorCodes.PERMISSION_DENIED,
                    "PMC_1003 校验结论 DENY（checkId=" + checkId + "）：" + result.reason());
        }
        return result;
    }

    // ---------------------------------------------------------- data-check

    /** 数据权限校验（同步）：数据范围计算（机构树本级+下级/归属客户/产品）+ 列级过滤（最小授权） */
    public PermissionCheckDtos.DataCheckResult dataCheck(PermissionCheckDtos.DataCheckRequest request, String requestId) {
        PermissionCheckDtos.Identity identity = request == null ? null : request.identity();
        if (identity == null || isBlank(identity.userId())) {
            throw new BaiException(PermissionCheckErrorCodes.PARAM_INVALID, "PMC_1001 参数缺失：identity.userId 必填");
        }
        UserProfile user = USERS.get(identity.userId());
        if (user == null) {
            throw new BaiException(PermissionCheckErrorCodes.NOT_FOUND, "PMC_1004 用户不存在：" + identity.userId());
        }
        String dataType = request.dataType() == null ? "" : request.dataType().toUpperCase();
        if (!DATA_TYPES.contains(dataType)) {
            throw new BaiException(PermissionCheckErrorCodes.SCOPE_INVALID,
                    "PMC_1007 数据范围非法：" + request.dataType() + "，仅允许 CUSTOMER/LOAN/ACCOUNT/REPORT");
        }
        // 幂等：requestId+userId+dataType
        String idemKey = requestId + ":" + identity.userId() + ":" + dataType;
        PermissionCheckDtos.DataCheckResult cached = idempotency.get("data-check", idemKey);
        if (cached != null) {
            return cached;
        }
        String scopeType = resolveScope(user);
        PermissionCheckDtos.ScopeExpression expr = computeScope(user, scopeType,
                request.filters() == null ? List.of() : request.filters());
        // 列级：可见列 = 全列 - 受限列；deniedColumns 按最小授权
        List<String> denied = deniedColumns(scopeType, dataType);
        List<String> allCols = COLUMNS.get(dataType);
        List<String> need = request.needColumns() == null ? List.of() : request.needColumns();
        List<String> columns;
        List<String> deniedOut;
        if (need.isEmpty()) {
            columns = allCols.stream().filter(c -> !denied.contains(c)).toList();
            deniedOut = denied;
        } else {
            columns = need.stream().filter(c -> allCols.contains(c) && !denied.contains(c)).toList();
            deniedOut = need.stream().filter(denied::contains).toList();
        }
        PermissionCheckDtos.DataCheckResult result = new PermissionCheckDtos.DataCheckResult(
                nextId("PMC"), true, expr, columns, deniedOut);
        idempotency.put("data-check", idemKey, result);
        log.info("数据权限校验: requestId={}, userId={}, dataType={}, scope={}, deniedColumns={}",
                requestId, identity.userId(), dataType, scopeType, deniedOut);
        return result;
    }

    // --------------------------------------------------------------- query

    /** 权限策略查询（同步分页）：按 userId/roleId/operation 过滤，返回 RBAC+DIRECT 策略条目 */
    public PermissionCheckDtos.QueryResult query(String userId, String roleId, String operation, int page, int size) {
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 20 : Math.min(size, 100);
        if (userId != null && !isBlank(userId) && !USERS.containsKey(userId)) {
            throw new BaiException(PermissionCheckErrorCodes.NOT_FOUND, "PMC_1004 用户不存在：" + userId);
        }
        if (roleId != null && !isBlank(roleId) && !ROLE_OPS.containsKey(roleId)) {
            throw new BaiException(PermissionCheckErrorCodes.NOT_FOUND, "PMC_1004 角色不存在：" + roleId);
        }
        List<PermissionCheckDtos.QueryItem> all = new ArrayList<>();
        for (UserProfile user : USERS.values()) {
            if (userId != null && !isBlank(userId) && !user.userId().equals(userId)) {
                continue;
            }
            for (String userRole : user.roleIds()) {
                if (roleId != null && !isBlank(roleId) && !userRole.equals(roleId)) {
                    continue;
                }
                for (String op : ROLE_OPS.getOrDefault(userRole, List.of())) {
                    if (operation != null && !isBlank(operation) && !op.equals(operation)) {
                        continue;
                    }
                    all.add(new PermissionCheckDtos.QueryItem(user.userId(), List.of(userRole), op,
                            OP_RESOURCE.getOrDefault(op, "-"), "ALLOW", "ROLE", EFFECTIVE_FROM, null));
                }
                addInherited(user, userRole, roleId, operation, all);
            }
            // 直接授权（DIRECT，用户级）
            for (DirectGrant g : DIRECT_GRANTS.getOrDefault(user.userId(), List.of())) {
                if (operation != null && !isBlank(operation) && !g.operation().equals(operation)) {
                    continue;
                }
                if (roleId != null && !isBlank(roleId) && !user.roleIds().contains(roleId)) {
                    continue;
                }
                all.add(new PermissionCheckDtos.QueryItem(user.userId(), user.roleIds(), g.operation(),
                        OP_RESOURCE.getOrDefault(g.operation(), "-"), g.effect(), "DIRECT", EFFECTIVE_FROM, null));
            }
        }
        int total = all.size();
        int from = Math.min((p - 1) * s, total);
        int to = Math.min(from + s, total);
        return new PermissionCheckDtos.QueryResult(total, all.subList(from, to));
    }

    private void addInherited(UserProfile user, String roleId, String roleFilter, String operationFilter,
                              List<PermissionCheckDtos.QueryItem> out) {
        String parent = ROLE_PARENT.get(roleId);
        while (parent != null) {
            for (String op : ROLE_OPS.getOrDefault(parent, List.of())) {
                if (operationFilter != null && !isBlank(operationFilter) && !op.equals(operationFilter)) {
                    continue;
                }
                out.add(new PermissionCheckDtos.QueryItem(user.userId(), List.of(roleId, parent), op,
                        OP_RESOURCE.getOrDefault(op, "-"), "ALLOW", "INHERIT", EFFECTIVE_FROM, null));
            }
            parent = ROLE_PARENT.get(parent);
        }
    }

    // --------------------------------------------------------------- grant

    /** 授权申请（异步）：按授权级别路由（L1 自助 / L2 直属上级 / L3 部门负责人 / L4 双人+分管），按 applyId 幂等 */
    public PermissionCheckDtos.GrantResult grant(PermissionCheckDtos.GrantRequest request, String requestId) {
        if (request == null || isBlank(request.applicant())) {
            throw new BaiException(PermissionCheckErrorCodes.PARAM_INVALID, "PMC_1001 参数缺失：applicant 必填");
        }
        if (isBlank(request.targetUserId())) {
            throw new BaiException(PermissionCheckErrorCodes.PARAM_INVALID, "PMC_1001 参数缺失：targetUserId 必填");
        }
        if (!USERS.containsKey(request.targetUserId())) {
            throw new BaiException(PermissionCheckErrorCodes.NOT_FOUND, "PMC_1004 被授权用户不存在：" + request.targetUserId());
        }
        if (isBlank(request.operation())) {
            throw new BaiException(PermissionCheckErrorCodes.PARAM_INVALID, "PMC_1001 参数缺失：operation 必填");
        }
        if (!REGISTERED_OPERATIONS.contains(request.operation())) {
            throw new BaiException(PermissionCheckErrorCodes.SCOPE_INVALID, "PMC_1007 权限点非法：" + request.operation());
        }
        validatePeriod(request.validFrom(), request.validTo());
        // 幂等：授权按 applyId 幂等
        String applyId = isBlank(request.applyId()) ? nextId("GRA") : request.applyId();
        PermissionCheckDtos.GrantResult cached = idempotency.get("grant", applyId);
        if (cached != null) {
            return cached;
        }
        // 重复申请检测：同 target+operation+resource+dataScope 且 PENDING → PMC_1005
        String dupKey = request.targetUserId() + "|" + request.operation() + "|"
                + (isBlank(request.resource()) ? "-" : request.resource()) + "|"
                + (isBlank(request.dataScope()) ? "-" : request.dataScope());
        if (pendingGrants.containsKey(dupKey)) {
            throw new BaiException(PermissionCheckErrorCodes.STATE_CONFLICT,
                    "PMC_1005 授权冲突/重复申请：目标用户 " + request.targetUserId() + " 已有 PENDING 申请（" + pendingGrants.get(dupKey) + "）");
        }
        String now = LocalDateTime.now().format(TIME_FMT);
        int level = resolveLevel(request.operation(), request.dataScope());
        PermissionCheckDtos.GrantResult result;
        switch (level) {
            case 1 -> result = new PermissionCheckDtos.GrantResult(applyId, "APPROVED",
                    List.of(new PermissionCheckDtos.ApprovalStep(request.applicant(), "L1", "APPROVED", now)));
            case 2 -> {
                result = new PermissionCheckDtos.GrantResult(applyId, "PENDING",
                        List.of(new PermissionCheckDtos.ApprovalStep(resolveApprover(2, request.approver(), request.applicant()), "L2", "PENDING", now)));
                pendingGrants.put(dupKey, applyId);
            }
            case 3 -> {
                result = new PermissionCheckDtos.GrantResult(applyId, "PENDING",
                        List.of(new PermissionCheckDtos.ApprovalStep(resolveApprover(3, request.approver(), request.applicant()), "L3", "PENDING", now)));
                pendingGrants.put(dupKey, applyId);
            }
            default -> {
                // L4 双人+分管（特高危，双人审批）
                result = new PermissionCheckDtos.GrantResult(applyId, "PENDING", List.of(
                        new PermissionCheckDtos.ApprovalStep(resolveApprover(4, request.approver(), request.applicant()), "L4", "PENDING", now),
                        new PermissionCheckDtos.ApprovalStep("APPROVER_02_分管领导", "L4", "PENDING", now)));
                pendingGrants.put(dupKey, applyId);
            }
        }
        idempotency.put("grant", applyId, result);
        log.info("授权申请提交: requestId={}, applyId={}, target={}, operation={}, level=L{}, status={}",
                requestId, applyId, request.targetUserId(), request.operation(), level, result.status());
        return result;
    }

    // ------------------------------------------------------------- internal

    /** 角色策略加载：直配（source 入参）→ 父角色继承（INHERIT） */
    private void loadRolePolicies(String roleId, String source, List<PermissionCheckDtos.MatchedPolicy> out, String operation) {
        if (ROLE_OPS.getOrDefault(roleId, List.of()).contains(operation)) {
            out.add(new PermissionCheckDtos.MatchedPolicy("RP_" + roleId + "_" + operation, "ALLOW", source));
        }
        String parent = ROLE_PARENT.get(roleId);
        if (parent != null) {
            loadRolePolicies(parent, "INHERIT", out, operation);
        }
    }

    /** 数据范围类型：按角色取最高授权（ALL > ORG > SELF），最小授权原则 */
    private String resolveScope(UserProfile user) {
        String scope = null;
        for (String roleId : user.roleIds()) {
            String s = ROLE_SCOPE.getOrDefault(roleId, "SELF");
            if ("ALL".equals(s)) {
                return "ALL";
            }
            if ("ORG".equals(s) && !"ALL".equals(scope)) {
                scope = "ORG";
            } else if (scope == null) {
                scope = s;
            }
        }
        return scope == null ? "SELF" : scope;
    }

    /** 数据范围表达式：机构树（本级+下级）/归属客户/产品，CUSTOM 规则表达式 */
    private PermissionCheckDtos.ScopeExpression computeScope(UserProfile user, String scopeType, List<String> filters) {
        List<String> orgs = new ArrayList<>();
        List<String> customers = new ArrayList<>();
        List<String> products = new ArrayList<>();
        switch (scopeType) {
            case "ALL" -> {
                orgs.addAll(ORG_CUSTOMERS.keySet().stream().sorted().toList());
                customers.addAll(ORG_CUSTOMERS.get("ORG_BRANCH_001"));
                products.addAll(List.of("PROD_001", "PROD_002", "PROD_003"));
            }
            case "ORG" -> {
                orgs.addAll(collectOrgs(user.orgId()));
                customers.addAll(ORG_CUSTOMERS.getOrDefault(user.orgId(), List.of()));
                products.addAll(List.of("PROD_001", "PROD_002"));
            }
            default -> { // SELF：归属客户最小范围
                orgs.add(user.orgId());
                customers.addAll(CM_CUSTOMERS.getOrDefault(user.userId(), List.of()));
                products.add("PROD_001");
            }
        }
        String customRule = filters.isEmpty() ? null : "filters=" + String.join("&", filters);
        return new PermissionCheckDtos.ScopeExpression(orgs, customers, products, customRule);
    }

    /** 机构树遍历：本级 + 下级（递归） */
    private List<String> collectOrgs(String orgId) {
        List<String> out = new ArrayList<>();
        out.add(orgId);
        for (String child : ORG_CHILDREN.getOrDefault(orgId, List.of())) {
            out.addAll(collectOrgs(child));
        }
        return out;
    }

    /** 列级受限：SELF 全敏感受限 / ORG 部分受限 / ALL 不限（最小授权） */
    private List<String> deniedColumns(String scopeType, String dataType) {
        return switch (scopeType) {
            case "ALL" -> List.of();
            case "ORG" -> ORG_DENIED.getOrDefault(dataType, List.of());
            default -> SENSITIVE_COLUMNS.getOrDefault(dataType, List.of());
        };
    }

    /** 授权级别：查询类 L1/L2，机构操作 L2，敏感 L3，全局/资金类 L4 */
    private int resolveLevel(String operation, String dataScope) {
        String scope = isBlank(dataScope) ? "SELF" : dataScope.toUpperCase();
        if (operation.equals("GRANT_MGMT") || operation.equals("PMC_ADMIN") || operation.equals("ALL_QUERY")) {
            return 4; // 全局/资金类：双人+分管
        }
        if (operation.equals("FRD_CHECK") || operation.equals("AML_CHECK") || operation.equals("RISK_REPORT")) {
            return 3; // 跨机构/敏感：部门负责人
        }
        if (operation.equals("LOAN_APPROVE") || operation.equals("UIO_OP")) {
            return 2; // 本机构操作：直属上级
        }
        return "SELF".equals(scope) ? 1 : 2; // 查询类：本人数据自助，机构范围直属上级
    }

    private String resolveApprover(int level, String approver, String applicant) {
        if (approver != null && !isBlank(approver)) {
            return approver;
        }
        return switch (level) {
            case 2 -> "SUP_" + applicant;   // 直属上级
            case 3 -> "DEPT_" + applicant;  // 部门负责人
            default -> "APPROVER_01";
        };
    }

    private void validatePeriod(String validFrom, String validTo) {
        try {
            LocalDate from = validFrom == null || validFrom.isBlank() ? LocalDate.now() : LocalDate.parse(validFrom);
            LocalDate to = validTo == null || validTo.isBlank() ? null : LocalDate.parse(validTo);
            if (to != null && to.isBefore(from)) {
                throw new BaiException(PermissionCheckErrorCodes.PARAM_INVALID,
                        "PMC_1001 参数非法：validTo 不得早于 validFrom");
            }
        } catch (DateTimeParseException e) {
            throw new BaiException(PermissionCheckErrorCodes.PARAM_INVALID,
                    "PMC_1001 参数非法：validFrom/validTo 须为 yyyy-MM-dd 日期");
        }
    }

    private String nextId(String prefix) {
        return prefix + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private record UserProfile(String userId, List<String> roleIds, String orgId) {}

    private record DirectGrant(String policyId, String operation, String effect) {}
}
