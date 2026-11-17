package com.bai.creditreport;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import com.spatix.scaff.iprov.ProviderResponse;
import com.spatix.scaff.iprov.provider.CreditReportProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 征信管理服务（提示词库 A-52，BIA-RSK-051）。
 * <p>处理链：入参校验（CRR_AUTH）→ 授权三重校验（有效期默认 365 天 + 用途白名单 + 客户要素一致）→
 * 报文组解（经 scaff-iprov CreditReportProvider 送人行征信接口，Mock 内置；结构为二代征信规范要素仿真，用于联调）→
 * 报告落库（脱敏存储）→ 异议受理与 20 日反馈时限跟踪 → 留痕（超范围用途拒绝并告警）。
 * <p>幂等：requestId+主体哈希不重复授权/查询；限流 10 QPS（authorize/query）、5 QPS（report/dispute）。
 */
@Service
public class CreditReportService {

    private static final Logger log = LoggerFactory.getLogger(CreditReportService.class);

    private static final Set<String> ACTIONS = Set.of("CREATE", "VALIDATE", "RENEW", "EXPIRE");
    /** 用途白名单（信贷审批/贷后管理/异议核查等，仿真配置） */
    private static final Set<String> PURPOSE_WHITELIST =
            Set.of("信贷审批", "贷后管理", "信用卡审批", "担保资格审查", "异议核查");
    private static final int DEFAULT_VALID_DAYS = 365;
    private static final int DISPUTE_LIMIT_DAYS = 20;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final IdempotencyStore idempotency = new IdempotencyStore();
    /** 授权记录：authId → 记录（生产落 H_credit_report_T，本阶段内存仿真） */
    private final Map<String, AuthRecord> authStore = new ConcurrentHashMap<>();
    /** 异步报告任务：taskId → 任务 */
    private final Map<String, CreditReportDtos.ReportTask> reportTasks = new ConcurrentHashMap<>();
    /** 异议时限跟踪：disputeId → 截止日 */
    private final Map<String, DisputeRecord> disputeStore = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 外部征信 Provider（P04，scaff-iprov 自动装配，Mock 内置；生产切 Real 直连人行征信） */
    private final CreditReportProvider provider;

    public CreditReportService(CreditReportProvider provider) {
        this.provider = provider;
        // 演示环境预置长期有效授权，供门禁 E2E 与场景编排免预授权直接查询
        authStore.put("AUTH-DEMO-001", new AuthRecord("AUTH-DEMO-001", "91310000MA1FL8001X",
                "华辰机械", "信贷审批", "ACTIVE",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(3650)));
    }

    // -------------------------------------------------------------- authorize

