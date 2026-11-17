package com.bai.assetsupervise;

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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 押品监管服务（提示词库 A-53，设计文档 4.x 接口 + 6.3 算法 + 7.x 规则策略）。
 * <p>处理链：监管对象登记建档 → 监测数据采集（清洗/质量/断连检测）→ 实时监控判定（动产数量/价值、不动产状态）→
 * 质押率盯市（LTV=融资余额÷实时估值，按押品类型分档预警线/处置线）→ 预警触发与处置协同（策略匹配生成处置任务）→
 * 重估联动（估值缺失/过期触发 valuation-agent，结果回流盯市基准）。
 * <p>阈值：数量偏差 ±2%、价值跌穿 −10%、断连 24h、估值有效期动产 7 日/不动产 180 日；
 * LTV 分档线：动产 0.7/0.8、不动产 0.75/0.85、权利（仓单/监管仓）0.8/0.9。
 * <p>幂等：POST 以 requestId 幂等（参数不同抛 ASS_4004）；盯市以 objectId+时间戳幂等。
 * <p>错误码：ASS_4001 参数校验失败 / ASS_4002 对象不存在或已终止 / ASS_4003 押品或贷款关联缺失 /
 * ASS_4004 幂等冲突 / ASS_4101 数据质量过低 / ASS_4102 估值缺失或过期 / ASS_4103 监管失效 /
 * ASS_4104 处置策略不存在 / ASS_5001 内部计算错误 / ASS_5002 计算服务过载。
 */
@Service
public class AssetSuperviseService {

    private static final Logger log = LoggerFactory.getLogger(AssetSuperviseService.class);

    private static final Set<String> ACTIONS = Set.of("CREATE", "UPDATE", "TERMINATE");
    private static final Set<String> OBJECT_TYPES = Set.of("REAL_ESTATE", "MOVABLE", "WAREHOUSE_RECEIPT", "REGULATED_WAREHOUSE");
    private static final Set<String> DATA_SOURCES = Set.of("IOT", "WAREHOUSE", "REGISTRY", "MANUAL");
    private static final Set<String> MONITOR_TYPES = Set.of("MOVABLE", "REAL_ESTATE");
    private static final Set<String> ALERT_TYPES = Set.of("VALUE_BREACH", "QUANTITY_ABNORMAL", "REGULATORY_LOSS", "LTV_OVER", "STATUS_CHANGE");
    private static final Set<String> TRIGGER_TYPES = Set.of("PERIODIC", "MARKET_CHANGE", "ALERT", "REQUIRED");
    private static final Set<String> FREQUENCIES = Set.of("DAILY", "MONTHLY", "EVENT");
    private static final Set<String> QUERY_TYPES = Set.of("OBJECT", "MONITOR", "LTV", "ALERT");

    /** 数量偏差阈值 ±2%（动产） */
    private static final BigDecimal QTY_DEVIATION_THRESHOLD = new BigDecimal("0.02");
    /** 价值跌穿阈值 −10% */
    private static final BigDecimal VALUE_DROP_THRESHOLD = new BigDecimal("-0.10");
    /** 监管失效判定：数据断连超 24 小时 */
    private static final long DISCONNECT_HOURS = 24;
    /** 数据质量过低判定：异常占比 >20% */
    private static final BigDecimal MAX_ABNORMAL_RATE = new BigDecimal("0.20");
    /** 估值有效期（日）：动产 7 日、不动产 180 日 */
    private static final long VALIDITY_DAYS_MOVABLE = 7;
    private static final long VALIDITY_DAYS_REAL_ESTATE = 180;
    /** 当前生效处置策略版本 */
    private static final String STRATEGY_VERSION = "v1";

    private final IdempotencyStore idempotency = new IdempotencyStore();
    /** 监管对象台账：objectId → 对象（生产落 as_object 表，本阶段内存仿真） */
    private final Map<String, SupervisedObject> objectStore = new ConcurrentHashMap<>();
    /** 监控结果：monitorNo → 记录（as_monitor） */
    private final Map<String, AssetSuperviseDtos.MonitorResult> monitorStore = new ConcurrentHashMap<>();
    /** 盯市记录：ltvNo → 记录（as_ltv），对象内按序用于 trend 计算 */
    private final Map<String, AssetSuperviseDtos.LtvResult> ltvStore = new ConcurrentHashMap<>();
    private final Map<String, List<AssetSuperviseDtos.LtvResult>> objectLtvs = new ConcurrentHashMap<>();
    /** 预警记录：alertNo → 记录（as_alert / as_task） */
    private final Map<String, AssetSuperviseDtos.AlertResult> alertStore = new ConcurrentHashMap<>();
    /** 重估联动记录：revalueNo → 记录（as_revalue） */
    private final Map<String, AssetSuperviseDtos.RevalueResult> revalueStore = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    // ------------------------------------------------------------------ 4.2 监管对象登记

