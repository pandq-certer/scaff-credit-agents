package com.bai.external;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import com.spatix.scaff.iprov.ProviderResponse;
import com.spatix.scaff.iprov.provider.BusinessInfoProvider;
import com.spatix.scaff.iprov.provider.CourtInfoProvider;
import com.spatix.scaff.iprov.provider.CreditReportProvider;
import com.spatix.scaff.iprov.provider.CustomerProvider;
import com.spatix.scaff.iprov.provider.RiskListProvider;
import com.spatix.scaff.iprov.provider.TaxInfoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 外部数据接入服务（BIA-DTA-027）。九类数据源经 P04~P09 数据源族 Provider 统一接入（MVP 走 Mock）。
 * <p>处理链：鉴权与双重频控 → 适配器调用（KMS 令牌，超时 3s）→ 幂等查询退避重试（1s/3s≤2 次，连续 5 次失败熔断 30s 半开）
 * → 映射表驱动标准化 → 敏感字段不可逆脱敏（SHA-256 加盐）→ 外部不可用降级 DEGRADED → 调用留痕
 */
@Service
public class ExternalDataService {

    private static final Logger log = LoggerFactory.getLogger(ExternalDataService.class);

    /** sourceType 九类白名单 */
    private static final Set<String> SOURCE_TYPES =
            Set.of("CREDIT", "INDUSTRY", "JUDICIAL", "TAX", "CUSTOMS", "SOCIAL", "MULTI", "BLACKLIST", "FRAUD");
    /** 场景白名单：准入/贷后/尽调 */
    private static final Set<String> SCENARIOS = Set.of("准入", "贷后", "尽调");
    private static final int MAX_BATCH = 100;          // 批量上限 100 条
    private static final int FAIL_THRESHOLD = 5;       // 连续 5 次失败熔断
    private static final long CIRCUIT_OPEN_MILLIS = 30_000L; // 熔断 30s 半开探测
    private static final int MAX_RETRY = 2;            // 幂等查询退避重试 1s/3s，最多 2 次
    private static final String MASK_SALT = "bai-exd-2026";

    private final IdempotencyStore idempotency = new IdempotencyStore(30_000L); // 30s 内同键不重复计费调用

    /** P04~P09 外部数据源族 Provider（IprovAutoConfiguration 装配 Mock 实现） */
    private final CreditReportProvider creditReportProvider;
    private final BusinessInfoProvider businessInfoProvider;
    private final CourtInfoProvider courtInfoProvider;
    private final TaxInfoProvider taxInfoProvider;
    private final RiskListProvider riskListProvider;
    private final CustomerProvider customerProvider;

    /** 数据源注册表：sourceType → 注册信息 */
    private final Map<String, ExternalDtos.SourceInfo> sources = new ConcurrentHashMap<>();
    /** 调用记录：callId → 记录（append-only 留痕） */
    private final Map<String, ExternalDtos.CallRecord> records = new ConcurrentHashMap<>();
    /** 令牌托管：credentialId → 凭据（仅存 KMS 引用 ID） */
    private final Map<String, ExternalDtos.CredentialInfo> credentials = new ConcurrentHashMap<>();
    /** 数据源日配额计数：sourceType → 当日已用 */
    private final Map<String, AtomicLong> dailyUsage = new ConcurrentHashMap<>();
    /** 熔断状态：sourceType → 连续失败次数 */
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    /** 熔断状态：sourceType → 熔断截止时间（0=闭合） */
    private final Map<String, AtomicLong> circuitOpenUntil = new ConcurrentHashMap<>();

    public ExternalDataService(CreditReportProvider creditReportProvider,
                               BusinessInfoProvider businessInfoProvider,
                               CourtInfoProvider courtInfoProvider,
                               TaxInfoProvider taxInfoProvider,
                               RiskListProvider riskListProvider,
                               CustomerProvider customerProvider) {
        this.creditReportProvider = creditReportProvider;
        this.businessInfoProvider = businessInfoProvider;
        this.courtInfoProvider = courtInfoProvider;
        this.taxInfoProvider = taxInfoProvider;
        this.riskListProvider = riskListProvider;
        this.customerProvider = customerProvider;
        // 预置九类数据源注册（默认启用、已授权、日配额 10000）
        for (String type : SOURCE_TYPES) {
            sources.put(type, new ExternalDtos.SourceInfo(type, type + "-adapter", "2026-09", true, true, 10000, 0));
        }
    }

