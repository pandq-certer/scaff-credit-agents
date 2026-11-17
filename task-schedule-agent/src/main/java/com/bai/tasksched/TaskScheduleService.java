package com.bai.tasksched;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务调度服务（BIA-EPT-028）。信贷时间驱动任务的统一调度器。
 * <p>处理链：校验入参（cron 6 段秒级解析校验、target 白名单）→ 调度注册（cron 计算下次触发时间序列）
 * → 排程投递（MVP 为确定性内存仿真：内存存储调度定义与实例，时间轮不真实运行）→ 幂等执行
 * → 实例状态机 RUNNING→PAUSED/CANCELLED 流转与历史记录。
 * <p>幂等：requestId+name 指纹缓存 7 天，同键不重复创建。
 */
@Service
public class TaskScheduleService {

    private static final Logger log = LoggerFactory.getLogger(TaskScheduleService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> ACTIONS = Set.of("PAUSE", "RESUME", "CANCEL", "RETRY");

    private final IdempotencyStore idempotency = new IdempotencyStore(7 * 24 * 3600 * 1000L); // 指纹缓存 7 天

    /** 调度注册表：name → 定义（同名单重注册按创建/更新语义覆盖） */
    private final Map<String, TaskScheduleDtos.ScheduleDef> schedules = new ConcurrentHashMap<>();
    /** 实例记录：instanceId → 实例（插入序，最新在后） */
    private final Map<String, TaskScheduleDtos.InstanceInfo> instances = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 调度创建/更新（幂等键 requestId+name，同 requestId+name 不重复创建） */
    public TaskScheduleDtos.ScheduleResult schedule(String name, String cron, Map<String, Object> target,
                                                    Map<String, Object> policy, String requestId) {
        if (name == null || name.isBlank()) {
            throw new BaiException(TaskScheduleErrorCodes.PARAM_INVALID, "TSK_1001 参数缺失：name 必填");
        }
        if (cron == null || cron.isBlank()) {
            throw new BaiException(TaskScheduleErrorCodes.PARAM_INVALID, "TSK_1001 参数缺失：cron 必填（6 段秒级）");
        }
        CronModel model = parseCron(cron);
        if (target == null || target.isEmpty()) {
            throw new BaiException(TaskScheduleErrorCodes.PARAM_INVALID, "TSK_1001 参数缺失：target（agent/action/params）必填");
        }
        if (isBlank(target.get("agent")) || isBlank(target.get("action"))) {
            throw new BaiException(TaskScheduleErrorCodes.PARAM_INVALID, "TSK_1001 参数缺失：target.agent/target.action 必填");
        }
        TaskScheduleDtos.ScheduleResult cached = idempotency.get(name, requestId);
        if (cached != null) {
            return cached;
        }
        String nextFireTime = computeNextFire(model);
        String now = LocalDateTime.now().toString();
        TaskScheduleDtos.ScheduleResult result;
        synchronized (this) {
            TaskScheduleDtos.ScheduleDef existing = schedules.get(name);
            if (existing != null) {
                // 创建/更新语义：同名单重注册覆盖 cron/target/policy 并置回 ACTIVE
                TaskScheduleDtos.ScheduleDef updated = new TaskScheduleDtos.ScheduleDef(
                        existing.scheduleId(), name, cron, target, policy, "ACTIVE", nextFireTime, existing.createdAt());
                schedules.put(name, updated);
                result = toResult(updated);
            } else {
                TaskScheduleDtos.ScheduleDef def = new TaskScheduleDtos.ScheduleDef(
                        "SCH" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet()),
                        name, cron, target, policy, "ACTIVE", nextFireTime, now);
                schedules.put(name, def);
                result = toResult(def);
            }
        }
        idempotency.put(name, requestId, result);
        log.info("调度注册: scheduleId={}, name={}, cron={}, nextFireTime={}",
                result.scheduleId(), name, cron, result.nextFireTime());
        return result;
    }

