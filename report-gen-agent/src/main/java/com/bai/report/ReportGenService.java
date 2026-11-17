package com.bai.report;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 报表生成服务（BIA-DTA-023）。数据绑定与渲染为确定性仿真（标注：仿真，用于联调：
 * 调不到真实 data-query/metric，直接按指标基线+参数哈希生成绑定数据，fileId 为对象存储仿真键）。
 * <p>处理链：模板解析（不存在/未发布 RPG_1004）→ 口径解析（关联指标清单）
 * → 数据绑定（仿真）→ 渲染（fileId=OBJ+日期+序号）→ 三校验 checks{caliber,required,balance}
 * （勾稽不平可用 flag=UNBALANCE 仿真触发 RPG_1006）→ 输出 reportId/submissionId(null)。
 * <p>报送登记（/submissions）为内存模拟，重报同 reportId 不新增；
 * 幂等：requestId+templateId+params，缓存 24h；限流 generate 30 QPS / batch 10 QPS / submissions 20 QPS
 */
@Service
public class ReportGenService {

    private static final Logger log = LoggerFactory.getLogger(ReportGenService.class);

    private static final int MAX_BATCH = 200;
    private static final String TEMPLATE_VERSION = "2026-09-01";
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Set<String> FORMATS = Set.of("EXCEL", "PDF", "XML");

    /** 模板库（T_1104_G03_DRAFT 为未发布模板，用于 RPG_1004 仿真触发） */
    private static final Map<String, DataModel.Template> TEMPLATES = buildTemplates();

    /** 绑定数据基线（仿真，用于联调）：metricId → [base, span] */
    private static final Map<String, double[]> METRIC_BASE = Map.of(
            "LOAN_BALANCE", new double[]{125.60, 60.00},
            "DEPOSIT_BALANCE", new double[]{310.20, 150.00},
            "NPL_BALANCE", new double[]{45200.00, 20000.00},
            "BAD_LOAN_RATE", new double[]{2.35, 1.20},
            "APPROVAL_RATE", new double[]{86.50, 8.00},
            "OVERDUE_RATE", new double[]{1.82, 1.00});

    private final IdempotencyStore idempotency = new IdempotencyStore();
    /** 已生成报表：reportId → templateId（内存模拟） */
    private final Map<String, String> reportStore = new LinkedHashMap<>();
    /** 已登记报送：reportId → submissionId（重报不新增） */
    private final Map<String, DataModel.Submission> submissionStore = new LinkedHashMap<>();
    private final AtomicLong reportSeq = new AtomicLong();
    private final AtomicLong fileSeq = new AtomicLong();
    private final AtomicLong submissionSeq = new AtomicLong();
    private final AtomicLong batchSeq = new AtomicLong();

    /** 生成报表（幂等：requestId+templateId+params 相同返回首次结果，缓存 24h） */
    public ReportGenDtos.GenerateResult generate(ReportGenDtos.GenerateRequest req, String requestId) {
        DataModel.Template template = validate(req);
        String format = resolveFormat(req.format());
        String key = requestId + ":" + req.templateId() + ":" + req.params();
        ReportGenDtos.GenerateResult cached = idempotency.get("generate", key);
        if (cached != null) {
            return cached;
        }
        ReportGenDtos.GenerateResult result = render(template, req.params(), format, requestId);
        idempotency.put("generate", key, result);
        return result;
    }

