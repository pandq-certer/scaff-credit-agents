package com.bai.approvalroute;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审批流转服务（BIA-EPT-029）。全行审批流转的统一路由能力。
 * <p>处理链：审批上下文装配 → 路由规则按优先级升序匹配命中即停 → 委托优先→角色映射→指定人员解析（每节点≤20 人）
 * → SINGLE/COUNTERSIGN/ORSIGN 模式判定与任务分发 → 动作状态机迁移（PENDING→APPROVED/REJECTED/RETURNED/WITHDRAWN，终态不可逆，
 * 版本号乐观锁防重复）→ 剩余 25% 时限催办、超时升级上级 → 写审批记录（保留≥3 年）。
 * <p>MVP 为确定性内存仿真（固定规则表 + Map 存储），生产规则执行由 rule-engine-agent 承担。
 */
@Service
public class ApprovalRouteService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRouteService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_APPROVERS = 20;             // 每节点审批人≤20 人
    private static final int MAX_OPINION_LEN = 500;          // 审批意见≤500 字
    private static final List<String> ACTIONS = List.of("APPROVE", "REJECT", "RETURN", "WITHDRAW");
    private static final List<String> TERMINALS = List.of("APPROVED", "REJECTED", "RETURNED", "WITHDRAWN");

    private final IdempotencyStore idempotency = new IdempotencyStore();

    /** 审批任务：taskId → 任务（含投票与版本） */
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    /** 路由决策：routeId → 决策记录 */
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    /** 委托关系：fromUser → toUser（同一审批人单委托，委托冲突拒绝新委托） */
    private final Map<String, String> delegations = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    private static final class Task {
        final String taskId;
        final String routeId;
        final String bizType;
        final String nodeName;
        final String mode;
        final List<ApprovalRouteDtos.Approver> approvers;
        final ApprovalRouteDtos.TimeLimit timeLimit;
        final String createdAt;
        String status = "PENDING";
        int version = 1;
        final Map<String, String> votes = new LinkedHashMap<>();
        final List<ApprovalRouteDtos.ApprovalRecord> records = new ArrayList<>();

        Task(String taskId, String routeId, String bizType, String nodeName, String mode,
             List<ApprovalRouteDtos.Approver> approvers, ApprovalRouteDtos.TimeLimit timeLimit, String createdAt) {
            this.taskId = taskId;
            this.routeId = routeId;
            this.bizType = bizType;
            this.nodeName = nodeName;
            this.mode = mode;
            this.approvers = approvers;
            this.timeLimit = timeLimit;
            this.createdAt = createdAt;
        }
    }

    private record Route(String routeId, String bizType, String nodeName, List<String> rulesHit, String createdAt) {}

    /** 路由：按业务类型与上下文（金额/风险/机构/产品）解析当前节点审批人与审批模式，创建审批任务 */
    public ApprovalRouteDtos.RouteResult route(Map<String, Object> body, String requestId) {
        if (body == null || isBlank(body.get("bizType")) || body.get("context") == null) {
            throw new BaiException(ApprovalRouteErrorCodes.PARAM_INVALID,
                    "APR_1001 参数缺失：bizType 与 context（金额/风险等级/机构/产品）必填");
        }
        String bizType = str(body.get("bizType"));
        Map<String, Object> context = cast(body.get("context"));
        if (context == null) {
            throw new BaiException(ApprovalRouteErrorCodes.PARAM_INVALID, "APR_1001 参数非法：context 须为对象");
        }
        ApprovalRouteDtos.RouteResult cached = idempotency.get("route", requestId);
        if (cached != null) {
            return cached;
        }
        // 路由规则表按优先级升序匹配，命中即停（确定性规则仿真）
        double amount = doubleOf(context.get("amount"));
        String riskLevel = strOr(context.get("riskLevel"), "LOW").toUpperCase();
        List<String> rulesHit = new ArrayList<>();
        String nodeName;
        String mode;
        List<String> userIds;
        int hours;
        if (amount >= 50_000_000 && "HIGH".equals(riskLevel)) {
            nodeName = "支行信贷审批";
            mode = "COUNTERSIGN";
            userIds = List.of("u2001", "u2002");
            hours = 48;
            rulesHit.add("rule-amount-5000w");
            rulesHit.add("rule-risk-high");
        } else if (amount >= 10_000_000) {
            nodeName = "审批部复核";
            mode = "SINGLE";
            userIds = List.of("u3001");
            hours = 24;
            rulesHit.add("rule-amount-1000w");
        } else if ("HIGH".equals(riskLevel)) {
            nodeName = "风险审批岗";
            mode = "SINGLE";
            userIds = List.of("u4001");
            hours = 24;
            rulesHit.add("rule-risk-high");
        } else {
            nodeName = "支行客户经理审批";
            mode = "SINGLE";
            userIds = List.of("u1001");
            hours = 8;
            rulesHit.add("rule-default");
        }
        if (userIds.size() > MAX_APPROVERS) {
            throw new BaiException(ApprovalRouteErrorCodes.RULE_UNMATCHED,
                    "APR_1006 路由结果非法：单节点审批人超 20 人");
        }
        List<ApprovalRouteDtos.Approver> approvers = userIds.stream()
                .map(u -> new ApprovalRouteDtos.Approver(u, mode, 1)).toList();
        String routeId = "RT" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        ApprovalRouteDtos.TimeLimit timeLimit = new ApprovalRouteDtos.TimeLimit(hours,
                LocalDateTime.now().plusHours(hours).toString());
        // 创建审批任务（PENDING，含时限，供 todo-agent 生成待办）
        String taskId = "TSK" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        Task task = new Task(taskId, routeId, bizType, nodeName, mode, approvers, timeLimit,
                LocalDateTime.now().toString());
        tasks.put(taskId, task);
        routes.put(routeId, new Route(routeId, bizType, nodeName, List.copyOf(rulesHit), task.createdAt));
        log.info("审批路由决策: routeId={}, taskId={}, bizType={}, nodeName={}, mode={}, rulesHit={}",
                routeId, taskId, bizType, nodeName, mode, rulesHit);
        ApprovalRouteDtos.RouteResult result =
                new ApprovalRouteDtos.RouteResult(routeId, nodeName, approvers, timeLimit, List.copyOf(rulesHit));
        idempotency.put("route", requestId, result);
        return result;
    }

    /** 审批动作：同意/驳回/退回/撤回，状态机迁移 + 版本乐观锁；非任务审批人拒绝（APR_1003） */
    public ApprovalRouteDtos.ApproveResult approve(Map<String, Object> body, String requestId, String operator) {
        if (body == null || isBlank(body.get("taskId")) || isBlank(body.get("action"))) {
            throw new BaiException(ApprovalRouteErrorCodes.PARAM_INVALID,
                    "APR_1001 参数缺失：taskId/action（APPROVE/REJECT/RETURN/WITHDRAW）必填");
        }
        if (isBlank(operator)) {
            throw new BaiException(ApprovalRouteErrorCodes.PARAM_INVALID,
                    "APR_1001 参数缺失：Authorization Bearer Token（审批人身份）必填");
        }
        String taskId = str(body.get("taskId"));
        String action = str(body.get("action")).toUpperCase();
        if (!ACTIONS.contains(action)) {
            throw new BaiException(ApprovalRouteErrorCodes.PARAM_INVALID,
                    "APR_1001 参数非法：action 仅支持 " + ACTIONS);
        }
        String opinion = strOr(body.get("opinion"), "");
        if (opinion.length() > MAX_OPINION_LEN) {
            throw new BaiException(ApprovalRouteErrorCodes.PARAM_INVALID,
                    "APR_1001 参数非法：审批意见超 " + MAX_OPINION_LEN + " 字");
        }
        Task task = tasks.get(taskId);
        if (task == null) {
            throw new BaiException(ApprovalRouteErrorCodes.NOT_FOUND, "APR_1004 审批任务不存在：" + taskId);
        }
        // 非任务审批人发起动作拒绝（委托生效时以受托人身份校验）
        String effectiveUser = effectiveUser(operator);
        boolean isApprover = task.approvers.stream().anyMatch(a -> a.userId().equals(effectiveUser))
                && !"WITHDRAWN".equals(task.status);
        if (!isApprover) {
            throw new BaiException(ApprovalRouteErrorCodes.NOT_APPROVER,
                    "APR_1003 无审批权限：非任务审批人（operator=" + operator + "）");
        }
        // 版本乐观锁（可选传 expectedVersion，冲突即 APR_1005）
        Integer expected = (Integer) intOrNull(body.get("expectedVersion"));
        if (expected != null && expected != task.version) {
            throw new BaiException(ApprovalRouteErrorCodes.STATE_CONFLICT,
                    "APR_1005 版本冲突：期望 version=" + expected + "，实际 version=" + task.version + "，刷新后重试");
        }
        if (TERMINALS.contains(task.status)) {
            throw new BaiException(ApprovalRouteErrorCodes.STATE_CONFLICT,
                    "APR_1005 状态冲突：任务已终态（" + task.status + "），终态不可逆");
        }
        // 重复审批（同一审批人已投票）返回 APR_1005
        if (task.votes.containsKey(effectiveUser)) {
            throw new BaiException(ApprovalRouteErrorCodes.STATE_CONFLICT,
                    "APR_1005 重复审批：" + effectiveUser + " 已提交动作 " + task.votes.get(effectiveUser));
        }
        synchronized (task) {
            String at = LocalDateTime.now().toString();
            task.records.add(new ApprovalRouteDtos.ApprovalRecord(operator, action, opinion, at));
            task.votes.put(effectiveUser, action);
            switch (action) {
                case "REJECT" -> task.status = "REJECTED";
                case "RETURN" -> task.status = "RETURNED";
                case "WITHDRAW" -> task.status = "WITHDRAWN";
                case "APPROVE" -> {
                    long approvedCount = task.votes.values().stream().filter("APPROVE"::equals).count();
                    boolean passed = switch (task.mode) {
                        case "COUNTERSIGN" -> approvedCount == task.approvers.size(); // 会签全部同意
                        case "ORSIGN" -> approvedCount >= 1;                          // 或签任一同意
                        default -> approvedCount >= 1;                                // 单人
                    };
                    if (passed) {
                        task.status = "APPROVED";
                    }
                }
                default -> throw new BaiException(ApprovalRouteErrorCodes.PARAM_INVALID,
                        "APR_1001 参数非法：action 仅支持 " + ACTIONS);
            }
            task.version++;
            log.info("审批动作: taskId={}, action={}, operator={}, status={}, version={}",
                    taskId, action, operator, task.status, task.version);
            return new ApprovalRouteDtos.ApproveResult(taskId, action, task.status, task.version,
                    operator, opinion, at);
        }
    }

    /** 审批任务查询（status/bizType/approver 过滤） */
    public List<ApprovalRouteDtos.TaskInfo> queryTasks(Map<String, Object> criteria) {
        Map<String, Object> c = criteria == null ? Map.of() : criteria;
        String status = strOrNull(c.get("status"));
        String bizType = strOrNull(c.get("bizType"));
        String approver = strOrNull(c.get("approver"));
        return tasks.values().stream()
                .filter(t -> status == null || t.status.equals(status))
                .filter(t -> bizType == null || t.bizType.equals(bizType))
                .filter(t -> approver == null || t.approvers.stream().anyMatch(a -> a.userId().equals(approver)))
                .sorted(java.util.Comparator.comparing(t -> t.taskId))
                .map(t -> new ApprovalRouteDtos.TaskInfo(t.taskId, t.routeId, t.bizType, t.nodeName, t.mode,
                        t.approvers, t.status, t.version, t.timeLimit, Map.copyOf(t.votes), t.createdAt))
                .toList();
    }

    /** 审批链查询（按 taskId 或 routeId，返回决策 + 任务 + 全量记录） */
    public ApprovalRouteDtos.ChainResult chain(String taskId, String routeId) {
        Task task;
        if (taskId != null && !taskId.isBlank()) {
            task = tasks.get(taskId);
            if (task == null) {
                throw new BaiException(ApprovalRouteErrorCodes.NOT_FOUND, "APR_1004 审批任务不存在：" + taskId);
            }
        } else if (routeId != null && !routeId.isBlank()) {
            Route r = routes.get(routeId);
            if (r == null) {
                throw new BaiException(ApprovalRouteErrorCodes.NOT_FOUND, "APR_1004 路由决策不存在：" + routeId);
            }
            task = tasks.values().stream().filter(t -> t.routeId.equals(routeId)).findFirst()
                    .orElseThrow(() -> new BaiException(ApprovalRouteErrorCodes.NOT_FOUND,
                            "APR_1004 审批链不存在：routeId=" + routeId));
        } else {
            throw new BaiException(ApprovalRouteErrorCodes.PARAM_INVALID,
                    "APR_1001 参数缺失：taskId 或 routeId 至少传一个");
        }
        return new ApprovalRouteDtos.ChainResult(task.routeId, task.taskId, task.bizType, task.nodeName,
                task.status, task.version, List.copyOf(task.records), task.createdAt);
    }

    /** 委托解析：有效委托→受托人；委托冲突（受托人已委托他人）拒绝 */
    private String effectiveUser(String operator) {
        String target = delegations.get(operator);
        if (target == null) {
            return operator;
        }
        String next = delegations.get(target);
        if (next != null) {
            throw new BaiException(ApprovalRouteErrorCodes.RULE_UNMATCHED,
                    "APR_1006 委托冲突：受托人 " + target + " 已存在生效委托，拒绝新委托");
        }
        return target;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static boolean isBlank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String strOrNull(Object o) {
        String s = str(o);
        return s == null || s.isBlank() ? null : s;
    }

    private static String strOr(Object o, String defaultValue) {
        String s = str(o);
        return s == null || s.isBlank() ? defaultValue : s;
    }

    private static Integer intOrNull(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? null : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static double doubleOf(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? 0 : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