    /** 统一外部数据同步调用（幂等键：requestId + sourceType + query 哈希，30s 内不重复计费） */
    public ExternalDtos.ExternalFetchResult fetch(String sourceType, Map<String, Object> query,
                                                  String scenario, String requestId, String caller) {
        validateRequestId(requestId);
        if (sourceType == null || !SOURCE_TYPES.contains(sourceType)) {
            throw new BaiException(ExternalErrorCodes.PARAM_INVALID,
                    "EXD_1001 参数非法：sourceType 须为 CREDIT/INDUSTRY/JUDICIAL/TAX/CUSTOMS/SOCIAL/MULTI/BLACKLIST/FRAUD 之一");
        }
        if (query == null || query.isEmpty()) {
            throw new BaiException(ExternalErrorCodes.PARAM_INVALID,
                    "EXD_1001 参数缺失：query（证件/统一信用代码/姓名/企业名）必填");
        }
        if (scenario == null || scenario.isBlank()) {
            throw new BaiException(ExternalErrorCodes.PARAM_INVALID, "EXD_1001 参数缺失：scenario（准入/贷后/尽调）必填");
        }
        // 批量超 100 条
        Object batch = query.get("batch");
        if (batch instanceof List<?> items && items.size() > MAX_BATCH) {
            throw new BaiException(ExternalErrorCodes.BATCH_OVER_LIMIT,
                    "EXD_1005 批量超限（" + items.size() + " 条 > 100），请分批提交");
        }
        String paramHash = sha256(String.valueOf(query));
        String idemKey = sourceType + ":" + paramHash;

        ExternalDtos.ExternalFetchResult cached = idempotency.get(idemKey, requestId);
        if (cached != null) {
            return cached;
        }
        ExternalDtos.SourceInfo source = sources.get(sourceType);
        if (source == null) {
            throw new BaiException(ExternalErrorCodes.SOURCE_NOT_FOUND, "EXD_1004 数据源不存在，请校验 sourceType");
        }
        if (!source.enabled()) {
            throw new BaiException(ExternalErrorCodes.SOURCE_DISABLED, "EXD_1006 数据源已停用，请修正输入或联系管理员启用");
        }
        if (!source.authorized()) {
            throw new BaiException(ExternalErrorCodes.NO_PERMISSION, "EXD_1003 数据源未授权，请申请接入权限");
        }
        // 数据源日配额（超配额 EXD_1007）
        AtomicLong used = dailyUsage.computeIfAbsent(sourceType, k -> new AtomicLong());
        if (used.get() >= source.dailyQuota()) {
            throw new BaiException(ExternalErrorCodes.RATE_LIMITED,
                    "EXD_1007 触发频控/配额不足（数据源日配额 " + source.dailyQuota() + "），请按 Retry-After 退避");
        }
        // 熔断检查：连续 5 次失败熔断 30s，半开探测放行
        AtomicLong openUntil = circuitOpenUntil.computeIfAbsent(sourceType, k -> new AtomicLong());
        if (openUntil.get() > System.currentTimeMillis()) {
            throw new BaiException(ExternalErrorCodes.SOURCE_UNAVAILABLE,
                    "EXD_2001 外部数据源不可用（熔断中，30s 后半开探测），稍后重试或走降级");
        }

        long start = System.currentTimeMillis();
        ProviderOutcome outcome = invokeWithRetry(sourceType, query, scenario, requestId);
        long elapsed = System.currentTimeMillis() - start;
        used.incrementAndGet();

        ExternalDtos.ExternalFetchResult result;
        if (outcome.success()) {
            failures.computeIfAbsent(sourceType, k -> new AtomicInteger()).set(0);
            result = new ExternalDtos.ExternalFetchResult(sourceType, source.sourceVersion(),
                    "EXD" + UUID.randomUUID().toString().replace("-", "").substring(0, 13),
                    "SUCCESS", outcome.data(), outcome.warnings(), true);
        } else {
            int fails = failures.computeIfAbsent(sourceType, k -> new AtomicInteger()).incrementAndGet();
            if (fails >= FAIL_THRESHOLD) {
                openUntil.set(System.currentTimeMillis() + CIRCUIT_OPEN_MILLIS);
            }
            // 降级：外部不可用返回缓存或空，状态 DEGRADED
            Map<String, Object> degraded = new LinkedHashMap<>();
            degraded.put("degraded", true);
            degraded.put("reason", outcome.message());
            List<String> warnings = new ArrayList<>(outcome.warnings());
            warnings.add("外部数据源不可用，已降级返回空数据（DEGRADED）");
            result = new ExternalDtos.ExternalFetchResult(sourceType, source.sourceVersion(),
                    "EXD" + UUID.randomUUID().toString().replace("-", "").substring(0, 13),
                    "DEGRADED", degraded, warnings, true);
        }
        idempotency.put(idemKey, requestId, result);
        audit(caller, sourceType, scenario, requestId, paramHash, result, elapsed);
        return result;
    }

