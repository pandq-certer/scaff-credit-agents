package com.bai.todo;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 任务待办服务（BIA-TSK-030）。接收上游场景（审批/检查/贷后/放款）产出的待办任务，完成
 * 创建、认领、处理、完成、取消、转办的状态机管理，并输出个人/组织维度的待办列表与统计。
 * <p>状态机：PENDING →(CLAIM)→ CLAIMED →(START)→ IN_PROGRESS →(COMPLETE)→ DONE；
 * CANCEL 任一非终态可取消；TRANSFER 非终态改派受理人；到期未处理自动置 EXPIRED（读取时惰性判定）。
 */
@Service
public class TodoService {

    private static final Logger log = LoggerFactory.getLogger(TodoService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_TITLE_LEN = 200;                        // 标题超限 TDO_1006
    private static final Set<String> SOURCES = Set.of("APPROVAL", "INSPECT", "COLLECT", "DISBURSE");
    private static final Set<String> ACTIONS = Set.of("CLAIM", "START", "COMPLETE", "CANCEL", "TRANSFER");
    private static final Set<String> PRIORITIES = Set.of("P0", "P1", "P2", "P3");
    private static final Set<String> TERMINAL = Set.of("DONE", "CANCELLED", "EXPIRED");

    /** 幂等缓存：requestId+source+bizId，7 天 */
    private final IdempotencyStore idempotency = new IdempotencyStore(7 * 24 * 3600_1000L);

    /** 待办库：todoId → 待办记录 */
    private final Map<String, Todo> todos = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 内部待办模型（状态流转以整条替换实现） */
    private record Todo(String todoId, String source, String bizId, String title, TodoDtos.Assignee assignee,
                        String priority, String dueTime, String status, String createdAt) {}

    /** 待办创建（幂等键 requestId+source+bizId；同键 7 天内返回首次结果，不重复建单） */
    public TodoDtos.TodoView create(Map<String, Object> body, String requestId) {
        String source = str(body.get("source"));
        if (source == null || source.isBlank()) {
            throw new BaiException(TodoErrorCodes.PARAM_INVALID, "TDO_1001 参数缺失：source 必填");
        }
        source = source.toUpperCase();
        if (!SOURCES.contains(source)) {
            throw new BaiException(TodoErrorCodes.RULE_VIOLATED,
                    "TDO_1006 source 未注册：" + source + "（仅支持 APPROVAL/INSPECT/COLLECT/DISBURSE）");
        }
        String bizId = str(body.get("bizId"));
        if (isBlank(bizId)) {
            throw new BaiException(TodoErrorCodes.PARAM_INVALID, "TDO_1001 参数缺失：bizId 必填");
        }
        String title = str(body.get("title"));
        if (isBlank(title)) {
            throw new BaiException(TodoErrorCodes.PARAM_INVALID, "TDO_1001 参数缺失：title 必填");
        }
        if (title.length() > MAX_TITLE_LEN) {
            throw new BaiException(TodoErrorCodes.RULE_VIOLATED,
                    "TDO_1006 标题超限（" + title.length() + " > " + MAX_TITLE_LEN + " 字符），请缩减标题");
        }
        TodoDtos.Assignee assignee = assigneeOf(body.get("assignee"));
        if (assignee == null) {
            throw new BaiException(TodoErrorCodes.RULE_VIOLATED,
                    "TDO_1006 受理人缺失：assignee.userId/roleId/orgId 至少一项必填");
        }
        String priority = str(body.get("priority"));
        if (isBlank(priority)) {
            priority = "P2";
        }
        priority = priority.toUpperCase();
        if (!PRIORITIES.contains(priority)) {
            throw new BaiException(TodoErrorCodes.PARAM_INVALID, "TDO_1001 参数非法：priority 仅支持 P0-P3");
        }
        String dueTime = str(body.get("dueTime"));
        if (!isBlank(dueTime)) {
            try {
                LocalDateTime.parse(dueTime);
            } catch (DateTimeParseException e) {
                throw new BaiException(TodoErrorCodes.PARAM_INVALID,
                        "TDO_1001 参数非法：dueTime 须为 ISO 时间（如 2026-11-30T18:00:00）");
            }
        }
        String idemKey = requestId + "|" + source + "|" + bizId;
        Todo cached = idempotency.get("create", idemKey);
        if (cached != null) {
            return view(expireIfNeeded(cached));
        }
        String todoId = "TDO" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet() % 10000);
        Todo todo = new Todo(todoId, source, bizId, title, assignee, priority, dueTime,
                "PENDING", LocalDateTime.now().withNano(0).toString());
        todos.put(todoId, todo);
        idempotency.put("create", idemKey, todo);
        log.info("待办创建: todoId={}, source={}, bizId={}, assignee={}/{}, priority={}, dueTime={}",
                todoId, source, bizId, assignee.type(), assignee.id(), priority, dueTime);
        return view(todo);
    }