    /** 批量生成（≤200 张，fail-fast） */
    public ReportGenDtos.BatchResult batch(ReportGenDtos.BatchRequest req, String requestId) {
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BaiException(ReportGenErrorCodes.PARAM_INVALID, "RPG_1001 参数缺失：batch.items 必填");
        }
        if (req.items().size() > MAX_BATCH) {
            throw new BaiException(ReportGenErrorCodes.OVER_LIMIT,
                    "RPG_1005 批量超 " + MAX_BATCH + " 张（实际 " + req.items().size() + "），请分批");
        }
        for (ReportGenDtos.GenerateRequest item : req.items()) {
            DataModel.Template template = validate(item);
            String format = resolveFormat(item.format());
            String key = requestId + ":" + item.templateId() + ":" + item.params();
            ReportGenDtos.GenerateResult r = idempotency.get("generate", key);
            if (r == null) {
                r = render(template, item.params(), format, requestId);
                idempotency.put("generate", key, r);
            }
        }
        String batchId = "RPB" + dayStamp() + String.format("%07d", batchSeq.incrementAndGet());
        log.info("报表批量生成受理: requestId={}, batchId={}, total={}", requestId, batchId, req.items().size());
        return new ReportGenDtos.BatchResult(batchId, "ACCEPTED", req.items().size());
    }

    /** 报送登记（内存模拟；重报同 reportId 不新增，返回原 submissionId） */
    public synchronized ReportGenDtos.SubmissionResult submissions(ReportGenDtos.SubmissionRequest req, String requestId) {
        long start = System.currentTimeMillis();
        if (req == null || req.reportId() == null || req.reportId().isBlank()
                || req.channel() == null || req.channel().isBlank()) {
            throw new BaiException(ReportGenErrorCodes.PARAM_INVALID, "RPG_1001 参数缺失：reportId/channel 必填");
        }
        if (!reportStore.containsKey(req.reportId())) {
            throw new BaiException(ReportGenErrorCodes.TEMPLATE_NOT_FOUND, "RPG_1004 reportId 不存在：" + req.reportId());
        }
        DataModel.Submission existing = submissionStore.get(req.reportId());
        if (existing != null) {
            log.info("报送重复登记（返回原 submissionId）: requestId={}, reportId={}, submissionId={}",
                    requestId, req.reportId(), existing.submissionId());
            return new ReportGenDtos.SubmissionResult(existing.submissionId(), req.reportId(), existing.channel(),
                    existing.status(), System.currentTimeMillis() - start);
        }
        String submissionId = "SUB" + String.format("%07d", submissionSeq.incrementAndGet());
        submissionStore.put(req.reportId(), new DataModel.Submission(submissionId, req.channel(), "SUBMITTED"));
        log.info("报送登记: requestId={}, submissionId={}, reportId={}, channel={}",
                requestId, submissionId, req.reportId(), req.channel());
        return new ReportGenDtos.SubmissionResult(submissionId, req.reportId(), req.channel(), "SUBMITTED",
                System.currentTimeMillis() - start);
    }

    /** 模板库（GET /templates） */
    public ReportGenDtos.TemplatesResult templates() {
        List<ReportGenDtos.TemplateInfo> infos = TEMPLATES.values().stream()
                .map(t -> new ReportGenDtos.TemplateInfo(t.templateId(), t.templateName(), t.version(), t.status(),
                        t.period(), t.metrics()))
                .toList();
        return new ReportGenDtos.TemplatesResult(TEMPLATE_VERSION, infos);
    }

    /** 入参校验：模板存在且已发布、params 三要素、format 枚举 */
    private DataModel.Template validate(ReportGenDtos.GenerateRequest req) {
        if (req == null || req.templateId() == null || req.templateId().isBlank()) {
            throw new BaiException(ReportGenErrorCodes.PARAM_INVALID, "RPG_1001 参数缺失：templateId 必填");
        }
        if (req.params() == null || req.params().isEmpty()) {
            throw new BaiException(ReportGenErrorCodes.PARAM_INVALID, "RPG_1001 参数缺失：params 必填（orgId/date/granularity）");
        }
        DataModel.Template template = TEMPLATES.get(req.templateId());
        if (template == null) {
            throw new BaiException(ReportGenErrorCodes.TEMPLATE_NOT_FOUND, "RPG_1004 模板不存在：" + req.templateId());
        }
        if (!"ACTIVE".equals(template.status())) {
            throw new BaiException(ReportGenErrorCodes.TEMPLATE_NOT_FOUND,
                    "RPG_1004 模板未发布：" + req.templateId() + "（当前状态 " + template.status() + "）");
        }
        for (String field : List.of("orgId", "date", "granularity")) {
            Object v = req.params().get(field);
            if (v == null || String.valueOf(v).isBlank()) {
                throw new BaiException(ReportGenErrorCodes.PARAM_INVALID, "RPG_1001 参数缺失：params." + field + " 必填");
            }
        }
        if (!DATE.matcher(String.valueOf(req.params().get("date"))).matches()) {
            throw new BaiException(ReportGenErrorCodes.PARAM_INVALID, "RPG_1001 参数非法：params.date 格式须为 yyyy-MM-dd");
        }
        return template;
    }

    private String resolveFormat(String format) {
        if (format == null || format.isBlank()) {
            return "EXCEL";
        }
        String upper = format.toUpperCase();
        if (!FORMATS.contains(upper)) {
            throw new BaiException(ReportGenErrorCodes.PARAM_INVALID, "RPG_1001 参数非法：format 须为 EXCEL/PDF/XML");
        }
        return upper;
    }

    /** 口径解析 → 数据绑定（仿真）→ 渲染（仿真 fileId）→ 三校验 */
    private ReportGenDtos.GenerateResult render(DataModel.Template template, Map<String, Object> params,
                                                String format, String requestId) {
        long start = System.currentTimeMillis();
        // 口径解析（关联指标清单，确定性）
        if (template.metrics().isEmpty()) {
            throw new BaiException(ReportGenErrorCodes.INTERNAL_ERROR, "RPG_2000 口径解析失败：模板未关联指标清单 " + template.templateId());
        }
        // 数据绑定（仿真，用于联调：按指标基线+参数哈希生成绑定数据）
        String orgId = String.valueOf(params.get("orgId"));
        String date = String.valueOf(params.get("date"));
        Map<String, Object> bindings = new LinkedHashMap<>();
        for (String metricId : template.metrics()) {
            double[] base = METRIC_BASE.getOrDefault(metricId, new double[]{100.00, 50.00});
            int h = (metricId + "|" + orgId + "|" + date).hashCode() & 0x7fffffff;
            bindings.put(metricId, Math.round((base[0] + (h % 10000) / 10000.0 * base[1]) * 100.0) / 100.0);
        }
        // 渲染（仿真：fileId=OBJ+日期+序号）
        String fileId = "OBJ" + dayStamp() + String.format("%07d", fileSeq.incrementAndGet());
        // 三校验：caliber（口径解析成功即 PASS）/required（必填单元格全部可绑定，PASS）/balance（勾稽）
        String balance = "UNBALANCE".equals(String.valueOf(params.get("flag"))) ? "FAIL" : "PASS";
        if ("FAIL".equals(balance)) {
            throw new BaiException(ReportGenErrorCodes.RENDER_FAILED,
                    "RPG_1006 渲染校验失败：勾稽不平（flag=UNBALANCE，仿真触发），请修正数据");
        }
        ReportGenDtos.Checks checks = new ReportGenDtos.Checks("PASS", "PASS", balance);
        String reportId = "RPT" + dayStamp() + String.format("%07d", reportSeq.incrementAndGet());
        synchronized (this) {
            reportStore.put(reportId, template.templateId());
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("报表生成: requestId={}, reportId={}, templateId={}, format={}, fileId={}, bindings 指标数={}(仿真，用于联调)",
                requestId, reportId, template.templateId(), format, fileId, bindings.size());
        return new ReportGenDtos.GenerateResult(reportId, template.templateId(), TEMPLATE_VERSION, params, format,
                fileId, checks, null, elapsed);
    }

    private String dayStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    /** 内部数据模型（避免与 DTO 混用） */
    private static final class DataModel {

        private record Template(String templateId, String templateName, String version, String status,
                                String period, List<String> metrics) {}

        private record Submission(String submissionId, String channel, String status) {}
    }

    private static Map<String, DataModel.Template> buildTemplates() {
        Map<String, DataModel.Template> t = new LinkedHashMap<>();
        t.put("T_1104_G01", new DataModel.Template("T_1104_G01", "G01 资产负债表", TEMPLATE_VERSION, "ACTIVE",
                "MONTH", List.of("LOAN_BALANCE", "DEPOSIT_BALANCE", "NPL_BALANCE")));
        t.put("T_1104_G21", new DataModel.Template("T_1104_G21", "G21 流动性期限缺口统计表", TEMPLATE_VERSION, "ACTIVE",
                "MONTH", List.of("LOAN_BALANCE", "DEPOSIT_BALANCE")));
        t.put("T_MGMT_MONTHLY", new DataModel.Template("T_MGMT_MONTHLY", "经营管理月报", TEMPLATE_VERSION, "ACTIVE",
                "MONTH", List.of("BAD_LOAN_RATE", "APPROVAL_RATE", "LOAN_BALANCE", "OVERDUE_RATE")));
        t.put("T_LOAN_AFTER_CHECK", new DataModel.Template("T_LOAN_AFTER_CHECK", "贷后检查报告", TEMPLATE_VERSION, "ACTIVE",
                "MONTH", List.of("LOAN_BALANCE", "OVERDUE_RATE", "BAD_LOAN_RATE")));
        t.put("T_POST_EVALUATION", new DataModel.Template("T_POST_EVALUATION", "后评价报告", TEMPLATE_VERSION, "ACTIVE",
                "MONTH", List.of("APPROVAL_RATE", "LOAN_BALANCE", "OVERDUE_RATE")));
        t.put("T_MODEL_MONITOR", new DataModel.Template("T_MODEL_MONITOR", "模型监控报告", TEMPLATE_VERSION, "ACTIVE",
                "MONTH", List.of("APPROVAL_RATE", "OVERDUE_RATE", "BAD_LOAN_RATE")));
        t.put("T_ANNUAL_CHECK", new DataModel.Template("T_ANNUAL_CHECK", "年检报告", TEMPLATE_VERSION, "ACTIVE",
                "MONTH", List.of("LOAN_BALANCE", "DEPOSIT_BALANCE", "NPL_BALANCE", "BAD_LOAN_RATE")));
        t.put("T_1104_G03_DRAFT", new DataModel.Template("T_1104_G03_DRAFT", "G03 资产负债项目统计表（草稿）",
                TEMPLATE_VERSION, "DRAFT", "MONTH", List.of("LOAN_BALANCE")));
        return Map.copyOf(t);
    }
}
