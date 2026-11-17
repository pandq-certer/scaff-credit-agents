package com.bai.validate;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据校验服务（BIA-DTA-021）。模板与规则库为确定性仿真（标注：仿真，用于联调），
 * 覆盖必填/格式/范围/枚举/一致性/完整性/阈值/跨系统八类算子；
 * 真实跨系统比对（核心/征信/票交所）通道未接入，context.target 非空时统一置 crossSystemSkipped=true 并产生一条 WARN。
 * <p>处理链：校验入参（templateId 与 rules 二选一、data 必填且序列化≤512KB）→ 规则装载（模板展开/自定义规则）
 * → 逐规则执行（任一 ERROR→FAIL，仅 WARN→WARN，否则 PASS）→ 汇总与明细输出
 * <p>幂等：requestId+数据 SHA-256 摘要，缓存 24h；限流 validate 100 QPS / batch 20 QPS
 */
@Service
public class DataValidateService {

    private static final Logger log = LoggerFactory.getLogger(DataValidateService.class);

    private static final int MAX_BATCH = 5000;
    private static final int MAX_DATA_BYTES = 512 * 1024;
    private static final String RULE_VERSION = "2026.09.01";

    /** 规则库（确定性仿真）：ruleId → 可执行规则（含算子/字段/级别/阈值参数） */
    private static final Map<String, ExecRule> RULES = buildRules();

    /** 模板库：templateId → 规则集展开 */
    private static final Map<String, DataValidateDtos.TemplateDef> TEMPLATES = buildTemplates();

    private final ObjectMapper mapper;
    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final AtomicLong validateSeq = new AtomicLong();
    private final AtomicLong batchSeq = new AtomicLong();

    public DataValidateService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 单次校验（幂等：requestId+数据 SHA-256 相同返回首次结果，缓存 24h） */
    public DataValidateDtos.ValidateResult validate(DataValidateDtos.ValidateRequest req, String requestId) {
        long start = System.currentTimeMillis();
        if (req == null || req.data() == null || req.data().isEmpty()) {
            throw new BaiException(DataValidateErrorCodes.PARAM_INVALID, "DVA_1001 参数缺失：data 必填");
        }
        LoadedRules loaded = loadRules(req);
        byte[] json = serialize(req.data());
        if (json.length > MAX_DATA_BYTES) {
            throw new BaiException(DataValidateErrorCodes.PARAM_INVALID,
                    "DVA_1001 参数非法：data 序列化超 512KB（实际 " + json.length + " 字节）");
        }
        String key = requestId + ":" + sha256(json);
        DataValidateDtos.ValidateResult cached = idempotency.get("validate", key);
        if (cached != null) {
            return cached;
        }
        DataValidateDtos.ValidateResult result = execute(loaded.templateId(), loaded.rules(), req.data(), req.context(),
                requestId, start);
        idempotency.put("validate", key, result);
        return result;
    }

