package com.bai.valuation;

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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 资产估值服务（提示词库 A-57）。
 * <p>处理链：校验标的要素 → 按 method 走估值路径：
 * 市场法 value = 数量（面积/件数/面值）× 可比单价（NPL 为 面值×回收率）；
 * 收益法 value = 年净收益 × 年金现值系数 [1-(1+r)^-n]/r（+ 终值折现）；
 * 成本法 value = 重置成本 × 成新率 → 计算区间 range(low, high) → 判定 conclusion（VALUED/MANUAL）→
 * 市场数据带时间戳、超期 180 天标记 STALE → 落审计。
 * <p>约束：low ≤ value ≤ high 恒等；估值结果为负返回 VAL_1001；市场数据冲突（caseLow>caseHigh）返回 VAL_1005。
 * <p>幂等：requestId + 标的哈希为键；同 requestId 不同要素视为市场数据冲突（VAL_1005）。
 */
@Service
public class ValuationService {

    private static final Logger log = LoggerFactory.getLogger(ValuationService.class);

    private static final Set<String> ASSET_TYPES = Set.of("REAL_ESTATE", "EQUIPMENT", "INVENTORY", "NPL");
    private static final Set<String> METHODS = Set.of("MARKET", "INCOME", "COST");
    /** 市场数据陈旧阈值（天）：超期标记 STALE 并转人工估值 */
    private static final long STALE_DAYS = 180;
    /** 无可比案例区间时的默认置信区间 ±10% */
    private static final BigDecimal RANGE_BAND = new BigDecimal("0.10");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final IdempotencyStore idempotency = new IdempotencyStore();
    /** 估值留痕：valueId → 记录（生产落 val_case 表，本阶段内存仿真） */
    private final Map<String, ValuationDtos.ValuationRecord> valuationStore = new ConcurrentHashMap<>();
    /** 估值反馈：valueId → feedbackId（生产落 val_feedback 表，uk_value 唯一） */
    private final Map<String, String> feedbackStore = new ConcurrentHashMap<>();
    /** 市场数据：assetType|region|industry → 数据（生产走 external-data/Redis，本阶段内存仿真） */
    private final Map<String, MarketData> marketStore = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 估值计算（幂等：同 requestId+标的哈希返回首次结果，缓存 24h） */
    public ValuationDtos.ValuationResult value(ValuationDtos.ValuationRequest req, String requestId) {
        validate(req, requestId);

        String hash = sha256(String.join("|",
                str(req.assetType()), str(req.method()), str(req.assetRef()),
                str(req.valueDate()), String.valueOf(req.params())));
        String prevHash = idempotency.get("value-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(ValuationErrorCodes.DATA_CONFLICT,
                        "VAL_1005 重复提交/估值要素冲突，请使用原 requestId 查询首次估值结果");
            }
            return idempotency.get("value", requestId);
        }

        ValuationDtos.ValuationResult result = compute(req);
        idempotency.put("value-hash", requestId, hash);
        idempotency.put("value", requestId, result);
        recordValuation(requestId, req, result);
        log.info("估值完成: requestId={}, assetType={}, method={}, value={}, conclusion={}, basisRef={}",
                requestId, result.assetType(), result.method(), result.value(), result.conclusion(), result.basisRef());
        return result;
    }

    /** 估值留痕（FR-10）：写 val_case 内存仿真，供 /query 查询与 /feedback 关联 */
    private void recordValuation(String requestId, ValuationDtos.ValuationRequest req,
                                 ValuationDtos.ValuationResult result) {
        String valueId = "VL" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000);
        ValuationDtos.ValuationRecord record = new ValuationDtos.ValuationRecord(valueId,
                req.assetRef(), req.assetType(), result.value(), result.range(),
                List.of(new ValuationDtos.MethodResult(req.method(), result.value(), BigDecimal.ONE, result.basisRef())),
                result.conclusion(), LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        valuationStore.put(valueId, record);
        log.info("估值留痕: requestId={}, valueId={}, assetId={}, createdAt={}",
                requestId, valueId, record.assetId(), record.createdAt());
    }

    // ------------------------------------------------------------------ query

    /** 估值查询：按 valueId 或押品/资产（assetId/assetType）查询历史估值记录，分页返回；无记录抛 VAL_1004 */
    public ValuationDtos.ValuationQueryResult query(String valueId, String assetId, String assetType,
                                                    int page, int size, String requestId) {
        if (page < 1) {
            throw new BaiException(ValuationErrorCodes.PARAM_INVALID, "VAL_1001 参数非法：page 须为正整数");
        }
        if (size < 1 || size > 100) {
            throw new BaiException(ValuationErrorCodes.PARAM_INVALID, "VAL_1001 参数非法：size 须在 [1,100] 内");
        }
        if (valueId == null || valueId.isBlank()) {
            // 按押品/资产维度查询：assetId/assetType 至少一项
            if ((assetId == null || assetId.isBlank()) && (assetType == null || assetType.isBlank())) {
                throw new BaiException(ValuationErrorCodes.PARAM_INVALID,
                        "VAL_1001 参数缺失：valueId 或 assetId/assetType 至少一项必填");
            }
            if (assetType != null && !assetType.isBlank() && !ASSET_TYPES.contains(assetType)) {
                throw new BaiException(ValuationErrorCodes.ELEMENT_ILLEGAL,
                        "VAL_1007 资产类型非法：assetType 须为 REAL_ESTATE/EQUIPMENT/INVENTORY/NPL");
            }
            List<ValuationDtos.ValuationRecord> matched = valuationStore.values().stream()
                    .filter(r -> assetId == null || assetId.isBlank() || assetId.equals(r.assetId()))
                    .filter(r -> assetType == null || assetType.isBlank() || assetType.equals(r.assetType()))
                    .sorted(Comparator.comparing(ValuationDtos.ValuationRecord::createdAt).reversed())
                    .toList();
            if (matched.isEmpty()) {
                throw new BaiException(ValuationErrorCodes.NOT_FOUND,
                        "VAL_1004 无估值记录：assetId/assetType 无匹配");
            }
            return pageOf(matched, page, size);
        }
        ValuationDtos.ValuationRecord record = valuationStore.get(valueId);
        if (record == null) {
            throw new BaiException(ValuationErrorCodes.NOT_FOUND, "VAL_1004 估值记录不存在：" + valueId);
        }
        log.info("估值查询: requestId={}, valueId={}, hit=1", requestId, valueId);
        return new ValuationDtos.ValuationQueryResult(1, page, size, List.of(record));
    }

    // -------------------------------------------------------------- feedback

    /** 估值反馈（FR-09）：人工结论回流（ACCEPT|ADJUST|REJECT），feedbackId 受理；幂等 requestId */
    public ValuationDtos.FeedbackResult feedback(ValuationDtos.FeedbackRequest req, String requestId) {
        long start = System.currentTimeMillis();
        if (req == null || req.valueId() == null || req.valueId().isBlank()) {
            throw new BaiException(ValuationErrorCodes.PARAM_INVALID, "VAL_1001 参数缺失：valueId 必填");
        }
        if (req.humanConclusion() == null || !Set.of("ACCEPT", "ADJUST", "REJECT").contains(req.humanConclusion())) {
            throw new BaiException(ValuationErrorCodes.ELEMENT_ILLEGAL,
                    "VAL_1007 反馈结论非法：humanConclusion 须为 ACCEPT/ADJUST/REJECT");
        }
        if (req.reviewer() == null || req.reviewer().isBlank()) {
            throw new BaiException(ValuationErrorCodes.PARAM_INVALID, "VAL_1001 参数缺失：reviewer 必填");
        }
        if ("ADJUST".equals(req.humanConclusion()) && req.adjustValue() == null) {
            throw new BaiException(ValuationErrorCodes.PARAM_INVALID,
                    "VAL_1001 参数缺失：ADJUST 结论须附调整值 adjustValue");
        }
        if (req.adjustValue() != null && req.adjustValue().signum() < 0) {
            throw new BaiException(ValuationErrorCodes.PARAM_INVALID, "VAL_1001 参数非法：adjustValue 须为非负");
        }
        if (req.methodAdjust() != null && req.methodAdjust().method() != null
                && !METHODS.contains(req.methodAdjust().method())) {
            throw new BaiException(ValuationErrorCodes.ELEMENT_ILLEGAL,
                    "VAL_1007 方法非法：methodAdjust.method 须为 MARKET/INCOME/COST");
        }
        ValuationDtos.FeedbackResult cached = idempotency.get("feedback", requestId);
        if (cached != null) {
            return cached;
        }
        if (!valuationStore.containsKey(req.valueId())) {
            throw new BaiException(ValuationErrorCodes.NOT_FOUND, "VAL_1004 估值记录不存在：" + req.valueId());
        }
        if (feedbackStore.containsKey(req.valueId())) {
            throw new BaiException(ValuationErrorCodes.DATA_CONFLICT,
                    "VAL_1005 重复反馈：估值 " + req.valueId() + " 已有反馈（uk_value 唯一）");
        }

        String feedbackId = "FB" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000);
        feedbackStore.put(req.valueId(), feedbackId);
        ValuationDtos.FeedbackResult result = new ValuationDtos.FeedbackResult(feedbackId, "RECEIVED",
                System.currentTimeMillis() - start);
        idempotency.put("feedback", requestId, result);
        log.info("估值反馈受理: requestId={}, feedbackId={}, valueId={}, humanConclusion={}, reviewer={}, adjustValue={}",
                requestId, feedbackId, req.valueId(), req.humanConclusion(), req.reviewer(), req.adjustValue());
        return result;
    }

    // ----------------------------------------------------------------- market

    /** 市场数据接入（异步仿真）：action=REFRESH|QUERY|UPDATE，数据版本递增，幂等 requestId */
    public ValuationDtos.MarketResult market(ValuationDtos.MarketRequest req, String requestId) {
        long start = System.currentTimeMillis();
        if (req == null) {
            throw new BaiException(ValuationErrorCodes.PARAM_INVALID, "VAL_1001 参数缺失：请求体必填");
        }
        String action = req.action() == null || req.action().isBlank() ? "REFRESH" : req.action().toUpperCase();
        if (!Set.of("REFRESH", "QUERY", "UPDATE").contains(action)) {
            throw new BaiException(ValuationErrorCodes.ELEMENT_ILLEGAL,
                    "VAL_1007 action 非法：允许 REFRESH/QUERY/UPDATE");
        }
        if (req.assetType() == null || !ASSET_TYPES.contains(req.assetType())) {
            throw new BaiException(ValuationErrorCodes.ELEMENT_ILLEGAL,
                    "VAL_1007 资产类型非法：assetType 须为 REAL_ESTATE/EQUIPMENT/INVENTORY/NPL");
        }
        if (!"QUERY".equals(action) && (req.data() == null || req.data().isEmpty())) {
            throw new BaiException(ValuationErrorCodes.PARAM_INVALID,
                    "VAL_1001 参数缺失：REFRESH/UPDATE 须附市场数据 data（行情日期/成交均价/价格指数）");
        }
        for (ValuationDtos.MarketPoint p : req.data() == null ? List.<ValuationDtos.MarketPoint>of() : req.data()) {
            parseDate(p.date(), "data.date");
            if (p.price() != null && p.price().signum() < 0) {
                throw new BaiException(ValuationErrorCodes.PARAM_INVALID, "VAL_1001 参数非法：data.price 须为非负");
            }
            if (p.priceIndex() != null && p.priceIndex().signum() < 0) {
                throw new BaiException(ValuationErrorCodes.PARAM_INVALID, "VAL_1001 参数非法：data.priceIndex 须为非负");
            }
        }
        ValuationDtos.MarketResult cached = idempotency.get("market", requestId);
        if (cached != null) {
            return cached;
        }

        String key = req.assetType() + "|" + str(req.region()) + "|" + str(req.industry());
        String dataId = "MK" + LocalDate.now() + String.format("%04d", seq.incrementAndGet() % 10000);
        MarketData current = marketStore.get(key);
        String status;
        int version;
        int indexed;
        switch (action) {
            case "REFRESH" -> {
                version = current == null ? 1 : current.version + 1;
                marketStore.put(key, new MarketData(version, req.data(), LocalDateTime.now()
                        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
                status = "ACCEPTED";
                indexed = req.data().size();
            }
            case "UPDATE" -> {
                // 按行情日期合并去重（新数据覆盖同日期旧数据）
                Map<String, ValuationDtos.MarketPoint> merged = new java.util.LinkedHashMap<>();
                if (current != null) {
                    for (ValuationDtos.MarketPoint p : current.points) {
                        merged.put(p.date(), p);
                    }
                }
                for (ValuationDtos.MarketPoint p : req.data()) {
                    merged.put(p.date(), p);
                }
                version = current == null ? 1 : current.version + 1;
                marketStore.put(key, new MarketData(version, List.copyOf(merged.values()),
                        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
                status = "UPDATED";
                indexed = merged.size();
            }
            default -> {
                // QUERY：读取当前市场数据，无数据抛 VAL_1004
                MarketData existing = marketStore.get(key);
                if (existing == null) {
                    throw new BaiException(ValuationErrorCodes.NOT_FOUND,
                            "VAL_1004 市场数据不存在：assetType=" + req.assetType() + " 尚未接入");
                }
                version = existing.version;
                status = "QUERIED";
                indexed = existing.points.size();
            }
        }
        int accepted = "QUERY".equals(action) ? 0 : req.data().size();
        ValuationDtos.MarketResult result = new ValuationDtos.MarketResult(dataId, status, version,
                accepted, indexed, System.currentTimeMillis() - start);
        idempotency.put("market", requestId, result);
        log.info("市场数据接入: requestId={}, action={}, dataId={}, assetType={}, version={}, accepted={}, indexed={}",
                requestId, action, dataId, req.assetType(), version, accepted, indexed);
        return result;
    }

    /** 分页截取查询结果 */
    private ValuationDtos.ValuationQueryResult pageOf(List<ValuationDtos.ValuationRecord> all, int page, int size) {
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return new ValuationDtos.ValuationQueryResult(all.size(), page, size, all.subList(from, to));
    }

    private void validate(ValuationDtos.ValuationRequest req, String requestId) {
        if (req == null || req.assetType() == null || !ASSET_TYPES.contains(req.assetType())) {
            throw new BaiException(ValuationErrorCodes.ELEMENT_ILLEGAL,
                    "VAL_1007 资产类型非法：assetType 须为 REAL_ESTATE/EQUIPMENT/INVENTORY/NPL");
        }
        if (req.method() == null || !METHODS.contains(req.method())) {
            throw new BaiException(ValuationErrorCodes.ELEMENT_ILLEGAL,
                    "VAL_1007 估值方法非法：method 须为 MARKET/INCOME/COST");
        }
        if (req.assetRef() == null || req.assetRef().isBlank()) {
            throw new BaiException(ValuationErrorCodes.NOT_FOUND, "VAL_1004 标的不存在：assetRef 必填");
        }
        if (req.params() == null || req.params().isEmpty()) {
            throw new BaiException(ValuationErrorCodes.PARAM_INVALID, "VAL_1001 参数缺失：params 估值参数必填");
        }
        parseDate(req.valueDate(), "valueDate");
    }

    /** 按估值方法计算 value 与区间，并判定结论 */
    private ValuationDtos.ValuationResult compute(ValuationDtos.ValuationRequest req) {
        LocalDate valueDate = LocalDate.parse(req.valueDate());
        BigDecimal value;
        BigDecimal low;
        BigDecimal high;
        String basisRef;
        switch (req.method()) {
            case "MARKET" -> {
                BigDecimal qty;
                BigDecimal unitPrice;
                if ("REAL_ESTATE".equals(req.assetType())) {
                    qty = num(req.params(), "area");
                    unitPrice = num(req.params(), "unitPrice");
                } else if ("NPL".equals(req.assetType())) {
                    qty = num(req.params(), "faceAmount");
                    unitPrice = num(req.params(), "recoveryRate");
                    if (unitPrice.signum() <= 0 || unitPrice.compareTo(BigDecimal.ONE) > 0) {
                        throw new BaiException(ValuationErrorCodes.PARAM_INVALID,
                                "VAL_1001 参数非法：NPL 回收率 recoveryRate 须在 (0,1] 内");
                    }
                } else {
                    qty = num(req.params(), "quantity");
                    unitPrice = num(req.params(), "unitPrice");
                }
                value = qty.multiply(unitPrice);
                // 可比案例区间（caseLow/caseHigh，与数量同口径单价）；冲突（low>high）返回 VAL_1005
                BigDecimal caseLow = optNum(req.params(), "caseLow");
                BigDecimal caseHigh = optNum(req.params(), "caseHigh");
                if (caseLow != null && caseHigh != null) {
                    if (caseLow.compareTo(caseHigh) > 0) {
                        throw new BaiException(ValuationErrorCodes.DATA_CONFLICT,
                                "VAL_1005 市场数据冲突：可比案例下限 " + caseLow + " 高于上限 " + caseHigh);
                    }
                    low = qty.multiply(caseLow);
                    high = qty.multiply(caseHigh);
                } else {
                    low = value.multiply(BigDecimal.ONE.subtract(RANGE_BAND));
                    high = value.multiply(BigDecimal.ONE.add(RANGE_BAND));
                }
                basisRef = "NPL".equals(req.assetType())
                        ? "回收参数：" + req.assetRef() + " 面值 " + qty + " 元 × 回收率 " + unitPrice
                        : "可比案例：" + req.assetRef() + " 近期成交单价 " + unitPrice + "（N=3）";
            }
            case "INCOME" -> {
                BigDecimal annualIncome = num(req.params(), "annualIncome");
                BigDecimal discountRate = num(req.params(), "discountRate");
                BigDecimal years = num(req.params(), "years");
                if (discountRate.signum() <= 0 || discountRate.compareTo(BigDecimal.ONE) >= 0) {
                    throw new BaiException(ValuationErrorCodes.PARAM_INVALID,
                            "VAL_1001 参数非法：折现率 discountRate 须在 (0,1) 内");
                }
                if (years.signum() < 1 || years.remainder(BigDecimal.ONE).signum() != 0) {
                    throw new BaiException(ValuationErrorCodes.PARAM_INVALID, "VAL_1001 参数非法：收益年限 years 须为正整数");
                }
                int n = years.intValue();
                double r = discountRate.doubleValue();
                // 年金现值：Σ CF/(1+r)^t = CF × [1-(1+r)^-n]/r
                double factor = (1 - Math.pow(1 + r, -n)) / r;
                value = annualIncome.multiply(BigDecimal.valueOf(factor));
                // 终值折现（可选）：+ TV/(1+r)^n
                BigDecimal terminal = optNum(req.params(), "terminalValue");
                if (terminal != null) {
                    value = value.add(terminal.multiply(BigDecimal.valueOf(Math.pow(1 + r, -n))));
                }
                low = value.multiply(BigDecimal.ONE.subtract(RANGE_BAND));
                high = value.multiply(BigDecimal.ONE.add(RANGE_BAND));
                basisRef = "收益参数：" + req.assetRef() + " 年净收益 " + annualIncome + " 元，折现率 " + discountRate
                        + "，期限 " + n + " 年";
            }
            default -> {
                // COST 成本法：重置成本 × 成新率
                BigDecimal replacementCost = num(req.params(), "replacementCost");
                BigDecimal newnessRate = num(req.params(), "newnessRate");
                if (newnessRate.signum() < 0 || newnessRate.compareTo(BigDecimal.ONE) > 0) {
                    throw new BaiException(ValuationErrorCodes.PARAM_INVALID,
                            "VAL_1001 参数非法：成新率 newnessRate 须在 [0,1] 内");
                }
                value = replacementCost.multiply(newnessRate);
                low = value.multiply(BigDecimal.ONE.subtract(RANGE_BAND));
                high = value.multiply(BigDecimal.ONE.add(RANGE_BAND));
                basisRef = "成本参数：" + req.assetRef() + " 重置成本 " + replacementCost + " 元，成新率 " + newnessRate;
            }
        }

        value = value.setScale(2, RoundingMode.HALF_UP);
        if (value.signum() < 0) {
            throw new BaiException(ValuationErrorCodes.PARAM_INVALID, "VAL_1001 参数非法：估值结果为负（" + value + "）");
        }
        // 区间约束：low ≤ value ≤ high 恒等（舍入后兜底钳位）
        BigDecimal lowV = low.setScale(2, RoundingMode.HALF_UP).min(value);
        BigDecimal highV = high.setScale(2, RoundingMode.HALF_UP).max(value);

        // 市场数据时间戳：带 marketDate 比对陈旧度，超期标记 STALE 并转人工估值
        String marketDate = str(req.params().get("marketDate"));
        String conclusion = "VALUED";
        if ("NPL".equals(req.assetType())) {
            conclusion = "MANUAL";
            basisRef += "；需人工复核：不良资产估值须人工确认处置口径";
        } else if (!marketDate.isBlank()) {
            LocalDate md;
            try {
                md = LocalDate.parse(marketDate);
            } catch (DateTimeParseException e) {
                throw new BaiException(ValuationErrorCodes.PARAM_INVALID,
                        "VAL_1001 参数非法：marketDate 须为 ISO 日期（yyyy-MM-dd）");
            }
            long days = ChronoUnit.DAYS.between(md, valueDate);
            if (days > STALE_DAYS) {
                conclusion = "MANUAL";
                basisRef = "STALE（市场数据基准日 " + marketDate + " 距估值基准日超 " + STALE_DAYS + " 天）" + basisRef
                        + "；需人工复核：市场数据陈旧";
            }
        }

        return new ValuationDtos.ValuationResult("SUCCESS", req.assetType(), req.method(), value,
                new ValuationDtos.ValueRange(lowV, highV), conclusion, req.valueDate(), basisRef);
    }

    /** 必填数值参数（支持数值与可解析字符串），缺失抛 VAL_1001 */
    private static BigDecimal num(Map<String, Object> params, String key) {
        BigDecimal v = optNum(params, key);
        if (v == null) {
            throw new BaiException(ValuationErrorCodes.PARAM_INVALID, "VAL_1001 参数缺失：params." + key + " 必填");
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
            throw new BaiException(ValuationErrorCodes.PARAM_INVALID,
                    "VAL_1001 参数非法：params." + key + " 须为数值");
        }
    }

    private static void parseDate(String date, String field) {
        if (date == null || date.isBlank()) {
            throw new BaiException(ValuationErrorCodes.PARAM_INVALID, "VAL_1001 参数缺失：" + field + " 必填");
        }
        try {
            LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new BaiException(ValuationErrorCodes.PARAM_INVALID,
                    "VAL_1001 参数非法：" + field + " 须为 ISO 日期（yyyy-MM-dd）");
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

    /** 市场数据条目（版本化存储，QUERY 读取当前版本） */
    private static final class MarketData {
        final int version;
        final List<ValuationDtos.MarketPoint> points;
        final String updatedAt;

        MarketData(int version, List<ValuationDtos.MarketPoint> points, String updatedAt) {
            this.version = version;
            this.points = points;
            this.updatedAt = updatedAt;
        }
    }
}