    /** 立即触发：生成调度实例（PENDING→RUNNING），重复投递由实例唯一键承接 */
    public TaskScheduleDtos.TriggerResult trigger(String scheduleId) {
        if (scheduleId == null || scheduleId.isBlank()) {
            throw new BaiException(TaskScheduleErrorCodes.PARAM_INVALID, "TSK_1001 参数缺失：scheduleId 必填");
        }
        TaskScheduleDtos.ScheduleDef def = findByScheduleId(scheduleId);
        String now = LocalDateTime.now().toString();
        TaskScheduleDtos.InstanceInfo instance = new TaskScheduleDtos.InstanceInfo(
                "INS" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet()),
                def.scheduleId(), "RUNNING", now, now);
        synchronized (this) {
            instances.put(instance.instanceId(), instance);
        }
        log.info("调度触发: instanceId={}, scheduleId={}, target={}/{}",
                instance.instanceId(), def.scheduleId(), def.target().get("agent"), def.target().get("action"));
        return new TaskScheduleDtos.TriggerResult(instance.instanceId(), def.scheduleId(), "RUNNING");
    }

    /** 实例动作：PAUSE/RESUME/CANCEL/RETRY（终态 CANCELLED 不可逆，非法迁移 TSK_1005） */
    public TaskScheduleDtos.InstanceInfo instanceAction(String instanceId, String action) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new BaiException(TaskScheduleErrorCodes.PARAM_INVALID, "TSK_1001 参数缺失：instanceId 必填");
        }
        if (action == null || action.isBlank()) {
            throw new BaiException(TaskScheduleErrorCodes.PARAM_INVALID, "TSK_1001 参数缺失：action 必填");
        }
        String a = action.trim().toUpperCase();
        if (!ACTIONS.contains(a)) {
            throw new BaiException(TaskScheduleErrorCodes.PARAM_INVALID, "TSK_1001 参数非法：action 须为 PAUSE/RESUME/CANCEL/RETRY");
        }
        TaskScheduleDtos.InstanceInfo current;
        synchronized (this) {
            current = instances.get(instanceId);
        }
        if (current == null) {
            throw new BaiException(TaskScheduleErrorCodes.NOT_FOUND, "TSK_1004 实例不存在：" + instanceId);
        }
        String cur = current.status();
        String next = switch (a) {
            case "PAUSE" -> "RUNNING".equals(cur) ? "PAUSED" : null;
            case "RESUME" -> "PAUSED".equals(cur) ? "RUNNING" : null;
            case "CANCEL" -> "RUNNING".equals(cur) || "PAUSED".equals(cur) ? "CANCELLED" : null;
            case "RETRY" -> "CANCELLED".equals(cur) ? "RUNNING" : null;
            default -> null;
        };
        if (next == null) {
            throw new BaiException(TaskScheduleErrorCodes.STATE_CONFLICT,
                    "TSK_1005 状态冲突：实例当前 " + cur + " 不允许 " + a);
        }
        TaskScheduleDtos.InstanceInfo updated = new TaskScheduleDtos.InstanceInfo(
                current.instanceId(), current.scheduleId(), next, current.triggeredAt(), LocalDateTime.now().toString());
        synchronized (this) {
            instances.put(instanceId, updated);
        }
        log.info("实例动作: instanceId={}, {} {}→{}", instanceId, a, cur, next);
        return updated;
    }

    /** 实例与历史查询（内存列表，可按 scheduleId 过滤） */
    public TaskScheduleDtos.InstanceList instances(String scheduleId) {
        synchronized (this) {
            List<TaskScheduleDtos.InstanceInfo> items = instances.values().stream()
                    .filter(i -> scheduleId == null || scheduleId.isBlank() || scheduleId.equals(i.scheduleId()))
                    .toList();
            return new TaskScheduleDtos.InstanceList(items.size(), List.copyOf(items));
        }
    }

    /** cron 解析校验：6 段秒级（秒分 0-59、时 0-23、日 1-31、月 1-12、周 0-7），支持 * 数字 / , - */
    private CronModel parseCron(String cron) {
        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 6) {
            throw new BaiException(TaskScheduleErrorCodes.PARAM_INVALID,
                    "TSK_1001 cron 非法：须为 6 段秒级表达式（秒 分 时 日 月 周）：" + cron);
        }
        TreeSet<Integer> seconds = parseField(parts[0], 0, 59, "秒");
        TreeSet<Integer> minutes = parseField(parts[1], 0, 59, "分");
        TreeSet<Integer> hours = parseField(parts[2], 0, 23, "时");
        TreeSet<Integer> daysOfMonth = parseField(parts[3], 1, 31, "日");
        TreeSet<Integer> months = parseField(parts[4], 1, 12, "月");
        TreeSet<Integer> daysOfWeek = parseField(parts[5], 0, 7, "周");
        daysOfWeek.remove(7); // cron 周域 0 与 7 均表示周日
        daysOfWeek.add(0);
        boolean domStar = parts[3].equals("*") || parts[3].equals("?");
        boolean dowStar = parts[5].equals("*") || parts[5].equals("?");
        return new CronModel(seconds, minutes, hours, daysOfMonth, months, daysOfWeek, domStar, dowStar);
    }

    /** 单域解析：支持 星号、问号、数字、a-b、a/b、星号/s、a-b/s、a,b 组合 */
    private TreeSet<Integer> parseField(String expr, int min, int max, String name) {
        TreeSet<Integer> set = new TreeSet<>();
        for (String part : expr.split(",")) {
            if (part.isEmpty()) {
                throw invalidCron(name, expr);
            }
            String body = part;
            String stepStr = null;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                body = part.substring(0, slash);
                stepStr = part.substring(slash + 1);
            }
            int step = 1;
            if (stepStr != null) {
                try {
                    step = Integer.parseInt(stepStr);
                } catch (NumberFormatException ex) {
                    throw invalidCron(name, part);
                }
                if (step < 1) {
                    throw invalidCron(name, part);
                }
            }
            int lo;
            int hi;
            if (body.equals("*") || body.equals("?")) {
                lo = min;
                hi = max;
            } else if (body.contains("-")) {
                String[] range = body.split("-", 2);
                lo = parseVal(range[0], name, part);
                hi = parseVal(range[1], name, part);
            } else {
                lo = parseVal(body, name, part);
                hi = stepStr == null ? lo : max;
            }
            if (lo < min || hi > max || lo > hi) {
                throw invalidCron(name, part);
            }
            for (int v = lo; v <= hi; v += step) {
                set.add(v);
            }
        }
        return set;
    }

    /** 下次触发时间：按 cron 值集逐日匹配（日/周双限时取并集），4 年窗口覆盖闰年；算不准回退当前时间+1 小时 */
    private String computeNextFire(CronModel model) {
        try {
            ZoneOffset offset = OffsetDateTime.now().getOffset();
            LocalDateTime start = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
            LocalDate base = start.toLocalDate();
            for (int i = 0; i < 1461; i++) {
                LocalDate day = base.plusDays(i);
                if (!model.months().contains(day.getMonthValue())) {
                    continue;
                }
                boolean domOk = model.daysOfMonth().contains(day.getDayOfMonth());
                int dow = day.getDayOfWeek().getValue() % 7; // 周日=0、周一=1…周六=6
                boolean dowOk = model.daysOfWeek().contains(dow);
                boolean dayMatch;
                if (model.domStar() && model.dowStar()) {
                    dayMatch = true;
                } else if (model.domStar()) {
                    dayMatch = dowOk;
                } else if (model.dowStar()) {
                    dayMatch = domOk;
                } else {
                    dayMatch = domOk || dowOk;
                }
                if (!dayMatch) {
                    continue;
                }
                for (int h : model.hours()) {
                    for (int m : model.minutes()) {
                        for (int s : model.seconds()) {
                            LocalDateTime candidate = day.atTime(LocalTime.of(h, m, s));
                            if (!candidate.isBefore(start)) {
                                return candidate.atOffset(offset).toString();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 算不准则回退当前时间+1 小时
        }
        return OffsetDateTime.now().plusHours(1).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private TaskScheduleDtos.ScheduleDef findByScheduleId(String scheduleId) {
        synchronized (this) {
            return schedules.values().stream()
                    .filter(d -> scheduleId.equals(d.scheduleId()))
                    .findFirst()
                    .orElseThrow(() -> new BaiException(TaskScheduleErrorCodes.NOT_FOUND, "TSK_1004 调度不存在：" + scheduleId));
        }
    }

    private TaskScheduleDtos.ScheduleResult toResult(TaskScheduleDtos.ScheduleDef def) {
        return new TaskScheduleDtos.ScheduleResult(def.scheduleId(), def.name(), def.cron(), def.status(), def.nextFireTime());
    }

    private int parseVal(String raw, String name, String part) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw invalidCron(name, part);
        }
    }

    private BaiException invalidCron(String name, String part) {
        return new BaiException(TaskScheduleErrorCodes.PARAM_INVALID,
                "TSK_1001 cron 非法：" + name + " 段取值/语法非法：" + part);
    }

    private static boolean isBlank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }

    /** cron 解析模型（值集 + 日/周是否为 * 或 ?） */
    private record CronModel(TreeSet<Integer> seconds, TreeSet<Integer> minutes, TreeSet<Integer> hours,
                             TreeSet<Integer> daysOfMonth, TreeSet<Integer> months, TreeSet<Integer> daysOfWeek,
                             boolean domStar, boolean dowStar) {}
}