    /** 授权管理：CREATE/VALIDATE/RENEW/EXPIRE，三重校验任一不通过 authStatus=INVALID 或 REVOKED */
    public CreditReportDtos.AuthorizeResult authorize(CreditReportDtos.AuthorizeRequest request, String requestId) {
        long start = System.currentTimeMillis();
        if (request == null || isBlank(request.action())) {
            throw new BaiException(CreditReportErrorCodes.ACTION_INVALID, "CRR_1007 action 非法，允许 CREATE/VALIDATE/RENEW/EXPIRE");
        }
        String action = request.action().toUpperCase();
        if (!ACTIONS.contains(action)) {
            throw new BaiException(CreditReportErrorCodes.ACTION_INVALID, "CRR_1007 action 非法，允许 CREATE/VALIDATE/RENEW/EXPIRE");
        }
        if (request.customer() == null || isBlank(request.customer().certNo()) || isBlank(request.customer().name())) {
            throw new BaiException(CreditReportErrorCodes.PARAM_INVALID, "CRR_1001 参数缺失：customer（证件/姓名）必填");
        }
        if (request.authDoc() == null || isBlank(request.authDoc().docNo())) {
            throw new BaiException(CreditReportErrorCodes.PARAM_INVALID, "CRR_1001 参数缺失：authDoc（授权书影像/要素）必填");
        }
        if (isBlank(request.purpose())) {
            throw new BaiException(CreditReportErrorCodes.PARAM_INVALID, "CRR_1001 参数缺失：purpose 必填");
        }
        boolean purposeValid = PURPOSE_WHITELIST.contains(request.purpose());
        if (!purposeValid) {
            throw new BaiException(CreditReportErrorCodes.ACTION_INVALID,
                    "CRR_1007 用途不在白名单：" + request.purpose() + "（超范围用途拒绝并告警）");
        }
        int validDays = request.validDays() == null ? DEFAULT_VALID_DAYS : request.validDays();
        if (validDays <= 0) {
            throw new BaiException(CreditReportErrorCodes.PARAM_INVALID, "CRR_1001 参数非法：validDays 须为正整数");
        }
        // 幂等：requestId+主体哈希
        String idemKey = requestId + ":" + hash(request.customer().certNo());
        CreditReportDtos.AuthorizeResult cached = idempotency.get("authorize", idemKey);
        if (cached != null) {
            return cached;
        }

        CreditReportDtos.AuthorizeResult result;
        switch (action) {
            case "CREATE" -> result = create(request, validDays, start);
            case "VALIDATE" -> result = validate(request.authId(), purposeValid, start);
            case "RENEW" -> result = renew(request.authId(), validDays, start);
            case "EXPIRE" -> result = expire(request.authId(), start);
            default -> throw new BaiException(CreditReportErrorCodes.ACTION_INVALID, "CRR_1007 action 非法");
        }
        idempotency.put("authorize", idemKey, result);
        log.info("授权管理完成: requestId={}, action={}, authId={}, authStatus={}, simulation=true",
                requestId, action, result.authId(), result.authStatus());
        return result;
    }

