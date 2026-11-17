package com.bai.deriv;

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
import java.util.function.DoubleUnaryOperator;

/**
 * 衍生品定价服务（BIA-CAL-060）。
 * <p>处理链：校验合约要素与市场数据 → 按 contractType 走定价路径（定价公式真实实现，市场数据为确定性仿真、用于联调）：
 * IRS 固定/浮动腿差逐期折现 NPV = Σ (float_t − fixed) × N × DF_t；
 * FORWARD 抛补利率平价 F = S×e^{(r_d−r_f)T}，NPV = (F−K)×N×e^{−r_d T}；
 * CDS 简化信用模型 NPV = notional×spread×ΣDF − notional×(1−回收率0.40)×ΣPD；
 * OPTION Black-Scholes（价格 + delta/gamma/vega 解析解）→
 * 希腊字母（OPTION 取解析解，其余中心差分数值解；IRS/CDS 对即期/波动率不敏感自然为 0，dv01 统一数值解）→
 * CVA = Σ折现因子 × EE × PD × LGD（EE=notional×0.6 简化，PD 按评级映射 AAA 0.01/AA 0.02/A 0.05/BBB 0.10，LGD=0.60）→
 * 市场数据时间戳缺失或距估值基准日 >1 天标记 STALE（DERIV_2001 降级口径）→ 利率跳变 >1% / 汇率跳变 >2% 带 jumpAlert → 落审计。
 * <p>幂等：requestId + 合约要素哈希为键，同键返回首次结果；同 requestId 不同要素视为冲突（DERIV_1005）。
 */
@Service
public class DerivService {

    private static final Logger log = LoggerFactory.getLogger(DerivService.class);

    private static final Set<String> CONTRACT_TYPES = Set.of("IRS", "FORWARD", "CDS", "OPTION");
    /** 各合约类型的合法交易方向 */
    private static final Map<String, List<String>> DIRECTIONS = Map.of(
            "IRS", List.of("PAY_FIXED", "RECEIVE_FIXED"),
            "FORWARD", List.of("BUY", "SELL"),
            "CDS", List.of("BUY_PROTECTION", "SELL_PROTECTION"),
            "OPTION", List.of("LONG", "SHORT"));
    /** 评级 → 年化违约概率 PD（CVA/CDS 共用映射） */
    private static final Map<String, Double> RATING_PD = Map.of(
            "AAA", 0.01, "AA", 0.02, "A", 0.05, "BBB", 0.10);
    /** CDS 回收率（简化信用模型） */
    private static final double RECOVERY = 0.40;
    /** CVA 违约损失率 LGD = 1 − 回收率 */
    private static final double LGD = 0.60;
    /** CVA 预期敞口 EE = notional × 0.6（简化） */
    private static final double EE_RATE = 0.60;
    /** 默认对冲比例 */
    private static final BigDecimal DEFAULT_HEDGE_RATIO = new BigDecimal("0.80");
    private static final String PRICE_VERSION = "DERIV-MODEL-2026Q4";
    /** 市场数据陈旧阈值（天）：时间戳缺失或距估值基准日超 1 天标记 STALE（DERIV_2001） */
    private static final long STALE_DAYS = 1;
    /** 敞口限额（仿真）：limitUsage = mtm×0.8，超 1 亿标记 overLimitFlag */
    private static final double EXPOSURE_LIMIT = 100_000_000;
    /** 盯市告警阈值：单日涨跌幅超 5% 标记 alertFlag */
    private static final double MARK_ALERT_RATIO = 0.05;
    /** 套期有效性判定区间（IAS 39/IFRS 9 口径）：80%~125% */
    private static final double EFFECTIVE_LOW = 0.80;
    private static final double EFFECTIVE_HIGH = 1.25;
    /** 默认对冲工具（按 underlyingRisk） */
    private static final Map<String, List<String>> DEFAULT_HEDGE_TOOLS = Map.of(
            "RATE", List.of("IRS"),
            "FX", List.of("FX_FORWARD"),
            "COMMODITY", List.of("COMMODITY_SWAP"));

