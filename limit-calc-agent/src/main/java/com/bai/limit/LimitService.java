package com.bai.limit;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 额度计算服务（提示词库 A-56）。
 * <p>处理链：校验入参 → 按口径核定 limitAmount（SINGLE/INDUSTRY 取 min(incomeBased, collateralBased)×ratingFactor；
 * GROUP 按收入 0.6/押品 0.4 加权×ratingFactor）→ usedAmount = limitAmount - availableLimit（下限 0）→
 * remainAmount = limitAmount - usedAmount（恒等约束）→ 校验 usedAmount + requestedAmount 是否超 limitAmount，
 * 输出 controlStatus（PASS/OVER_LIMIT/REJECT）与 overAmount → 超限额阻断并落审计。
 * <p>幂等：requestId + 客户 + 金额哈希为键；同 requestId 不同要素视为限额冲突（LMT_1005）。
 * <p>限额管理（4.3）：action CREATE/UPDATE/GRAY/DISABLE，内存 Map 存储（lmt_rule 仿真），幂等以 requestId+限额要素哈希为键；
 * 试算查询（4.4）读 calc 留痕记录；占用查询（4.5）按限额仿真占用比例与明细（确定性：同入同出）。
 */
@Service
public class LimitService {

    private static final Logger log = LoggerFactory.getLogger(LimitService.class);

    private static final Set<String> LIMIT_TYPES = Set.of("SINGLE", "GROUP", "INDUSTRY");
    private static final Set<String> LIMIT_ACTIONS = Set.of("CREATE", "UPDATE", "GRAY", "DISABLE");
    private static final Set<String> LIMIT_SOURCES = Set.of("监管", "行内");
    private static final Set<String> CONTROL_STATUSES = Set.of("PASS", "OVER_LIMIT", "REJECT");
    /** 试算查询分页：默认 page=1/size=20，size 上限 100 */
    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    /** GROUP（集团口径）加权系数：收入测算 0.6 / 押品覆盖 0.4 */
    private static final BigDecimal GROUP_INCOME_WEIGHT = new BigDecimal("0.6");
    private static final BigDecimal GROUP_COLLATERAL_WEIGHT = new BigDecimal("0.4");
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final IdempotencyStore idempotency = new IdempotencyStore();
    /** 限额规则（lmt_rule 仿真）：复合键 limitType|scope */
    private final Map<String, LimitRule> limitStore = new ConcurrentHashMap<>();
    /** 试算留痕（lmt_calc 仿真）：calcId → 记录 */
    private final Map<String, CalcRecord> calcHistory = new ConcurrentHashMap<>();
    private final AtomicLong limitSeq = new AtomicLong();
    private final AtomicLong calcSeq = new AtomicLong();

    /** 限额规则记录（lmt_rule 行） */
    private record LimitRule(String limitId, String limitType, String scope, BigDecimal amount,
                             String source, String effectiveFrom, String effectiveTo, int version, String status) {}

    /** 试算留痕记录（lmt_calc 行） */
    private record CalcRecord(String calcId, String requestId, String customerId, String limitType,
                              BigDecimal availableLimit, BigDecimal limitAmount, BigDecimal usedAmount,
                              BigDecimal remainAmount, BigDecimal requestedAmount, String controlStatus,
                              String createdAt) {}