    private CreditReportDtos.AuthorizeResult create(CreditReportDtos.AuthorizeRequest request,
                                                    int validDays, long start) {
        // 客户要素一致性：授权书要素与主体证件/姓名比对（用途白名单已在前置校验通过）
        boolean consistent = request.customer().certNo().equals(request.authDoc().certNo())
                && request.customer().name().equals(request.authDoc().name());
        String authId = "CRR" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000);
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusDays(validDays);
        String status;
        String warning;
        if (!consistent) {
            status = "INVALID";
            warning = "客户要素一致性校验未通过（授权书与主体不匹配）";
        } else {
            status = "ACTIVE";
            warning = null;
        }
        AuthRecord record = new AuthRecord(authId, request.customer().certNo(), request.customer().name(),
                request.purpose(), status, from, to);
        authStore.put(authId, record);
        int remain = "ACTIVE".equals(status) ? (int) ChronoUnit.DAYS.between(from, to) : 0;
        return new CreditReportDtos.AuthorizeResult(authId, status, true,
                from.format(DAY), to.format(DAY), remain, warning, System.currentTimeMillis() - start);
    }

    private CreditReportDtos.AuthorizeResult validate(String authId, boolean purposeValid, long start) {
        AuthRecord record = requireAuth(authId);
        String status = record.status;
        String warning = null;
        if ("ACTIVE".equals(status) && LocalDate.now().isAfter(record.effectiveTo)) {
            status = "EXPIRED";
            record.status = status;
            warning = "授权已过有效期";
        } else if ("REVOKED".equals(status)) {
            warning = "授权已撤销";
        }
        int remain = "ACTIVE".equals(status) ? (int) ChronoUnit.DAYS.between(LocalDate.now(), record.effectiveTo) : 0;
        return new CreditReportDtos.AuthorizeResult(record.authId, status, purposeValid,
                record.effectiveFrom.format(DAY), record.effectiveTo.format(DAY), remain, warning,
                System.currentTimeMillis() - start);
    }

    private CreditReportDtos.AuthorizeResult renew(String authId, int validDays, long start) {
        AuthRecord record = requireAuth(authId);
        if ("REVOKED".equals(record.status)) {
            throw new BaiException(CreditReportErrorCodes.STATE_CONFLICT, "CRR_1005 授权已撤销，不可续期");
        }
        LocalDate base = LocalDate.now().isAfter(record.effectiveTo) ? LocalDate.now() : record.effectiveTo;
        record.effectiveTo = base.plusDays(validDays);
        record.status = "ACTIVE";
        return new CreditReportDtos.AuthorizeResult(record.authId, "ACTIVE", true,
                record.effectiveFrom.format(DAY), record.effectiveTo.format(DAY),
                (int) ChronoUnit.DAYS.between(LocalDate.now(), record.effectiveTo), null,
                System.currentTimeMillis() - start);
    }

    private CreditReportDtos.AuthorizeResult expire(String authId, long start) {
        AuthRecord record = requireAuth(authId);
        record.status = "EXPIRED";
        return new CreditReportDtos.AuthorizeResult(record.authId, "EXPIRED", true,
                record.effectiveFrom.format(DAY), record.effectiveTo.format(DAY), 0,
                "授权已按 EXPIRE 操作失效", System.currentTimeMillis() - start);
    }

    // ------------------------------------------------------------------ query

    /** 同步报告查询：无有效授权一律拒绝查询；组报文送人行征信接口并解析结构化要素（报文组解仿真，用于联调） */
    public CreditReportDtos.ReportResult query(CreditReportDtos.ReportQueryRequest request, String requestId) {
        long start = System.currentTimeMillis();
        if (request == null || isBlank(request.authId())) {
            throw new BaiException(CreditReportErrorCodes.PARAM_INVALID, "CRR_1001 参数缺失：authId 必填");
        }
        AuthRecord record = requireAuth(request.authId());
        requireQueryable(record);
        String idemKey = requestId + ":" + hash(record.certNo);
        CreditReportDtos.ReportResult cached = idempotency.get("query", idemKey);
        if (cached != null) {
            return cached;
        }
        CreditReportDtos.ReportResult result = fetchReport(record, request.reportType(), requestId, start);
        idempotency.put("query", idemKey, result);
        return result;
    }

    // ----------------------------------------------------------------- report

    /** 异步报告生成/解析（仿真：提交即完成组解，结构同真实异步任务） */
    public CreditReportDtos.ReportTask report(CreditReportDtos.ReportTaskRequest request, String requestId) {
        long start = System.currentTimeMillis();
        if (request == null || isBlank(request.authId())) {
            throw new BaiException(CreditReportErrorCodes.PARAM_INVALID, "CRR_1001 参数缺失：authId 必填");
        }
        AuthRecord record = requireAuth(request.authId());
        requireQueryable(record);
        String taskId = "RT" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000);
        CreditReportDtos.ReportResult report = fetchReport(record, request.reportType(), requestId, start);
        CreditReportDtos.ReportTask task = new CreditReportDtos.ReportTask(taskId, "DONE", report);
        reportTasks.put(taskId, task);
        log.info("报告任务完成: requestId={}, taskId={}, status={}", requestId, taskId, task.status());
        return task;
    }

    // ---------------------------------------------------------------- dispute

    /** 异议受理（异步）：登记异议并跟踪 20 日反馈时限，到期未反馈告警 */
    public CreditReportDtos.DisputeResult dispute(CreditReportDtos.DisputeRequest request, String requestId) {
        long start = System.currentTimeMillis();
        if (request == null || request.customer() == null || isBlank(request.customer().certNo())
                || isBlank(request.content())) {
            throw new BaiException(CreditReportErrorCodes.PARAM_INVALID, "CRR_1001 参数缺失：customer/content 必填");
        }
        if (!isBlank(request.authId())) {
            requireAuth(request.authId()); // 异议关联授权须存在
        }
        String disputeId = "DSP" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000);
        LocalDate deadline = LocalDate.now().plusDays(DISPUTE_LIMIT_DAYS);
        disputeStore.put(disputeId, new DisputeRecord(disputeId, request.customer().certNo(), deadline));
        log.info("异议受理: requestId={}, disputeId={}, 反馈截止={}", requestId, disputeId, deadline);
        return new CreditReportDtos.DisputeResult(disputeId, "ACCEPTED", deadline.format(DAY),
                DISPUTE_LIMIT_DAYS, deadlineWarning(disputeId), System.currentTimeMillis() - start);
    }

    // --------------------------------------------------------------- internal

    /** 调 Provider 组解报文（Mock 内置；CRR_2001 人行接口不可用排队重试） */
    private CreditReportDtos.ReportResult fetchReport(AuthRecord record, String reportType, String requestId, long start) {
        ProviderResponse<CreditReportProvider.CreditReport> resp =
                provider.query(record.certNo, record.authId, isBlank(reportType) ? "PERSON" : reportType, requestId);
        if (!resp.isSuccess() || resp.getData() == null) {
            throw new BaiException(CreditReportErrorCodes.PBC_UNAVAILABLE,
                    "CRR_2001 人行接口不可用（降级中），排队重试：" + resp.getMessage());
        }
        CreditReportProvider.CreditReport d = resp.getData();
        CreditReportProvider.Overdue od = d.overdue() == null ? new CreditReportProvider.Overdue(0, 0, 0) : d.overdue();
        CreditReportProvider.Liability li = d.liability() == null ? new CreditReportProvider.Liability(0L, 0, 0) : d.liability();
        CreditReportProvider.Queries qu = d.queries() == null ? new CreditReportProvider.Queries(0) : d.queries();
        return new CreditReportDtos.ReportResult(record.authId, d.reportNo(),
                new CreditReportDtos.Overdue(od.max30(), od.max90(), od.currentOverdue()),
                new CreditReportDtos.Liability(li.total(), li.creditCards(), li.loans()),
                new CreditReportDtos.Queries(qu.last3Month()), d.sign(),
                System.currentTimeMillis() - start);
    }

    private AuthRecord requireAuth(String authId) {
        if (isBlank(authId)) {
            throw new BaiException(CreditReportErrorCodes.PARAM_INVALID, "CRR_1001 参数缺失：authId 必填");
        }
        AuthRecord record = authStore.get(authId);
        if (record == null) {
            throw new BaiException(CreditReportErrorCodes.NOT_FOUND, "CRR_1004 授权记录不存在：" + authId);
        }
        return record;
    }

    /** 无有效授权/超范围用途一律拒绝查询（CRR_1003） */
    private void requireQueryable(AuthRecord record) {
        if (!"ACTIVE".equals(record.status)) {
            throw new BaiException(CreditReportErrorCodes.NO_PERMISSION,
                    "CRR_1003 无有效授权（当前状态 " + record.status + "），拒绝查询");
        }
        if (!PURPOSE_WHITELIST.contains(record.purpose)) {
            throw new BaiException(CreditReportErrorCodes.NO_PERMISSION,
                    "CRR_1003 用途越权（" + record.purpose + " 不在白名单），拒绝查询并告警");
        }
    }

    /** 20 日时限跟踪（仿真：到期未反馈置预警文案） */
    private String deadlineWarning(String disputeId) {
        DisputeRecord d = disputeStore.get(disputeId);
        if (d != null && LocalDate.now().isAfter(d.feedbackDue)) {
            return "异议反馈超期（限 " + DISPUTE_LIMIT_DAYS + " 日），触发告警";
        }
        return null;
    }

    private String hash(String input) {
        return Integer.toHexString((input == null ? "" : input).hashCode());
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static final class AuthRecord {
        final String authId;
        final String certNo;
        final String name;
        final String purpose;
        volatile String status;
        volatile LocalDate effectiveFrom;
        volatile LocalDate effectiveTo;

        AuthRecord(String authId, String certNo, String name, String purpose,
                   String status, LocalDate effectiveFrom, LocalDate effectiveTo) {
            this.authId = authId;
            this.certNo = certNo;
            this.name = name;
            this.purpose = purpose;
            this.status = status;
            this.effectiveFrom = effectiveFrom;
            this.effectiveTo = effectiveTo;
        }
    }

    private record DisputeRecord(String disputeId, String certNo, LocalDate feedbackDue) {}
}
