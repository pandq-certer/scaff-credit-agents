package com.bai.pricing;

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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 定价计算服务（提示词库 A-55）。
 * <p>处理链：校验入参 → 加载产品参数与定价规则版本、取对应期限 LPR 基准（带生效日期）→
 * 按评级与担保查风险溢价表、叠加资金与运营成本加成 → finalRate = baseRate + riskPremium + costAddOn，
 * 做上限（≤36%）与步长（0.01%）校验 → 按产品费率表计算手续费 → 输出可复算构成并落审计。
 * <p>幂等：requestId + 产品 + 定价要素哈希为键，同键不重复试算；同 requestId 不同要素视为规则冲突（PRC_1005）。
 */
@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    /** LPR 基准（人民银行公布口径，生效日随取值落审计；期限品种：≤36 个月取 1 年期，>36 个月取 5 年期以上） */
    private static final BigDecimal LPR_1Y = new BigDecimal("3.45");
    private static final BigDecimal LPR_5Y_PLUS = new BigDecimal("3.95");
    private static final String LPR_EFFECTIVE_DATE = "2026-09-20";
    /** 固定利率锁定溢价（浮动利率不加成） */
    private static final BigDecimal FIXED_LOCK_PREMIUM = new BigDecimal("0.10");
    /** 资金成本加成 + 运营成本加成 */
    private static final BigDecimal COST_FUNDING = new BigDecimal("0.60");
    private static final BigDecimal COST_OPERATION = new BigDecimal("0.25");
    /** 产品费率表：手续费 0.05%，一次性收取 */
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005");
    /** 执行利率上限 36%，超限阻断 */
    private static final BigDecimal RATE_CAP = new BigDecimal("36");
    /** 利率步长 0.01% */
    private static final int RATE_SCALE = 2;

    /** 风险溢价表：客户风险等级 → 溢价（年化%） */
    private static final Map<String, BigDecimal> RATING_PREMIUM = Map.of(
            "AAA", new BigDecimal("0.40"),
            "AA", new BigDecimal("0.60"),
            "A", new BigDecimal("0.80"),
            "BBB", new BigDecimal("1.20"),
            "BB", new BigDecimal("1.80"),
            "B", new BigDecimal("2.50"),
            "C", new BigDecimal("3.50"));

    /** 风险溢价表：担保方式 → 担保调整（年化%） */
    private static final Map<String, BigDecimal> COLLATERAL_ADJUST = Map.of(
            "抵押", new BigDecimal("0.00"),
            "质押", new BigDecimal("0.10"),
            "保证", new BigDecimal("0.30"),
            "信用", new BigDecimal("0.50"));

    /** 定价规则版本（只增不可回写，变更须审批） */
    private static final Set<String> RULE_VERSIONS = Set.of(
            "PRC-RULE-2026-Q3", "PRC-RULE-2026-Q2", "PRC-RULE-2026-Q1");
    private static final String DEFAULT_RULE_VERSION = "PRC-RULE-2026-Q3";

    private final IdempotencyStore idempotency = new IdempotencyStore();

    /** 规则管理动作（4.3）：CREATE 建规则生效 / UPDATE 升版 / GRAY 灰度 / DISABLE 停用 */
    private static final Set<String> RULE_ACTIONS = Set.of("CREATE", "UPDATE", "GRAY", "DISABLE");
    /** 定价要素因子（5.4）：评级/担保/期限/行业/客群 */
    private static final Set<String> RULE_FACTORS = Set.of("评级", "担保", "期限", "行业", "客群");
    /** 人工结论（4.5）：ACCEPT/REJECT/ADJUST */
    private static final Set<String> HUMAN_CONCLUSIONS = Set.of("ACCEPT", "REJECT", "ADJUST");
    private static final DateTimeFormatter CREATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 定价规则表（内存仿真，对应 prc_rule）：ruleId → 规则 */
    private final Map<String, RuleRecord> ruleStore = new ConcurrentHashMap<>();
    /** 试算记录表（内存仿真，对应 prc_quote）：quoteId → 记录，供 /query 与反馈校验 */
    private final Map<String, QuoteRecord> quoteStore = new ConcurrentHashMap<>();
    /** 定价反馈表（内存仿真，对应 prc_feedback）：feedbackId → 记录 */
    private final Map<String, FeedbackRecord> feedbackStore = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 利率试算（幂等：同 requestId+要素哈希返回首次结果，缓存 24h）；pricingType=PENALTY 走违约金试算 */
    public PricingDtos.QuoteResult quote(PricingDtos.QuoteRequest req, String requestId) {
        if (req != null && "PENALTY".equalsIgnoreCase(req.pricingType())) {
            return penaltyQuote(req, requestId);
        }
        validate(req, requestId);

        String hash = sha256(String.join("|",
                req.productId(), req.amount().toPlainString(), String.valueOf(req.term()),
                req.rateType(), req.collateral(), req.customerRating(),
                req.ruleVersion() == null ? "" : req.ruleVersion()));
        String prevHash = idempotency.get("quote-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(PricingErrorCodes.DUPLICATE_CONFLICT,
                        "PRC_1005 重复提交/定价要素冲突，请使用原 requestId 查询首次试算结果");
            }
            return idempotency.get("quote", requestId);
        }

        PricingDtos.QuoteResult result = compute(req);
        persistQuote(requestId, result.productId(), req.term(), req.collateral(),
                result.finalRate(), result.fees(), req.params());
        idempotency.put("quote-hash", requestId, hash);
        idempotency.put("quote", requestId, result);
        log.info("定价试算完成: requestId={}, productId={}, term={}, baseRate={}（LPR 生效日 {}）, finalRate={}, ruleVersion={}",
                requestId, result.productId(), req.term(), result.baseRate(), LPR_EFFECTIVE_DATE,
                result.finalRate(), result.ruleVersion());
        return result;
    }

    private void validate(PricingDtos.QuoteRequest req, String requestId) {
        if (req == null || req.productId() == null || req.productId().isBlank()) {
            throw new BaiException(PricingErrorCodes.NOT_FOUND, "PRC_1004 产品不存在：productId 必填");
        }
        if (req.ruleVersion() != null && !req.ruleVersion().isBlank()
                && !RULE_VERSIONS.contains(req.ruleVersion())) {
            throw new BaiException(PricingErrorCodes.NOT_FOUND,
                    "PRC_1004 定价规则不存在：ruleVersion=" + req.ruleVersion());
        }
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new BaiException(PricingErrorCodes.PARAM_INVALID, "PRC_1001 参数非法：amount 必填且 > 0");
        }
        if (req.term() == null) {
            throw new BaiException(PricingErrorCodes.PARAM_INVALID, "PRC_1001 参数缺失：term 必填");
        }
        if (req.term() < 1 || req.term() > 360) {
            throw new BaiException(PricingErrorCodes.ELEMENT_ILLEGAL,
                    "PRC_1007 期限非法：term 须在 1~360 月内，当前 " + req.term());
        }
        if (!"FIXED".equals(req.rateType()) && !"FLOATING".equals(req.rateType())) {
            throw new BaiException(PricingErrorCodes.ELEMENT_ILLEGAL,
                    "PRC_1007 利率类型非法：rateType 须为 FIXED/FLOATING");
        }
        if (!COLLATERAL_ADJUST.containsKey(req.collateral())) {
            throw new BaiException(PricingErrorCodes.ELEMENT_ILLEGAL,
                    "PRC_1007 担保方式非法：collateral 须为 信用/抵押/质押/保证");
        }
        if (req.customerRating() == null || !RATING_PREMIUM.containsKey(req.customerRating())) {
            throw new BaiException(PricingErrorCodes.PARAM_INVALID,
                    "PRC_1001 参数非法：customerRating 须为 AAA/AA/A/BBB/BB/B/C");
        }
    }

    /** 真实定价公式：finalRate = LPR 基准 + 风险溢价（评级+担保） + 成本加成（资金+运营）［+ 固定利率锁定溢价］ */
    private PricingDtos.QuoteResult compute(PricingDtos.QuoteRequest req) {
        BigDecimal baseRate = req.term() <= 36 ? LPR_1Y : LPR_5Y_PLUS;
        BigDecimal riskPremium = RATING_PREMIUM.get(req.customerRating()).add(COLLATERAL_ADJUST.get(req.collateral()));
        BigDecimal costAddOn = COST_FUNDING.add(COST_OPERATION);
        if ("FIXED".equals(req.rateType())) {
            costAddOn = costAddOn.add(FIXED_LOCK_PREMIUM);
        }
        BigDecimal finalRate = baseRate.add(riskPremium).add(costAddOn).setScale(RATE_SCALE, RoundingMode.HALF_UP);
        if (finalRate.compareTo(RATE_CAP) > 0) {
            throw new BaiException(PricingErrorCodes.ELEMENT_ILLEGAL,
                    "PRC_1007 执行利率 " + finalRate + "% 超出 36% 上限，试算阻断：请调整风险等级或担保方式后重试");
        }
        // 费用按产品费率表计算（手续费 = 金额 × 0.05%，一次性），误差为 0
        BigDecimal fee = req.amount().multiply(FEE_RATE).setScale(RATE_SCALE, RoundingMode.HALF_UP);
        String ruleVersion = req.ruleVersion() == null || req.ruleVersion().isBlank()
                ? DEFAULT_RULE_VERSION : req.ruleVersion();
        return new PricingDtos.QuoteResult("SUCCESS", req.productId(), baseRate, riskPremium, costAddOn,
                finalRate, List.of(new PricingDtos.FeeItem("手续费", fee, "一次性")), ruleVersion);
    }

    // ---------------------------------------------------------------- rule

    /** 定价规则管理（4.3，内存仿真对应 prc_rule）：CREATE 建规则（版本 1/ACTIVE）、UPDATE 升版、GRAY 灰度、DISABLE 停用；
     *  幂等：requestId+action+规则标识哈希，同键不重复执行 */
    public PricingDtos.RuleResult rule(PricingDtos.RuleRequest req, String requestId) {
        if (req == null || isBlank(req.action())) {
            throw new BaiException(PricingErrorCodes.PARAM_INVALID, "PRC_1001 参数缺失：action 必填");
        }
        String action = req.action().toUpperCase();
        if (!RULE_ACTIONS.contains(action)) {
            throw new BaiException(PricingErrorCodes.PARAM_INVALID,
                    "PRC_1001 action 非法，允许 CREATE/UPDATE/GRAY/DISABLE，当前 " + req.action());
        }
        PricingDtos.RuleParam rule = req.rule();
        String target = rule == null ? "" : ("CREATE".equals(action) ? rule.name() : rule.ruleId());
        String idemKey = requestId + ":" + sha256(action + "|" + target);
        PricingDtos.RuleResult cached = idempotency.get("rule", idemKey);
        if (cached != null) {
            return cached;
        }
        PricingDtos.RuleResult result;
        switch (action) {
            case "CREATE" -> result = createRule(rule);
            case "UPDATE" -> result = updateRule(rule);
            case "GRAY" -> result = transitRule(rule, "GRAY");
            case "DISABLE" -> result = transitRule(rule, "DISABLED");
            default -> throw new BaiException(PricingErrorCodes.PARAM_INVALID, "PRC_1001 action 非法");
        }
        idempotency.put("rule", idemKey, result);
        log.info("定价规则变更完成: requestId={}, action={}, ruleId={}, version={}, status={}, simulation=true",
                requestId, action, result.ruleId(), result.version(), result.status());
        return result;
    }

    private PricingDtos.RuleResult createRule(PricingDtos.RuleParam rule) {
        requireRuleParam(rule);
        String ruleId = "PRCR" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000);
        ruleStore.put(ruleId, new RuleRecord(ruleId, rule.name(), rule.factor(), rule.condition(),
                rule.premium(), rule.effectiveFrom(), rule.effectiveTo(), 1, "ACTIVE"));
        return new PricingDtos.RuleResult(ruleId, 1, "ACTIVE");
    }

    private PricingDtos.RuleResult updateRule(PricingDtos.RuleParam rule) {
        requireRuleParam(rule);
        RuleRecord old = requireRule(rule.ruleId());
        int version = old.version() + 1;
        RuleRecord next = new RuleRecord(old.ruleId(), rule.name(), rule.factor(), rule.condition(),
                rule.premium(), rule.effectiveFrom(), rule.effectiveTo(), version, old.status());
        ruleStore.put(old.ruleId(), next);
        return new PricingDtos.RuleResult(old.ruleId(), version, next.status());
    }

    /** GRAY/DISABLE：仅流转状态，版本不变 */
    private PricingDtos.RuleResult transitRule(PricingDtos.RuleParam rule, String status) {
        if (rule == null || isBlank(rule.ruleId())) {
            throw new BaiException(PricingErrorCodes.PARAM_INVALID,
                    "PRC_1001 参数缺失：rule.ruleId 必填（" + status + " 需指定目标规则）");
        }
        RuleRecord old = requireRule(rule.ruleId());
        RuleRecord next = new RuleRecord(old.ruleId(), old.name(), old.factor(), old.condition(), old.premium(),
                old.effectiveFrom(), old.effectiveTo(), old.version(), status);
        ruleStore.put(old.ruleId(), next);
        return new PricingDtos.RuleResult(old.ruleId(), old.version(), status);
    }

    private void requireRuleParam(PricingDtos.RuleParam rule) {
        if (rule == null) {
            throw new BaiException(PricingErrorCodes.PARAM_INVALID, "PRC_1001 参数缺失：rule 必填");
        }
        if (isBlank(rule.name())) {
            throw new BaiException(PricingErrorCodes.PARAM_INVALID, "PRC_1001 参数缺失：rule.name 必填");
        }
        if (rule.factor() == null || !RULE_FACTORS.contains(rule.factor())) {
            throw new BaiException(PricingErrorCodes.ELEMENT_ILLEGAL,
                    "PRC_1007 定价要素非法：rule.factor 须为 评级/担保/期限/行业/客群");
        }
        if (rule.premium() == null) {
            throw new BaiException(PricingErrorCodes.PARAM_INVALID, "PRC_1001 参数缺失：rule.premium（加点 BP）必填");
        }
    }

    private RuleRecord requireRule(String ruleId) {
        if (isBlank(ruleId)) {
            throw new BaiException(PricingErrorCodes.PARAM_INVALID, "PRC_1001 参数缺失：rule.ruleId 必填");
        }
        RuleRecord rule = ruleStore.get(ruleId);
        if (rule == null) {
            throw new BaiException(PricingErrorCodes.NOT_FOUND, "PRC_1004 定价规则不存在：" + ruleId);
        }
        return rule;
    }

    // ---------------------------------------------------------------- query

    /** 试算查询（4.4，内存仿真对应 prc_quote）：按 quoteId 精确查询或按 product/customerId 过滤分页；无记录 PRC_1004 */
    public PricingDtos.QueryResult query(PricingDtos.QueryRequest req, String requestId) {
        // 入参可空：缺省查全部；lambda 需捕获 final 副本
        final PricingDtos.QueryRequest effective = req == null
                ? new PricingDtos.QueryRequest(null, null, null, null, null) : req;
        int page = effective.page() == null || effective.page() < 1 ? 1 : effective.page();
        int size = effective.size() == null ? 20 : Math.min(Math.max(effective.size(), 1), 100);
        String idemKey = requestId + ":" + sha256((effective.quoteId() == null ? "" : effective.quoteId()) + "|"
                + (effective.product() == null ? "" : effective.product()) + "|"
                + (effective.customerId() == null ? "" : effective.customerId()) + "|" + page + "|" + size);
        PricingDtos.QueryResult cached = idempotency.get("query", idemKey);
        if (cached != null) {
            return cached;
        }
        List<PricingDtos.QueryItem> matched;
        if (!isBlank(effective.quoteId())) {
            QuoteRecord r = quoteStore.get(effective.quoteId());
            if (r == null) {
                throw new BaiException(PricingErrorCodes.NOT_FOUND,
                        "PRC_1004 试算记录不存在：quoteId=" + effective.quoteId());
            }
            matched = List.of(toQueryItem(r));
        } else {
            matched = quoteStore.values().stream()
                    .filter(r -> isBlank(effective.product()) || effective.product().equals(r.product()))
                    .filter(r -> isBlank(effective.customerId()) || effective.customerId().equals(r.customerId()))
                    .sorted(Comparator.comparing(QuoteRecord::createdAt).reversed())
                    .map(this::toQueryItem)
                    .toList();
        }
        if (matched.isEmpty()) {
            throw new BaiException(PricingErrorCodes.NOT_FOUND,
                    "PRC_1004 试算记录不存在：按当前条件未查询到历史试算记录");
        }
        int total = matched.size();
        int from = Math.min((page - 1) * size, total);
        int to = Math.min(from + size, total);
        PricingDtos.QueryResult result = new PricingDtos.QueryResult(total, matched.subList(from, to));
        idempotency.put("query", idemKey, result);
        log.info("试算查询完成: requestId={}, quoteId={}, product={}, customerId={}, total={}, page={}, size={}",
                requestId, effective.quoteId(), effective.product(), effective.customerId(), total, page, size);
        return result;
    }

    private PricingDtos.QueryItem toQueryItem(QuoteRecord r) {
        return new PricingDtos.QueryItem(r.quoteId(), r.product(), r.term(), r.collateral(),
                r.finalRate(), r.fees(), r.createdAt().format(CREATED_AT));
    }

    // -------------------------------------------------------------- feedback

    /** 定价反馈受理（4.5，内存仿真对应 prc_feedback）：quoteId 须存在；humanConclusion=ACCEPT|REJECT|ADJUST；
     *  uk_quote 一单一反馈，重复受理 PRC_1005；ruleAdjust.ruleId 须为已存在规则 */
    public PricingDtos.FeedbackResult feedback(PricingDtos.FeedbackRequest req, String requestId) {
        long start = System.currentTimeMillis();
        if (req == null || isBlank(req.quoteId())) {
            throw new BaiException(PricingErrorCodes.PARAM_INVALID, "PRC_1001 参数缺失：quoteId 必填");
        }
        QuoteRecord quote = quoteStore.get(req.quoteId());
        if (quote == null) {
            throw new BaiException(PricingErrorCodes.NOT_FOUND,
                    "PRC_1004 试算记录不存在：quoteId=" + req.quoteId());
        }
        if (isBlank(req.humanConclusion()) || !HUMAN_CONCLUSIONS.contains(req.humanConclusion().toUpperCase())) {
            throw new BaiException(PricingErrorCodes.PARAM_INVALID,
                    "PRC_1001 参数非法：humanConclusion 须为 ACCEPT/REJECT/ADJUST");
        }
        if (isBlank(req.reviewer())) {
            throw new BaiException(PricingErrorCodes.PARAM_INVALID, "PRC_1001 参数缺失：reviewer 必填");
        }
        if (req.ruleAdjust() != null && !isBlank(req.ruleAdjust().ruleId())) {
            requireRule(req.ruleAdjust().ruleId());
        }
        String idemKey = requestId + ":" + sha256(req.quoteId());
        PricingDtos.FeedbackResult cached = idempotency.get("feedback", idemKey);
        if (cached != null) {
            return cached;
        }
        boolean exists = feedbackStore.values().stream().anyMatch(f -> f.quoteId().equals(req.quoteId()));
        if (exists) {
            throw new BaiException(PricingErrorCodes.DUPLICATE_CONFLICT,
                    "PRC_1005 重复提交：该试算记录已有反馈，一单一反馈不可重复受理");
        }
        String feedbackId = "FDBK" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000);
        FeedbackRecord record = new FeedbackRecord(feedbackId, req.quoteId(), req.humanConclusion().toUpperCase(),
                req.reviewer(), req.comment(),
                req.ruleAdjust() == null ? null : req.ruleAdjust().ruleId(),
                req.ruleAdjust() == null ? null : req.ruleAdjust().suggestion());
        feedbackStore.put(feedbackId, record);
        PricingDtos.FeedbackResult result = new PricingDtos.FeedbackResult(feedbackId, "ACCEPTED",
                System.currentTimeMillis() - start);
        idempotency.put("feedback", idemKey, result);
        log.info("定价反馈受理: requestId={}, feedbackId={}, quoteId={}, humanConclusion={}, reviewer={}, ruleId={}, simulation=true",
                requestId, feedbackId, req.quoteId(), record.humanConclusion(), req.reviewer(), record.ruleId());
        return result;
    }

    // --------------------------------------------------------------- internal

    /** 试算留痕（FR-08）：写内存试算记录表，供 /query 查询与 /feedback 校验；customerId 取自 params 快照 */
    private void persistQuote(String requestId, String productId, Integer term, String collateral,
                              BigDecimal finalRate, List<PricingDtos.FeeItem> fees, Map<String, Object> params) {
        Object customerId = params == null ? null : params.get("customerId");
        String quoteId = "PRCQ" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000);
        quoteStore.put(quoteId, new QuoteRecord(quoteId, requestId, productId, term, collateral,
                customerId == null ? null : String.valueOf(customerId), finalRate, fees, LocalDateTime.now()));
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
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

    /**
     * 提前还款违约金试算（定价类型 PENALTY，复用 /quote 契约）：
     * 违约金 = params.remainingPrincipal（剩余本金）× params.penaltyRate（违约金率），2 位小数（元）。
     * 幂等键与利率试算共用，同 requestId 要素冲突 PRC_1005。
     */
    private PricingDtos.QuoteResult penaltyQuote(PricingDtos.QuoteRequest req, String requestId) {
        if (req.productId() == null || req.productId().isBlank()) {
            throw new BaiException(PricingErrorCodes.NOT_FOUND, "PRC_1004 产品不存在：productId 必填");
        }
        if (req.ruleVersion() != null && !req.ruleVersion().isBlank()
                && !RULE_VERSIONS.contains(req.ruleVersion())) {
            throw new BaiException(PricingErrorCodes.NOT_FOUND,
                    "PRC_1004 定价规则不存在：ruleVersion=" + req.ruleVersion());
        }
        Map<String, Object> params = req.params() == null ? Map.of() : req.params();
        BigDecimal remainingPrincipal = toDecimal(params.get("remainingPrincipal"));
        BigDecimal penaltyRate = toDecimal(params.get("penaltyRate"));
        if (remainingPrincipal == null || remainingPrincipal.signum() <= 0) {
            throw new BaiException(PricingErrorCodes.PARAM_INVALID,
                    "PRC_1001 参数非法：params.remainingPrincipal 必填且 > 0");
        }
        if (penaltyRate == null || penaltyRate.signum() < 0) {
            throw new BaiException(PricingErrorCodes.PARAM_INVALID,
                    "PRC_1001 参数非法：params.penaltyRate 必填且 ≥ 0");
        }
        String hash = sha256("PENALTY|" + req.productId() + "|" + remainingPrincipal.toPlainString()
                + "|" + penaltyRate.toPlainString() + "|" + (req.ruleVersion() == null ? "" : req.ruleVersion()));
        String prevHash = idempotency.get("quote-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(PricingErrorCodes.DUPLICATE_CONFLICT,
                        "PRC_1005 重复提交/定价要素冲突，请使用原 requestId 查询首次试算结果");
            }
            return idempotency.get("quote", requestId);
        }
        BigDecimal penalty = remainingPrincipal.multiply(penaltyRate).setScale(2, RoundingMode.HALF_UP);
        String ruleVersion = req.ruleVersion() == null || req.ruleVersion().isBlank()
                ? DEFAULT_RULE_VERSION : req.ruleVersion();
        PricingDtos.QuoteResult result = new PricingDtos.QuoteResult("SUCCESS", req.productId(), null, null, null,
                null, List.of(new PricingDtos.FeeItem("提前还款违约金", penalty, "一次性")), ruleVersion);
        persistQuote(requestId, req.productId(), null, null, null, result.fees(), req.params());
        idempotency.put("quote-hash", requestId, hash);
        idempotency.put("quote", requestId, result);
        log.info("提前还款违约金试算: requestId={}, productId={}, remainingPrincipal={}, penaltyRate={}, penalty={}",
                requestId, req.productId(), remainingPrincipal, penaltyRate, penalty);
        return result;
    }

    /** 数值解析：Number 直转，字符串可解析则转，否则 null（→ PRC_1001） */
    private static BigDecimal toDecimal(Object v) {
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(String.valueOf(n));
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 内存规则记录（对应 prc_rule） */
    private record RuleRecord(String ruleId, String name, String factor, String condition, BigDecimal premium,
                              String effectiveFrom, String effectiveTo, int version, String status) {}

    /** 内存试算记录（对应 prc_quote） */
    private record QuoteRecord(String quoteId, String requestId, String product, Integer term, String collateral,
                               String customerId, BigDecimal finalRate, List<PricingDtos.FeeItem> fees,
                               LocalDateTime createdAt) {}

    /** 内存反馈记录（对应 prc_feedback） */
    private record FeedbackRecord(String feedbackId, String quoteId, String humanConclusion, String reviewer,
                                  String comment, String ruleId, String suggestion) {}
}