    /** 额度测算（幂等：同 requestId+客户+金额哈希返回首次结果，缓存 24h） */
    public LimitDtos.LimitCalcResult calc(LimitDtos.LimitCalcRequest req, String requestId) {
        validate(req, requestId);

        String hash = sha256(String.join("|",
                req.customerId(), req.limitType(), money(req.incomeBased()), money(req.collateralBased()),
                money(req.ratingFactor()), money(req.availableLimit()), money(req.requestedAmount())));
        String prevHash = idempotency.get("calc-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(LimitErrorCodes.DUPLICATE_CONFLICT,
                        "LMT_1005 重复提交/限额要素冲突，请使用原 requestId 查询首次测算结果");
            }
            return idempotency.get("calc", requestId);
        }

        LimitDtos.LimitCalcResult result = compute(req);
        // 试算留痕（lmt_calc 仿真）：供 4.4 试算查询按 calcId/主体检索
        String calcId = "C" + LocalDate.now().format(DAY).replace("-", "")
                + String.format("%04d", calcSeq.incrementAndGet() % 10000);
        calcHistory.put(calcId, new CalcRecord(calcId, requestId, req.customerId(), req.limitType(),
                req.availableLimit(), result.limitAmount(), result.usedAmount(), result.remainAmount(),
                result.requestedAmount(), result.controlStatus(), LocalDateTime.now().format(DATE_TIME)));
        idempotency.put("calc-hash", requestId, hash);
        idempotency.put("calc", requestId, result);
        log.info("额度测算完成: requestId={}, calcId={}, customerId={}, limitType={}, limitAmount={}, controlStatus={}, overAmount={}",
                requestId, calcId, result.customerId(), req.limitType(), result.limitAmount(),
                result.controlStatus(), result.overAmount());
        return result;
    }

    private void validate(LimitDtos.LimitCalcRequest req, String requestId) {
        if (req == null || req.customerId() == null || req.customerId().isBlank()) {
            throw new BaiException(LimitErrorCodes.NOT_FOUND, "LMT_1004 客户不存在：customerId 必填");
        }
        if (req.limitType() == null || !LIMIT_TYPES.contains(req.limitType())) {
            throw new BaiException(LimitErrorCodes.ELEMENT_ILLEGAL,
                    "LMT_1007 限额口径非法：limitType 须为 SINGLE/GROUP/INDUSTRY");
        }
        if (req.incomeBased() == null || req.collateralBased() == null) {
            throw new BaiException(LimitErrorCodes.PARAM_INVALID,
                    "LMT_1001 参数缺失：incomeBased/collateralBased 必填");
        }
        if (req.incomeBased().signum() < 0 || req.collateralBased().signum() < 0) {
            throw new BaiException(LimitErrorCodes.ELEMENT_ILLEGAL, "LMT_1007 金额非法：收入/押品额度不得为负");
        }
        if (req.ratingFactor() == null || req.ratingFactor().signum() < 0
                || req.ratingFactor().compareTo(BigDecimal.ONE) > 0) {
            throw new BaiException(LimitErrorCodes.ELEMENT_ILLEGAL,
                    "LMT_1007 评级调节系数非法：ratingFactor 须在 0~1 内");
        }
        if (req.availableLimit() == null || req.availableLimit().signum() < 0) {
            throw new BaiException(LimitErrorCodes.ELEMENT_ILLEGAL, "LMT_1007 金额非法：availableLimit 须 ≥ 0");
        }
        if (req.requestedAmount() == null || req.requestedAmount().signum() <= 0) {
            throw new BaiException(LimitErrorCodes.PARAM_INVALID,
                    "LMT_1001 参数缺失/非法：requestedAmount 必填且 > 0");
        }
    }

    /** 真实额度公式：limitAmount = min(incomeBased, collateralBased) × ratingFactor（GROUP 口径按加权） */
    private LimitDtos.LimitCalcResult compute(LimitDtos.LimitCalcRequest req) {
        BigDecimal base;
        if ("GROUP".equals(req.limitType())) {
            base = req.incomeBased().multiply(GROUP_INCOME_WEIGHT)
                    .add(req.collateralBased().multiply(GROUP_COLLATERAL_WEIGHT));
        } else {
            base = req.incomeBased().min(req.collateralBased());
        }
        BigDecimal limitAmount = base.multiply(req.ratingFactor()).setScale(2, RoundingMode.HALF_UP);
        // availableLimit 为当前口径下的可用额度，已用额度 = 核定额度 - 可用额度（下限 0）
        BigDecimal usedAmount = limitAmount.subtract(req.availableLimit()).max(ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal remainAmount = limitAmount.subtract(usedAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal overAmount;
        String controlStatus;
        if (limitAmount.signum() <= 0) {
            // 核定额度为 0：无可用核定额度，直接拒绝
            controlStatus = "REJECT";
            overAmount = req.requestedAmount().setScale(2, RoundingMode.HALF_UP);
        } else if (req.requestedAmount().compareTo(remainAmount) <= 0) {
            controlStatus = "PASS";
            overAmount = ZERO.setScale(2, RoundingMode.HALF_UP);
        } else {
            // 超限额：阻断并返回差额 overAmount = usedAmount + requestedAmount - limitAmount
            controlStatus = "OVER_LIMIT";
            overAmount = usedAmount.add(req.requestedAmount()).subtract(limitAmount)
                    .setScale(2, RoundingMode.HALF_UP);
        }
        return new LimitDtos.LimitCalcResult("SUCCESS", req.customerId(), limitAmount, usedAmount,
                remainAmount, req.requestedAmount().setScale(2, RoundingMode.HALF_UP), controlStatus, overAmount);
    }

    // ---------------------------------------------------------------- limit

    /** 限额管理（4.3，幂等：同 requestId+限额要素哈希返回首次结果；UPDATE/GRAY/DISABLE 按 limitType|scope 定位） */
    public LimitDtos.LimitResult limit(LimitDtos.LimitRequest req, String requestId) {
        validateLimit(req, requestId);

        String action = req.action().toUpperCase();
        String hash = sha256(String.join("|", action, str(req.limit().limitType()), str(req.limit().scope()),
                money(req.limit().amount()), str(req.limit().source()), str(req.limit().effectiveFrom()),
                str(req.limit().effectiveTo())));
        String prevHash = idempotency.get("limit-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(LimitErrorCodes.DUPLICATE_CONFLICT,
                        "LMT_1005 重复提交/限额冲突，请使用原 requestId 查询首次限额结果");
            }
            return idempotency.get("limit", requestId);
        }

        LimitDtos.LimitResult result = applyLimit(req);
        idempotency.put("limit-hash", requestId, hash);
        idempotency.put("limit", requestId, result);
        log.info("限额管理完成: requestId={}, action={}, limitId={}, limitType={}, scope={}, version={}, status={}",
                requestId, action, result.limitId(), req.limit().limitType(),
                req.limit().scope(), result.version(), result.status());
        return result;
    }

    private void validateLimit(LimitDtos.LimitRequest req, String requestId) {
        if (req == null || isBlank(req.action())) {
            throw new BaiException(LimitErrorCodes.PARAM_INVALID, "LMT_1001 参数缺失：action 必填");
        }
        String action = req.action().toUpperCase();
        if (!LIMIT_ACTIONS.contains(action)) {
            throw new BaiException(LimitErrorCodes.ELEMENT_ILLEGAL,
                    "LMT_1007 action 非法，允许 CREATE/UPDATE/GRAY/DISABLE");
        }
        if (req.limit() == null) {
            throw new BaiException(LimitErrorCodes.PARAM_INVALID, "LMT_1001 参数缺失：limit 限额配置必填");
        }
        LimitDtos.LimitConfig c = req.limit();
        if (c.limitType() == null || !LIMIT_TYPES.contains(c.limitType())) {
            throw new BaiException(LimitErrorCodes.ELEMENT_ILLEGAL,
                    "LMT_1007 限额口径非法：limitType 须为 SINGLE/GROUP/INDUSTRY");
        }
        if (isBlank(c.scope())) {
            throw new BaiException(LimitErrorCodes.PARAM_INVALID, "LMT_1001 参数缺失：scope 主体标识必填");
        }
        if (c.amount() == null || c.amount().signum() <= 0) {
            throw new BaiException(LimitErrorCodes.ELEMENT_ILLEGAL, "LMT_1007 金额非法：amount 必填且 > 0");
        }
        if (isBlank(c.source()) || !LIMIT_SOURCES.contains(c.source())) {
            throw new BaiException(LimitErrorCodes.ELEMENT_ILLEGAL,
                    "LMT_1007 限额来源非法：source 须为 监管/行内");
        }
        if (isBlank(c.effectiveFrom())) {
            throw new BaiException(LimitErrorCodes.PARAM_INVALID, "LMT_1001 参数缺失：effectiveFrom 生效日必填");
        }
        try {
            LocalDate from = LocalDate.parse(c.effectiveFrom());
            if (!isBlank(c.effectiveTo()) && LocalDate.parse(c.effectiveTo()).isBefore(from)) {
                throw new BaiException(LimitErrorCodes.ELEMENT_ILLEGAL,
                        "LMT_1007 生效期非法：effectiveTo 不得早于 effectiveFrom");
            }
        } catch (DateTimeParseException e) {
            throw new BaiException(LimitErrorCodes.PARAM_INVALID,
                    "LMT_1001 参数非法：effectiveFrom/effectiveTo 须为 ISO 日期（yyyy-MM-dd）");
        }
    }

    private LimitDtos.LimitResult applyLimit(LimitDtos.LimitRequest req) {
        LimitDtos.LimitConfig c = req.limit();
        String key = compositeKey(c.limitType(), c.scope());
        switch (req.action().toUpperCase()) {
            case "CREATE" -> {
                if (limitStore.containsKey(key)) {
                    throw new BaiException(LimitErrorCodes.DUPLICATE_CONFLICT,
                            "LMT_1005 限额冲突：同口径同主体限额已存在（" + c.limitType() + "/" + c.scope() + "）");
                }
                String limitId = "LMT" + LocalDate.now().format(DAY).replace("-", "")
                        + String.format("%04d", limitSeq.incrementAndGet() % 10000);
                LimitRule rule = new LimitRule(limitId, c.limitType(), c.scope(),
                        c.amount().setScale(2, RoundingMode.HALF_UP), c.source(),
                        c.effectiveFrom(), c.effectiveTo(), 1, "ACTIVE");
                limitStore.put(key, rule);
                return new LimitDtos.LimitResult("SUCCESS", limitId, 1, "ACTIVE");
            }
            case "UPDATE" -> {
                LimitRule rule = requireLimit(key);
                LimitRule updated = new LimitRule(rule.limitId(), rule.limitType(), rule.scope(),
                        c.amount().setScale(2, RoundingMode.HALF_UP), c.source(),
                        c.effectiveFrom(), c.effectiveTo(), rule.version() + 1, "ACTIVE");
                limitStore.put(key, updated);
                return new LimitDtos.LimitResult("SUCCESS", updated.limitId(), updated.version(), updated.status());
            }
            case "GRAY" -> {
                LimitRule rule = requireLimit(key);
                LimitRule updated = new LimitRule(rule.limitId(), rule.limitType(), rule.scope(), rule.amount(),
                        rule.source(), rule.effectiveFrom(), rule.effectiveTo(), rule.version() + 1, "GRAY");
                limitStore.put(key, updated);
                return new LimitDtos.LimitResult("SUCCESS", updated.limitId(), updated.version(), "GRAY");
            }
            default -> {
                LimitRule rule = requireLimit(key);
                LimitRule updated = new LimitRule(rule.limitId(), rule.limitType(), rule.scope(), rule.amount(),
                        rule.source(), rule.effectiveFrom(), rule.effectiveTo(), rule.version() + 1, "DISABLED");
                limitStore.put(key, updated);
                return new LimitDtos.LimitResult("SUCCESS", updated.limitId(), updated.version(), "DISABLED");
            }
        }
    }

    private LimitRule requireLimit(String key) {
        LimitRule rule = limitStore.get(key);
        if (rule == null) {
            throw new BaiException(LimitErrorCodes.NOT_FOUND,
                    "LMT_1004 限额不存在：" + key.replace("|", "/"));
        }
        return rule;
    }

    // ---------------------------------------------------------------- query

    /** 试算查询（4.4）：calcId 精确查，或按 customerId（可加 limitType/status 过滤）分页查；无记录 LMT_1004 */
    public LimitDtos.CalcQueryResult query(LimitDtos.CalcQueryRequest req, String requestId) {
        if (req == null || (isBlank(req.calcId()) && isBlank(req.customerId()))) {
            throw new BaiException(LimitErrorCodes.PARAM_INVALID, "LMT_1001 参数缺失：calcId 或 customerId 必填");
        }
        if (!isBlank(req.limitType()) && !LIMIT_TYPES.contains(req.limitType())) {
            throw new BaiException(LimitErrorCodes.ELEMENT_ILLEGAL,
                    "LMT_1007 限额口径非法：limitType 须为 SINGLE/GROUP/INDUSTRY");
        }
        if (!isBlank(req.status()) && !CONTROL_STATUSES.contains(req.status())) {
            throw new BaiException(LimitErrorCodes.ELEMENT_ILLEGAL,
                    "LMT_1007 状态非法：status 须为 PASS/OVER_LIMIT/REJECT");
        }
        int page = req.page() == null ? DEFAULT_PAGE : Math.max(1, req.page());
        int size = req.size() == null ? DEFAULT_SIZE : Math.min(MAX_PAGE_SIZE, Math.max(1, req.size()));

        if (!isBlank(req.calcId())) {
            CalcRecord record = calcHistory.get(req.calcId());
            if (record == null) {
                throw new BaiException(LimitErrorCodes.NOT_FOUND, "LMT_1004 试算记录不存在：" + req.calcId());
            }
            log.info("试算查询完成: requestId={}, calcId={}, customerId={}, status={}",
                    requestId, record.calcId(), record.customerId(), record.controlStatus());
            return new LimitDtos.CalcQueryResult(1, List.of(toItem(record)));
        }

        List<CalcRecord> matched = calcHistory.values().stream()
                .filter(r -> r.customerId().equals(req.customerId()))
                .filter(r -> isBlank(req.limitType()) || r.limitType().equals(req.limitType()))
                .filter(r -> isBlank(req.status()) || r.controlStatus().equals(req.status()))
                .sorted(Comparator.comparing(CalcRecord::createdAt).reversed()
                        .thenComparing(CalcRecord::calcId))
                .toList();
        if (matched.isEmpty()) {
            throw new BaiException(LimitErrorCodes.NOT_FOUND, "LMT_1004 无试算记录：customerId=" + req.customerId());
        }
        int from = Math.min((page - 1) * size, matched.size());
        int to = Math.min(from + size, matched.size());
        List<LimitDtos.CalcQueryItem> items = matched.subList(from, to).stream().map(this::toItem).toList();
        log.info("试算查询完成: requestId={}, customerId={}, limitType={}, status={}, total={}, page={}, size={}",
                requestId, req.customerId(), req.limitType(), req.status(), matched.size(), page, items.size());
        return new LimitDtos.CalcQueryResult(matched.size(), items);
    }

    private LimitDtos.CalcQueryItem toItem(CalcRecord r) {
        return new LimitDtos.CalcQueryItem(r.calcId(), r.customerId(), r.availableLimit(),
                new LimitDtos.CalcControl(r.limitType(), r.limitAmount(), r.usedAmount(), r.remainAmount(),
                        r.requestedAmount(), r.controlStatus()),
                r.controlStatus(), r.createdAt());
    }

    // ---------------------------------------------------------------- usage

    /** 占用查询（4.5，确定性仿真）：命中限额后以主体哈希确定占用比例（0.20~0.80）与两笔合同明细 */
    public LimitDtos.UsageResult usage(LimitDtos.UsageQueryRequest req, String requestId) {
        if (req == null || isBlank(req.customerId())) {
            throw new BaiException(LimitErrorCodes.PARAM_INVALID, "LMT_1001 参数缺失：customerId 必填");
        }
        String limitType = isBlank(req.limitType()) ? "SINGLE" : req.limitType();
        if (!LIMIT_TYPES.contains(limitType)) {
            throw new BaiException(LimitErrorCodes.ELEMENT_ILLEGAL,
                    "LMT_1007 限额口径非法：limitType 须为 SINGLE/GROUP/INDUSTRY");
        }
        LimitRule rule = requireLimit(compositeKey(limitType, req.customerId()));

        BigDecimal limitAmount = rule.amount();
        int seed = Math.abs(hash(req.customerId() + "|" + limitType));
        BigDecimal ratio = new BigDecimal("0.20")
                .add(BigDecimal.valueOf(seed % 61).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
        BigDecimal usedAmount = limitAmount.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal remainAmount = limitAmount.subtract(usedAmount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal first = usedAmount.multiply(new BigDecimal("0.6")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal second = usedAmount.subtract(first).setScale(2, RoundingMode.HALF_UP);
        String c1 = "LOAN" + String.format("%08d", seed % 100000000);
        String c2 = "LOAN" + String.format("%08d",
                Math.abs(hash(req.customerId() + "#" + limitType)) % 100000000);
        String ratioText = ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)
                .toPlainString() + "%";
        log.info("占用查询完成: requestId={}, customerId={}, limitType={}, limitAmount={}, usedAmount={}, remainAmount={}, ratio={}",
                requestId, req.customerId(), limitType, limitAmount, usedAmount, remainAmount, ratioText);
        return new LimitDtos.UsageResult("SUCCESS", limitType, limitAmount, usedAmount, remainAmount,
                ratioText, List.of(new LimitDtos.UsageDetail(c1, first, "ACTIVE"),
                new LimitDtos.UsageDetail(c2, second, "ACTIVE")));
    }

    private static String compositeKey(String limitType, String scope) {
        return limitType + "|" + scope;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static int hash(String input) {
        return (input == null ? "" : input).hashCode();
    }

    private static String money(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(s.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