    /** 待办动作（终态操作→TDO_1006；重复领取/状态冲突→TDO_1005；非归属人→TDO_1003；不存在→TDO_1004） */
    public TodoDtos.ActionResult action(Map<String, Object> body, String requestId) {
        String todoId = str(body.get("todoId"));
        if (isBlank(todoId)) {
            throw new BaiException(TodoErrorCodes.PARAM_INVALID, "TDO_1001 参数缺失：todoId 必填");
        }
        Todo todo = todos.get(todoId);
        if (todo == null) {
            throw new BaiException(TodoErrorCodes.NOT_FOUND, "TDO_1004 待办不存在：" + todoId);
        }
        todo = expireIfNeeded(todo);
        String action = str(body.get("action"));
        if (action == null || action.isBlank()) {
            throw new BaiException(TodoErrorCodes.PARAM_INVALID, "TDO_1001 参数缺失：action 必填");
        }
        action = action.toUpperCase();
        if (!ACTIONS.contains(action)) {
            throw new BaiException(TodoErrorCodes.PARAM_INVALID,
                    "TDO_1001 参数非法：action 仅支持 CLAIM/START/COMPLETE/CANCEL/TRANSFER");
        }
        String operator = str(body.get("operator"));
        String status = todo.status();
        if (TERMINAL.contains(status)) {
            throw new BaiException(TodoErrorCodes.RULE_VIOLATED,
                    "TDO_1006 待办已终态（" + status + "），不允许 " + action + " 操作");
        }
        // 归属校验：CLAIM 任意人可认领，其余动作须为当前受理人
        if (!"CLAIM".equals(action) && todo.assignee().type().equals("USER")
                && !isBlank(operator) && !operator.equals(todo.assignee().id())) {
            throw new BaiException(TodoErrorCodes.NO_PERMISSION,
                    "TDO_1003 非归属人：操作人 " + operator + " 与受理人 " + todo.assignee().id() + " 不一致");
        }
        Todo updated = switch (action) {
            case "CLAIM" -> {
                if (!"PENDING".equals(status)) {
                    throw new BaiException(TodoErrorCodes.STATE_CONFLICT,
                            "TDO_1005 状态冲突：待办已认领或处理中（" + status + "），不可重复领取");
                }
                yield withStatus(todo, "CLAIMED");
            }
            case "START" -> {
                if (!"CLAIMED".equals(status)) {
                    throw new BaiException(TodoErrorCodes.STATE_CONFLICT,
                            "TDO_1005 状态冲突：" + status + " 状态不可开始处理（须先认领）");
                }
                yield withStatus(todo, "IN_PROGRESS");
            }
            case "COMPLETE" -> {
                if (!"CLAIMED".equals(status) && !"IN_PROGRESS".equals(status)) {
                    throw new BaiException(TodoErrorCodes.STATE_CONFLICT,
                            "TDO_1005 状态冲突：" + status + " 状态不可完成（须先认领/开始处理）");
                }
                yield withStatus(todo, "DONE");
            }
            case "CANCEL" -> withStatus(todo, "CANCELLED");
            case "TRANSFER" -> {
                TodoDtos.Assignee transferTo = assigneeOf(body.get("transferTo"));
                if (transferTo == null) {
                    throw new BaiException(TodoErrorCodes.PARAM_INVALID,
                            "TDO_1001 参数缺失：transferTo.userId/roleId/orgId 必填其一");
                }
                yield new Todo(todo.todoId(), todo.source(), todo.bizId(), todo.title(), transferTo,
                        todo.priority(), todo.dueTime(), status, todo.createdAt());
            }
            default -> throw new BaiException(TodoErrorCodes.PARAM_INVALID,
                    "TDO_1001 参数非法：action 仅支持 CLAIM/START/COMPLETE/CANCEL/TRANSFER");
        };
        todos.put(todoId, updated);
        log.info("待办动作: todoId={}, action={}, {} -> {}, operator={}, requestId={}",
                todoId, action, status, updated.status(), operator, requestId);
        return new TodoDtos.ActionResult(todoId, action, updated.status(), operator);
    }

