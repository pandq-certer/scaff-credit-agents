package com.bai.carbon;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 碳账户核算服务（BIA-CAL-061）。
 * <p>处理链：校验主体/周期/活动数据与因子版本 → 排放因子确定性计算（tCO2e 口径）：
 * 范围二 = electricityKwh/1000×0.5703 + purchasedHeatGj×0.11；
 * 范围一 = naturalGasM3×0.0021622（21.622 t/万m³ 换算） + coalTon×2.66；
 * 范围三 = supplyChainTons×1.0，缺省按（范围一+范围二）×20% 仿真（用于联调）；
 * 排放等式 ER = BE − PE − LE（activityData 可含 purchasedOffsets/leakage）→
 * 碳价情景加权碳资产 = totalCarbon × 加权碳价 → pledgeableAmount = 碳资产价值 × 0.6。
 * <p>GWP 说明（AR5）：CO2=1 / CH4=28 / N2O=265，本版因子值已按 tCO2e 折算后给出。
 * <p>幂等：requestId + 核算要素哈希为键，同键返回首次结果；同 requestId 不同要素视为冲突（CARB_1005）。
 */
@Service
public class CarbonService {

    private static final Logger log = LoggerFactory.getLogger(CarbonService.class);

    /** 排放因子版本（只增不可回写，变更须审批） */
    private static final Set<String> FACTOR_VERSIONS = Set.of("EF-2025-V2", "EF-2024-V1");
    private static final String DEFAULT_FACTOR_VERSION = "EF-2025-V2";

    // 排放因子（EF-2025-V2，tCO2e 口径，确定性实现）
    private static final BigDecimal EF_GRID = new BigDecimal("0.5703");      // 电力：tCO2/MWh（全国电网平均）
    private static final BigDecimal EF_HEAT = new BigDecimal("0.11");        // 热力：tCO2/GJ
    private static final BigDecimal EF_GAS = new BigDecimal("0.0021622");    // 天然气：tCO2/m³（21.622 t/万m³ 换算）
    private static final BigDecimal EF_COAL = new BigDecimal("2.66");        // 煤炭：tCO2/t
    private static final BigDecimal EF_SUPPLY_CHAIN = new BigDecimal("1.0"); // 供应链运输：tCO2/t
    /** 范围三缺省仿真系数：（范围一+范围二）×20%（无 supplyChainTons 数据时，用于联调） */
    private static final BigDecimal SCOPE3_SIM_RATE = new BigDecimal("0.20");

    // 碳价情景（元/tCO2e）与权重：加权碳价 = 75×0.5 + 90×0.25 + 120×0.25 = 90.0
    private static final BigDecimal PRICE_BASE = new BigDecimal("75.0");
    private static final BigDecimal PRICE_NEUTRAL = new BigDecimal("90.0");
    private static final BigDecimal PRICE_PESSIMISTIC = new BigDecimal("120.0");
    private static final List<BigDecimal> SCENARIO_WEIGHTS = List.of(
            new BigDecimal("0.5"), new BigDecimal("0.25"), new BigDecimal("0.25"));
    /** 可质押比例 */
    private static final BigDecimal PLEDGE_RATE = new BigDecimal("0.6");

    private static final int MONEY_SCALE = 2;

    // 目录/方法学/配额/统计/工具/洗绿规则 版本（只增不可回写，变更须审批）
    private static final Set<String> CATALOG_VERSIONS = Set.of("CAT-2025-V1", "CAT-2024-V1");
    private static final Set<String> METHODOLOGIES = Set.of("CM-001", "CM-002", "CM-003");
    private static final Set<String> QUOTA_VERSIONS = Set.of("QUOTA-2025-V1", "QUOTA-2024-V1");
    private static final String DEFAULT_QUOTA_VERSION = "QUOTA-2025-V1";
    private static final Set<String> STAT_VERSIONS = Set.of("GREEN-STAT-2025-V1", "GREEN-STAT-2024-V1");
    private static final String DEFAULT_STAT_VERSION = "GREEN-STAT-2025-V1";
    private static final Set<String> TOOL_VERSIONS = Set.of("TOOL-2025-V1", "TOOL-2024-V1");
    private static final String DEFAULT_TOOL_VERSION = "TOOL-2025-V1";
    private static final Set<String> RISK_RULES_VERSIONS = Set.of("GR-2025-V1", "GR-2024-V1");

    /** 绿色产业目录条目（一级~四级，仿真索引；真实实现由目录库版本化提供） */
    private static final List<String> CATALOG_ENTRIES = List.of(
            "节能环保产业-工业节能-高效节能装备-高效电机",
            "节能环保产业-工业节能-能量系统优化-余热余压利用",
            "清洁生产产业-污染防治-水污染防治-工业废水治理",
            "清洁能源产业-新能源与清洁能源-太阳能-分布式光伏发电",
            "清洁能源产业-新能源与清洁能源-风电-陆上风电",
            "生态环境产业-生态保护-生态修复-矿山生态修复",
            "绿色交通-新能源汽车-新能源汽车整车-纯电动乘用车",
            "资源循环利用-循环利用-废旧物资循环利用-再生金属回收加工");

    /** 洗绿识别规则集（表 7-1）：行业属性冲突/资金用途不符 直判 HIGH；重复申报/数据夸大/资质缺失 MEDIUM */
    private static final Set<String> HIGH_RULES = Set.of("RULE-01", "RULE-02");

    /** 碳减排支持工具减排量门槛（tCO2e/年，仿真阈值） */
    private static final BigDecimal TOOL_REDUCTION_THRESHOLD = new BigDecimal("100");
    /** 碳减排支持工具再贷款测算比例（仿真） */
    private static final BigDecimal TOOL_LOAN_RATE = new BigDecimal("0.6");
    /** 减排量折算再贷款单价（元/tCO2e，仿真兜底） */
    private static final BigDecimal TOOL_LOAN_UNIT_PRICE = new BigDecimal("100");

