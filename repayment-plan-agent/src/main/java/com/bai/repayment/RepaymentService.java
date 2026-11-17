package com.bai.repayment;

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
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 还款计划服务（提示词库 A-60）。
 * <p>处理链：校验本金/利率/期限/还款法 → 月利率 = 年利率/12 → 按 method 计算：
 * 等额本息用年金公式 M = P×r×(1+r)^n/((1+r)^n−1)；等额本金每期本金 = 本金/期数；
 * 按期付息每期付息、到期还本；BULLET 到期一次还本付息 → 生成逐期计划（期次/本金/利息/月供/日期）→
 * recalcType 非空时重算剩余计划 → 合计利息与逐期累加一致并落审计。
 * <p>重算口径（重算要素未随请求输入，按行业标准默认口径）：
 * PREPAY 保持月供缩短期限（第 1 期还款后按剩余本金重排）；RATE_ADJUST/REFINANCE 按剩余本金与剩余期数重算月供；
 * EXTEND 展期 12 个月（上限 360）重算；非月供方法按剩余本金/剩余期数重排（按期付息/到期还本剩余计划与全期一致）。
 * <p>约束：逐期本金合计=本金、逐期利息合计=totalInterest（误差为 0）；幂等以 requestId+合同+方法哈希为键，
 * 同 requestId 不同要素视为计划冲突（RPP_1005）。
 */
@Service
public class RepaymentService {

    private static final Logger log = LoggerFactory.getLogger(RepaymentService.class);