    /** 监管对象登记/变更/终止（FR-01，幂等 requestId），返回台账状态与登记流水号 */
    public AssetSuperviseDtos.ObjectResult registerObject(AssetSuperviseDtos.ObjectRequest req, String requestId) {
        if (req == null || req.action() == null || !ACTIONS.contains(req.action())) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：action 须为 CREATE/UPDATE/TERMINATE");
        }
        if ("CREATE".equals(req.action())
                && (req.objectType() == null || !OBJECT_TYPES.contains(req.objectType()))) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：objectType 须为 REAL_ESTATE/MOVABLE/WAREHOUSE_RECEIPT/REGULATED_WAREHOUSE");
        }
        if (req.collateralId() == null || req.collateralId().isBlank()
                || req.loanNo() == null || req.loanNo().isBlank()) {
            throw new BaiException(AssetSuperviseErrorCodes.REF_NOT_FOUND,
                    "ASS_4003 押品/贷款不存在：collateralId/loanNo 关联标识缺失或无效");
        }
        if ("CREATE".equals(req.action())) {
            requireObjectNotExists(req.objectId());
        }
        String hash = sha256(String.join("|", req.action(), str(req.objectType()), str(req.collateralId()),
                str(req.loanNo()), String.valueOf(req.details())));
        String prevHash = idempotency.get("object-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(AssetSuperviseErrorCodes.IDEMPOTENT_CONFLICT,
                        "ASS_4004 幂等冲突：requestId 重复且登记参数不同，请使用原 requestId 查询");
            }
            return idempotency.get("object", requestId);
        }

        AssetSuperviseDtos.ObjectResult result = switch (req.action()) {
            case "CREATE" -> createObject(req);
            case "UPDATE" -> updateObject(req);
            default -> terminateObject(req);
        };
        idempotency.put("object-hash", requestId, hash);
        idempotency.put("object", requestId, result);
        log.info("监管对象登记: requestId={}, action={}, objectId={}, objectType={}, status={}, registerNo={}",
                requestId, req.action(), result.objectId(), req.objectType(), result.status(), result.registerNo());
        return result;
    }

    private AssetSuperviseDtos.ObjectResult createObject(AssetSuperviseDtos.ObjectRequest req) {
        String objectId = "OBJ" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000);
        SupervisedObject o = new SupervisedObject(objectId, req.objectType(), req.collateralId(), req.loanNo(),
                req.details() == null ? Map.of() : Map.copyOf(req.details()), "ACTIVE", registerNo());
        objectStore.put(objectId, o);
        return new AssetSuperviseDtos.ObjectResult("SUCCESS", "监管对象建档成功", objectId, "ACTIVE", o.registerNo);
    }

    private AssetSuperviseDtos.ObjectResult updateObject(AssetSuperviseDtos.ObjectRequest req) {
        SupervisedObject o = requireActiveObject(req.objectId());
        o.status = "CHANGED";
        o.updatedAt = now();
        return new AssetSuperviseDtos.ObjectResult("SUCCESS", "监管对象变更成功", o.objectId, "CHANGED", o.registerNo);
    }

    private AssetSuperviseDtos.ObjectResult terminateObject(AssetSuperviseDtos.ObjectRequest req) {
        SupervisedObject o = requireActiveObject(req.objectId());
        o.status = "TERMINATED";
        o.updatedAt = now();
        return new AssetSuperviseDtos.ObjectResult("SUCCESS", "监管对象终止成功", o.objectId, "TERMINATED", o.registerNo);
    }

    // ------------------------------------------------------------------ 4.3 监测数据采集

    /** 监测数据采集（FR-02，幂等 requestId）：清洗/质量校验/断连检测，更新对象当前数量/估值/状态 */
    public AssetSuperviseDtos.CollectResult collect(AssetSuperviseDtos.CollectRequest req, String requestId) {
        if (req == null || req.objectId() == null || req.objectId().isBlank()) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：objectId 必填");
        }
        if (req.dataSource() == null || !DATA_SOURCES.contains(req.dataSource())) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：dataSource 须为 IOT/WAREHOUSE/REGISTRY/MANUAL");
        }
        if (req.metrics() == null || req.metrics().isEmpty()) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：metrics 监测指标必填");
        }
        requireActiveObject(req.objectId());

        String hash = sha256(String.join("|", req.objectId(), req.dataSource(),
                String.valueOf(req.metrics()), str(req.rawRef())));
        String prevHash = idempotency.get("collect-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(AssetSuperviseErrorCodes.IDEMPOTENT_CONFLICT,
                        "ASS_4004 幂等冲突：requestId 重复且采集参数不同，请使用原 requestId 查询");
            }
            return idempotency.get("collect", requestId);
        }

        // 数据清洗与质量校验：code/value/time 齐全为有效，异常占比 >20% 抛 ASS_4101
        List<String> issues = new ArrayList<>();
        int valid = 0;
        LocalDateTime latest = null;
        for (AssetSuperviseDtos.CollectMetric m : req.metrics()) {
            boolean ok = m.code() != null && !m.code().isBlank() && m.value() != null;
            LocalDateTime t = parseDateTime(m.time(), "metrics.time");
            if (ok && t != null) {
                valid++;
                if (latest == null || t.isAfter(latest)) {
                    latest = t;
                }
            } else {
                issues.add("指标 " + str(m.code()) + " 数据非法（code/value/time 须齐全）");
            }
        }
        BigDecimal abnormalRate = BigDecimal.valueOf(req.metrics().size() - valid)
                .divide(BigDecimal.valueOf(req.metrics().size()), 4, RoundingMode.HALF_UP);
        if (abnormalRate.compareTo(MAX_ABNORMAL_RATE) > 0) {
            throw new BaiException(AssetSuperviseErrorCodes.DATA_QUALITY_LOW,
                    "ASS_4101 监测数据质量过低：异常占比 " + abnormalRate + " >20%，请重新采集或人工核实");
        }

        // 断连检测：最新上报时间距当前超 24h 标记 REGULATORY_LOSS
        String disconnected = null;
        if (latest != null && ChronoUnit.HOURS.between(latest, LocalDateTime.now()) > DISCONNECT_HOURS) {
            disconnected = "REGULATORY_LOSS";
        }

        // 回流对象当前状态：数量/估值/状态
        SupervisedObject o = objectStore.get(req.objectId());
        for (AssetSuperviseDtos.CollectMetric m : req.metrics()) {
            if ("QUANTITY".equals(m.code())) {
                o.currentQuantity = m.value();
            } else if ("VALUATION".equals(m.code())) {
                o.currentValuation = m.value();
                o.valuationTime = m.time();
            } else if ("STATUS".equals(m.code())) {
                o.seized = m.value() != null && m.value().compareTo(BigDecimal.ONE) == 0;
            }
        }
        if (latest != null) {
            o.lastDataTime = latest.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }

        AssetSuperviseDtos.QualityReport quality = new AssetSuperviseDtos.QualityReport(
                req.metrics().size(), valid, abnormalRate, issues);
        AssetSuperviseDtos.CollectResult result = new AssetSuperviseDtos.CollectResult(
                "CL" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000),
                req.objectId(), valid, quality, disconnected);
        idempotency.put("collect-hash", requestId, hash);
        idempotency.put("collect", requestId, result);
        log.info("监测数据采集: requestId={}, collectNo={}, objectId={}, source={}, accepted={}, disconnected={}",
                requestId, result.collectNo(), req.objectId(), req.dataSource(), valid, disconnected);
        return result;
    }

    // ------------------------------------------------------------------ 4.4 实时监控

    /** 实时监控（FR-03/FR-04，幂等 requestId）：动产数量/价值三维判定，不动产状态异动判定 */
    public AssetSuperviseDtos.MonitorResult monitor(AssetSuperviseDtos.MonitorRequest req, String requestId) {
        if (req == null || req.monitorType() == null || !MONITOR_TYPES.contains(req.monitorType())) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：monitorType 须为 MOVABLE/REAL_ESTATE");
        }
        SupervisedObject o = requireActiveObject(req.objectId());
        if (!req.monitorType().equals(o.objectType)) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：monitorType 与押品类型不匹配（对象类型 " + o.objectType + "）");
        }
        AssetSuperviseDtos.MonitorResult cached = idempotency.get("monitor", requestId);
        if (cached != null) {
            return cached;
        }

        List<AssetSuperviseDtos.Abnormal> abnormals = new ArrayList<>();
        if ("MOVABLE".equals(req.monitorType())) {
            // 监管失效检测：数据断连超 24h → ASS_4103
            if (o.lastDataTime != null && ChronoUnit.HOURS.between(parseDateTime(o.lastDataTime, "lastDataTime"),
                    LocalDateTime.now()) > DISCONNECT_HOURS) {
                throw new BaiException(AssetSuperviseErrorCodes.REGULATORY_LOSS,
                        "ASS_4103 监管失效：数据断连超 24 小时，请触发监管失效预警并现场核查");
            }
            // 数量监控：实际 vs 应存基准，|偏差| > ±2% → 数量异常
            BigDecimal expected = optNum(o.details, "expectedQuantity");
            if (o.currentQuantity != null && expected != null) {
                BigDecimal dev = o.currentQuantity.subtract(expected)
                        .divide(expected, 4, RoundingMode.HALF_UP);
                if (dev.abs().compareTo(QTY_DEVIATION_THRESHOLD) > 0) {
                    abnormals.add(new AssetSuperviseDtos.Abnormal("QUANTITY_ABNORMAL", "QUANTITY", dev,
                            "实际数量 " + o.currentQuantity + " vs 应存基准 " + expected + "，偏差 " + dev,
                            "补足差额/账实核对（客户经理，7 日内落实）"));
                }
            }
            // 价值监控：实时估值 vs 放款估值，跌穿 −10% → 价值预警
            BigDecimal loanValuation = optNum(o.details, "loanValuation");
            if (o.currentValuation != null && loanValuation != null) {
                BigDecimal change = o.currentValuation.subtract(loanValuation)
                        .divide(loanValuation, 4, RoundingMode.HALF_UP);
                if (change.compareTo(VALUE_DROP_THRESHOLD) < 0) {
                    abnormals.add(new AssetSuperviseDtos.Abnormal("VALUE_BREACH", "VALUATION", change,
                            "实时估值 " + o.currentValuation + " vs 放款估值 " + loanValuation + "，下跌 "
                                    + change.abs().multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%",
                            "追加担保/联动重估（valuation-agent）"));
                }
            }
        } else {
            // 不动产状态监控：查封/权属变更/毁损/占用 → 状态异动
            if (o.seized) {
                abnormals.add(new AssetSuperviseDtos.Abnormal("STATUS_CHANGE", "STATUS", null,
                        "权属状态异动：查封/毁损/占用，需现场核查（onsite-inspect）",
                        "法律处置协同（法务/保全域，即时）"));
            }
            // 估值过期：超出不动产估值有效期 180 日 → 提示级
            if (o.valuationTime != null && daysBetween(o.valuationTime) > VALIDITY_DAYS_REAL_ESTATE) {
                abnormals.add(new AssetSuperviseDtos.Abnormal("VALUATION_EXPIRED", "VALUATION", null,
                        "估值基准日 " + o.valuationTime + " 超 180 日有效期",
                        "触发重估后复核（valuation-agent，T+1）"));
            }
        }

        String conclusion = abnormals.isEmpty() ? "NORMAL" : "ABNORMAL";
        AssetSuperviseDtos.MonitorResult result = new AssetSuperviseDtos.MonitorResult(
                "MN" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000),
                o.objectId, conclusion, List.copyOf(abnormals));
        idempotency.put("monitor", requestId, result);
        monitorStore.put(result.monitorNo(), result);
        log.info("实时监控: requestId={}, monitorNo={}, objectId={}, conclusion={}, abnormals={}",
                requestId, result.monitorNo(), o.objectId, conclusion, abnormals.size());
        return result;
    }

    // ------------------------------------------------------------------ 4.5 质押率盯市

    /** 质押率盯市（FR-05）：LTV=融资余额÷实时估值，按押品类型分档预警线/处置线判定 NORMAL/WARNING/DISPOSAL；
     * 幂等键 objectId+时间戳，趋势与上一盯市记录比对 */
    public AssetSuperviseDtos.LtvResult ltv(AssetSuperviseDtos.LtvRequest req, String requestId) {
        if (req == null || req.objectId() == null || req.objectId().isBlank()) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：objectId 必填");
        }
        SupervisedObject o = requireActiveObject(req.objectId());
        if (req.valuation() == null) {
            throw new BaiException(AssetSuperviseErrorCodes.VALUATION_MISSING,
                    "ASS_4102 估值缺失或过期：请先触发重估（revalue）获取实时估值");
        }
        if (req.valuation().signum() <= 0) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：valuation 实时估值须为正数");
        }
        if (req.loanBalance() == null || req.loanBalance().signum() < 0) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：loanBalance 融资余额须为非负");
        }
        if (req.frequency() != null && !FREQUENCIES.contains(req.frequency())) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：frequency 须为 DAILY/MONTHLY/EVENT");
        }

        // 幂等：objectId + 时间戳（缺省取当前分钟）
        String timestamp = req.timestamp() == null || req.timestamp().isBlank()
                ? LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")) : req.timestamp();
        String idemKey = req.objectId() + "#" + timestamp;
        String prevHash = idempotency.get("ltv-hash", idemKey);
        if (prevHash != null) {
            if (!prevHash.equals(sha256(String.join("|", String.valueOf(req.valuation()), String.valueOf(req.loanBalance()))))) {
                throw new BaiException(AssetSuperviseErrorCodes.IDEMPOTENT_CONFLICT,
                        "ASS_4004 幂等冲突：同 objectId+时间戳 参数不同，请使用原盯市记录查询");
            }
            return idempotency.get("ltv", idemKey);
        }

        // 分档线：显式 lines 覆盖，否则按押品类型（动产 0.7/0.8、不动产 0.75/0.85、权利 0.8/0.9）
        BigDecimal warningLine;
        BigDecimal disposalLine;
        if (req.lines() != null && req.lines().warningLine() != null && req.lines().disposalLine() != null) {
            warningLine = req.lines().warningLine();
            disposalLine = req.lines().disposalLine();
            if (warningLine.compareTo(disposalLine) > 0) {
                throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                        "ASS_4001 参数校验失败：预警线须不高于处置线");
            }
        } else {
            warningLine = switch (o.objectType) {
                case "MOVABLE" -> new BigDecimal("0.70");
                case "REAL_ESTATE" -> new BigDecimal("0.75");
                default -> new BigDecimal("0.80"); // WAREHOUSE_RECEIPT/REGULATED_WAREHOUSE（权利）
            };
            disposalLine = switch (o.objectType) {
                case "MOVABLE" -> new BigDecimal("0.80");
                case "REAL_ESTATE" -> new BigDecimal("0.85");
                default -> new BigDecimal("0.90");
            };
        }

        BigDecimal ltvValue = req.loanBalance().divide(req.valuation(), 4, RoundingMode.HALF_UP);
        if (ltvValue.signum() < 0) {
            throw new BaiException(AssetSuperviseErrorCodes.INTERNAL_ERROR,
                    "ASS_5001 内部计算错误：LTV 计算结果为负（" + ltvValue + "）");
        }
        String status = ltvValue.compareTo(warningLine) <= 0 ? "NORMAL"
                : ltvValue.compareTo(disposalLine) <= 0 ? "WARNING" : "DISPOSAL";
        String trend = trendOf(o.objectId, ltvValue);

        AssetSuperviseDtos.LtvResult result = new AssetSuperviseDtos.LtvResult(
                "LT" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000),
                o.objectId, ltvValue, req.valuation(), req.loanBalance(), status, trend,
                warningLine, disposalLine, str(req.frequency()), timestamp);
        idempotency.put("ltv-hash", idemKey,
                sha256(String.join("|", String.valueOf(req.valuation()), String.valueOf(req.loanBalance()))));
        idempotency.put("ltv", idemKey, result);
        ltvStore.put(result.ltvNo(), result);
        objectLtvs.computeIfAbsent(o.objectId, k -> new ArrayList<>()).add(result);
        log.info("质押率盯市: requestId={}, ltvNo={}, objectId={}, ltv={}, status={}, trend={}, lines={}/{}",
                requestId, result.ltvNo(), o.objectId, ltvValue, status, trend, warningLine, disposalLine);
        return result;
    }

    /** 趋势：与对象最近一条盯市记录比对 */
    private String trendOf(String objectId, BigDecimal current) {
        List<AssetSuperviseDtos.LtvResult> list = objectLtvs.get(objectId);
        if (list == null || list.isEmpty()) {
            return "NONE";
        }
        BigDecimal prev = list.get(list.size() - 1).ltv();
        int cmp = current.compareTo(prev);
        return cmp > 0 ? "UP" : cmp < 0 ? "DOWN" : "FLAT";
    }

    // ------------------------------------------------------------------ 4.6 预警触发与处置

    /** 预警触发与处置协同（FR-06/FR-07，幂等 requestId）：按预警类型分级并匹配处置策略生成处置任务 */
    public AssetSuperviseDtos.AlertResult alert(AssetSuperviseDtos.AlertRequest req, String requestId) {
        if (req == null || req.alertType() == null || !ALERT_TYPES.contains(req.alertType())) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：alertType 须为 VALUE_BREACH/QUANTITY_ABNORMAL/REGULATORY_LOSS/LTV_OVER/STATUS_CHANGE");
        }
        SupervisedObject o = requireActiveObject(req.objectId());
        if (req.strategyVersion() != null && !req.strategyVersion().isBlank()
                && !STRATEGY_VERSION.equals(req.strategyVersion())) {
            throw new BaiException(AssetSuperviseErrorCodes.STRATEGY_NOT_FOUND,
                    "ASS_4104 处置策略不存在：strategyVersion 须为当前生效版本 " + STRATEGY_VERSION);
        }
        String hash = sha256(String.join("|", req.objectId(), req.alertType(), str(req.evidence()),
                str(req.strategyVersion())));
        String prevHash = idempotency.get("alert-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(AssetSuperviseErrorCodes.IDEMPOTENT_CONFLICT,
                        "ASS_4004 幂等冲突：requestId 重复且预警参数不同，请使用原 requestId 查询");
            }
            return idempotency.get("alert", requestId);
        }

        String level;
        List<AssetSuperviseDtos.AlertTask> tasks;
        String evidence = str(req.evidence());
        switch (req.alertType()) {
            case "VALUE_BREACH" -> {
                // A01 价值跌穿：实时估值较放款估值下跌超 10% → 警告，追加担保 7 日
                level = "WARNING";
                tasks = List.of(task("追加担保/补足差额", "客户经理", plusDays(7)));
            }
            case "QUANTITY_ABNORMAL" -> {
                // A02 数量异常：偏差超 ±2% 或账实不符 → 警告
                level = "WARNING";
                tasks = List.of(task("补足差额/账实核对", "客户经理", plusDays(7)));
            }
            case "REGULATORY_LOSS" -> {
                // A03 监管失效：断连超 24h → 严重，立即现场核查+暂停用信 24h 内
                level = "SEVERE";
                tasks = List.of(task("现场核查+暂停用信", "客户经理+监管机构", plusDays(1)));
            }
            case "LTV_OVER" -> {
                // A04/A05 盯市超限：越处置线（evidence 含 DISPOSAL）严重，否则警告
                if (evidence.contains("DISPOSAL") || evidence.contains("SEVERE")) {
                    level = "SEVERE";
                    tasks = List.of(task("处置变现/解除合作", "保全/贷后域", plusDays(30)));
                } else {
                    level = "WARNING";
                    tasks = List.of(task("追加担保/补足差额", "客户经理", plusDays(7)));
                }
            }
            default -> {
                // A06 状态异动：查封/权属变更/毁损/占用 → 严重，法律处置协同即时
                level = "SEVERE";
                tasks = List.of(task("法律处置协同", "法务/保全域", plusDays(0)));
            }
        }

        AssetSuperviseDtos.AlertResult result = new AssetSuperviseDtos.AlertResult(
                "AN" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000),
                "AL" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000),
                o.objectId, req.alertType(), level, tasks, evidence, str(req.strategyVersion()));
        idempotency.put("alert-hash", requestId, hash);
        idempotency.put("alert", requestId, result);
        alertStore.put(result.alertNo(), result);
        log.info("预警处置: requestId={}, alertNo={}, objectId={}, type={}, level={}, tasks={}",
                requestId, result.alertNo(), o.objectId, req.alertType(), level, tasks.size());
        return result;
    }

    private AssetSuperviseDtos.AlertTask task(String taskType, String assignee, LocalDate deadline) {
        return new AssetSuperviseDtos.AlertTask(
                "TK" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000),
                taskType, assignee, deadline.toString(), "CREATED");
    }

    // ------------------------------------------------------------------ 4.7 押品重估联动

    /** 押品重估联动（FR-08，幂等 requestId）：估值缺失/过期触发重估，结果回流更新盯市基准 */
    public AssetSuperviseDtos.RevalueResult revalue(AssetSuperviseDtos.RevalueRequest req, String requestId) {
        if (req == null || req.triggerType() == null || !TRIGGER_TYPES.contains(req.triggerType())) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：triggerType 须为 PERIODIC/MARKET_CHANGE/ALERT/REQUIRED");
        }
        SupervisedObject o = requireActiveObject(req.objectId());
        String hash = sha256(String.join("|", req.objectId(), req.triggerType(), String.valueOf(req.valuationRequest())));
        String prevHash = idempotency.get("revalue-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(AssetSuperviseErrorCodes.IDEMPOTENT_CONFLICT,
                        "ASS_4004 幂等冲突：requestId 重复且重估参数不同，请使用原 requestId 查询");
            }
            return idempotency.get("revalue", requestId);
        }

        long validityDays = "REAL_ESTATE".equals(o.objectType) ? VALIDITY_DAYS_REAL_ESTATE : VALIDITY_DAYS_MOVABLE;
        boolean expired = o.valuationTime == null || daysBetween(o.valuationTime) > validityDays;

        BigDecimal newValuation = req.valuationRequest() == null ? null
                : optNum(req.valuationRequest(), "valuation");
        boolean benchmarkUpdated;
        String message;
        if ("PERIODIC".equals(req.triggerType()) && !expired) {
            // 周期触发但估值未过期：无需重估
            benchmarkUpdated = false;
            message = "估值未过期（有效期 " + validityDays + " 日），无需重估";
        } else {
            // 估值缺失/过期或市场异动/预警/业务要求触发：须附新估值，结果回流盯市基准
            if (newValuation == null || newValuation.signum() <= 0) {
                throw new BaiException(AssetSuperviseErrorCodes.VALUATION_MISSING,
                        "ASS_4102 估值缺失或过期：重估请求须附新估值 valuation（>0）");
            }
            o.currentValuation = newValuation;
            o.valuationTime = now();
            benchmarkUpdated = true;
            message = "重估完成，估值 " + newValuation + " 已回流盯市基准";
        }

        AssetSuperviseDtos.RevalueResult result = new AssetSuperviseDtos.RevalueResult(
                "RV" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000),
                "RT" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000),
                o.objectId, req.triggerType(),
                benchmarkUpdated ? newValuation : o.currentValuation, benchmarkUpdated, message);
        idempotency.put("revalue-hash", requestId, hash);
        idempotency.put("revalue", requestId, result);
        revalueStore.put(result.revalueNo(), result);
        log.info("押品重估联动: requestId={}, revalueNo={}, objectId={}, triggerType={}, benchmarkUpdated={}",
                requestId, result.revalueNo(), o.objectId, req.triggerType(), benchmarkUpdated);
        return result;
    }

    // ------------------------------------------------------------------ 查询

    /** 盯市/监控/预警/处置记录查询（FR-10 留痕消费）：type=OBJECT|MONITOR|LTV|ALERT，按 objectId/no 过滤分页 */
    public AssetSuperviseDtos.QueryResult query(String type, String objectId, String no, int page, int size,
                                                String requestId) {
        if (type == null || !QUERY_TYPES.contains(type)) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：type 须为 OBJECT/MONITOR/LTV/ALERT");
        }
        if (page < 1) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：page 须为正整数");
        }
        if (size < 1 || size > 100) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：size 须在 [1,100] 内");
        }

        List<Object> matched;
        switch (type) {
            case "OBJECT" -> matched = objectStore.values().stream()
                    .filter(o -> objectId == null || objectId.isBlank() || objectId.equals(o.objectId))
                    .filter(o -> no == null || no.isBlank() || no.equals(o.registerNo))
                    .sorted(Comparator.comparing((SupervisedObject o) -> o.updatedAt).reversed())
                    .map(o -> (Object) new AssetSuperviseDtos.SupervisedObjectView(o.objectId, o.objectType,
                            o.collateralId, o.loanNo, o.status, o.registerNo, o.updatedAt))
                    .toList();
            case "MONITOR" -> matched = monitorStore.values().stream()
                    .filter(r -> objectId == null || objectId.isBlank() || objectId.equals(r.objectId()))
                    .filter(r -> no == null || no.isBlank() || no.equals(r.monitorNo()))
                    .sorted(Comparator.comparing(AssetSuperviseDtos.MonitorResult::monitorNo).reversed())
                    .map(r -> (Object) r)
                    .toList();
            case "LTV" -> matched = ltvStore.values().stream()
                    .filter(r -> objectId == null || objectId.isBlank() || objectId.equals(r.objectId()))
                    .filter(r -> no == null || no.isBlank() || no.equals(r.ltvNo()))
                    .sorted(Comparator.comparing(AssetSuperviseDtos.LtvResult::ltvNo).reversed())
                    .map(r -> (Object) r)
                    .toList();
            default -> matched = alertStore.values().stream()
                    .filter(r -> objectId == null || objectId.isBlank() || objectId.equals(r.objectId()))
                    .filter(r -> no == null || no.isBlank() || no.equals(r.alertNo()))
                    .sorted(Comparator.comparing(AssetSuperviseDtos.AlertResult::alertNo).reversed())
                    .map(r -> (Object) r)
                    .toList();
        }
        if (matched.isEmpty()) {
            throw new BaiException(AssetSuperviseErrorCodes.OBJECT_NOT_FOUND,
                    "ASS_4002 无匹配记录：type=" + type + " 无 " + (objectId == null ? "" : "objectId=" + objectId + " ")
                            + (no == null ? "" : "no=" + no));
        }
        log.info("监管记录查询: requestId={}, type={}, objectId={}, hit={}", requestId, type, objectId, matched.size());
        return new AssetSuperviseDtos.QueryResult(matched.size(), page, size, pageOf(matched, page, size));
    }

    // ------------------------------------------------------------------ 工具

    private void requireObjectNotExists(String objectId) {
        if (objectId != null && !objectId.isBlank() && objectStore.containsKey(objectId)) {
            throw new BaiException(AssetSuperviseErrorCodes.OBJECT_NOT_FOUND,
                    "ASS_4002 监管对象已存在：" + objectId + "，CREATE 请勿携带已存在对象标识");
        }
    }

    private SupervisedObject requireActiveObject(String objectId) {
        if (objectId == null || objectId.isBlank()) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：objectId 必填");
        }
        SupervisedObject o = objectStore.get(objectId);
        if (o == null || "TERMINATED".equals(o.status)) {
            throw new BaiException(AssetSuperviseErrorCodes.OBJECT_NOT_FOUND,
                    "ASS_4002 监管对象不存在或已终止：" + objectId);
        }
        return o;
    }

    private String registerNo() {
        return "AS" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000);
    }

    private static List<Object> pageOf(List<Object> all, int page, int size) {
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return all.subList(from, to);
    }

    private static BigDecimal optNum(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                    "ASS_4001 参数校验失败：details." + key + " 须为数值");
        }
    }

    private static LocalDateTime parseDateTime(String s, String field) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(s).atStartOfDay();
            } catch (DateTimeParseException e2) {
                throw new BaiException(AssetSuperviseErrorCodes.PARAM_INVALID,
                        "ASS_4001 参数校验失败：" + field + " 须为 ISO 日期时间（yyyy-MM-dd'T'HH:mm:ss 或 yyyy-MM-dd）");
            }
        }
    }

    private static long daysBetween(String isoDateTime) {
        return ChronoUnit.DAYS.between(parseDateTime(isoDateTime, "valuationTime"), LocalDateTime.now());
    }

    private static String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static LocalDate plusDays(int days) {
        return LocalDate.now().plusDays(days);
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

    /** 监管对象台账（as_object 行结构；采集回流 currentQuantity/currentValuation/valuationTime/seized/lastDataTime） */
    private static final class SupervisedObject {
        final String objectId;
        final String objectType;
        final String collateralId;
        final String loanNo;
        final Map<String, Object> details;
        String status;
        String registerNo;
        String updatedAt;
        BigDecimal currentQuantity;
        BigDecimal currentValuation;
        String valuationTime;
        boolean seized;
        String lastDataTime;

        SupervisedObject(String objectId, String objectType, String collateralId, String loanNo,
                         Map<String, Object> details, String status, String registerNo) {
            this.objectId = objectId;
            this.objectType = objectType;
            this.collateralId = collateralId;
            this.loanNo = loanNo;
            this.details = details;
            this.status = status;
            this.registerNo = registerNo;
            this.updatedAt = now();
            this.seized = false;
        }
    }
}