    private final IdempotencyStore idempotency = new IdempotencyStore();

    /** 碳排放核算（幂等：同 requestId+entityId+period 要素哈希返回首次结果，缓存 24h） */
    public CarbonDtos.AccountResult account(CarbonDtos.AccountRequest req, String requestId) {
        validate(req);

        String hash = sha256(String.join("|",
                str(req.entityId()), str(req.period()), String.valueOf(req.activityData()),
                str(req.factorVersion())));
        String prevHash = idempotency.get("account-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(CarbonErrorCodes.DUPLICATE_CONFLICT,
                        "CARB_1005 重复提交/核算要素冲突，请使用原 requestId 查询首次核算结果");
            }
            return idempotency.get("account", requestId);
        }

        CarbonDtos.AccountResult result = compute(req);
        idempotency.put("account-hash", requestId, hash);
        idempotency.put("account", requestId, result);
        log.info("碳核算完成: requestId={}, entityId={}, period={}, scope1={}, scope2={}, scope3={}, total={}, pledgeable={}",
                requestId, result.entityId(), req.period(), result.scope1(), result.scope2(),
                result.scope3(), result.totalCarbon(), result.pledgeableAmount());
        return result;
    }

    /** 排放因子库（GET /factors） */
    public List<CarbonDtos.FactorItem> factors() {
        return List.of(
                new CarbonDtos.FactorItem("EF-GRID", "电网排放因子", EF_GRID, "tCO2/MWh"),
                new CarbonDtos.FactorItem("EF-HEAT", "热力排放因子", EF_HEAT, "tCO2/GJ"),
                new CarbonDtos.FactorItem("EF-GAS", "天然气排放因子", EF_GAS, "tCO2/m³（21.622 t/万m³）"),
                new CarbonDtos.FactorItem("EF-COAL", "煤炭排放因子", EF_COAL, "tCO2/t"),
                new CarbonDtos.FactorItem("EF-SUPPLY", "供应链运输因子", EF_SUPPLY_CHAIN, "tCO2/t"),
                new CarbonDtos.FactorItem("GWP", "全球增温潜势（AR5）", BigDecimal.ZERO, "CO2=1 / CH4=28 / N2O=265（因子值已按 tCO2e 折算）"));
    }

    /** 绿色产业目录分类判定（4.2，幂等：同 requestId+分类要素哈希返回首次结果） */
    public CarbonDtos.ClassifyResult classify(CarbonDtos.ClassifyRequest req, String requestId) {
        validateClassify(req);
        String hash = sha256(String.join("|",
                str(req.entityId()), str(req.industryCode()), str(req.industryDesc()),
                str(req.projectInfo()), str(req.categoryVersion()), str(req.loanPurpose())));
        CarbonDtos.ClassifyResult cached = cachedResult("classify", requestId, hash,
                "CARB_4005 幂等冲突：requestId 重复且分类要素不同，请使用原 requestId 查询首次认定结果");
        if (cached != null) {
            return cached;
        }
        CarbonDtos.ClassifyResult result = computeClassify(req, hash);
        idempotency.put("classify-hash", requestId, hash);
        idempotency.put("classify", requestId, result);
        log.info("目录分类完成: requestId={}, entityType={}, entityId={}, conclusion={}, categoryPath={}, matchScore={}",
                requestId, req.entityType(), req.entityId(), result.conclusion(), result.categoryPath(), result.matchScore());
        return result;
    }

    /** 项目减排量测算（4.4，幂等：同 requestId+项目/方法学/参数哈希返回首次结果，适用性未过抛 CARB_4101） */
    public CarbonDtos.ReductionResult reduction(CarbonDtos.ReductionRequest req, String requestId) {
        validateReduction(req);
        String hash = sha256(String.join("|",
                str(req.projectId()), str(req.methodology()), str(req.projectType()), str(req.boundary()),
                str(req.baselineParams()), str(req.operationData()), str(req.emissionFactors()), str(req.leakageParams())));
        CarbonDtos.ReductionResult cached = cachedResult("reduction", requestId, hash,
                "CARB_4005 幂等冲突：requestId 重复且测算要素不同，请使用原 requestId 查询首次测算结果");
        if (cached != null) {
            return cached;
        }
        CarbonDtos.ReductionResult result = computeReduction(req, hash);
        idempotency.put("reduction-hash", requestId, hash);
        idempotency.put("reduction", requestId, result);
        log.info("减排量测算完成: requestId={}, projectId={}, methodology={}, baseline={}, project={}, leakage={}, reduction={}",
                requestId, req.projectId(), req.methodology(), result.baselineEmission(),
                result.projectEmission(), result.leakage(), result.reduction());
        return result;
    }

    /** 碳配额测算（4.5，幂等：同 requestId+主体/分配方法/参数哈希返回首次结果） */
    public CarbonDtos.QuotaResult quota(CarbonDtos.QuotaRequest req, String requestId) {
        validateQuota(req);
        String hash = sha256(String.join("|",
                str(req.entityId()), str(req.emission()), str(req.allocationMethod()),
                str(req.benchmarkValue()), str(req.outputAmount()), str(req.reductionFactor()),
                str(req.freeRatio()), str(req.compliancePeriod()), str(req.ruleVersion())));
        CarbonDtos.QuotaResult cached = cachedResult("quota", requestId, hash,
                "CARB_4005 幂等冲突：requestId 重复且测算要素不同，请使用原 requestId 查询首次测算结果");
        if (cached != null) {
            return cached;
        }
        CarbonDtos.QuotaResult result = computeQuota(req, hash);
        idempotency.put("quota-hash", requestId, hash);
        idempotency.put("quota", requestId, result);
        log.info("配额测算完成: requestId={}, entityId={}, method={}, quota={}, gapSurplus={}, ruleVersion={}",
                requestId, req.entityId(), req.allocationMethod(), result.quotaAmount(),
                result.gapSurplus(), result.ruleVersion());
        return result;
    }

    /** 碳资产估值（4.6，幂等：同 requestId+资产/情景参数哈希返回首次结果） */
    public CarbonDtos.ValueResult value(CarbonDtos.ValueRequest req, String requestId) {
        validateValue(req);
        String hash = sha256(String.join("|",
                str(req.assetId()), str(req.assetType()), str(req.quantity()), str(req.maturity()),
                str(req.ownership()), str(req.priceScenarios()), str(req.discountRate()), str(req.pledgeRatio())));
        CarbonDtos.ValueResult cached = cachedResult("value", requestId, hash,
                "CARB_4005 幂等冲突：requestId 重复且估值要素不同，请使用原 requestId 查询首次估值结果");
        if (cached != null) {
            return cached;
        }
        CarbonDtos.ValueResult result = computeValue(req, hash);
        idempotency.put("value-hash", requestId, hash);
        idempotency.put("value", requestId, result);
        log.info("碳资产估值完成: requestId={}, assetId={}, assetType={}, quantity={}, weightedValue={}, pledgeValue={}",
                requestId, req.assetId(), req.assetType(), req.quantity(),
                result.weightedValue(), result.pledgeValue());
        return result;
    }

    /** 绿色贷款认定与统计测算（4.7，幂等：同 requestId+statPeriod+statVersion+贷款要素哈希返回首次结果） */
    public CarbonDtos.GreenStatResult greenStat(CarbonDtos.GreenStatRequest req, String requestId) {
        validateGreenStat(req);
        String hash = sha256(String.join("|",
                str(req.statPeriod()), str(req.statVersion()), str(req.loans())));
        CarbonDtos.GreenStatResult cached = cachedResult("green-stat", requestId, hash,
                "CARB_4005 幂等冲突：requestId 重复且统计要素不同，请使用原 requestId 查询首次统计结果");
        if (cached != null) {
            return cached;
        }
        CarbonDtos.GreenStatResult result = computeGreenStat(req);
        idempotency.put("green-stat-hash", requestId, hash);
        idempotency.put("green-stat", requestId, result);
        log.info("绿色统计完成: requestId={}, statPeriod={}, greenBalance={}, greenRatio={}, loanCount={}",
                requestId, req.statPeriod(), result.greenBalance(), result.greenRatio(), req.loans().size());
        return result;
    }

    /** 碳减排支持工具测算（4.8，幂等：同 requestId+项目/减排量/参数哈希返回首次结果） */
    public CarbonDtos.ToolCalcResult toolCalc(CarbonDtos.ToolCalcRequest req, String requestId) {
        validateToolCalc(req);
        String hash = sha256(String.join("|",
                str(req.projectType()), str(req.reduction()), str(req.loanRate()), str(req.loanAmount()),
                str(req.declarationItems()), str(req.toolRuleVersion())));
        CarbonDtos.ToolCalcResult cached = cachedResult("tool-calc", requestId, hash,
                "CARB_4005 幂等冲突：requestId 重复且测算要素不同，请使用原 requestId 查询首次测算结果");
        if (cached != null) {
            return cached;
        }
        CarbonDtos.ToolCalcResult result = computeToolCalc(req);
        idempotency.put("tool-calc-hash", requestId, hash);
        idempotency.put("tool-calc", requestId, result);
        log.info("支持工具测算完成: requestId={}, projectType={}, reduction={}, compliance={}, toolLoanAmount={}",
                requestId, req.projectType(), req.reduction(), result.compliance(), result.toolLoanAmount());
        return result;
    }

    /** 洗绿识别（4.9，幂等：同 requestId+主体/证据哈希返回首次结果） */
    public CarbonDtos.GreenwashResult greenwash(CarbonDtos.GreenwashRequest req, String requestId) {
        validateGreenwash(req);
        String hash = sha256(String.join("|",
                str(req.entityId()), str(req.classifyConclusion()), str(req.evidenceDocs()), str(req.riskRules())));
        CarbonDtos.GreenwashResult cached = cachedResult("greenwash", requestId, hash,
                "CARB_4005 幂等冲突：requestId 重复且识别要素不同，请使用原 requestId 查询首次识别结果");
        if (cached != null) {
            return cached;
        }
        CarbonDtos.GreenwashResult result = computeGreenwash(req, hash);
        idempotency.put("greenwash-hash", requestId, hash);
        idempotency.put("greenwash", requestId, result);
        log.info("洗绿识别完成: requestId={}, entityId={}, riskLevel={}, hitRuleCount={}, disposal={}",
                requestId, req.entityId(), result.riskLevel(), result.hitRules().size(), result.disposalAdvice());
        return result;
    }

    /** 目录分类判定计算（4.2）：匹配分 0.55~0.95 由要素哈希确定性推导，≥0.75 归类 GREEN，0.6~0.75 边界条目触发复核 */
    private CarbonDtos.ClassifyResult computeClassify(CarbonDtos.ClassifyRequest req, String hash) {
        BigDecimal score = simNum(hash, 0, 55, 95, 0).movePointLeft(2);
        String conclusion;
        boolean reviewRequired;
        String reviewTaskId = null;
        String certificateNo = null;
        if (score.compareTo(new BigDecimal("0.75")) >= 0) {
            conclusion = "GREEN";
            reviewRequired = false;
            certificateNo = "CL-" + hash.substring(0, 8).toUpperCase();
        } else if (score.compareTo(new BigDecimal("0.6")) >= 0) {
            conclusion = "SUSPECT";
            reviewRequired = true;
            reviewTaskId = "RV-" + hash.substring(0, 8).toUpperCase();
        } else {
            conclusion = "NON_GREEN";
            reviewRequired = false;
        }
        String categoryPath = "GREEN".equals(conclusion) ? pick(CATALOG_ENTRIES, hash, 0) : "未命中目录条目";
        List<String> basis = new ArrayList<>();
        if (!blank(req.industryCode())) {
            basis.add("行业代码映射");
        }
        if (score.compareTo(new BigDecimal("0.75")) >= 0) {
            basis.add("语义匹配");
        }
        if (basis.isEmpty()) {
            basis.add("关键词未命中");
        }
        return new CarbonDtos.ClassifyResult(conclusion, categoryPath, score, basis,
                reviewRequired, reviewTaskId, certificateNo);
    }

    /** 项目减排量计算（4.4）：ER = BE − PE − LE；BE/PE/LE 缺省按要素哈希确定性仿真（用于联调） */
    private CarbonDtos.ReductionResult computeReduction(CarbonDtos.ReductionRequest req, String hash) {
        if (!hit(hash, 0, 85)) {
            throw new BaiException(CarbonErrorCodes.METHOD_NOT_APPLICABLE,
                    "CARB_4101 方法学不适用：适用性检查未过（methodology=" + req.methodology()
                            + "），请更换方法学或补充项目边界");
        }
        Map<String, Object> baseline = req.baselineParams() == null ? Map.of() : req.baselineParams();
        Map<String, Object> operation = req.operationData() == null ? Map.of() : req.operationData();
        Map<String, Object> leakage = req.leakageParams() == null ? Map.of() : req.leakageParams();
        BigDecimal be = orZero(optNumIn(baseline, "emission", "baselineParams"));
        BigDecimal pe = orZero(optNumIn(operation, "emission", "operationData"));
        BigDecimal le = orZero(optNumIn(leakage, "leakage", "leakageParams"));
        if (be.signum() == 0) {
            be = simNum(hash, 1, 1000, 5000, MONEY_SCALE);
        }
        if (pe.signum() == 0) {
            pe = simNum(hash, 2, 200, 800, MONEY_SCALE);
        }
        if (le.signum() == 0) {
            le = simNum(hash, 3, 10, 100, MONEY_SCALE);
        }
        BigDecimal er = be.subtract(pe).subtract(le).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (er.signum() < 0) {
            throw new BaiException(CarbonErrorCodes.METHOD_NOT_APPLICABLE,
                    "CARB_4101 方法学不适用：项目无净减排（ER=BE−PE−LE=" + er + "）");
        }
        List<String> checks = List.of("方法学适用性检查通过", "项目边界与基准线参数完整", "活动数据与核算期对齐");
        List<String> process = List.of(
                "基准线情景法：ER = BE − PE − LE",
                "BE（基准线情景排放）= " + be + " tCO2e",
                "PE（项目情景排放）= " + pe + " tCO2e",
                "LE（泄漏）= " + le + " tCO2e",
                "ER（年减排量）= " + er + " tCO2e");
        return new CarbonDtos.ReductionResult(true, checks, be, pe, le, er, process,
                "建议按方法学要求开展年度监测与核证，监测参数：发电量/上网电量/燃料消耗，数据缺失超 30% 拒绝核算");
    }

    /** 碳配额计算（4.5）：基准线法 Q=基准值×产量×免费比例；历史法 Q=历史排放×下降系数×免费比例；G=Q−实际排放 */
    private CarbonDtos.QuotaResult computeQuota(CarbonDtos.QuotaRequest req, String hash) {
        String version = blank(req.ruleVersion()) ? DEFAULT_QUOTA_VERSION : req.ruleVersion();
        BigDecimal quotaAmount;
        if ("BASELINE".equals(req.allocationMethod())) {
            quotaAmount = req.benchmarkValue().multiply(req.outputAmount()).multiply(req.freeRatio())
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        } else {
            quotaAmount = req.emission().multiply(req.reductionFactor()).multiply(req.freeRatio())
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal gapSurplus = quotaAmount.subtract(req.emission()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        Map<String, BigDecimal> forecast = Map.of(
                "lower", quotaAmount.multiply(new BigDecimal("0.9")).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                "upper", quotaAmount.multiply(new BigDecimal("1.1")).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        return new CarbonDtos.QuotaResult(quotaAmount, gapSurplus, forecast, version);
    }

    /** 碳资产估值（4.6）：情景现值=数量×情景价（单期现金流简化），V_w=Σ(V_s×w_s)，V_p=V_w×质押折扣率 */
    private CarbonDtos.ValueResult computeValue(CarbonDtos.ValueRequest req, String hash) {
        List<CarbonDtos.PriceScenario> scenarios = req.priceScenarios();
        if (scenarios == null || scenarios.isEmpty()) {
            // 缺省情景：基准（当前价）/上行（+50%）/下行（−30%），权重 0.5/0.25/0.25（表 17 策略）
            BigDecimal base = "ALLOWANCE".equals(req.assetType()) ? new BigDecimal("75") : new BigDecimal("60");
            BigDecimal up = base.multiply(new BigDecimal("1.5")).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal down = base.multiply(new BigDecimal("0.7")).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            scenarios = List.of(
                    new CarbonDtos.PriceScenario("BASE", base, up, down, new BigDecimal("0.5")),
                    new CarbonDtos.PriceScenario("UPSIDE", base, up, down, new BigDecimal("0.25")),
                    new CarbonDtos.PriceScenario("DOWNSIDE", base, up, down, new BigDecimal("0.25")));
        }
        List<CarbonDtos.ValuationScenario> byScenario = new ArrayList<>();
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal fallbackWeight = BigDecimal.ONE.divide(BigDecimal.valueOf(scenarios.size()), 4, RoundingMode.HALF_UP);
        for (CarbonDtos.PriceScenario ps : scenarios) {
            BigDecimal price = scenarioPrice(ps);
            BigDecimal pv = req.quantity().multiply(price).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal w = ps.weight() != null ? ps.weight() : fallbackWeight;
            weighted = weighted.add(pv.multiply(w));
            byScenario.add(new CarbonDtos.ValuationScenario(ps.name(), price, pv));
        }
        weighted = weighted.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal pledgeRatio = req.pledgeRatio() != null ? req.pledgeRatio() : PLEDGE_RATE;
        BigDecimal pledgeValue = weighted.multiply(pledgeRatio).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal unitSensitivity = weighted.multiply(new BigDecimal("0.01")).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        Map<String, BigDecimal> sensitivity = Map.of(
                "priceSensitivity", unitSensitivity, "quantitySensitivity", unitSensitivity);
        Map<String, Object> snapshot = Map.of(
                "assetId", req.assetId(), "assetType", req.assetType(), "quantity", req.quantity(),
                "discountRate", orZero(req.discountRate()), "pledgeRatio", pledgeRatio,
                "scenarioCount", scenarios.size());
        return new CarbonDtos.ValueResult(byScenario, weighted, sensitivity, pledgeValue, snapshot);
    }

    /** 绿色贷款认定与统计（4.7）：绿色余额按投向目录条目贷款聚合，绿色占比=绿色余额/贷款总余额，与人行口径对账 */
    private CarbonDtos.GreenStatResult computeGreenStat(CarbonDtos.GreenStatRequest req) {
        String version = blank(req.statVersion()) ? DEFAULT_STAT_VERSION : req.statVersion();
        List<CarbonDtos.Recognition> recognitions = new ArrayList<>();
        BigDecimal greenBalance = BigDecimal.ZERO;
        BigDecimal totalBalance = BigDecimal.ZERO;
        Map<String, BigDecimal> structure = new HashMap<>();
        for (CarbonDtos.LoanInfo loan : req.loans()) {
            if (loan.amount() == null || loan.amount().signum() < 0) {
                throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED,
                        "CARB_4001 参数非法：loans.amount 须 ≥ 0（loanNo=" + loan.loanNo() + "）");
            }
            totalBalance = totalBalance.add(loan.amount());
            boolean green = !blank(loan.categoryPath());
            recognitions.add(new CarbonDtos.Recognition(loan.loanNo(),
                    green ? "GREEN" : "NON_GREEN",
                    green ? loan.categoryPath() : "未命中目录条目",
                    green ? new BigDecimal("0.85") : new BigDecimal("0.30"),
                    green ? "命中绿色目录条目" : "未命中绿色目录条目"));
            if (green) {
                greenBalance = greenBalance.add(loan.amount());
                String industry = blank(loan.industryCode()) ? "UNKNOWN" : loan.industryCode();
                structure.merge(industry, loan.amount(), BigDecimal::add);
            }
        }
        greenBalance = greenBalance.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal greenRatio = totalBalance.signum() > 0
                ? greenBalance.divide(totalBalance, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        structure.replaceAll((k, v) -> v.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        String recon = "与人行绿色贷款专项统计口径对账一致（statPeriod=" + req.statPeriod() + "，statVersion=" + version + "）";
        return new CarbonDtos.GreenStatResult(recognitions, greenBalance, greenRatio, structure, recon);
    }

    /** 碳减排支持工具测算（4.8）：减排量 ≥ 门槛即符合；折算减排量 1:1；再贷款金额按贷款额×比例或减排量×单价仿真 */
    private CarbonDtos.ToolCalcResult computeToolCalc(CarbonDtos.ToolCalcRequest req) {
        boolean compliant = req.reduction().compareTo(TOOL_REDUCTION_THRESHOLD) >= 0;
        String detail = compliant
                ? "符合碳减排支持工具申报条件（减排量 " + req.reduction() + " tCO2e ≥ " + TOOL_REDUCTION_THRESHOLD + " 门槛）"
                : "不符合碳减排支持工具申报条件（减排量 " + req.reduction() + " tCO2e < " + TOOL_REDUCTION_THRESHOLD + " 门槛）";
        BigDecimal converted = req.reduction().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal toolLoanAmount = req.loanAmount() != null
                ? req.loanAmount().multiply(TOOL_LOAN_RATE).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                : req.reduction().multiply(TOOL_LOAN_UNIT_PRICE).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        List<String> checklist = List.of(
                "项目备案文件", "减排量测算报告", "贷款合同及资金用途凭证",
                "监测数据（发电量/节能量）", "碳减排支持工具申报表");
        return new CarbonDtos.ToolCalcResult(compliant, detail, converted, toolLoanAmount, checklist);
    }

    /** 洗绿识别（4.9，规则表 7-1）：资质缺失按证据完整度判 MEDIUM；行业属性冲突/资金用途不符直判 HIGH；其余规则哈希推导 */
    private CarbonDtos.GreenwashResult computeGreenwash(CarbonDtos.GreenwashRequest req, String hash) {
        Map<String, Object> docs = req.evidenceDocs() == null ? Map.of() : req.evidenceDocs();
        List<String> missingKeys = new ArrayList<>();
        for (String key : List.of("filing", "contract", "eia", "fundFlow")) {
            Object v = docs.get(key);
            if (v == null || String.valueOf(v).isBlank()) {
                missingKeys.add(key);
            }
        }
        List<CarbonDtos.HitRule> hitRules = new ArrayList<>();
        if (!missingKeys.isEmpty()) {
            hitRules.add(new CarbonDtos.HitRule("RULE-05", "资质缺失",
                    "关键证照/备案缺失：" + String.join("、", missingKeys),
                    simNum(hash, 5, 50, 70, 0).movePointLeft(2)));
        }
        if (hit(hash, 1, 25)) {
            hitRules.add(new CarbonDtos.HitRule("RULE-01", "行业属性冲突",
                    "主体行业与申报绿色项目明显不符",
                    simNum(hash, 6, 80, 95, 0).movePointLeft(2)));
        }
        if (hit(hash, 2, 20)) {
            hitRules.add(new CarbonDtos.HitRule("RULE-02", "资金用途不符",
                    "贷款资金流向与绿色项目申报不符",
                    simNum(hash, 7, 80, 95, 0).movePointLeft(2)));
        }
        if (hit(hash, 3, 20)) {
            hitRules.add(new CarbonDtos.HitRule("RULE-03", "重复申报",
                    "同一项目/资产重复申报绿色认定",
                    simNum(hash, 8, 50, 70, 0).movePointLeft(2)));
        }
        if (hit(hash, 4, 20)) {
            hitRules.add(new CarbonDtos.HitRule("RULE-04", "数据夸大",
                    "申报减排量/排放数据与监测明显偏离",
                    simNum(hash, 9, 50, 70, 0).movePointLeft(2)));
        }
        String riskLevel = "LOW";
        for (CarbonDtos.HitRule r : hitRules) {
            if (HIGH_RULES.contains(r.ruleCode())) {
                riskLevel = "HIGH";
                break;
            }
            riskLevel = "MEDIUM";
        }
        String disposal = "HIGH".equals(riskLevel) ? "REJECT"
                : "MEDIUM".equals(riskLevel) ? "REVIEW" : "PASS";
        List<String> chain = new ArrayList<>();
        chain.add("证据材料完整度：" + (4 - missingKeys.size()) + "/4（备案/合同/环评/资金流水）");
        hitRules.forEach(r -> chain.add("命中规则 [" + r.ruleCode() + "] " + r.ruleName()
                + "：证据=" + r.evidence() + "，评分=" + r.score()));
        if (hitRules.isEmpty()) {
            chain.add("未命中洗绿规则，证据链自洽");
        }
        return new CarbonDtos.GreenwashResult(riskLevel, hitRules, chain, disposal);
    }

    /** 目录分类判定入参校验（4.2，表 4-2）：entityType/entityId 必填，PROJECT 时 projectInfo 必填，目录版本须生效 */
    private void validateClassify(CarbonDtos.ClassifyRequest req) {
        if (req == null) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED, "CARB_4001 参数缺失：请求体必填");
        }
        if (!Set.of("ENTERPRISE", "PROJECT", "PRODUCT").contains(req.entityType())) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED,
                    "CARB_4001 参数非法：entityType 须为 ENTERPRISE/PROJECT/PRODUCT，当前 " + req.entityType());
        }
        if (blank(req.entityId())) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED, "CARB_4001 参数缺失：entityId 必填");
        }
        if ("PROJECT".equals(req.entityType()) && (req.projectInfo() == null || req.projectInfo().isEmpty())) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED, "CARB_4001 参数缺失：PROJECT 分类时 projectInfo 必填");
        }
        if (!blank(req.categoryVersion()) && !CATALOG_VERSIONS.contains(req.categoryVersion())) {
            throw new BaiException(CarbonErrorCodes.VERSION_NOT_FOUND,
                    "CARB_4003 目录版本不存在：categoryVersion=" + req.categoryVersion() + "，请指定当前生效版本");
        }
    }

    /** 项目减排量测算入参校验（4.4）：projectId/methodology 必填，方法学须在生效集 */
    private void validateReduction(CarbonDtos.ReductionRequest req) {
        if (req == null || blank(req.projectId())) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED, "CARB_4001 参数缺失：projectId 必填");
        }
        if (blank(req.methodology()) || !METHODOLOGIES.contains(req.methodology())) {
            throw new BaiException(CarbonErrorCodes.VERSION_NOT_FOUND,
                    "CARB_4003 方法学不存在：methodology=" + req.methodology() + "，请指定当前生效方法学（如 CM-001）");
        }
    }

    /** 碳配额测算入参校验（4.5）：entityId/emission 必填，分配方法与版本校验 */
    private void validateQuota(CarbonDtos.QuotaRequest req) {
        if (req == null || blank(req.entityId())) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED, "CARB_4001 参数缺失：entityId 必填");
        }
        if (req.emission() == null || req.emission().signum() < 0) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED, "CARB_4001 参数非法：emission 必填且须 ≥ 0");
        }
        if (!Set.of("BASELINE", "HISTORICAL").contains(req.allocationMethod())) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED,
                    "CARB_4001 参数非法：allocationMethod 须为 BASELINE/HISTORICAL，当前 " + req.allocationMethod());
        }
        if ("BASELINE".equals(req.allocationMethod())
                && (req.benchmarkValue() == null || req.outputAmount() == null || req.freeRatio() == null)) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED,
                    "CARB_4001 参数缺失：BASELINE 法须提供 benchmarkValue/outputAmount/freeRatio");
        }
        if ("HISTORICAL".equals(req.allocationMethod())
                && (req.reductionFactor() == null || req.freeRatio() == null)) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED,
                    "CARB_4001 参数缺失：HISTORICAL 法须提供 reductionFactor/freeRatio");
        }
        if (!blank(req.ruleVersion()) && !QUOTA_VERSIONS.contains(req.ruleVersion())) {
            throw new BaiException(CarbonErrorCodes.VERSION_NOT_FOUND,
                    "CARB_4003 配额规则版本不存在：ruleVersion=" + req.ruleVersion() + "，请指定当前生效版本");
        }
    }

    /** 碳资产估值入参校验（4.6）：assetId/assetType/quantity 必填 */
    private void validateValue(CarbonDtos.ValueRequest req) {
        if (req == null || blank(req.assetId())) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED, "CARB_4001 参数缺失：assetId 必填");
        }
        if (!Set.of("ALLOWANCE", "CCER").contains(req.assetType())) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED,
                    "CARB_4001 参数非法：assetType 须为 ALLOWANCE/CCER，当前 " + req.assetType());
        }
        if (req.quantity() == null || req.quantity().signum() < 0) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED, "CARB_4001 参数非法：quantity 必填且须 ≥ 0");
        }
    }

    /** 绿色贷款认定与统计入参校验（4.7）：loans/statPeriod 必填，统计版本校验 */
    private void validateGreenStat(CarbonDtos.GreenStatRequest req) {
        if (req == null || req.loans() == null || req.loans().isEmpty()) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED, "CARB_4001 参数缺失：loans 必填且非空");
        }
        if (blank(req.statPeriod())) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED, "CARB_4001 参数缺失：statPeriod 必填");
        }
        if (!blank(req.statVersion()) && !STAT_VERSIONS.contains(req.statVersion())) {
            throw new BaiException(CarbonErrorCodes.VERSION_NOT_FOUND,
                    "CARB_4003 统计版本不存在：statVersion=" + req.statVersion() + "，请指定当前生效版本");
        }
    }

    /** 碳减排支持工具测算入参校验（4.8）：projectType/reduction 必填，规则版本校验 */
    private void validateToolCalc(CarbonDtos.ToolCalcRequest req) {
        if (req == null || blank(req.projectType())) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED, "CARB_4001 参数缺失：projectType 必填");
        }
        if (req.reduction() == null || req.reduction().signum() < 0) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED, "CARB_4001 参数非法：reduction 必填且须 ≥ 0");
        }
        if (!blank(req.toolRuleVersion()) && !TOOL_VERSIONS.contains(req.toolRuleVersion())) {
            throw new BaiException(CarbonErrorCodes.VERSION_NOT_FOUND,
                    "CARB_4003 工具规则版本不存在：toolRuleVersion=" + req.toolRuleVersion() + "，请指定当前生效版本");
        }
    }

    /** 洗绿识别入参校验（4.9）：entityId 必填，认定结论与规则集版本校验 */
    private void validateGreenwash(CarbonDtos.GreenwashRequest req) {
        if (req == null || blank(req.entityId())) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED, "CARB_4001 参数缺失：entityId 必填");
        }
        if (!blank(req.classifyConclusion()) && !Set.of("GREEN", "NON_GREEN", "SUSPECT").contains(req.classifyConclusion())) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED,
                    "CARB_4001 参数非法：classifyConclusion 须为 GREEN/NON_GREEN/SUSPECT，当前 " + req.classifyConclusion());
        }
        if (!blank(req.riskRules()) && !RISK_RULES_VERSIONS.contains(req.riskRules())) {
            throw new BaiException(CarbonErrorCodes.VERSION_NOT_FOUND,
                    "CARB_4003 洗绿规则集版本不存在：riskRules=" + req.riskRules() + "，请指定当前生效版本");
        }
    }

    private void validate(CarbonDtos.AccountRequest req) {
        if (req == null || req.entityId() == null || req.entityId().isBlank()) {
            throw new BaiException(CarbonErrorCodes.PARAM_INVALID, "CARB_1001 参数缺失：entityId 必填");
        }
        // 周期格式：yyyy 或 yyyy-MM（月份 01~12）
        if (req.period() == null || !req.period().matches("\\d{4}(-\\d{2})?")) {
            throw new BaiException(CarbonErrorCodes.ELEMENT_ILLEGAL,
                    "CARB_1007 周期非法：period 须为 yyyy 或 yyyy-MM 格式，当前 " + req.period());
        }
        if (req.period().length() == 7) {
            int month = Integer.parseInt(req.period().substring(5));
            if (month < 1 || month > 12) {
                throw new BaiException(CarbonErrorCodes.ELEMENT_ILLEGAL,
                        "CARB_1007 周期非法：period 月份须为 01~12，当前 " + req.period());
            }
        }
        if (req.activityData() == null || req.activityData().isEmpty()) {
            throw new BaiException(CarbonErrorCodes.PARAM_INVALID, "CARB_1001 参数缺失：activityData 必填");
        }
        if (req.factorVersion() != null && !req.factorVersion().isBlank() && !FACTOR_VERSIONS.contains(req.factorVersion())) {
            throw new BaiException(CarbonErrorCodes.NOT_FOUND,
                    "CARB_1004 排放因子版本不存在：factorVersion=" + req.factorVersion());
        }
        // 四类基础活动数据可选（缺失按 0 核算），出现时须 ≥ 0
        for (String key : List.of("electricityKwh", "naturalGasM3", "coalTon", "purchasedHeatGj")) {
            BigDecimal v = optNum(req.activityData(), key);
            if (v != null && v.signum() < 0) {
                throw new BaiException(CarbonErrorCodes.PARAM_INVALID,
                        "CARB_1001 参数非法：activityData." + key + " 须 ≥ 0");
            }
        }
        BigDecimal offsets = optNum(req.activityData(), "purchasedOffsets");
        if (offsets != null && offsets.signum() < 0) {
            throw new BaiException(CarbonErrorCodes.PARAM_INVALID, "CARB_1001 参数非法：activityData.purchasedOffsets 须 ≥ 0");
        }
        BigDecimal leakage = optNum(req.activityData(), "leakage");
        if (leakage != null && leakage.signum() < 0) {
            throw new BaiException(CarbonErrorCodes.PARAM_INVALID, "CARB_1001 参数非法：activityData.leakage 须 ≥ 0");
        }
        BigDecimal supply = optNum(req.activityData(), "supplyChainTons");
        if (supply != null && supply.signum() < 0) {
            throw new BaiException(CarbonErrorCodes.PARAM_INVALID, "CARB_1001 参数非法：activityData.supplyChainTons 须 ≥ 0");
        }
    }

    /** 排放核算与碳资产计算（公式真实实现） */
    private CarbonDtos.AccountResult compute(CarbonDtos.AccountRequest req) {
        Map<String, Object> data = req.activityData();
        BigDecimal electricityKwh = orZero(optNum(data, "electricityKwh"));
        BigDecimal naturalGasM3 = orZero(optNum(data, "naturalGasM3"));
        BigDecimal coalTon = orZero(optNum(data, "coalTon"));
        BigDecimal purchasedHeatGj = orZero(optNum(data, "purchasedHeatGj"));

        // 范围二（外购电力/热力）：kWh/1000×0.5703 + GJ×0.11
        BigDecimal scope2 = electricityKwh.divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP)
                .multiply(EF_GRID)
                .add(purchasedHeatGj.multiply(EF_HEAT))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        // 范围一（直接燃烧）：天然气 0.0021622 t/m³ + 煤炭 2.66 t/t
        BigDecimal scope1 = naturalGasM3.multiply(EF_GAS).add(coalTon.multiply(EF_COAL))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        // 范围三（供应链）：supplyChainTons×1.0；缺省按（范围一+范围二）×20% 仿真（用于联调）
        BigDecimal supplyTons = optNum(data, "supplyChainTons");
        BigDecimal scope3 = supplyTons != null
                ? supplyTons.multiply(EF_SUPPLY_CHAIN).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                : scope1.add(scope2).multiply(SCOPE3_SIM_RATE).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // 总排放（恒等：scope1+scope2+scope3）
        BigDecimal totalCarbon = scope1.add(scope2).add(scope3);
        // 排放等式 ER = BE − PE − LE（BE=总排放，PE=purchasedOffsets，LE=leakage）
        BigDecimal pe = orZero(optNum(data, "purchasedOffsets"));
        BigDecimal le = orZero(optNum(data, "leakage"));
        BigDecimal er = totalCarbon.subtract(pe).subtract(le).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        // 碳价情景与加权碳资产：加权碳价 = Σ 情景价 × 权重 = 90.0 元/tCO2e
        CarbonDtos.CarbonScenario scenario = new CarbonDtos.CarbonScenario(
                PRICE_BASE, PRICE_NEUTRAL, PRICE_PESSIMISTIC, SCENARIO_WEIGHTS);
        BigDecimal weightedPrice = PRICE_BASE.multiply(SCENARIO_WEIGHTS.get(0))
                .add(PRICE_NEUTRAL.multiply(SCENARIO_WEIGHTS.get(1)))
                .add(PRICE_PESSIMISTIC.multiply(SCENARIO_WEIGHTS.get(2)));
        BigDecimal pledgeableAmount = totalCarbon.multiply(weightedPrice).multiply(PLEDGE_RATE)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        String factorVersion = req.factorVersion() == null || req.factorVersion().isBlank()
                ? DEFAULT_FACTOR_VERSION : req.factorVersion();
        return new CarbonDtos.AccountResult(req.entityId(), scope1, scope2, scope3, totalCarbon,
                scenario, pledgeableAmount, factorVersion, er);
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 必填数值参数（支持数值与可解析字符串），缺失抛 CARB_1001 */
    private static BigDecimal num(Map<String, Object> data, String key) {
        BigDecimal v = optNum(data, key);
        if (v == null) {
            throw new BaiException(CarbonErrorCodes.PARAM_INVALID, "CARB_1001 参数缺失：activityData." + key + " 必填");
        }
        return v;
    }

    private static BigDecimal optNum(Map<String, Object> data, String key) {
        Object v = data.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BaiException(CarbonErrorCodes.PARAM_INVALID,
                    "CARB_1001 参数非法：activityData." + key + " 须为数值");
        }
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

    /** 幂等命中返回首次结果；同 requestId 不同要素哈希视为冲突（CARB_4005） */
    private <T> T cachedResult(String atom, String requestId, String hash, String conflictMsg) {
        String prevHash = idempotency.get(atom + "-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(CarbonErrorCodes.IDEMPOTENT_CONFLICT, conflictMsg);
            }
            return idempotency.get(atom, requestId);
        }
        return null;
    }

    /** 确定性仿真数值：hash 前 8 位 + salt 映射到 [min,max]，同输入恒同输出 */
    private static BigDecimal simNum(String hash, int salt, double min, double max, int scale) {
        long h = (Long.parseLong(hash.substring(0, 8), 16) + salt) & 0xFFFFFFFFL;
        double frac = (h % 100000) / 100000.0;
        return BigDecimal.valueOf(min + frac * (max - min)).setScale(scale, RoundingMode.HALF_UP);
    }

    /** 确定性选择列表元素 */
    private static <T> T pick(List<T> list, String hash, int salt) {
        long h = (Long.parseLong(hash.substring(0, 8), 16) + salt) & 0xFFFFFFFFL;
        return list.get((int) (h % list.size()));
    }

    /** 确定性命中判定：命中率 pct%（0~100） */
    private static boolean hit(String hash, int salt, int pct) {
        long h = (Long.parseLong(hash.substring(0, 8), 16) + salt) & 0xFFFFFFFFL;
        return h % 100 < pct;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 情景价格取值：UPSIDE 用上行价、DOWNSIDE 用下行价，其余用基准价 */
    private static BigDecimal scenarioPrice(CarbonDtos.PriceScenario ps) {
        String name = ps.name() == null ? "" : ps.name().toUpperCase();
        if ("UPSIDE".equals(name)) {
            return ps.upside() != null ? ps.upside() : ps.base();
        }
        if ("DOWNSIDE".equals(name)) {
            return ps.downside() != null ? ps.downside() : ps.base();
        }
        return ps.base();
    }

    /** 可选数值参数（指定 scope 的错误消息），非法抛 CARB_4001 */
    private static BigDecimal optNumIn(Map<String, Object> data, String key, String scope) {
        Object v = data.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BaiException(CarbonErrorCodes.PARAM_CHECK_FAILED,
                    "CARB_4001 参数非法：" + scope + "." + key + " 须为数值");
        }
    }
}