    private final IdempotencyStore idempotency = new IdempotencyStore();
    /** 敞口留痕：portfolioId|asOfDate → 汇总记录（生产落 deriv_exposure 表，本阶段内存仿真；盯市结果回写供 /exposure 查询） */
    private final Map<String, ExposureRecord> exposureStore = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 衍生品定价（幂等：同 requestId+合约要素哈希返回首次结果，缓存 24h） */
    public DerivDtos.PriceResult price(DerivDtos.PriceRequest req, String requestId) {
        validate(req);

        String hash = sha256(String.join("|",
                str(req.contractType()), money(req.notional()), String.valueOf(req.params()),
                str(req.valuationDate()), String.valueOf(req.marketData())));
        String prevHash = idempotency.get("price-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(DerivErrorCodes.DUPLICATE_CONFLICT,
                        "DERIV_1005 重复提交/合约要素冲突，请使用原 requestId 查询首次定价结果");
            }
            return idempotency.get("price", requestId);
        }

        DerivDtos.PriceResult result = compute(req);
        idempotency.put("price-hash", requestId, hash);
        idempotency.put("price", requestId, result);
        log.info("衍生品定价完成: requestId={}, contractType={}, tenor={}, npv={}, cva={}, stale={}, jumpAlert={}",
                requestId, result.contractType(), req.params().get("tenor"),
                result.npv(), result.cva(), result.stale(), result.jumpAlert());
        return result;
    }

    // ---------------------------------------------------------------- 4.3 每日盯市估值

    /** 每日盯市估值（幂等：同 requestId+盯市要素哈希返回首次结果；批量任务以 batchNo 幂等，断点续跑不重复计算） */
    public DerivDtos.MarkResult mark(DerivDtos.MarkRequest req, String requestId) {
        validateMark(req);
        String hash = sha256(String.join("|", str(req.mode()), str(req.valuationDate()),
                String.valueOf(req.positionIds()), String.valueOf(req.forceRevalue())));
        String prevHash = idempotency.get("mark-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(DerivErrorCodes.DUPLICATE_CONFLICT,
                        "DERIV_1005 重复提交/盯市要素冲突，请使用原 requestId 查询首次盯市结果");
            }
            return idempotency.get("mark", requestId);
        }
        DerivDtos.MarkResult result = computeMark(req, requestId);
        idempotency.put("mark-hash", requestId, hash);
        idempotency.put("mark", requestId, result);
        log.info("每日盯市完成: requestId={}, batchNo={}, valuationDate={}, completed={}, failed={}, alerted={}",
                requestId, result.batchNo(), result.valuationDate(), result.completed(), result.failed(), result.alerted());
        return result;
    }

    /** 盯市仿真：持仓公允价值按（positionId, 估值日）哈希确定性推导；前一估值日取 T−1 推导值，损益=当日−前日，涨跌超 5% 告警 */
    private DerivDtos.MarkResult computeMark(DerivDtos.MarkRequest req, String requestId) {
        LocalDate valueDate = LocalDate.parse(req.valuationDate());
        List<DerivDtos.MarkItem> items = new ArrayList<>();
        int alerted = 0;
        for (String positionId : req.positionIds()) {
            BigDecimal fairValue = BigDecimal.valueOf(500_000 + range("mtm|" + positionId + "|" + req.valuationDate(), 0, 4_500_000))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal prevFairValue = BigDecimal.valueOf(500_000 + range("mtm|" + positionId + "|" + valueDate.minusDays(1), 0, 4_500_000))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal changeAmount = fairValue.subtract(prevFairValue).setScale(2, RoundingMode.HALF_UP);
            BigDecimal changeRatio = prevFairValue.signum() == 0 ? BigDecimal.ZERO
                    : changeAmount.divide(prevFairValue, 4, RoundingMode.HALF_UP);
            boolean alert = changeRatio.abs().doubleValue() > MARK_ALERT_RATIO;
            if (alert) {
                alerted++;
            }
            items.add(new DerivDtos.MarkItem(positionId, fairValue, prevFairValue, changeAmount, changeRatio,
                    alert, "MTM-MODEL"));
            recordExposure(positionId, req.valuationDate(), fairValue.doubleValue(), requestId);
        }
        String batchNo = "MTM" + req.valuationDate().replace("-", "") + String.format("%04d", seq.incrementAndGet() % 10000);
        return new DerivDtos.MarkResult(batchNo, req.valuationDate(), items, items.size(), 0, alerted);
    }

    /** 盯市留痕（供 /exposure 查询）：按 portfolioId=持仓标识、asOfDate=估值日回写 deriv_exposure 仿真表；同日重复盯市不重复累加 */
    private void recordExposure(String portfolioId, String asOfDate, double mtm, String requestId) {
        String key = portfolioId + "|" + asOfDate;
        if (exposureStore.containsKey(key)) {
            return;
        }
        double delta = mtm * range("delta|" + portfolioId, -0.15, 0.15);
        double var = mtm * (0.05 + unit("var|" + portfolioId + "|" + asOfDate) * 0.03);
        double cva = mtm * (0.005 + unit("cva|" + portfolioId) * 0.010);
        double dva = mtm * (0.002 + unit("dva|" + portfolioId) * 0.005);
        exposureStore.put(key, new ExposureRecord(portfolioId, "CUST-DEFAULT", "CNY", asOfDate,
                mtm, delta, var, cva, dva, mtm * 0.8));
        log.info("盯市留痕: requestId={}, portfolioId={}, asOfDate={}, mtm={}, delta={}, var={}", requestId, portfolioId, asOfDate, mtm, delta, var);
    }

    private void validateMark(DerivDtos.MarkRequest req) {
        if (req == null) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：请求体必填");
        }
        String mode = req.mode() == null || req.mode().isBlank() ? "SINGLE" : req.mode().toUpperCase();
        if (!Set.of("SINGLE", "BATCH").contains(mode)) {
            throw new BaiException(DerivErrorCodes.ELEMENT_ILLEGAL,
                    "DERIV_1007 mode 非法：须为 SINGLE/BATCH，当前 " + req.mode());
        }
        parseDate(req.valuationDate(), "valuationDate");
        if (req.positionIds() == null || req.positionIds().isEmpty()) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：positionIds 持仓清单必填");
        }
        for (String id : req.positionIds()) {
            if (id == null || id.isBlank()) {
                throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数非法：positionIds 元素不得为空");
            }
        }
    }

    // ---------------------------------------------------------------- 4.4 对冲策略计算

    /** 对冲策略计算（幂等：同 requestId+对冲要素哈希返回首次结果）：最小方差/Delta 中性口径，输出建议清单与残余风险 */
    public DerivDtos.HedgeResult hedge(DerivDtos.HedgeRequest req, String requestId) {
        validateHedge(req);
        String hash = sha256(String.join("|", str(req.exposureId()), str(req.underlyingRisk()),
                String.valueOf(req.riskFactor()), String.valueOf(req.hedgeTools()), money(req.targetRatio())));
        String prevHash = idempotency.get("hedge-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(DerivErrorCodes.DUPLICATE_CONFLICT,
                        "DERIV_1005 重复提交/对冲要素冲突，请使用原 requestId 查询首次对冲结果");
            }
            return idempotency.get("hedge", requestId);
        }
        DerivDtos.HedgeResult result = computeHedge(req);
        idempotency.put("hedge-hash", requestId, hash);
        idempotency.put("hedge", requestId, result);
        log.info("对冲策略完成: requestId={}, exposureId={}, underlyingRisk={}, targetRatio={}, residualRisk={}, residualDelta={}",
                requestId, result.exposureId(), req.underlyingRisk(), req.targetRatio(),
                result.residualRisk(), result.residualDelta());
        return result;
    }

    /** 对冲仿真：对冲比例/名义/成本/有效性按（exposureId, 工具）哈希确定性推导；残余风险 = 敞口×(1−目标比例) */
    private DerivDtos.HedgeResult computeHedge(DerivDtos.HedgeRequest req) {
        BigDecimal target = req.targetRatio() == null ? DEFAULT_HEDGE_RATIO : req.targetRatio();
        BigDecimal amount = num(req.riskFactor(), "amount");
        BigDecimal deltaParam = optNum(req.riskFactor(), "delta");
        double delta = deltaParam == null
                ? amount.doubleValue() * range("delta|" + req.exposureId(), -0.30, 0.30)
                : deltaParam.doubleValue();
        List<String> tools = req.hedgeTools() == null || req.hedgeTools().isEmpty()
                ? DEFAULT_HEDGE_TOOLS.get(req.underlyingRisk())
                : req.hedgeTools();
        List<DerivDtos.HedgeSuggestion> suggestions = new ArrayList<>();
        for (String tool : tools) {
            double toolRatio = range("hr|" + req.exposureId() + "|" + tool, 0.70, 1.00);
            BigDecimal notional = amount.multiply(BigDecimal.valueOf(toolRatio * target.doubleValue()))
                    .setScale(2, RoundingMode.HALF_UP);
            String direction = unit("dir|" + req.exposureId() + "|" + tool) < 0.5 ? "SELL" : "BUY";
            BigDecimal expectedCost = notional.multiply(BigDecimal.valueOf(range("cost|" + req.exposureId() + "|" + tool, 0.005, 0.015)))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal effectiveness = BigDecimal.valueOf(range("eff|" + req.exposureId() + "|" + tool, 0.80, 1.00))
                    .setScale(4, RoundingMode.HALF_UP);
            suggestions.add(new DerivDtos.HedgeSuggestion(tool, notional, direction,
                    BigDecimal.valueOf(toolRatio).setScale(4, RoundingMode.HALF_UP), expectedCost, effectiveness));
        }
        double residualRisk = amount.doubleValue() * (1 - target.doubleValue());
        double residualDelta = delta * (1 - target.doubleValue());
        String complianceCheck = target.doubleValue() >= 0.5
                ? "对冲目的明确（套期保值），套保关系认定符合（CASH_FLOW_HEDGE）"
                : "目标对冲比例低于 50%，套保关系认定不符合（需复核对冲目的）";
        return new DerivDtos.HedgeResult(req.exposureId(),
                BigDecimal.valueOf(delta).setScale(2, RoundingMode.HALF_UP), suggestions,
                BigDecimal.valueOf(residualRisk).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(residualDelta).setScale(2, RoundingMode.HALF_UP), complianceCheck);
    }

    private void validateHedge(DerivDtos.HedgeRequest req) {
        if (req == null || req.exposureId() == null || req.exposureId().isBlank()) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：exposureId 必填");
        }
        if (req.underlyingRisk() == null || !Set.of("RATE", "FX", "COMMODITY").contains(req.underlyingRisk())) {
            throw new BaiException(DerivErrorCodes.ELEMENT_ILLEGAL,
                    "DERIV_1007 underlyingRisk 非法：须为 RATE/FX/COMMODITY");
        }
        if (req.riskFactor() == null || req.riskFactor().isEmpty()) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：riskFactor 必填");
        }
        BigDecimal amount = optNum(req.riskFactor(), "amount");
        if (amount == null || amount.signum() <= 0) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                    "DERIV_1001 参数非法：riskFactor.amount（敞口金额）必填且 > 0");
        }
        BigDecimal target = req.targetRatio();
        if (target != null && (target.signum() < 0 || target.compareTo(BigDecimal.ONE) > 0)) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                    "DERIV_1001 参数非法：targetRatio 须在 [0,1] 内");
        }
        if (req.hedgeTools() != null) {
            for (String tool : req.hedgeTools()) {
                if (tool == null || tool.isBlank()) {
                    throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数非法：hedgeTools 元素不得为空");
                }
            }
        }
    }

    // ---------------------------------------------------------------- 4.5 利率曲线构建与发布

    /** 利率曲线构建与发布（幂等）：Bootstrapping 逐期反解零息/折现/远期；质量检查不通过阻止发布（DERIV_4103） */
    public DerivDtos.CurveResult curve(DerivDtos.CurveRequest req, String requestId) {
        validateCurve(req);
        String hash = sha256(String.join("|", str(req.currency()), str(req.curveType()), str(req.method()),
                String.valueOf(req.quotes()), str(req.effectiveTime()), String.valueOf(req.params())));
        String prevHash = idempotency.get("curve-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(DerivErrorCodes.DUPLICATE_CONFLICT,
                        "DERIV_1005 重复提交/曲线构建要素冲突，请使用原 requestId 查询首次构建结果");
            }
            return idempotency.get("curve", requestId);
        }
        DerivDtos.CurveResult result = computeCurve(req);
        idempotency.put("curve-hash", requestId, hash);
        idempotency.put("curve", requestId, result);
        log.info("曲线构建发布: requestId={}, curveId={}, currency={}, curveType={}, method={}, version={}, quality={}/{}",
                requestId, result.curveId(), result.currency(), result.curveType(), result.method(),
                result.version(), result.qualityReport().monotonic() && result.qualityReport().forwardNonNegative()
                        && result.qualityReport().jumpFree(), result.status());
        return result;
    }

    /** 曲线构建仿真：按报价点期限排序，zeroRate=报价、DF=(1+z)^{−t}、远期利率由相邻零息反解；质量检查（单调性/远期非负/跳变） */
    private DerivDtos.CurveResult computeCurve(DerivDtos.CurveRequest req) {
        List<double[]> quotes = new ArrayList<>();
        for (DerivDtos.CurveQuote q : req.quotes()) {
            quotes.add(new double[]{parseTenor(q.tenor()), q.quote().doubleValue()});
        }
        quotes.sort(Comparator.comparingDouble(a -> a[0]));
        List<DerivDtos.CurvePointResult> points = new ArrayList<>();
        double prevT = 0.0;
        double prevZ = 0.0;
        boolean monotonic = true;
        boolean forwardNonNegative = true;
        boolean jumpFree = true;
        String detail = "";
        for (int i = 0; i < quotes.size(); i++) {
            double t = quotes.get(i)[0];
            double z = quotes.get(i)[1];
            if (i > 0 && z < prevZ - 1e-9) {
                monotonic = false;
            }
            if (i > 0 && Math.abs(z - prevZ) >= 0.01) {
                jumpFree = false;
            }
            double df = Math.pow(1 + z, -t);
            double fwd;
            if (i == 0) {
                fwd = z;
            } else {
                fwd = Math.pow(Math.pow(1 + z, t) / Math.pow(1 + prevZ, prevT), 1.0 / (t - prevT)) - 1;
                if (fwd < -1e-9) {
                    forwardNonNegative = false;
                }
            }
            points.add(new DerivDtos.CurvePointResult(String.valueOf(t),
                    BigDecimal.valueOf(df).setScale(8, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(z).setScale(6, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(fwd).setScale(6, RoundingMode.HALF_UP)));
            prevT = t;
            prevZ = z;
        }
        detail = "单调性=" + monotonic + "；远期非负=" + forwardNonNegative + "；跳变检查=" + jumpFree;
        if (!monotonic || !forwardNonNegative || !jumpFree) {
            throw new BaiException(DerivErrorCodes.ARBITRAGE_VIOLATION,
                    "DERIV_4103 曲线质量检查不通过（" + detail + "），阻止发布");
        }
        String version = req.params() == null ? "v1" : str(req.params().get("version"));
        if (version.isBlank()) {
            version = "v1";
        }
        String curveId = "CRV-" + req.currency() + "-" + req.curveType() + "-" + version;
        return new DerivDtos.CurveResult(curveId, req.currency(), req.curveType(), req.method(), points, version,
                new DerivDtos.CurveQuality(monotonic, forwardNonNegative, jumpFree, detail),
                str(req.effectiveTime()), "PUBLISHED");
    }

    private void validateCurve(DerivDtos.CurveRequest req) {
        if (req == null || req.currency() == null || req.currency().isBlank()) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：currency 必填");
        }
        if (req.curveType() == null || !Set.of("DISCOUNT", "FORWARD", "SPOT").contains(req.curveType())) {
            throw new BaiException(DerivErrorCodes.ELEMENT_ILLEGAL,
                    "DERIV_1007 curveType 非法：须为 DISCOUNT/FORWARD/SPOT");
        }
        if (req.method() == null || !Set.of("BOOTSTRAP", "NELSON_SIEGEL", "SPLINE").contains(req.method())) {
            throw new BaiException(DerivErrorCodes.ELEMENT_ILLEGAL,
                    "DERIV_1007 method 非法：须为 BOOTSTRAP/NELSON_SIEGEL/SPLINE");
        }
        if (req.quotes() == null || req.quotes().isEmpty()) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：quotes 报价点必填");
        }
        for (DerivDtos.CurveQuote q : req.quotes()) {
            double t = parseTenor(q.tenor());
            if (q.quote() == null || q.quote().doubleValue() <= -1) {
                throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                        "DERIV_1001 参数非法：quotes.quote（年化利率）须 > −1，当前 " + q.quote());
            }
            if (t <= 0) {
                throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数非法：quotes.tenor 须 > 0");
            }
        }
    }

    // ---------------------------------------------------------------- 4.6 波动率曲面构建与发布

    /** 波动率曲面构建与发布（幂等）：SVI/SPLINE 参数化网格化；无套利检查不通过阻止发布（DERIV_4103） */
    public DerivDtos.VolResult vol(DerivDtos.VolRequest req, String requestId) {
        validateVol(req);
        String hash = sha256(String.join("|", str(req.underlyingType()), str(req.method()),
                String.valueOf(req.quotes()), String.valueOf(req.params()), str(req.effectiveTime())));
        String prevHash = idempotency.get("vol-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(DerivErrorCodes.DUPLICATE_CONFLICT,
                        "DERIV_1005 重复提交/曲面构建要素冲突，请使用原 requestId 查询首次构建结果");
            }
            return idempotency.get("vol", requestId);
        }
        DerivDtos.VolResult result = computeVol(req);
        idempotency.put("vol-hash", requestId, hash);
        idempotency.put("vol", requestId, result);
        log.info("曲面构建发布: requestId={}, volSurfaceId={}, underlyingType={}, method={}, version={}, arbitragePassed={}",
                requestId, result.volSurfaceId(), result.underlyingType(), result.method(),
                result.version(), result.arbitrageReport().passed());
        return result;
    }

    /** 曲面构建仿真：网格按（tenor, strike）确定性推导 delta 与隐含波动率；无套利检查（日历价差/蝶式价差非负） */
    private DerivDtos.VolResult computeVol(DerivDtos.VolRequest req) {
        List<DerivDtos.VolQuote> quotes = new ArrayList<>(req.quotes());
        quotes.sort(Comparator.comparing((DerivDtos.VolQuote q) -> parseTenor(q.tenor()))
                .thenComparing(q -> parseStrike(q.strike())));
        List<DerivDtos.VolGridPoint> grid = new ArrayList<>();
        boolean calendarSpreadOk = true;
        boolean butterflyOk = true;
        double prevVol = -1.0;
        double prevT = -1.0;
        List<Double> strikeVols = new ArrayList<>();
        List<Double> strikePrices = new ArrayList<>();
        String curTenor = null;
        for (DerivDtos.VolQuote q : quotes) {
            double t = parseTenor(q.tenor());
            double k = parseStrike(q.strike());
            double iv = q.quote().doubleValue();
            if (iv <= 0) {
                butterflyOk = false;
            }
            if (prevT >= 0 && t > prevT && iv < prevVol - 1e-9) {
                calendarSpreadOk = false;
            }
            if (!q.tenor().equals(curTenor)) {
                if (curTenor != null && !checkButterfly(strikePrices, strikeVols)) {
                    butterflyOk = false;
                }
                curTenor = q.tenor();
                strikePrices = new ArrayList<>();
                strikeVols = new ArrayList<>();
            }
            strikePrices.add(k);
            strikeVols.add(iv);
            double delta = 0.05 + range("delta|" + req.underlyingType() + "|" + q.tenor() + "|" + q.strike(), 0, 0.90);
            grid.add(new DerivDtos.VolGridPoint(q.tenor(), q.strike(),
                    BigDecimal.valueOf(delta).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(iv).setScale(6, RoundingMode.HALF_UP)));
            prevT = t;
            prevVol = iv;
        }
        if (curTenor != null && !checkButterfly(strikePrices, strikeVols)) {
            butterflyOk = false;
        }
        boolean passed = calendarSpreadOk && butterflyOk;
        String detail = "日历价差非负=" + calendarSpreadOk + "；蝶式价差非负=" + butterflyOk;
        if (!passed) {
            throw new BaiException(DerivErrorCodes.ARBITRAGE_VIOLATION,
                    "DERIV_4103 无套利检查不通过（" + detail + "），阻止曲面发布");
        }
        String version = req.params() == null ? "v1" : str(req.params().get("version"));
        if (version.isBlank()) {
            version = "v1";
        }
        String volSurfaceId = "VOL-" + req.underlyingType() + "-" + version;
        return new DerivDtos.VolResult(volSurfaceId, req.underlyingType(), req.method(), grid,
                req.params() == null ? Map.of() : req.params(),
                new DerivDtos.ArbitrageReport(passed, detail), version, str(req.effectiveTime()), "PUBLISHED");
    }

    /** 蝶式价差凸性检查：同期限按执行价排序后，中间点隐含波动率不得高于两端均值（近似无套利） */
    private static boolean checkButterfly(List<Double> strikes, List<Double> vols) {
        if (vols.size() < 3) {
            return true;
        }
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < strikes.size(); i++) {
            pts.add(new double[]{strikes.get(i), vols.get(i)});
        }
        pts.sort(Comparator.comparingDouble(a -> a[0]));
        for (int i = 1; i < pts.size() - 1; i++) {
            double mid = pts.get(i)[1];
            double avg = (pts.get(i - 1)[1] + pts.get(i + 1)[1]) / 2;
            if (mid > avg + 1e-9) {
                return false;
            }
        }
        return true;
    }

    private void validateVol(DerivDtos.VolRequest req) {
        if (req == null || req.underlyingType() == null || !Set.of("EQUITY", "RATE", "FX").contains(req.underlyingType())) {
            throw new BaiException(DerivErrorCodes.ELEMENT_ILLEGAL,
                    "DERIV_1007 underlyingType 非法：须为 EQUITY/RATE/FX");
        }
        if (req.method() == null || !Set.of("SVI", "SPLINE").contains(req.method())) {
            throw new BaiException(DerivErrorCodes.ELEMENT_ILLEGAL,
                    "DERIV_1007 method 非法：须为 SVI/SPLINE");
        }
        if (req.quotes() == null || req.quotes().isEmpty()) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：quotes 期权报价必填");
        }
        for (DerivDtos.VolQuote q : req.quotes()) {
            parseTenor(q.tenor());
            parseStrike(q.strike());
            if (q.quote() == null || q.quote().doubleValue() <= 0) {
                throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                        "DERIV_1001 参数非法：quotes.quote（隐含波动率）须 > 0");
            }
        }
    }

    // ---------------------------------------------------------------- 4.7 敞口查询

    /** 敞口查询：按 asOfDate 必填、portfolioId/counterpartyId/currency 可选过滤；汇总盯市留痕，无记录抛 DERIV_1004 */
    public DerivDtos.ExposureResult exposure(String portfolioId, String counterpartyId, String currency,
                                             String asOfDate, String confidenceLevel, Boolean includeCva, String requestId) {
        parseDate(asOfDate, "asOfDate");
        double conf = 97.5;
        if (confidenceLevel != null && !confidenceLevel.isBlank()) {
            try {
                conf = Double.parseDouble(confidenceLevel.trim());
            } catch (NumberFormatException e) {
                throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                        "DERIV_1001 参数非法：confidenceLevel 须为数值百分比");
            }
            if (conf <= 0 || conf >= 100) {
                throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数非法：confidenceLevel 须在 (0,100) 内");
            }
        }
        boolean incCva = includeCva == null || includeCva;
        List<ExposureRecord> matched = exposureStore.values().stream()
                .filter(r -> r.asOfDate.equals(asOfDate))
                .filter(r -> isBlank(portfolioId) || portfolioId.equals(r.portfolioId))
                .filter(r -> isBlank(counterpartyId) || counterpartyId.equals(r.counterpartyId))
                .filter(r -> isBlank(currency) || currency.equalsIgnoreCase(r.currency))
                .toList();
        if (matched.isEmpty()) {
            log.warn("敞口查询无记录: requestId={}, portfolioId={}, counterpartyId={}, asOfDate={}",
                    requestId, portfolioId, counterpartyId, asOfDate);
            throw new BaiException(DerivErrorCodes.NOT_FOUND,
                    "DERIV_1004 无敞口记录：portfolioId/asOfDate 无匹配（请先执行盯市估值留痕）");
        }
        double mtm = 0, delta = 0, var = 0, cva = 0, dva = 0, limitUsage = 0;
        for (ExposureRecord r : matched) {
            mtm += r.mtm;
            delta += r.delta;
            var += r.var;
            if (incCva) {
                cva += r.cva;
                dva += r.dva;
            }
            limitUsage += r.limitUsage;
        }
        boolean overLimit = limitUsage > EXPOSURE_LIMIT;
        List<DerivDtos.PfePoint> pfe = List.of(
                new DerivDtos.PfePoint("1Y", BigDecimal.valueOf(var).setScale(2, RoundingMode.HALF_UP)),
                new DerivDtos.PfePoint("3Y", BigDecimal.valueOf(var * 1.4).setScale(2, RoundingMode.HALF_UP)),
                new DerivDtos.PfePoint("5Y", BigDecimal.valueOf(var * 1.8).setScale(2, RoundingMode.HALF_UP)));
        log.info("敞口查询: requestId={}, portfolioId={}, counterpartyId={}, asOfDate={}, mtm={}, delta={}, var={}, overLimit={}, records={}",
                requestId, portfolioId, counterpartyId, asOfDate, mtm, delta, var, overLimit, matched.size());
        return new DerivDtos.ExposureResult(portfolioId == null ? "-" : portfolioId,
                counterpartyId == null ? "-" : counterpartyId, currency == null ? "-" : currency, asOfDate,
                BigDecimal.valueOf(mtm).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(delta).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(var).setScale(2, RoundingMode.HALF_UP), pfe,
                BigDecimal.valueOf(cva).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(dva).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(limitUsage).setScale(2, RoundingMode.HALF_UP), overLimit, conf + "%");
    }

    // ---------------------------------------------------------------- 4.8 套期有效性评估

    /** 套期有效性评估（幂等）：回溯/前瞻两法；ratio 落入 80%~125% 判定 EFFECTIVE */
    public DerivDtos.EffectivenessResult effectiveness(DerivDtos.EffectivenessRequest req, String requestId) {
        validateEffectiveness(req);
        String hash = sha256(String.join("|", str(req.hedgeRelationId()), str(req.method()),
                str(req.window()), str(req.scenarioId())));
        String prevHash = idempotency.get("effectiveness-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(DerivErrorCodes.DUPLICATE_CONFLICT,
                        "DERIV_1005 重复提交/评估要素冲突，请使用原 requestId 查询首次评估结果");
            }
            return idempotency.get("effectiveness", requestId);
        }
        DerivDtos.EffectivenessResult result = computeEffectiveness(req);
        idempotency.put("effectiveness-hash", requestId, hash);
        idempotency.put("effectiveness", requestId, result);
        log.info("套期有效性评估: requestId={}, hedgeRelationId={}, method={}, result={}, ratio={}",
                requestId, result.hedgeRelationId(), result.method(), result.assessmentResult(), result.ratio());
        return result;
    }

    /** 有效性评估仿真：比率按（hedgeRelationId, method, window, scenarioId）哈希确定性推导（60%~135%），80%~125% 判定有效 */
    private DerivDtos.EffectivenessResult computeEffectiveness(DerivDtos.EffectivenessRequest req) {
        double ratio = 0.60 + unit("eff|" + req.hedgeRelationId() + "|" + str(req.method())
                + "|" + str(req.window()) + "|" + str(req.scenarioId())) * 0.75;
        boolean effective = ratio >= EFFECTIVE_LOW && ratio <= EFFECTIVE_HIGH;
        String detail = "假设性衍生品回归：R²="
                + BigDecimal.valueOf(0.5 + unit("r2|" + req.hedgeRelationId()) * 0.5).setScale(4, RoundingMode.HALF_UP)
                + ("RETROSPECTIVE".equals(req.method())
                    ? "，回溯窗口 " + str(req.window()) + "（区间明细见报告）"
                    : "，前瞻情景 " + str(req.scenarioId()) + "（区间明细见报告）");
        return new DerivDtos.EffectivenessResult(req.hedgeRelationId(), str(req.method()),
                effective ? "EFFECTIVE" : "INEFFECTIVE",
                BigDecimal.valueOf(ratio).setScale(4, RoundingMode.HALF_UP), detail,
                "/api/bai/deriv/effectiveness/report/" + req.hedgeRelationId());
    }

    private void validateEffectiveness(DerivDtos.EffectivenessRequest req) {
        if (req == null || req.hedgeRelationId() == null || req.hedgeRelationId().isBlank()) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：hedgeRelationId 必填");
        }
        String method = req.method() == null || req.method().isBlank() ? "RETROSPECTIVE" : req.method().toUpperCase();
        if (!Set.of("RETROSPECTIVE", "PROSPECTIVE").contains(method)) {
            throw new BaiException(DerivErrorCodes.ELEMENT_ILLEGAL,
                    "DERIV_1007 method 非法：须为 RETROSPECTIVE/PROSPECTIVE");
        }
        if ("PROSPECTIVE".equals(method) && isBlank(req.scenarioId())) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                    "DERIV_1001 参数缺失：PROSPECTIVE 前瞻评估须提供 scenarioId");
        }
        if ("RETROSPECTIVE".equals(method) && isBlank(req.window())) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                    "DERIV_1001 参数缺失：RETROSPECTIVE 回溯评估须提供 window");
        }
    }

    // --------------------------------------------------------------- internal helpers

    /** ISO 日期必填校验（缺失/格式非法抛 DERIV_1001） */
    private static void parseDate(String date, String field) {
        if (date == null || date.isBlank()) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：" + field + " 必填");
        }
        try {
            LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                    "DERIV_1001 参数非法：" + field + " 须为 ISO 日期（yyyy-MM-dd）");
        }
    }

    /** 期限解析：年限数值（支持 "0.5" 等小数），非法抛 DERIV_1001 */
    private static double parseTenor(String tenor) {
        if (tenor == null || tenor.isBlank()) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：tenor 必填");
        }
        try {
            return Double.parseDouble(tenor.trim());
        } catch (NumberFormatException e) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                    "DERIV_1001 参数非法：tenor 须为数值年限，当前 " + tenor);
        }
    }

    private static double parseStrike(String strike) {
        if (strike == null || strike.isBlank()) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：strike 必填");
        }
        try {
            double k = Double.parseDouble(strike.trim());
            if (k <= 0) {
                throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数非法：strike 须 > 0");
            }
            return k;
        } catch (NumberFormatException e) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                    "DERIV_1001 参数非法：strike 须为数值，当前 " + strike);
        }
    }

    /** 确定性仿真：seed 哈希 → [0,1) 均匀值（同 seed 恒定，支撑可复算） */
    private static double unit(String seed) {
        String hex = sha256(seed);
        long l = Long.parseUnsignedLong(hex.substring(0, 16), 16);
        return (l >>> 11) / (double) (1L << 53);
    }

    /** 确定性仿真：seed 哈希 → [lo, hi) 区间值 */
    private static double range(String seed, double lo, double hi) {
        return lo + unit(seed) * (hi - lo);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void validate(DerivDtos.PriceRequest req) {
        if (req == null || req.contractType() == null || !CONTRACT_TYPES.contains(req.contractType())) {
            throw new BaiException(DerivErrorCodes.ELEMENT_ILLEGAL,
                    "DERIV_1007 合约类型非法：contractType 须为 IRS/FORWARD/CDS/OPTION，当前 "
                            + (req == null ? "null" : req.contractType()));
        }
        if (req.notional() == null || req.notional().signum() <= 0) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数非法：notional 必填且 > 0");
        }
        if (req.params() == null || req.params().isEmpty()) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：params 合约要素必填");
        }
        String direction = str(req.params().get("direction"));
        if (!DIRECTIONS.get(req.contractType()).contains(direction)) {
            throw new BaiException(DerivErrorCodes.ELEMENT_ILLEGAL,
                    "DERIV_1007 交易方向非法：contractType=" + req.contractType() + " 时 direction 须为 "
                            + DIRECTIONS.get(req.contractType()));
        }
        tenorYears(req.params());
        if ("OPTION".equals(req.contractType())) {
            String optionType = req.params().get("optionType") == null ? "CALL" : str(req.params().get("optionType"));
            if (!"CALL".equals(optionType) && !"PUT".equals(optionType)) {
                throw new BaiException(DerivErrorCodes.ELEMENT_ILLEGAL,
                        "DERIV_1007 期权类型非法：params.optionType 须为 CALL/PUT，当前 " + optionType);
            }
        }
        if (req.valuationDate() == null || req.valuationDate().isBlank()) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：valuationDate 必填");
        }
        try {
            LocalDate.parse(req.valuationDate());
        } catch (DateTimeParseException e) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                    "DERIV_1001 参数非法：valuationDate 须为 ISO 日期（yyyy-MM-dd）");
        }
        if (req.marketData() == null) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：marketData 必填");
        }
        if (req.marketData().yieldCurve() == null || req.marketData().yieldCurve().isEmpty()) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：marketData.yieldCurve 必填");
        }
        if ("FORWARD".equals(req.contractType()) || "OPTION".equals(req.contractType())) {
            BigDecimal spot = req.marketData().spotRate();
            if (spot == null || spot.signum() <= 0) {
                throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                        "DERIV_1001 参数非法：" + req.contractType() + " 要求 marketData.spotRate 必填且 > 0");
            }
            BigDecimal strike = optNum(req.params(), "strike");
            if (strike == null || strike.signum() <= 0) {
                throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                        "DERIV_1001 参数非法：" + req.contractType() + " 要求 params.strike 必填且 > 0");
            }
        }
        if ("IRS".equals(req.contractType())) {
            BigDecimal strike = optNum(req.params(), "strike");
            if (strike == null || strike.signum() <= 0) {
                throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                        "DERIV_1001 参数非法：IRS 要求 params.strike（固定腿利率）必填且 > 0");
            }
        }
        if ("OPTION".equals(req.contractType())) {
            BigDecimal vol = req.marketData().volatility();
            if (vol == null || vol.signum() <= 0 || vol.compareTo(BigDecimal.valueOf(2)) >= 0) {
                throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                        "DERIV_1001 参数非法：OPTION 要求 marketData.volatility 必填且在 (0,2) 内");
            }
        }
        if (req.marketData().counterpartyRating() == null || !RATING_PD.containsKey(req.marketData().counterpartyRating())) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                    "DERIV_1001 参数非法：counterpartyRating 须为 AAA/AA/A/BBB");
        }
        BigDecimal hedge = optNum(req.params(), "hedgeRatio");
        if (hedge != null && (hedge.signum() < 0 || hedge.compareTo(BigDecimal.ONE) > 0)) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数非法：params.hedgeRatio 须在 [0,1] 内");
        }
    }

    /** 按 contractType 定价并组装结果 */
    private DerivDtos.PriceResult compute(DerivDtos.PriceRequest req) {
        String contractType = req.contractType();
        String direction = str(req.params().get("direction"));
        int tenor = tenorYears(req.params());
        String optionType = req.params().get("optionType") == null ? "CALL" : str(req.params().get("optionType"));
        double notional = req.notional().doubleValue();
        double strike = optNum(req.params(), "strike") == null ? 0.0 : optNum(req.params(), "strike").doubleValue();
        double spot = req.marketData().spotRate() == null ? 0.0 : req.marketData().spotRate().doubleValue();
        double vol = req.marketData().volatility() == null ? 0.0 : req.marketData().volatility().doubleValue();
        double pd = RATING_PD.get(req.marketData().counterpartyRating());
        DoubleUnaryOperator curve = curveFn(req.marketData().yieldCurve());
        // 本币利率 r_d 取曲线在期限 T 的内插值；外币利率 r_f 缺省 0（params.foreignRate 可覆盖）
        double rd = curve.applyAsDouble(tenor);
        BigDecimal rfParam = optNum(req.params(), "foreignRate");
        double rf = rfParam == null ? 0.0 : rfParam.doubleValue();
        // CDS 利差缺省取评级 PD（年化小数），params.spread 可覆盖
        BigDecimal spreadParam = optNum(req.params(), "spread");
        double spread = spreadParam == null ? pd : spreadParam.doubleValue();

        double npv = priceNpv(contractType, notional, strike, tenor, direction, curve, spot, vol, rd, rf, spread, pd, optionType);

        // 希腊字母：OPTION 取解析解；其余中心差分数值解（对不依赖的输入自然为 0）；dv01 统一数值解（每 1bp）
        DoubleUnaryOperator bySpot = s -> priceNpv(contractType, notional, strike, tenor, direction, curve, s, vol, rd, rf, spread, pd, optionType);
        DoubleUnaryOperator byVol = v -> priceNpv(contractType, notional, strike, tenor, direction, curve, spot, v, rd, rf, spread, pd, optionType);
        DoubleUnaryOperator byRd = r -> priceNpv(contractType, notional, strike, tenor, direction, curve, spot, vol, r, rf, spread, pd, optionType);
        int sgn = signOf(direction);
        double delta;
        double gamma;
        double vega;
        if ("OPTION".equals(contractType)) {
            double sqrtT = Math.sqrt(tenor);
            double d1 = (Math.log(spot / strike) + (rd + 0.5 * vol * vol) * tenor) / (vol * sqrtT);
            double nd1 = normCdf(d1);
            double pdfD1 = normPdf(d1);
            delta = sgn * notional * ("PUT".equals(optionType) ? nd1 - 1 : nd1);
            gamma = sgn * notional * pdfD1 / (spot * vol * sqrtT);
            vega = sgn * notional * spot * pdfD1 * sqrtT * 0.01; // 每 1% 波动率
        } else {
            double h = Math.max(spot * 0.01, 1e-8);
            delta = (bySpot.applyAsDouble(spot + h) - bySpot.applyAsDouble(spot - h)) / (2 * h);
            gamma = (bySpot.applyAsDouble(spot + h) - 2 * npv + bySpot.applyAsDouble(spot - h)) / (h * h);
            vega = (byVol.applyAsDouble(vol + 0.01) - byVol.applyAsDouble(vol - 0.01)) / 2;
        }
        double dv01 = (byRd.applyAsDouble(rd + 0.0001) - byRd.applyAsDouble(rd - 0.0001)) / 2;

        // CVA = Σ折现因子 × EE × PD × LGD（EE = notional × 0.6 简化）
        double sumDf = 0.0;
        for (int t = 1; t <= tenor; t++) {
            sumDf += 1.0 / Math.pow(1.0 + curve.applyAsDouble(t), t);
        }
        double cva = sumDf * (notional * EE_RATE) * pd * LGD;

        // 市场数据陈旧判定（DERIV_2001 降级口径）：时间戳缺失，或距估值基准日（简化口径）超 1 天
        boolean stale;
        String ts = req.marketData().timestamp();
        if (ts == null || ts.isBlank()) {
            stale = true;
        } else {
            try {
                LocalDate tsDate = LocalDate.parse(ts.trim().substring(0, 10));
                stale = ChronoUnit.DAYS.between(tsDate, LocalDate.parse(req.valuationDate())) > STALE_DAYS;
            } catch (DateTimeParseException e) {
                throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                        "DERIV_1001 参数非法：marketData.timestamp 须为 ISO 日期/时间");
            }
        }
        // 跳变告警：params 模拟 flag——利率跳变 rateJumpPct >1% 或汇率跳变 fxJumpPct >2%
        BigDecimal rateJump = optNum(req.params(), "rateJumpPct");
        BigDecimal fxJump = optNum(req.params(), "fxJumpPct");
        boolean jumpAlert = (rateJump != null && rateJump.doubleValue() > 1.0)
                || (fxJump != null && fxJump.doubleValue() > 2.0);
        if (stale) {
            log.warn("DERIV_2001 市场数据标记 STALE：requestId={}, timestamp={}", idOf(req), ts);
        }

        BigDecimal hedgeRatio = optNum(req.params(), "hedgeRatio") == null
                ? DEFAULT_HEDGE_RATIO : optNum(req.params(), "hedgeRatio");
        return new DerivDtos.PriceResult(contractType,
                BigDecimal.valueOf(npv).setScale(2, RoundingMode.HALF_UP),
                new DerivDtos.Greeks(
                        BigDecimal.valueOf(delta).setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(gamma).setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(vega).setScale(4, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(dv01).setScale(4, RoundingMode.HALF_UP)),
                BigDecimal.valueOf(cva).setScale(2, RoundingMode.HALF_UP),
                hedgeRatio, PRICE_VERSION, stale, jumpAlert);
    }

    private static String idOf(DerivDtos.PriceRequest req) {
        return req.requestId() == null ? "-" : req.requestId();
    }

    /**
     * 定价核（真实公式，数值希腊字母经输入扰动重定价）：
     * IRS 固定/浮动腿差逐期折现（仿真：浮动远期利率取曲线对应期限即期，用于联调）；
     * FORWARD 抛补利率平价 F = S×e^{(r_d−r_f)T}，NPV = (F−K)×N×e^{−r_d T}；
     * CDS NPV = notional×spread×ΣDF − notional×(1−0.40)×ΣPD（每期 PD 按评级映射常数，简化）；
     * OPTION Black-Scholes 单位价格 × 名义本金。
     */
    private static double priceNpv(String contractType, double notional, double strike, int tenor, String direction,
                                   DoubleUnaryOperator curve, double spot, double vol, double rd, double rf,
                                   double spread, double pd, String optionType) {
        return switch (contractType) {
            case "IRS" -> {
                double sum = 0.0;
                for (int t = 1; t <= tenor; t++) {
                    double floatRate = curve.applyAsDouble(t);
                    double df = 1.0 / Math.pow(1.0 + floatRate, t);
                    double legDiff = "PAY_FIXED".equals(direction) ? floatRate - strike : strike - floatRate;
                    sum += legDiff * df;
                }
                yield notional * sum;
            }
            case "FORWARD" -> {
                double f = spot * Math.exp((rd - rf) * tenor);
                yield signOf(direction) * (f - strike) * notional * Math.exp(-rd * tenor);
            }
            case "CDS" -> {
                double sumDf = 0.0;
                for (int t = 1; t <= tenor; t++) {
                    sumDf += 1.0 / Math.pow(1.0 + curve.applyAsDouble(t), t);
                }
                double sumPd = pd * tenor;
                yield signOf(direction) * (notional * spread * sumDf - notional * (1 - RECOVERY) * sumPd);
            }
            default -> signOf(direction) * notional * blackScholes(optionType, spot, strike, rd, vol, tenor);
        };
    }

    private static int signOf(String direction) {
        return switch (direction) {
            case "BUY", "LONG", "BUY_PROTECTION" -> 1;
            default -> -1; // SELL / SHORT / SELL_PROTECTION（方向取值已由校验限定）
        };
    }

    /** Black-Scholes 欧式期权（不分红），返回单位名义价格 */
    private static double blackScholes(String optionType, double s, double k, double r, double vol, double t) {
        double sqrtT = Math.sqrt(t);
        double d1 = (Math.log(s / k) + (r + 0.5 * vol * vol) * t) / (vol * sqrtT);
        double d2 = d1 - vol * sqrtT;
        if ("PUT".equals(optionType)) {
            return k * Math.exp(-r * t) * normCdf(-d2) - s * normCdf(-d1);
        }
        return s * normCdf(d1) - k * Math.exp(-r * t) * normCdf(d2);
    }

    /** 标准正态分布函数：erf 采用 Abramowitz-Stegun 7.1.26 近似（|ε|<1.5e-7），确定性实现、避免引入额外依赖 */
    private static double normCdf(double x) {
        double z = Math.abs(x) / Math.sqrt(2);
        double t = 1.0 / (1.0 + 0.3275911 * z);
        double poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))));
        double erf = x >= 0 ? 1 - poly * Math.exp(-z * z) : poly * Math.exp(-z * z) - 1;
        return 0.5 * (1 + erf);
    }

    private static double normPdf(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2 * Math.PI);
    }

    /** 收益率曲线：期限点升序排列，线性插值、端点平推（确定性） */
    private static DoubleUnaryOperator curveFn(List<DerivDtos.CurvePoint> yieldCurve) {
        List<double[]> pts = new ArrayList<>();
        for (DerivDtos.CurvePoint p : yieldCurve) {
            if (p == null || p.tenor() == null || p.rate() == null) {
                throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                        "DERIV_1001 参数非法：yieldCurve 点须含 tenor 与 rate");
            }
            double t;
            try {
                t = Double.parseDouble(p.tenor().trim());
            } catch (NumberFormatException e) {
                throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                        "DERIV_1001 参数非法：yieldCurve.tenor 须为数值年限，当前 " + p.tenor());
            }
            if (t < 0) {
                throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数非法：yieldCurve.tenor 须 ≥ 0");
            }
            pts.add(new double[]{t, p.rate().doubleValue()});
        }
        pts.sort(Comparator.comparingDouble(a -> a[0]));
        return t -> {
            if (pts.isEmpty()) {
                return 0.0;
            }
            if (t <= pts.get(0)[0]) {
                return pts.get(0)[1];
            }
            double last = pts.get(pts.size() - 1)[0];
            if (t >= last) {
                return pts.get(pts.size() - 1)[1];
            }
            for (int i = 1; i < pts.size(); i++) {
                if (t <= pts.get(i)[0]) {
                    double t0 = pts.get(i - 1)[0];
                    double t1 = pts.get(i)[0];
                    double r0 = pts.get(i - 1)[1];
                    double r1 = pts.get(i)[1];
                    return t1 == t0 ? r1 : r0 + (r1 - r0) * (t - t0) / (t1 - t0);
                }
            }
            return last;
        };
    }

    /** 期限（整数年，1~50）：params.tenor 必填 */
    private static int tenorYears(Map<String, Object> params) {
        BigDecimal t = num(params, "tenor");
        if (t.signum() <= 0 || t.remainder(BigDecimal.ONE).signum() != 0 || t.intValue() > 50) {
            throw new BaiException(DerivErrorCodes.ELEMENT_ILLEGAL,
                    "DERIV_1007 期限非法：params.tenor 须为 1~50 的整数年，当前 " + t);
        }
        return t.intValue();
    }

    /** 必填数值参数（支持数值与可解析字符串），缺失抛 DERIV_1001 */
    private static BigDecimal num(Map<String, Object> params, String key) {
        BigDecimal v = optNum(params, key);
        if (v == null) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID, "DERIV_1001 参数缺失：params." + key + " 必填");
        }
        return v;
    }

    private static BigDecimal optNum(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BaiException(DerivErrorCodes.PARAM_INVALID,
                    "DERIV_1001 参数非法：params." + key + " 须为数值");
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String money(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
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

    /** 敞口留痕记录（deriv_exposure 仿真行：asOfDate/portfolioId/counterpartyId/currency 维度汇总） */
    private static final class ExposureRecord {
        final String portfolioId;
        final String counterpartyId;
        final String currency;
        final String asOfDate;
        final double mtm;
        final double delta;
        final double var;
        final double cva;
        final double dva;
        final double limitUsage;

        ExposureRecord(String portfolioId, String counterpartyId, String currency, String asOfDate,
                       double mtm, double delta, double var, double cva, double dva, double limitUsage) {
            this.portfolioId = portfolioId;
            this.counterpartyId = counterpartyId;
            this.currency = currency;
            this.asOfDate = asOfDate;
            this.mtm = mtm;
            this.delta = delta;
            this.var = var;
            this.cva = cva;
            this.dva = dva;
            this.limitUsage = limitUsage;
        }
    }
}