    /** 待办列表（MINE=按受理人 userId，ORG=按组织 orgId） */
    public List<TodoDtos.TodoView> list(String scope, String userId, String orgId) {
        if (scope == null || scope.isBlank()) {
            throw new BaiException(TodoErrorCodes.PARAM_INVALID, "TDO_1001 参数缺失：scope 必填（MINE/ORG）");
        }
        List<Todo> matched;
        switch (scope.toUpperCase()) {
            case "MINE" -> {
                if (isBlank(userId)) {
                    throw new BaiException(TodoErrorCodes.PARAM_INVALID, "TDO_1001 参数缺失：scope=MINE 时 userId 必填");
                }
                matched = todos.values().stream()
                        .filter(t -> "USER".equals(t.assignee().type()) && userId.equals(t.assignee().id()))
                        .toList();
            }
            case "ORG" -> {
                if (isBlank(orgId)) {
                    throw new BaiException(TodoErrorCodes.PARAM_INVALID, "TDO_1001 参数缺失：scope=ORG 时 orgId 必填");
                }
                matched = todos.values().stream()
                        .filter(t -> "ORG".equals(t.assignee().type()) && orgId.equals(t.assignee().id()))
                        .toList();
            }
            default -> throw new BaiException(TodoErrorCodes.PARAM_INVALID,
                    "TDO_1001 参数非法：scope 仅支持 MINE/ORG");
        }
        return matched.stream().map(this::expireIfNeeded).map(this::view)
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt())).toList();
    }

    /** 待办统计（按状态/按来源） */
    public TodoDtos.Stats stats() {
        Map<String, Long> byStatus = todos.values().stream()
                .map(t -> expireIfNeeded(t).status())
                .collect(Collectors.groupingBy(s -> s, LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> bySource = todos.values().stream()
                .collect(Collectors.groupingBy(Todo::source, LinkedHashMap::new, Collectors.counting()));
        return new TodoDtos.Stats(todos.size(), byStatus, bySource);
    }

    /** 惰性到期：非终态且已过 dueTime → EXPIRED（回写存储） */
    private Todo expireIfNeeded(Todo todo) {
        if (TERMINAL.contains(todo.status()) || isBlank(todo.dueTime())) {
            return todo;
        }
        try {
            if (LocalDateTime.now().isAfter(LocalDateTime.parse(todo.dueTime()))) {
                Todo expired = withStatus(todo, "EXPIRED");
                todos.put(todo.todoId(), expired);
                log.info("待办到期置 EXPIRED: todoId={}, dueTime={}", todo.todoId(), todo.dueTime());
                return expired;
            }
        } catch (DateTimeParseException ignored) {
            // dueTime 已在创建时校验，此处防御性忽略
        }
        return todo;
    }

    private Todo withStatus(Todo todo, String status) {
        return new Todo(todo.todoId(), todo.source(), todo.bizId(), todo.title(), todo.assignee(),
                todo.priority(), todo.dueTime(), status, todo.createdAt());
    }

    /** 受理人解析：userId > roleId > orgId 优先取一 */
    private TodoDtos.Assignee assigneeOf(Object raw) {
        if (!(raw instanceof Map<?, ?> m) || m.isEmpty()) {
            return null;
        }
        String userId = str(m.get("userId"));
        String roleId = str(m.get("roleId"));
        String orgId = str(m.get("orgId"));
        if (!isBlank(userId)) {
            return new TodoDtos.Assignee("USER", userId);
        }
        if (!isBlank(roleId)) {
            return new TodoDtos.Assignee("ROLE", roleId);
        }
        if (!isBlank(orgId)) {
            return new TodoDtos.Assignee("ORG", orgId);
        }
        return null;
    }

    private TodoDtos.TodoView view(Todo todo) {
        return new TodoDtos.TodoView(todo.todoId(), todo.source(), todo.bizId(), todo.title(), todo.assignee(),
                todo.priority(), todo.dueTime(), todo.status(), todo.createdAt());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