    /** 批量校验（≤5000 条，逐条校验/逐条幂等，fail-fast） */
    public DataValidateDtos.BatchResult batch(DataValidateDtos.BatchRequest req, String requestId) {
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BaiException(DataValidateErrorCodes.PARAM_INVALID, "DVA_1001 参数缺失：batch.items 必填");
        }
        if (req.items().size() > MAX_BATCH) {
            throw new BaiException(DataValidateErrorCodes.OVER_LIMIT,
                    "DVA_1005 批量超 " + MAX_BATCH + " 条（实际 " + req.items().size() + "），请分批");
        }
        for (int i = 0; i < req.items().size(); i++) {
            DataValidateDtos.ValidateRequest item = req.items().get(i);
            if (item == null || item.data() == null || item.data().isEmpty()) {
                throw new BaiException(DataValidateErrorCodes.PARAM_INVALID, "DVA_1001 参数缺失：items[" + i + "].data 必填");
            }
            LoadedRules loaded = loadRules(item);
            byte[] json = serialize(item.data());
            DataValidateDtos.ValidateResult r = execute(loaded.templateId(), loaded.rules(), item.data(), item.context(),
                    requestId, System.currentTimeMillis());
            idempotency.put("validate", requestId + ":" + i + ":" + sha256(json), r);
        }
        String batchId = "DVB" + dayStamp() + String.format("%07d", batchSeq.incrementAndGet());
        log.info("数据校验批量受理: requestId={}, batchId={}, total={}", requestId, batchId, req.items().size());
        return new DataValidateDtos.BatchResult(batchId, "ACCEPTED", req.items().size());
    }

    /** 规则库（GET /rules） */
    public DataValidateDtos.RulesResult rules() {
        List<DataValidateDtos.RuleDef> defs = RULES.values().stream()
                .map(r -> new DataValidateDtos.RuleDef(r.ruleId(), r.ruleName(), r.operator(), r.field(),
                        r.level(), r.message(), r.param()))
                .toList();
        return new DataValidateDtos.RulesResult(RULE_VERSION, defs);
    }

    /** 模板库（GET /templates） */
    public DataValidateDtos.TemplatesResult templates() {
        return new DataValidateDtos.TemplatesResult(RULE_VERSION, List.copyOf(TEMPLATES.values()));
    }

    /** 规则装载：templateId 优先，其次自定义 rules（未知 ID → DVA_1004，两者都缺 → DVA_1001） */
    private LoadedRules loadRules(DataValidateDtos.ValidateRequest req) {
        if (req.templateId() != null && !req.templateId().isBlank()) {
            DataValidateDtos.TemplateDef t = TEMPLATES.get(req.templateId());
            if (t == null) {
                throw new BaiException(DataValidateErrorCodes.NOT_FOUND, "DVA_1004 templateId 不存在：" + req.templateId());
            }
            List<ExecRule> rules = t.ruleIds().stream().map(RULES::get).toList();
            return new LoadedRules(t.templateId(), rules);
        }
        if (req.rules() != null && !req.rules().isEmpty()) {
            List<ExecRule> rules = new ArrayList<>();
            for (String rid : req.rules()) {
                ExecRule r = RULES.get(rid);
                if (r == null) {
                    throw new BaiException(DataValidateErrorCodes.NOT_FOUND, "DVA_1004 ruleId 不存在：" + rid);
                }
                rules.add(r);
            }
            return new LoadedRules("CUSTOM", rules);
        }
        throw new BaiException(DataValidateErrorCodes.PARAM_INVALID, "DVA_1001 参数缺失：templateId 与 rules 二选一必填");
    }

    /** 逐规则执行 + 汇总（跨系统算子统一由 target 通道仿真） */
    private DataValidateDtos.ValidateResult execute(String templateId, List<ExecRule> rules,
                                                    Map<String, Object> data, DataValidateDtos.Context ctx,
                                                    String requestId, long start) {
        boolean hasTarget = ctx != null && ctx.target() != null && !ctx.target().isBlank();
        List<DataValidateDtos.Detail> details = new ArrayList<>();
        int passed = 0;
        int warned = 0;
        int failed = 0;
        ExecRule crossSystemRule = null;
        for (ExecRule rule : rules) {
            if ("CROSS_SYSTEM".equals(rule.operator())) {
                crossSystemRule = rule; // 跨系统比对仿真：真实比对通道未接入，target 非空时统一置跳过标记并产生一条 WARN
                continue;
            }
            DataValidateDtos.Detail d = evaluate(rule, data);
            if (d == null) {
                passed++;
            } else {
                details.add(d);
                if ("ERROR".equals(d.level())) {
                    failed++;
                } else {
                    warned++;
                }
            }
        }
        boolean crossSystemSkipped = false;
        if (hasTarget) {
            crossSystemSkipped = true;
            ExecRule xsys = crossSystemRule != null ? crossSystemRule
                    : RULES.get("R_XSYS_001");
            details.add(new DataValidateDtos.Detail(xsys.ruleId(), xsys.ruleName(), null, "WARN",
                    "跨系统比对已跳过（仿真，用于联调；target=" + ctx.target() + "）", null));
            warned++;
        }
        String result = failed > 0 ? "FAIL" : (warned > 0 ? "WARN" : "PASS");
        String validateId = "DVA" + dayStamp() + String.format("%07d", validateSeq.incrementAndGet());
        long elapsed = System.currentTimeMillis() - start;
        log.info("数据校验: requestId={}, validateId={}, templateId={}, result={}, passed/warned/failed={}/{}/{}",
                requestId, validateId, templateId, result, passed, warned, failed);
        return new DataValidateDtos.ValidateResult(validateId, templateId, result,
                new DataValidateDtos.Summary(passed, warned, failed), details, crossSystemSkipped, elapsed);
    }

    /** 八类算子求值：返回 null 表示通过 */
    private DataValidateDtos.Detail evaluate(ExecRule rule, Map<String, Object> data) {
        Object raw = rule.field() == null ? null : data.get(rule.field());
        boolean failedCheck = switch (rule.operator()) {
            case "REQUIRED" -> raw == null || String.valueOf(raw).isBlank();
            case "FORMAT" -> raw == null || !String.valueOf(raw).matches((String) rule.param());
            case "RANGE" -> !inRange(raw, (double[]) rule.param()); // 缺值不判范围（必填由 REQUIRED 覆盖）
            case "ENUM" -> raw == null || !toStringList(rule.param()).contains(String.valueOf(raw));
            case "CONSISTENCY" -> !consistent((String) rule.param(), data);
            case "COMPLETENESS" -> toStringList(rule.param()).stream()
                    .allMatch(f -> data.get(f) == null || String.valueOf(data.get(f)).isBlank());
            case "THRESHOLD" -> overThreshold(raw, ((Number) rule.param()).doubleValue()); // 缺值不判阈值
            default -> false;
        };
        return failedCheck
                ? new DataValidateDtos.Detail(rule.ruleId(), rule.ruleName(), rule.field(), rule.level(), rule.message(), raw)
                : null;
    }

    private boolean inRange(Object raw, double[] range) {
        if (raw instanceof Number n) {
            double v = n.doubleValue();
            return v >= range[0] && v <= range[1];
        }
        if (raw == null) {
            return true;
        }
        try {
            double v = Double.parseDouble(String.valueOf(raw));
            return v >= range[0] && v <= range[1];
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean overThreshold(Object raw, double threshold) {
        if (raw == null) {
            return false;
        }
        try {
            return Double.parseDouble(String.valueOf(raw)) > threshold;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 一致性表达式：形如 "endDate>=startDate"/"repayAmount<=loanAmount"，数值优先、字符串兜底（ISO 日期可比较） */
    private boolean consistent(String expr, Map<String, Object> data) {
        for (String op : List.of(">=", "<=", "==", "!=")) {
            int i = expr.indexOf(op);
            if (i > 0) {
                String leftField = expr.substring(0, i).trim();
                String rightField = expr.substring(i + op.length()).trim();
                Object lv = data.get(leftField);
                Object rv = data.get(rightField);
                if (lv == null || rv == null) {
                    return true; // 缺值不判一致性（必填由 REQUIRED 覆盖）
                }
                int cmp;
                try {
                    cmp = Double.compare(Double.parseDouble(String.valueOf(lv)), Double.parseDouble(String.valueOf(rv)));
                } catch (NumberFormatException e) {
                    cmp = String.valueOf(lv).compareTo(String.valueOf(rv));
                }
                return switch (op) {
                    case ">=" -> cmp >= 0;
                    case "<=" -> cmp <= 0;
                    case "==" -> cmp == 0;
                    default -> cmp != 0;
                };
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object param) {
        return param instanceof List<?> l ? (List<String>) l : List.of();
    }

    private byte[] serialize(Map<String, Object> data) {
        try {
            return mapper.writeValueAsBytes(data);
        } catch (Exception e) {
            throw new BaiException(DataValidateErrorCodes.PARAM_INVALID, "DVA_1001 参数非法：data 不可序列化为 JSON");
        }
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(bytes)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String dayStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    private record LoadedRules(String templateId, List<ExecRule> rules) {}

    /** 可执行规则（八类算子：REQUIRED/FORMAT/RANGE/ENUM/CONSISTENCY/COMPLETENESS/THRESHOLD/CROSS_SYSTEM） */
    private record ExecRule(String ruleId, String ruleName, String operator, String field, String level,
                            String message, Object param) {}

    // ---------- 规则库与模板库（确定性仿真，用于联调） ----------

    private static Map<String, ExecRule> buildRules() {
        Map<String, ExecRule> r = new LinkedHashMap<>();
        // 必填
        r.put("R_REQ_CUST_NAME", new ExecRule("R_REQ_CUST_NAME", "必填-客户名称", "REQUIRED", "name", "ERROR", "客户名称必填", null));
        r.put("R_REQ_ID_CARD", new ExecRule("R_REQ_ID_CARD", "必填-证件号码", "REQUIRED", "idCard", "ERROR", "证件号码必填", null));
        r.put("R_REQ_LOAN_NO", new ExecRule("R_REQ_LOAN_NO", "必填-借据号", "REQUIRED", "loanNo", "ERROR", "借据号必填", null));
        r.put("R_REQ_ORG_ID", new ExecRule("R_REQ_ORG_ID", "必填-机构号", "REQUIRED", "orgId", "ERROR", "机构号必填", null));
        r.put("R_REQ_REASON", new ExecRule("R_REQ_REASON", "必填-回退原因", "REQUIRED", "reason", "ERROR", "回退原因必填", null));
        r.put("R_REQ_OPERATOR", new ExecRule("R_REQ_OPERATOR", "必填-操作员", "REQUIRED", "operator", "ERROR", "操作员必填", null));
        r.put("R_REQ_DOC_TYPE", new ExecRule("R_REQ_DOC_TYPE", "必填-文档类型", "REQUIRED", "docType", "ERROR", "文档类型必填", null));
        r.put("R_REQ_FILE_KEY", new ExecRule("R_REQ_FILE_KEY", "必填-对象存储 fileKey", "REQUIRED", "fileKey", "ERROR", "对象存储 fileKey 必填", null));
        // 格式
        r.put("R_FMT_ID_CARD", new ExecRule("R_FMT_ID_CARD", "格式-身份证号", "FORMAT", "idCard", "ERROR", "身份证号格式不符（18 位）", "\\d{17}[\\dXx]"));
        r.put("R_FMT_PHONE", new ExecRule("R_FMT_PHONE", "格式-手机号", "FORMAT", "phone", "ERROR", "手机号格式不符（1 开头 11 位）", "1\\d{10}"));
        r.put("R_FMT_DATE", new ExecRule("R_FMT_DATE", "格式-申请日期", "FORMAT", "applyDate", "ERROR", "申请日期格式须为 yyyy-MM-dd", "\\d{4}-\\d{2}-\\d{2}"));
        r.put("R_FMT_ACCOUNT", new ExecRule("R_FMT_ACCOUNT", "格式-账号", "FORMAT", "accountNo", "ERROR", "账号格式不符（16-24 位数字）", "\\d{16,24}"));
        // 范围
        r.put("R_RNG_TERM", new ExecRule("R_RNG_TERM", "范围-期限（月）", "RANGE", "term", "ERROR", "期限须在 1-240 月之间", new double[]{1, 240}));
        r.put("R_RNG_AMOUNT", new ExecRule("R_RNG_AMOUNT", "范围-申请金额", "RANGE", "applyAmount", "ERROR", "申请金额须在 1000-5000 万之间", new double[]{1000, 50000000}));
        r.put("R_RNG_RATE", new ExecRule("R_RNG_RATE", "范围-执行利率", "RANGE", "rate", "ERROR", "执行利率须在 0-0.24 之间", new double[]{0, 0.24}));
        // 枚举
        r.put("R_ENUM_STATUS", new ExecRule("R_ENUM_STATUS", "枚举-借据状态", "ENUM", "status", "ERROR", "借据状态须为 NORMAL/OVERDUE/SETTLED",
                List.of("NORMAL", "OVERDUE", "SETTLED")));
        r.put("R_ENUM_DOC_TYPE", new ExecRule("R_ENUM_DOC_TYPE", "枚举-文档类型", "ENUM", "docType", "ERROR", "文档类型须为 ID_CARD/INCOME_PROOF/CREDIT_REPORT/CONTRACT",
                List.of("ID_CARD", "INCOME_PROOF", "CREDIT_REPORT", "CONTRACT")));
        // 一致性
        r.put("R_CON_DATE", new ExecRule("R_CON_DATE", "一致性-到期日不早于起始日", "CONSISTENCY", "endDate", "ERROR", "到期日不得早于起始日", "endDate>=startDate"));
        r.put("R_CON_AMOUNT", new ExecRule("R_CON_AMOUNT", "一致性-还款不超本金", "CONSISTENCY", "repayAmount", "ERROR", "还款金额不得超贷款金额", "repayAmount<=loanAmount"));
        // 完整性
        r.put("R_CMP_CONTACT", new ExecRule("R_CMP_CONTACT", "完整性-联系方式至少一项", "COMPLETENESS", "phone", "ERROR", "phone/email 至少填一项", List.of("phone", "email")));
        // 阈值
        r.put("R_THR_LTV", new ExecRule("R_THR_LTV", "阈值-LTV 抵押率", "THRESHOLD", "ltv", "WARN", "LTV 超 0.70，建议追加担保", 0.70));
        r.put("R_THR_AMOUNT", new ExecRule("R_THR_AMOUNT", "阈值-单户金额", "THRESHOLD", "applyAmount", "WARN", "单户申请金额超 100 万提醒", 1000000));
        // 跨系统（仿真：真实比对通道未接入，target 非空时统一置跳过标记并产生一条 WARN）
        r.put("R_XSYS_001", new ExecRule("R_XSYS_001", "跨系统-核心/征信比对", "CROSS_SYSTEM", null, "WARN", "跨系统比对（核心/征信）通道", null));
        return Map.copyOf(r);
    }

    private static Map<String, DataValidateDtos.TemplateDef> buildTemplates() {
        Map<String, DataValidateDtos.TemplateDef> t = new LinkedHashMap<>();
        t.put("T_SUBMIT", new DataValidateDtos.TemplateDef("T_SUBMIT", "进件数据校验模板", RULE_VERSION, "ACTIVE",
                List.of("R_REQ_CUST_NAME", "R_REQ_ID_CARD", "R_FMT_ID_CARD", "R_FMT_PHONE", "R_FMT_DATE",
                        "R_RNG_AMOUNT", "R_RNG_TERM", "R_CON_DATE", "R_THR_LTV", "R_XSYS_001")));
        t.put("T_LOAN", new DataValidateDtos.TemplateDef("T_LOAN", "借据数据校验模板", RULE_VERSION, "ACTIVE",
                List.of("R_REQ_LOAN_NO", "R_FMT_ACCOUNT", "R_ENUM_STATUS", "R_RNG_RATE", "R_CON_AMOUNT", "R_THR_AMOUNT")));
        t.put("T_RECON", new DataValidateDtos.TemplateDef("T_RECON", "对账数据校验模板", RULE_VERSION, "ACTIVE",
                List.of("R_REQ_ORG_ID", "R_FMT_DATE", "R_CON_AMOUNT")));
        t.put("T_ROLLBACK", new DataValidateDtos.TemplateDef("T_ROLLBACK", "回退数据校验模板", RULE_VERSION, "ACTIVE",
                List.of("R_REQ_REASON", "R_REQ_OPERATOR", "R_FMT_DATE")));
        t.put("T_DOC", new DataValidateDtos.TemplateDef("T_DOC", "影像文档校验模板", RULE_VERSION, "ACTIVE",
                List.of("R_REQ_DOC_TYPE", "R_ENUM_DOC_TYPE", "R_REQ_FILE_KEY")));
        return Map.copyOf(t);
    }
}