    private static final Set<String> METHODS =
            Set.of("EQUAL_INSTALLMENT", "EQUAL_PRINCIPAL", "PERIOD_INTEREST", "BULLET");
    private static final Set<String> RECALC_TYPES = Set.of("PREPAY", "RATE_ADJUST", "EXTEND", "REFINANCE");
    /** 期次数上限 */
    private static final int MAX_TERMS = 360;
    /** 展期月数 */
    private static final int EXTEND_MONTHS = 12;
    private static final int MONEY_SCALE = 2;
    private static final BigDecimal TWELVE = new BigDecimal("12");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final IdempotencyStore idempotency = new IdempotencyStore();
    /** 计划留痕：planId → 计划记录（生产落还款计划表，本阶段内存仿真，供查询/重算检索） */
    private final Map<String, PlanRecord> planStore = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 计划生成/重算（幂等：同 requestId+合同+方法哈希返回首次结果，缓存 24h） */
    public RepaymentDtos.PlanResult plan(RepaymentDtos.PlanRequest req, String requestId) {
        validate(req, requestId);

        String hash = sha256(String.join("|",
                str(req.contractId()), money(req.principal()), money(req.rate()), String.valueOf(req.terms()),
                str(req.method()), str(req.startDate()), str(req.recalcType())));
        String prevHash = idempotency.get("plan-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(RepaymentErrorCodes.DUPLICATE_CONFLICT,
                        "RPP_1005 重复生成/计划冲突，请修正后重试或使用原 requestId 查询首次计划");
            }
            return idempotency.get("plan", requestId);
        }

        RepaymentDtos.PlanResult result = compute(req);
        String planId = newPlanId();
        planStore.put(planId, new PlanRecord(planId, result.contractId(), req.principal(), req.rate(), req.terms(),
                result.method(), req.startDate(), "SCHEDULED", result.schedule(),
                new RepaymentDtos.InterestDetail(ZERO, ZERO, ZERO), LocalDate.now().toString()));
        idempotency.put("plan-hash", requestId, hash);
        idempotency.put("plan", requestId, result);
        log.info("还款计划生成: requestId={}, planId={}, contractId={}, method={}, terms={}, monthlyPayment={}, totalInterest={}, recalcType={}",
                requestId, planId, result.contractId(), result.method(), req.terms(),
                result.monthlyPayment(), result.totalInterest(), result.recalcType());
        return result;
    }

    /** 计划重算（4.3，独立端点）：基于已生成计划 planId + 触发要素，复用同一重算引擎 compute()，
     * 返回新计划（newPlanId/oldPlanId/逐期明细/重算概要）；幂等：同 requestId+planId+要素哈希返回首次结果 */
    public RepaymentDtos.RecalcResult recalculate(RepaymentDtos.RecalcRequest req, String requestId) {
        if (req == null || isBlank(req.planId())) {
            throw new BaiException(RepaymentErrorCodes.PARAM_INVALID, "RPP_1001 参数缺失：planId 必填");
        }
        if (isBlank(req.recalcType()) || !RECALC_TYPES.contains(req.recalcType())) {
            throw new BaiException(RepaymentErrorCodes.ELEMENT_ILLEGAL,
                    "RPP_1007 重算类型非法：recalcType 须为 PREPAY/RATE_ADJUST/EXTEND/REFINANCE");
        }
        PlanRecord record = requirePlan(req.planId());
        RepaymentDtos.RecalcTrigger trigger = req.trigger();
        // 触发要素校验：amount 冲减本金、newRate 新利率、newEndDate 新到期日（折算期数）
        if (trigger != null && trigger.amount() != null && trigger.amount().signum() < 0) {
            throw new BaiException(RepaymentErrorCodes.PARAM_INVALID, "RPP_1001 参数非法：trigger.amount 须 ≥ 0");
        }
        if (trigger != null && trigger.newRate() != null
                && (trigger.newRate().signum() <= 0 || trigger.newRate().compareTo(BigDecimal.ONE) > 0)) {
            throw new BaiException(RepaymentErrorCodes.PARAM_INVALID, "RPP_1001 参数非法：trigger.newRate 须在 (0,1] 内");
        }
        LocalDate endDate = null;
        if (trigger != null && !isBlank(trigger.newEndDate())) {
            try {
                endDate = LocalDate.parse(trigger.newEndDate());
            } catch (DateTimeParseException e) {
                throw new BaiException(RepaymentErrorCodes.PARAM_INVALID,
                        "RPP_1001 参数非法：trigger.newEndDate 须为 ISO 日期（yyyy-MM-dd）");
            }
            if (!endDate.isAfter(LocalDate.parse(record.startDate))) {
                throw new BaiException(RepaymentErrorCodes.PARAM_INVALID,
                        "RPP_1001 参数非法：trigger.newEndDate 须晚于起始日");
            }
        }

        String hash = sha256(String.join("|", str(req.planId()), str(req.recalcType()),
                money(trigger == null ? null : trigger.amount()), money(trigger == null ? null : trigger.newRate()),
                str(trigger == null ? null : trigger.newEndDate())));
        String prevHash = idempotency.get("recalc-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(RepaymentErrorCodes.DUPLICATE_CONFLICT,
                        "RPP_1005 重复重算/计划冲突，请修正后重试或使用原 requestId 查询首次结果");
            }
            return idempotency.get("recalc", requestId);
        }

        // 按重算类型叠加触发要素映射到同一计划计算引擎：PREPAY 冲减本金、RATE_ADJUST 新利率、
        // EXTEND 新到期日折算期数、REFINANCE 新利率+新期数；未给触发要素时沿用原计划要素（引擎内默认口径）
        BigDecimal principal = record.principal;
        BigDecimal rate = record.rate;
        int terms = record.terms;
        if ("PREPAY".equals(req.recalcType()) && trigger != null && trigger.amount() != null) {
            principal = record.principal.subtract(trigger.amount());
            if (principal.signum() <= 0) {
                throw new BaiException(RepaymentErrorCodes.PARAM_INVALID,
                        "RPP_1001 参数非法：trigger.amount 须小于剩余本金");
            }
        }
        if (("RATE_ADJUST".equals(req.recalcType()) || "REFINANCE".equals(req.recalcType()))
                && trigger != null && trigger.newRate() != null) {
            rate = trigger.newRate();
        }
        if (("EXTEND".equals(req.recalcType()) || "REFINANCE".equals(req.recalcType())) && endDate != null) {
            int t = (int) ChronoUnit.MONTHS.between(LocalDate.parse(record.startDate), endDate);
            if (t < 1 || t > MAX_TERMS) {
                throw new BaiException(RepaymentErrorCodes.PARAM_INVALID,
                        "RPP_1001 参数非法：trigger.newEndDate 折算期数须在 1~360 内");
            }
            terms = t;
        }

        RepaymentDtos.PlanRequest planReq = new RepaymentDtos.PlanRequest(record.contractId, principal, rate,
                terms, record.method, record.startDate, req.recalcType(), requestId);
        RepaymentDtos.PlanResult newPlan = compute(planReq);
        String newPlanId = newPlanId();
        planStore.put(newPlanId, new PlanRecord(newPlanId, newPlan.contractId(), principal, rate, terms,
                newPlan.method(), record.startDate, "SCHEDULED", newPlan.schedule(),
                new RepaymentDtos.InterestDetail(ZERO, ZERO, ZERO), LocalDate.now().toString()));

        BigDecimal newRateOut = null;
        Integer newTerms = null;
        if ("RATE_ADJUST".equals(req.recalcType()) || "REFINANCE".equals(req.recalcType())) {
            newRateOut = rate;
            newTerms = terms;
        } else if ("EXTEND".equals(req.recalcType())) {
            newTerms = Math.min(terms + EXTEND_MONTHS, MAX_TERMS);
        }
        RepaymentDtos.RecalcResult result = new RepaymentDtos.RecalcResult(newPlanId, req.planId(), newPlan.schedule(),
                new RepaymentDtos.RecalcOutcome(req.recalcType(), newPlan.contractId(), newPlan.method(),
                        newPlan.monthlyPayment(), newPlan.totalInterest(), newRateOut, newTerms));
        idempotency.put("recalc-hash", requestId, hash);
        idempotency.put("recalc", requestId, result);
        log.info("还款计划重算: requestId={}, oldPlanId={}, newPlanId={}, recalcType={}, contractId={}, monthlyPayment={}, totalInterest={}",
                requestId, req.planId(), newPlanId, req.recalcType(), newPlan.contractId(),
                newPlan.monthlyPayment(), newPlan.totalInterest());
        return result;
    }

    /** 计划查询（4.4）：按 planId/contractId（可叠加 method/status，分页）检索历史生成计划；无记录 RPP_1004 */
    public RepaymentDtos.PlanQueryResult query(RepaymentDtos.PlanQueryRequest q, String requestId) {
        if (q == null || (isBlank(q.planId()) && isBlank(q.contractId()))) {
            throw new BaiException(RepaymentErrorCodes.PARAM_INVALID,
                    "RPP_1001 参数缺失：planId 或 contractId 至少一个必填");
        }
        RepaymentDtos.PlanQueryResult cached = idempotency.get("query", requestId);
        if (cached != null) {
            return cached;
        }
        List<RepaymentDtos.PlanItem> matched = planStore.values().stream()
                .filter(r -> isBlank(q.planId()) || r.planId.equals(q.planId()))
                .filter(r -> isBlank(q.contractId()) || r.contractId.equals(q.contractId()))
                .filter(r -> isBlank(q.method()) || r.method.equals(q.method()))
                .filter(r -> isBlank(q.status()) || r.status.equals(q.status()))
                .sorted(Comparator.comparing(PlanRecord::createdAt).reversed())
                .map(r -> new RepaymentDtos.PlanItem(r.planId, r.contractId, r.method, r.status,
                        r.details, r.interestDetail, r.createdAt))
                .toList();
        if (matched.isEmpty()) {
            throw new BaiException(RepaymentErrorCodes.NOT_FOUND,
                    "RPP_1004 计划不存在：planId=" + q.planId() + ", contractId=" + q.contractId());
        }
        int page = q.page() == null ? 1 : Math.max(1, q.page());
        int size = q.size() == null ? 20 : Math.max(1, Math.min(100, q.size()));
        int from = (page - 1) * size;
        List<RepaymentDtos.PlanItem> items = from >= matched.size() ? List.of()
                : matched.subList(from, Math.min(from + size, matched.size()));
        RepaymentDtos.PlanQueryResult result = new RepaymentDtos.PlanQueryResult(matched.size(), items);
        idempotency.put("query", requestId, result);
        log.info("还款计划查询: requestId={}, planId={}, contractId={}, total={}, page={}, size={}",
                requestId, q.planId(), q.contractId(), matched.size(), page, size);
        return result;
    }

    /** 罚息试算（4.5，仿真）：罚息 = 逾期本金×罚息年利率/360×逾期天数，复利 = 逾期利息×复利年利率/360×逾期天数，
     * 逐日明细；幂等：同 requestId+要素哈希返回首次结果 */
    public RepaymentDtos.PenaltyResult penalty(RepaymentDtos.PenaltyRequest req, String requestId) {
        if (req == null || isBlank(req.planId())) {
            throw new BaiException(RepaymentErrorCodes.PARAM_INVALID, "RPP_1001 参数缺失：planId 必填");
        }
        requirePlan(req.planId());
        if (req.overduePrincipal() == null || req.overduePrincipal().signum() <= 0) {
            throw new BaiException(RepaymentErrorCodes.PARAM_INVALID, "RPP_1001 参数非法：overduePrincipal 必填且 > 0");
        }
        if (req.overdueDays() == null || req.overdueDays() < 1 || req.overdueDays() > MAX_TERMS) {
            throw new BaiException(RepaymentErrorCodes.PARAM_INVALID, "RPP_1001 参数非法：overdueDays 须在 1~360 内");
        }
        if (req.penaltyRate() == null || req.penaltyRate().signum() <= 0
                || req.penaltyRate().compareTo(BigDecimal.ONE) > 0) {
            throw new BaiException(RepaymentErrorCodes.PARAM_INVALID,
                    "RPP_1001 参数非法：penaltyRate 为年利率小数，须在 (0,1] 内");
        }
        BigDecimal overdueInterest = req.overdueInterest() == null ? ZERO : req.overdueInterest();
        BigDecimal compoundRate = req.compoundRate() == null ? ZERO : req.compoundRate();
        if (overdueInterest.signum() < 0 || compoundRate.signum() < 0 || compoundRate.compareTo(BigDecimal.ONE) > 0) {
            throw new BaiException(RepaymentErrorCodes.PARAM_INVALID,
                    "RPP_1001 参数非法：overdueInterest 须 ≥ 0、compoundRate 须在 [0,1] 内");
        }

        String hash = sha256(String.join("|", str(req.planId()), money(req.overduePrincipal()),
                money(overdueInterest), String.valueOf(req.overdueDays()), money(req.penaltyRate()),
                money(compoundRate)));
        String prevHash = idempotency.get("penalty-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(RepaymentErrorCodes.DUPLICATE_CONFLICT,
                        "RPP_1005 重复试算/要素冲突，请修正后重试或使用原 requestId 查询首次结果");
            }
            return idempotency.get("penalty", requestId);
        }

        BigDecimal dayRatePenalty = req.penaltyRate().divide(new BigDecimal("360"), 10, RoundingMode.HALF_UP);
        BigDecimal dayRateCompound = compoundRate.divide(new BigDecimal("360"), 10, RoundingMode.HALF_UP);
        BigDecimal dailyPenalty = req.overduePrincipal().multiply(dayRatePenalty).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal dailyCompound = overdueInterest.multiply(dayRateCompound).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        List<RepaymentDtos.PenaltyItem> details = new ArrayList<>(req.overdueDays());
        BigDecimal cumulative = ZERO;
        for (int d = 1; d <= req.overdueDays(); d++) {
            cumulative = cumulative.add(dailyPenalty).add(dailyCompound);
            details.add(new RepaymentDtos.PenaltyItem(d, dailyPenalty, dailyCompound, cumulative));
        }
        BigDecimal penalty = dailyPenalty.multiply(BigDecimal.valueOf(req.overdueDays()));
        BigDecimal compound = dailyCompound.multiply(BigDecimal.valueOf(req.overdueDays()));
        RepaymentDtos.PenaltyResult result = new RepaymentDtos.PenaltyResult(penalty, compound, penalty.add(compound),
                List.copyOf(details));
        idempotency.put("penalty-hash", requestId, hash);
        idempotency.put("penalty", requestId, result);
        log.info("罚息试算: requestId={}, planId={}, overdueDays={}, penalty={}, compound={}, total={}",
                requestId, req.planId(), req.overdueDays(), penalty, compound, penalty.add(compound));
        return result;
    }

    private void validate(RepaymentDtos.PlanRequest req, String requestId) {
        if (req == null || req.contractId() == null || req.contractId().isBlank()) {
            throw new BaiException(RepaymentErrorCodes.NOT_FOUND, "RPP_1004 合同不存在：contractId 必填");
        }
        if (req.principal() == null || req.principal().signum() <= 0) {
            throw new BaiException(RepaymentErrorCodes.PARAM_INVALID, "RPP_1001 参数非法：principal 必填且 > 0");
        }
        if (req.rate() == null || req.rate().signum() <= 0 || req.rate().compareTo(BigDecimal.ONE) > 0) {
            throw new BaiException(RepaymentErrorCodes.PARAM_INVALID,
                    "RPP_1001 参数非法：rate 为年利率小数，须在 (0,1] 内");
        }
        if (req.terms() == null || req.terms() < 1) {
            throw new BaiException(RepaymentErrorCodes.PARAM_INVALID, "RPP_1001 参数非法：terms 须 ≥ 1");
        }
        if (req.terms() > MAX_TERMS) {
            throw new BaiException(RepaymentErrorCodes.TERMS_EXCEEDED,
                    "RPP_1006 期次数超限：terms " + req.terms() + " > 360，请缩减输入");
        }
        if (req.method() == null || !METHODS.contains(req.method())) {
            throw new BaiException(RepaymentErrorCodes.ELEMENT_ILLEGAL,
                    "RPP_1007 还款法非法：method 须为 EQUAL_INSTALLMENT/EQUAL_PRINCIPAL/PERIOD_INTEREST/BULLET");
        }
        if (req.recalcType() != null && !req.recalcType().isBlank() && !RECALC_TYPES.contains(req.recalcType())) {
            throw new BaiException(RepaymentErrorCodes.ELEMENT_ILLEGAL,
                    "RPP_1007 重算类型非法：recalcType 须为 PREPAY/RATE_ADJUST/EXTEND/REFINANCE");
        }
        if (req.startDate() == null || req.startDate().isBlank()) {
            throw new BaiException(RepaymentErrorCodes.PARAM_INVALID, "RPP_1001 参数缺失：startDate 起息日必填");
        }
        try {
            LocalDate.parse(req.startDate());
        } catch (DateTimeParseException e) {
            throw new BaiException(RepaymentErrorCodes.PARAM_INVALID,
                    "RPP_1001 参数非法：startDate 须为 ISO 日期（yyyy-MM-dd）");
        }
    }

    private RepaymentDtos.PlanResult compute(RepaymentDtos.PlanRequest req) {
        BigDecimal principal = req.principal();
        double rate = req.rate().doubleValue();
        double rm = rate / 12.0;                                   // 月利率 = 年利率/12
        BigDecimal rmBD = req.rate().divide(TWELVE, 10, RoundingMode.HALF_UP);
        LocalDate start = LocalDate.parse(req.startDate());
        int terms = req.terms();
        String recalcType = req.recalcType() == null || req.recalcType().isBlank() ? null : req.recalcType();

        List<RepaymentDtos.ScheduleItem> schedule;
        BigDecimal monthlyPayment = null;
        // 本计划口径的本金基数（首次生成/展期/再融资=合同本金；提前还款/利率调整重算=第 1 期还款后剩余本金）
        BigDecimal planBase = principal;
        switch (req.method()) {
            case "EQUAL_INSTALLMENT" -> {
                // 年金公式：M = P×r×(1+r)^n / ((1+r)^n − 1)
                double m = principal.doubleValue() * rm * Math.pow(1 + rm, terms) / (Math.pow(1 + rm, terms) - 1);
                BigDecimal pay = BigDecimal.valueOf(m).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                if (recalcType == null) {
                    monthlyPayment = pay;
                    schedule = buildEqualInstallment(principal, rmBD, terms, start, pay, terms);
                } else {
                    switch (recalcType) {
                        case "PREPAY" -> {
                            // 第 1 期还款后剩余本金，保持月供缩短期限：n' = ⌈ln(M/(M−bal×r))/ln(1+r)⌉（还款日自次月起）
                            double bal = principal.doubleValue() * (1 + rm) - pay.doubleValue();
                            int remainTerms = (int) Math.ceil(
                                    Math.log(pay.doubleValue() / (pay.doubleValue() - bal * rm)) / Math.log(1 + rm));
                            remainTerms = Math.max(1, Math.min(remainTerms, MAX_TERMS));
                            monthlyPayment = pay;
                            planBase = BigDecimal.valueOf(bal).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                            schedule = buildEqualInstallment(planBase, rmBD, remainTerms, start, pay, remainTerms + 1);
                        }
                        case "EXTEND" -> {
                            // 展期 12 个月（上限 360），全要素重算月供
                            int extended = Math.min(terms + EXTEND_MONTHS, MAX_TERMS);
                            double me = principal.doubleValue() * rm * Math.pow(1 + rm, extended)
                                    / (Math.pow(1 + rm, extended) - 1);
                            BigDecimal payE = BigDecimal.valueOf(me).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                            monthlyPayment = payE;
                            schedule = buildEqualInstallment(principal, rmBD, extended, start, payE, extended);
                        }
                        default -> {
                            // RATE_ADJUST / REFINANCE：第 1 期还款后按剩余本金与剩余期数重算月供（还款日自次月起）
                            double bal = principal.doubleValue() * (1 + rm) - pay.doubleValue();
                            int remainTerms = terms - 1;
                            double mRest = bal * rm * Math.pow(1 + rm, remainTerms) / (Math.pow(1 + rm, remainTerms) - 1);
                            BigDecimal payRest = BigDecimal.valueOf(mRest).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                            monthlyPayment = payRest;
                            planBase = BigDecimal.valueOf(bal).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                            schedule = buildEqualInstallment(planBase, rmBD, remainTerms, start, payRest, terms);
                        }
                    }
                }
            }
            case "EQUAL_PRINCIPAL" -> {
                // 每期本金 = 本金/期数（尾差并入末期），每期利息 = 剩余本金×月利率
                int n = terms;
                BigDecimal base = principal;
                if (recalcType != null && ("PREPAY".equals(recalcType) || "RATE_ADJUST".equals(recalcType))) {
                    // 第 1 期还款后的剩余计划：剩余本金 / 剩余期数重排
                    base = principal.subtract(principal.divide(BigDecimal.valueOf(terms), MONEY_SCALE, RoundingMode.HALF_UP));
                    n = terms - 1;
                } else if ("EXTEND".equals(recalcType)) {
                    n = Math.min(terms + EXTEND_MONTHS, MAX_TERMS);
                }
                planBase = base;
                schedule = buildEqualPrincipal(base, rmBD, n, start);
            }
            case "PERIOD_INTEREST" -> schedule = buildPeriodInterest(principal, rmBD, terms, start);
            default -> schedule = buildBullet(principal, rmBD, terms, start);
        }

        // 合计校验：逐期本金合计 = 计划本金基数、逐期利息合计 = totalInterest（误差为 0）
        BigDecimal principalSum = ZERO;
        BigDecimal totalInterest = ZERO;
        for (RepaymentDtos.ScheduleItem item : schedule) {
            principalSum = principalSum.add(item.principal());
            totalInterest = totalInterest.add(item.interest());
        }
        if (principalSum.compareTo(planBase.setScale(MONEY_SCALE, RoundingMode.HALF_UP)) != 0) {
            throw new BaiException(RepaymentErrorCodes.INTERNAL_ERROR,
                    "RPP_2000 计划合计校验失败：逐期本金合计 " + principalSum + " ≠ 本金基数 " + planBase);
        }
        return new RepaymentDtos.PlanResult("SUCCESS", req.contractId(), req.method(), monthlyPayment,
                totalInterest, List.copyOf(schedule), recalcType);
    }

    /** 等额本息：固定月供 pay，末期本金吸收尾差使 Σprincipal = 本金 */
    private List<RepaymentDtos.ScheduleItem> buildEqualInstallment(BigDecimal balance, BigDecimal rmBD,
                                                                   int terms, LocalDate start, BigDecimal pay,
                                                                   int dateOffsetFrom) {
        List<RepaymentDtos.ScheduleItem> schedule = new ArrayList<>(terms);
        balance = balance.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        for (int t = 1; t <= terms; t++) {
            BigDecimal interest = balance.multiply(rmBD).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal principalPart;
            BigDecimal payment;
            if (t == terms) {
                principalPart = balance;
                payment = principalPart.add(interest);
            } else {
                principalPart = pay.subtract(interest).max(ZERO);
                payment = pay;
            }
            schedule.add(new RepaymentDtos.ScheduleItem(t, principalPart, interest, payment,
                    start.plusMonths(dateOffsetFrom - terms + t).toString()));
            balance = balance.subtract(principalPart);
        }
        return schedule;
    }

    /** 等额本金：每期本金 = 本金/期数（末期吸收尾差），利息 = 剩余本金×月利率 */
    private List<RepaymentDtos.ScheduleItem> buildEqualPrincipal(BigDecimal principal, BigDecimal rmBD,
                                                                 int terms, LocalDate start) {
        List<RepaymentDtos.ScheduleItem> schedule = new ArrayList<>(terms);
        BigDecimal basePrincipal = principal.divide(BigDecimal.valueOf(terms), MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal balance = principal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        for (int t = 1; t <= terms; t++) {
            BigDecimal principalPart = t == terms ? balance : basePrincipal;
            BigDecimal interest = balance.multiply(rmBD).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            schedule.add(new RepaymentDtos.ScheduleItem(t, principalPart, interest,
                    principalPart.add(interest), start.plusMonths(t).toString()));
            balance = balance.subtract(principalPart);
        }
        return schedule;
    }

    /** 按期付息到期还本（先息后本）：每期利息 = 本金×月利率，末期归还本金 */
    private List<RepaymentDtos.ScheduleItem> buildPeriodInterest(BigDecimal principal, BigDecimal rmBD,
                                                                 int terms, LocalDate start) {
        List<RepaymentDtos.ScheduleItem> schedule = new ArrayList<>(terms);
        BigDecimal monthlyInterest = principal.multiply(rmBD).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal balance = principal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        for (int t = 1; t <= terms; t++) {
            BigDecimal principalPart = t == terms ? balance : ZERO;
            schedule.add(new RepaymentDtos.ScheduleItem(t, principalPart, monthlyInterest,
                    principalPart.add(monthlyInterest), start.plusMonths(t).toString()));
        }
        return schedule;
    }

    /** 到期一次还本付息（BULLET）：到期一次性归还本金与全部利息（利息 = 本金×月利率×期数） */
    private List<RepaymentDtos.ScheduleItem> buildBullet(BigDecimal principal, BigDecimal rmBD,
                                                         int terms, LocalDate start) {
        List<RepaymentDtos.ScheduleItem> schedule = new ArrayList<>(terms);
        BigDecimal totalInterest = principal.multiply(rmBD).multiply(BigDecimal.valueOf(terms))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        for (int t = 1; t <= terms; t++) {
            if (t < terms) {
                schedule.add(new RepaymentDtos.ScheduleItem(t, ZERO, ZERO, ZERO, start.plusMonths(t).toString()));
            } else {
                schedule.add(new RepaymentDtos.ScheduleItem(t, principal.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                        totalInterest, principal.add(totalInterest), start.plusMonths(t).toString()));
            }
        }
        return schedule;
    }

    private static String money(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 按 planId 取计划留痕，不存在抛 RPP_1004 */
    private PlanRecord requirePlan(String planId) {
        PlanRecord record = planStore.get(planId);
        if (record == null) {
            throw new BaiException(RepaymentErrorCodes.NOT_FOUND, "RPP_1004 计划不存在：" + planId);
        }
        return record;
    }

    /** 计划 ID：RP + 日期 + 四位序号（仿真） */
    private String newPlanId() {
        return "RP" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000);
    }

    /** 计划留痕记录：源请求要素 + 逐期明细 + 三息 + 状态，供查询/重算检索（生产落还款计划表） */
    private record PlanRecord(String planId, String contractId, BigDecimal principal, BigDecimal rate,
                              int terms, String method, String startDate, String status,
                              List<RepaymentDtos.ScheduleItem> details,
                              RepaymentDtos.InterestDetail interestDetail, String createdAt) {}

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