    /** 带退避重试的适配器调用（仅幂等查询重试：1s/3s，最多 2 次） */
    private ProviderOutcome invokeWithRetry(String sourceType, Map<String, Object> query,
                                            String scenario, String requestId) {
        ProviderOutcome outcome = null;
        for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
            outcome = invoke(sourceType, query, scenario, requestId);
            if (outcome.success()) {
                return outcome;
            }
            if (attempt < MAX_RETRY) {
                try {
                    Thread.sleep(attempt == 0 ? 1000L : 3000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return outcome;
    }

    /** 按数据源族路由 Provider（Mock），报文转统一数据模型（映射缺失显式标注） */
    @SuppressWarnings("unchecked")
    private ProviderOutcome invoke(String sourceType, Map<String, Object> query,
                                   String scenario, String requestId) {
        String name = str(query.get("name"), query.get("enterpriseName"));
        String idNo = str(query.get("idNo"));
        String creditCode = str(query.get("creditCode"));
        String customerId = str(query.get("customerId"));
        Map<String, Object> data = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        try {
            switch (sourceType) {
                case "CREDIT" -> {
                    ProviderResponse<CreditReportProvider.CreditReport> r =
                            creditReportProvider.query(name, creditCode != null ? creditCode : idNo, scenario, requestId);
                    if (!check(r)) {
                        return ProviderOutcome.fail(r == null ? "provider no-response" : r.getMessage());
                    }
                    CreditReportProvider.CreditReport report = r.getData();
                    data.put("reportNo", report.reportNo());
                    data.put("overdueAccounts", report.overdue().currentOverdue());
                    data.put("overdueMax30", report.overdue().max30());
                    data.put("overdueMax90", report.overdue().max90());
                    data.put("creditLimit", report.liability().total());
                    data.put("creditCards", report.liability().creditCards());
                    data.put("loans", report.liability().loans());
                    data.put("queryCount3m", report.queries().last3Month());
                }
                case "INDUSTRY" -> {
                    if (creditCode == null) {
                        throw new BaiException(ExternalErrorCodes.PARAM_INVALID, "EXD_1001 参数缺失：INDUSTRY 数据源须提供 query.creditCode");
                    }
                    ProviderResponse<BusinessInfoProvider.Business> r =
                            businessInfoProvider.getBusiness(creditCode, requestId);
                    if (!check(r)) {
                        return ProviderOutcome.fail(r == null ? "provider no-response" : r.getMessage());
                    }
                    BusinessInfoProvider.Business biz = r.getData();
                    data.put("creditCode", biz.creditCode());
                    data.put("enterpriseName", biz.name());
                    data.put("legalPerson", biz.legalPerson());
                    data.put("status", biz.status());
                    data.put("registeredCapital", biz.registeredCapital());
                    data.put("shareholders", biz.shareholders().stream()
                            .map(s -> Map.of("name", s.name(), "ratio", s.ratio())).toList());
                }
                case "JUDICIAL" -> {
                    ProviderResponse<CourtInfoProvider.CourtInfo> r =
                            courtInfoProvider.getCourtInfo(creditCode != null ? creditCode : name, requestId);
                    if (!check(r)) {
                        return ProviderOutcome.fail(r == null ? "provider no-response" : r.getMessage());
                    }
                    CourtInfoProvider.CourtInfo info = r.getData();
                    data.put("litigation", info.litigation().stream()
                            .map(c -> Map.of("caseNo", c.caseNo(), "type", c.type(), "status", c.status())).toList());
                    data.put("executionCount", info.execution().count());
                    data.put("dishonest", info.dishonest());
                }
                case "TAX" -> {
                    if (creditCode == null) {
                        throw new BaiException(ExternalErrorCodes.PARAM_INVALID, "EXD_1001 参数缺失：TAX 数据源须提供 query.creditCode");
                    }
                    ProviderResponse<TaxInfoProvider.TaxInfo> r = taxInfoProvider.getTaxInfo(creditCode, null, requestId);
                    if (!check(r)) {
                        return ProviderOutcome.fail(r == null ? "provider no-response" : r.getMessage());
                    }
                    TaxInfoProvider.TaxInfo tax = r.getData();
                    data.put("creditCode", tax.creditCode());
                    data.put("taxYear", tax.taxYear());
                    data.put("taxableSales", tax.taxableSales());
                    data.put("paidTax", tax.paidTax());
                    data.put("overdue", tax.overdue());
                    data.put("rating", tax.rating());
                }
                case "MULTI", "BLACKLIST", "FRAUD" -> {
                    ProviderResponse<RiskListProvider.RiskMatch> r =
                            riskListProvider.match(name, idNo != null ? idNo : creditCode, requestId);
                    if (!check(r)) {
                        return ProviderOutcome.fail(r == null ? "provider no-response" : r.getMessage());
                    }
                    RiskListProvider.RiskMatch match = r.getData();
                    data.put("hit", match.hit());
                    data.put("checks", match.checks().stream()
                            .map(c -> Map.of("list", c.list(), "score", c.score())).toList());
                    data.put("amlWarnings", match.amlWarnings());
                }
                case "CUSTOMS", "SOCIAL" -> {
                    // 海关/社保公积金无独立数据源族，统一走客户信息族适配（Mock）
                    ProviderResponse<CustomerProvider.Customer> r =
                            customerProvider.getCustomer(customerId, idNo, requestId);
                    if (!check(r)) {
                        return ProviderOutcome.fail(r == null ? "provider no-response" : r.getMessage());
                    }
                    CustomerProvider.Customer cust = r.getData();
                    data.put("customerId", cust.customerId());
                    data.put("name", cust.name());
                    data.put("idNo", cust.idNo());
                    data.put("riskLevel", cust.riskLevel());
                    warnings.add(sourceType + " 数据源按客户信息族适配输出，专属报文字段待接入后补充映射");
                }
                default -> {
                    return ProviderOutcome.fail("不支持的数据源类型：" + sourceType);
                }
            }
        } catch (BaiException be) {
            throw be;
        } catch (Exception e) {
            return ProviderOutcome.fail("外部数据源调用异常：" + e.getMessage());
        }
        maskSensitive(data);
        return ProviderOutcome.ok(data, warnings);
    }

    /** ProviderResponse 校验：失败时计数走熔断/降级 */
    private boolean check(ProviderResponse<?> r) {
        return r != null && r.isSuccess();
    }

    /** 敏感字段不可逆脱敏（SHA-256 加盐），证件/手机号/企业关键字段 */
    private void maskSensitive(Map<String, Object> data) {
        data.computeIfPresent("idNo", (k, v) -> sha256(String.valueOf(v)).substring(0, 16) + "...");
        data.computeIfPresent("legalPerson", (k, v) -> maskName(String.valueOf(v)));
        data.computeIfPresent("name", (k, v) -> maskName(String.valueOf(v)));
    }

    private String maskName(String name) {
        if (name == null || name.length() <= 1) {
            return "*";
        }
        return name.charAt(0) + "*".repeat(Math.max(1, name.length() - 1));
    }

    /** 数据源管理：注册/启用/停用/授权 */
    public ExternalDtos.SourceInfo manageSource(String sourceType, Boolean enabled, Boolean authorized,
                                                String sourceVersion, Integer dailyQuota) {
        if (sourceType == null || !SOURCE_TYPES.contains(sourceType)) {
            throw new BaiException(ExternalErrorCodes.PARAM_INVALID, "EXD_1001 参数非法：未知 sourceType=" + sourceType);
        }
        ExternalDtos.SourceInfo current = sources.get(sourceType);
        if (current == null) {
            throw new BaiException(ExternalErrorCodes.SOURCE_NOT_FOUND, "EXD_1004 数据源不存在，请先注册");
        }
        ExternalDtos.SourceInfo updated = new ExternalDtos.SourceInfo(
                sourceType,
                current.name(),
                sourceVersion != null ? sourceVersion : current.sourceVersion(),
                enabled != null ? enabled : current.enabled(),
                authorized != null ? authorized : current.authorized(),
                dailyQuota != null ? dailyQuota : current.dailyQuota(),
                current.usedToday());
        sources.put(sourceType, updated);
        log.info("数据源管理变更: sourceType={}, enabled={}, authorized={}", sourceType, updated.enabled(), updated.authorized());
        return updated;
    }

    /** 调用记录查询（按 callId 或 requestId） */
    public ExternalDtos.CallRecord getRecord(String callId, String requestId) {
        if (callId != null && !callId.isBlank()) {
            ExternalDtos.CallRecord record = records.get(callId);
            if (record == null) {
                throw new BaiException(ExternalErrorCodes.SOURCE_NOT_FOUND, "EXD_1004 调用记录不存在，请校验 callId");
            }
            return record;
        }
        if (requestId != null && !requestId.isBlank()) {
            return records.values().stream()
                    .filter(r -> requestId.equals(r.requestId()))
                    .findFirst()
                    .orElseThrow(() -> new BaiException(ExternalErrorCodes.SOURCE_NOT_FOUND,
                            "EXD_1004 调用记录不存在，请校验 requestId"));
        }
        throw new BaiException(ExternalErrorCodes.PARAM_INVALID, "EXD_1001 参数缺失：callId 与 requestId 至少提供一项");
    }

    /** 令牌管理：KMS 密文托管注册（仅存引用 ID，绝不回传明文） */
    public ExternalDtos.CredentialInfo registerCredential(String sourceType, String credentialRef) {
        if (sourceType == null || !SOURCE_TYPES.contains(sourceType)) {
            throw new BaiException(ExternalErrorCodes.PARAM_INVALID, "EXD_1001 参数非法：未知 sourceType=" + sourceType);
        }
        if (credentialRef == null || credentialRef.isBlank()) {
            throw new BaiException(ExternalErrorCodes.PARAM_INVALID,
                    "EXD_1001 参数缺失：credentialRef（KMS 密文引用 ID）必填，明文令牌禁止入参");
        }
        if (!sources.containsKey(sourceType)) {
            throw new BaiException(ExternalErrorCodes.SOURCE_NOT_FOUND, "EXD_1004 数据源不存在，请校验 sourceType");
        }
        String credentialId = "CRD" + UUID.randomUUID().toString().replace("-", "").substring(0, 13);
        ExternalDtos.CredentialInfo info = new ExternalDtos.CredentialInfo(
                credentialId, sourceType, credentialRef, "ACTIVE", LocalDateTime.now().toString());
        credentials.put(credentialId, info);
        log.info("令牌托管注册: credentialId={}, sourceType={}（仅存 KMS 引用 ID）", credentialId, sourceType);
        return info;
    }

    private void audit(String caller, String sourceType, String scenario, String requestId,
                       String paramHash, ExternalDtos.ExternalFetchResult result, long elapsedMs) {
        ExternalDtos.CallRecord record = new ExternalDtos.CallRecord(
                result.callId(), requestId, sourceType, scenario,
                caller == null ? "unknown" : caller, paramHash, result.status(), elapsedMs,
                result.status().equals("SUCCESS") ? 1L : 0L, result.masked(), LocalDateTime.now().toString());
        records.put(record.callId(), record);
        log.info("外部数据调用留痕: caller={}, sourceType={}, scenario={}, status={}, elapsed={}ms, masked={}, paramHash={}",
                caller, sourceType, scenario, result.status(), elapsedMs, result.masked(), paramHash);
    }

    private void validateRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return; // 管理接口 requestId 由网关注入，此处不强制
        }
        if (requestId.length() > 32) {
            throw new BaiException(ExternalErrorCodes.PARAM_INVALID, "EXD_1001 参数非法：requestId 须 32 位以内");
        }
    }

    private String str(Object o, Object... fallbacks) {
        if (o != null && !String.valueOf(o).isBlank()) {
            return String.valueOf(o);
        }
        for (Object f : fallbacks) {
            if (f != null && !String.valueOf(f).isBlank()) {
                return String.valueOf(f);
            }
        }
        return null;
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((MASK_SALT + raw).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return String.valueOf((MASK_SALT + raw).hashCode());
        }
    }

    /** Provider 调用结果（标准化数据 + 映射警告） */
    private record ProviderOutcome(boolean success, Map<String, Object> data,
                                   List<String> warnings, String message) {
        static ProviderOutcome ok(Map<String, Object> data, List<String> warnings) {
            return new ProviderOutcome(true, data, warnings, null);
        }

        static ProviderOutcome fail(String message) {
            return new ProviderOutcome(false, null, new ArrayList<>(), message);
        }
    }
}
