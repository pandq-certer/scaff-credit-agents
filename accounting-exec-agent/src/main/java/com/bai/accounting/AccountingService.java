package com.bai.accounting;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import com.spatix.scaff.iprov.ProviderResponse;
import com.spatix.scaff.iprov.provider.AccountingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 账务执行服务（提示词库 A-58，P11 accounting-provider 经 scaff-iprov 接入，Mock 内置、幂等 txnRef 防重复记账）。
 * <p>处理链：校验事件/分录/金额 → 借贷平衡校验（借方合计=贷方合计，且等于事件总金额）→ 按 eventType 关联会计科目分录
 * （如放款：借 1301 贷款 / 贷 2011 单位活期存款）→ 生成唯一凭证号 voucherNo（V+yyyyMMdd+3 位日序）→
 * 经 AccountingProvider.trans 过账总账（txnRef=requestId，Mock 幂等防重复）→ glStatus=POSTED 并记录过账时间 →
 * 计提/结息事件按 accrualType/settleType 校验口径并落审计。
 * <p>约束：借贷不平衡禁止过账（ACC_1001）；同 requestId 不重复生成凭证；凭证号唯一。
 * <p>幂等：requestId + 事件 + 合同哈希为键；同 requestId 不同要素视为凭证冲突（ACC_1005）。
 */
@Service
public class AccountingService {

    private static final Logger log = LoggerFactory.getLogger(AccountingService.class);

    /** 七类业务事件 */
    private static final Set<String> EVENT_TYPES =
            Set.of("LOAN", "REPAY", "SETTLE", "WRITEOFF", "IMPAIR", "CAPITALIZE", "FEE");
    /** 计提类型 */
    private static final Set<String> ACCRUAL_TYPES = Set.of("INTEREST", "IMPAIR", "RECEIVABLE_PAYABLE");
    /** 结息类型 */
    private static final Set<String> SETTLE_TYPES = Set.of("PERIOD", "TRANSFER", "REALIZE");
    /** 科目代码规则：3~8 位数字，且须与会计科目表一致（如 1301 贷款、2011 单位活期存款） */
    private static final String ACCOUNT_PATTERN = "\\d{3,8}";
    /** 凭证号日序 */
    private static final DateTimeFormatter VOUCHER_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 过账时间输出格式（ISO 带时区偏移） */
    private static final DateTimeFormatter POST_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");
    /** 计提/结息期间格式（yyyy-MM） */
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    /** 结息日期格式（yyyy-MM-dd） */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    /** 计提科目映射（仿真口径，对齐 5.4 事件记账规范/6.3 计提策略）：[借方科目, 贷方科目] */
    private static final Map<String, String[]> ACCRUAL_SUBJECTS = Map.of(
            "INTEREST", new String[]{"1122", "6011"},            // 借 应收利息 / 贷 利息收入
            "IMPAIR", new String[]{"6701", "1441"},              // 借 资产减值损失 / 贷 贷款损失准备
            "RECEIVABLE_PAYABLE", new String[]{"1122", "6011"}); // 借 应收利息 / 贷 利息收入（应收应付）
    /** 结息科目映射（5.4 结息：借 应收利息 / 贷 利息收入；入账/转实收同一核算口径） */
    private static final Map<String, String[]> SETTLE_SUBJECTS = Map.of(
            "PERIOD", new String[]{"1122", "6011"},
            "TRANSFER", new String[]{"1122", "6011"},
            "REALIZE", new String[]{"1122", "6011"});
    /** 计息天数基准（6.3：应计利息 = 本金×利率×天数/360） */
    private static final int DAY_BASIS = 360;

    private final AccountingProvider provider;
    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final Map<String, AtomicInteger> dailyVoucherSeq = new ConcurrentHashMap<>();
    /** 记账/计提/结息留痕（生产落 acc_entry/acc_accrual/acc_settle，本阶段内存仿真供 query 检索） */
    private final Map<String, LedgerRecord> ledgerStore = new ConcurrentHashMap<>();
    /** 记录 ID 日序（ENT/ACR/STL） */
    private final AtomicLong idSeq = new AtomicLong();

    public AccountingService(AccountingProvider provider) {
        this.provider = provider;
    }

    /** 记账执行（幂等：同 requestId+事件+合同哈希返回首次结果；Provider 以 txnRef=requestId 防重复记账） */
    public AccountingDtos.EntryResult entry(AccountingDtos.EntryRequest req, String requestId) {
        validate(req, requestId);

        String hash = sha256(String.join("|",
                str(req.eventType()), str(req.contractId()), str(req.amount()),
                str(req.accrualType()), str(req.settleType()), String.valueOf(req.entries())));
        String prevHash = idempotency.get("entry-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(AccountingErrorCodes.DUPLICATE_CONFLICT,
                        "ACC_1005 重复记账/凭证冲突，请使用原 requestId 查询首次记账结果");
            }
            return idempotency.get("entry", requestId);
        }

        AccountingDtos.EntryResult result = execute(req, requestId);
        idempotency.put("entry-hash", requestId, hash);
        idempotency.put("entry", requestId, result);
        ledgerStore.put(result.entryId(), new LedgerRecord("ENTRY", result.entryId(), requestId,
                str(req.contractId()), str(result.eventType()),
                result.entries().isEmpty() ? "" : result.entries().get(0).account(),
                req.amount().setScale(2, RoundingMode.HALF_UP), result.entries(), result.voucherNo(),
                result.glStatus(), result.postingTime()));
        log.info("记账完成: requestId={}, eventType={}, contractId={}, voucherNo={}, glStatus={}, postingTime={}",
                requestId, result.eventType(), req.contractId(), result.voucherNo(), result.glStatus(),
                result.postingTime());
        return result;
    }

    private void validate(AccountingDtos.EntryRequest req, String requestId) {
        if (req == null || req.eventType() == null || !EVENT_TYPES.contains(req.eventType())) {
            throw new BaiException(AccountingErrorCodes.ELEMENT_ILLEGAL,
                    "ACC_1007 业务事件非法：eventType 须为 LOAN/REPAY/SETTLE/WRITEOFF/IMPAIR/CAPITALIZE/FEE");
        }
        if (req.contractId() == null || req.contractId().isBlank()) {
            throw new BaiException(AccountingErrorCodes.NOT_FOUND, "ACC_1004 合同不存在：contractId 必填");
        }
        if (req.entries() == null || req.entries().isEmpty()) {
            throw new BaiException(AccountingErrorCodes.PARAM_INVALID, "ACC_1001 参数缺失：entries 分录列表必填");
        }
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new BaiException(AccountingErrorCodes.PARAM_INVALID, "ACC_1001 参数非法：amount 必填且 > 0");
        }
        if (req.accrualType() != null && !req.accrualType().isBlank()
                && !ACCRUAL_TYPES.contains(req.accrualType())) {
            throw new BaiException(AccountingErrorCodes.ELEMENT_ILLEGAL,
                    "ACC_1007 计提类型非法：accrualType 须为 INTEREST/IMPAIR/RECEIVABLE_PAYABLE");
        }
        if (req.settleType() != null && !req.settleType().isBlank() && !SETTLE_TYPES.contains(req.settleType())) {
            throw new BaiException(AccountingErrorCodes.ELEMENT_ILLEGAL,
                    "ACC_1007 结息类型非法：settleType 须为 PERIOD/TRANSFER/REALIZE");
        }
        for (AccountingDtos.EntryLine line : req.entries()) {
            if (line == null || line.account() == null || !line.account().matches(ACCOUNT_PATTERN)) {
                throw new BaiException(AccountingErrorCodes.ELEMENT_ILLEGAL,
                        "ACC_1007 科目非法：account 须为 3~8 位数字的会计科目代码");
            }
            if (!"DEBIT".equals(line.direction()) && !"CREDIT".equals(line.direction())) {
                throw new BaiException(AccountingErrorCodes.PARAM_INVALID,
                        "ACC_1001 参数非法：借贷方向 direction 须为 DEBIT/CREDIT");
            }
            if (line.amount() == null || line.amount().signum() <= 0) {
                throw new BaiException(AccountingErrorCodes.PARAM_INVALID,
                        "ACC_1001 参数非法：分录金额须 > 0（科目 " + line.account() + "）");
            }
        }
    }

    /** 借贷平衡校验 + 生成凭证号 + 过账总账（经 AccountingProvider） */
    private AccountingDtos.EntryResult execute(AccountingDtos.EntryRequest req, String requestId) {
        BigDecimal debitSum = BigDecimal.ZERO;
        BigDecimal creditSum = BigDecimal.ZERO;
        List<AccountingDtos.EntryLine> normalized = new ArrayList<>(req.entries().size());
        for (AccountingDtos.EntryLine line : req.entries()) {
            BigDecimal amount = line.amount().setScale(2, RoundingMode.HALF_UP);
            if ("DEBIT".equals(line.direction())) {
                debitSum = debitSum.add(amount);
            } else {
                creditSum = creditSum.add(amount);
            }
            normalized.add(new AccountingDtos.EntryLine(line.account(), line.direction(), amount,
                    line.summary() == null ? "" : line.summary()));
        }
        if (debitSum.compareTo(creditSum) != 0) {
            throw new BaiException(AccountingErrorCodes.PARAM_INVALID,
                    "ACC_1001 借贷不平衡：借方合计 " + debitSum + " ≠ 贷方合计 " + creditSum + "，禁止过账");
        }
        BigDecimal amount = req.amount().setScale(2, RoundingMode.HALF_UP);
        if (debitSum.compareTo(amount) != 0) {
            throw new BaiException(AccountingErrorCodes.PARAM_INVALID,
                    "ACC_1001 参数非法：事件总金额 " + amount + " 与分录合计 " + debitSum + " 不符");
        }

        String voucherNo = nextVoucherNo();
        // 过账至总账（P11 accounting-provider；txnRef=requestId 幂等防重复记账）
        long amountCents = amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        AccountingProvider.TxnRequest txn = new AccountingProvider.TxnRequest(
                requestId, req.eventType(), req.contractId(), amountCents, req.contractId(),
                "贷款" + req.eventType() + "记账 " + voucherNo);
        ProviderResponse<AccountingProvider.TxnResult> resp;
        try {
            resp = provider.trans(txn, requestId);
        } catch (Exception e) {
            log.error("总账过账异常: requestId={}, voucherNo={}", requestId, voucherNo, e);
            throw new BaiException(AccountingErrorCodes.GL_UNAVAILABLE,
                    "ACC_2001 总账服务不可用（降级中），凭证 " + voucherNo + " 未过账，请稍后重试");
        }
        if (resp == null || !resp.isSuccess()) {
            throw new BaiException(AccountingErrorCodes.GL_UNAVAILABLE,
                    "ACC_2001 总账过账失败：" + (resp == null ? "无响应" : resp.getCode() + " " + resp.getMessage()));
        }
        AccountingProvider.TxnResult data = resp.getData();
        String postingTime = data != null && data.postTime() != null && !data.postTime().isBlank()
                ? data.postTime() : OffsetDateTime.now().format(POST_TIME);
        return new AccountingDtos.EntryResult("SUCCESS", nextId("ENT"), voucherNo, req.eventType(), "PASS",
                "POSTED", postingTime, normalized);
    }

    /** 凭证号：V + yyyyMMdd + 3 位当日序号（唯一，同 requestId 幂等不重复生成） */
    private String nextVoucherNo() {
        String day = LocalDate.now().format(VOUCHER_DATE);
        int seq = dailyVoucherSeq.computeIfAbsent(day, k -> new AtomicInteger(0)).incrementAndGet();
        return String.format("V%s%03d", day, seq);
    }

    /** 记录 ID：ENT/ACR/STL + yyyyMMdd + 4 位日序（entryId/accrualId/settleId 唯一） */
    private String nextId(String prefix) {
        return prefix + LocalDate.now().format(VOUCHER_DATE) + String.format("%04d", idSeq.incrementAndGet() % 10000);
    }

    // ---------------------------------------------------------------- accrual

    /** 计提处理（幂等：同 requestId+类型+贷款+期间哈希返回首次结果；留痕供 query 检索） */
    public AccountingDtos.AccrualResult accrual(AccountingDtos.AccrualRequest req, String requestId) {
        validateAccrual(req, requestId);

        String hash = sha256(String.join("|",
                str(req.accrualType()), str(req.business()), str(req.params()), money(req.amount())));
        String prevHash = idempotency.get("accrual-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(AccountingErrorCodes.DUPLICATE_CONFLICT,
                        "ACC_1005 重复计提/计提冲突，请使用原 requestId 查询首次计提结果");
            }
            return idempotency.get("accrual", requestId);
        }

        AccountingDtos.AccrualResult result = executeAccrual(req, requestId);
        idempotency.put("accrual-hash", requestId, hash);
        idempotency.put("accrual", requestId, result);
        ledgerStore.put(result.accrualId(), new LedgerRecord("ACCRUAL", result.accrualId(), requestId,
                str(req.business().contract()), str(req.business().account()), str(req.accrualType()),
                result.amount(), result.entries(), result.voucherNo(), result.status(), result.createdAt()));
        log.info("计提完成: requestId={}, accrualId={}, accrualType={}, loanNo={}, period={}, amount={}, voucherNo={}, status={}",
                requestId, result.accrualId(), result.accrualType(), req.business().contract(),
                req.business().period(), result.amount(), result.voucherNo(), result.status());
        return result;
    }

    private void validateAccrual(AccountingDtos.AccrualRequest req, String requestId) {
        if (req == null || req.accrualType() == null || !ACCRUAL_TYPES.contains(req.accrualType())) {
            throw new BaiException(AccountingErrorCodes.ELEMENT_ILLEGAL,
                    "ACC_1007 计提类型非法：accrualType 须为 INTEREST/IMPAIR/RECEIVABLE_PAYABLE");
        }
        if (req.business() == null || req.business().contract() == null || req.business().contract().isBlank()) {
            throw new BaiException(AccountingErrorCodes.NOT_FOUND, "ACC_1004 合同不存在：business.contract 贷款号必填");
        }
        if (req.business().account() == null || !req.business().account().matches(ACCOUNT_PATTERN)) {
            throw new BaiException(AccountingErrorCodes.ELEMENT_ILLEGAL,
                    "ACC_1007 科目非法：business.account 须为 3~8 位数字的会计科目代码");
        }
        if (req.business().period() == null || req.business().period().isBlank()) {
            throw new BaiException(AccountingErrorCodes.PARAM_INVALID, "ACC_1001 参数缺失：business.period 计提期间必填");
        }
        try {
            YearMonth.parse(req.business().period(), MONTH);
        } catch (DateTimeParseException e) {
            throw new BaiException(AccountingErrorCodes.PARAM_INVALID,
                    "ACC_1001 参数非法：business.period 计提期间须为 yyyy-MM");
        }
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new BaiException(AccountingErrorCodes.PARAM_INVALID, "ACC_1001 参数非法：amount 计提金额必填且 > 0");
        }
        if (req.params() != null) {
            if (req.params().rate() != null
                    && (req.params().rate().signum() <= 0 || req.params().rate().compareTo(BigDecimal.ONE) > 0)) {
                throw new BaiException(AccountingErrorCodes.PARAM_INVALID,
                        "ACC_1001 参数非法：params.rate 为年利率小数，须在 (0,1] 内");
            }
            if (req.params().ecl() != null && req.params().ecl().signum() <= 0) {
                throw new BaiException(AccountingErrorCodes.PARAM_INVALID, "ACC_1001 参数非法：params.ecl 减值额须 > 0");
            }
        }
    }

    /** 计提执行：科目映射（借/贷）→ 分录生成 → 凭证生成（确定性仿真，不落总账） */
    private AccountingDtos.AccrualResult executeAccrual(AccountingDtos.AccrualRequest req, String requestId) {
        BigDecimal amount = req.amount().setScale(2, RoundingMode.HALF_UP);
        String[] pair = ACCRUAL_SUBJECTS.get(req.accrualType());
        String summary = switch (req.accrualType()) {
            case "INTEREST" -> "利息计提（权责发生制）";
            case "IMPAIR" -> "减值计提（ECL 转损失准备）";
            default -> "应收应付计提（权责发生制）";
        };
        List<AccountingDtos.EntryLine> entries = List.of(
                new AccountingDtos.EntryLine(pair[0], "DEBIT", amount, summary),
                new AccountingDtos.EntryLine(pair[1], "CREDIT", amount, summary));
        String voucherNo = nextVoucherNo();
        String accrualId = nextId("ACR");
        return new AccountingDtos.AccrualResult("SUCCESS", accrualId, req.accrualType(), amount, entries,
                voucherNo, "ACCRUED", OffsetDateTime.now().format(POST_TIME));
    }

    // ----------------------------------------------------------------- settle

    /** 结息处理（幂等：同 requestId+类型+贷款+期间哈希返回首次结果；留痕供 query 检索） */
    public AccountingDtos.SettleResult settle(AccountingDtos.SettleRequest req, String requestId) {
        validateSettle(req, requestId);

        String hash = sha256(String.join("|",
                str(req.settleType()), str(req.business()), str(req.params()),
                money(req.principal()), str(req.settleDate())));
        String prevHash = idempotency.get("settle-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(AccountingErrorCodes.DUPLICATE_CONFLICT,
                        "ACC_1005 重复结息/结息冲突，请使用原 requestId 查询首次结息结果");
            }
            return idempotency.get("settle", requestId);
        }

        AccountingDtos.SettleResult result = executeSettle(req, requestId);
        idempotency.put("settle-hash", requestId, hash);
        idempotency.put("settle", requestId, result);
        ledgerStore.put(result.settleId(), new LedgerRecord("SETTLE", result.settleId(), requestId,
                str(req.business().contract()), str(req.business().account()), str(req.settleType()),
                result.interest(), result.entries(), result.voucherNo(), result.status(), result.createdAt()));
        log.info("结息完成: requestId={}, settleId={}, settleType={}, loanNo={}, period={}, interest={}, rate={}, voucherNo={}, status={}",
                requestId, result.settleId(), result.settleType(), req.business().contract(),
                req.business().period(), result.interest(), result.rate(), result.voucherNo(), result.status());
        return result;
    }

    private void validateSettle(AccountingDtos.SettleRequest req, String requestId) {
        if (req == null || req.settleType() == null || !SETTLE_TYPES.contains(req.settleType())) {
            throw new BaiException(AccountingErrorCodes.ELEMENT_ILLEGAL,
                    "ACC_1007 结息类型非法：settleType 须为 PERIOD/TRANSFER/REALIZE");
        }
        if (req.business() == null || req.business().contract() == null || req.business().contract().isBlank()) {
            throw new BaiException(AccountingErrorCodes.NOT_FOUND, "ACC_1004 合同不存在：business.contract 贷款号必填");
        }
        if (req.business().account() != null && !req.business().account().isBlank()
                && !req.business().account().matches(ACCOUNT_PATTERN)) {
            throw new BaiException(AccountingErrorCodes.ELEMENT_ILLEGAL,
                    "ACC_1007 科目非法：business.account 须为 3~8 位数字的会计科目代码");
        }
        if (req.business().period() != null && !req.business().period().isBlank()) {
            try {
                YearMonth.parse(req.business().period(), MONTH);
            } catch (DateTimeParseException e) {
                throw new BaiException(AccountingErrorCodes.PARAM_INVALID,
                        "ACC_1001 参数非法：business.period 结息期间须为 yyyy-MM");
            }
        }
        if (req.params() == null || req.params().rate() == null
                || req.params().rate().signum() <= 0 || req.params().rate().compareTo(BigDecimal.ONE) > 0) {
            throw new BaiException(AccountingErrorCodes.PARAM_INVALID,
                    "ACC_1001 参数非法：params.rate 结息年利率必填且须在 (0,1] 内");
        }
        if (req.principal() == null || req.principal().signum() <= 0) {
            throw new BaiException(AccountingErrorCodes.PARAM_INVALID, "ACC_1001 参数非法：principal 贷款本金必填且 > 0");
        }
        if (req.settleDate() != null && !req.settleDate().isBlank()) {
            try {
                LocalDate.parse(req.settleDate(), DAY);
            } catch (DateTimeParseException e) {
                throw new BaiException(AccountingErrorCodes.PARAM_INVALID,
                        "ACC_1001 参数非法：settleDate 结息日期须为 yyyy-MM-dd");
            }
        }
    }

    /** 结息执行：结息金额=本金×利率×天数/360（6.3 口径，期间取结息日期所在自然月天数）→ 分录 → 凭证 */
    private AccountingDtos.SettleResult executeSettle(AccountingDtos.SettleRequest req, String requestId) {
        BigDecimal rate = req.params().rate().setScale(4, RoundingMode.HALF_UP);
        LocalDate settleDate = req.settleDate() == null || req.settleDate().isBlank()
                ? LocalDate.now() : LocalDate.parse(req.settleDate(), DAY);
        YearMonth ym = req.business().period() != null && !req.business().period().isBlank()
                ? YearMonth.parse(req.business().period(), MONTH) : YearMonth.from(settleDate);
        int days = ym.lengthOfMonth();
        BigDecimal interest = req.principal()
                .multiply(rate).multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(DAY_BASIS), 2, RoundingMode.HALF_UP);
        String[] pair = SETTLE_SUBJECTS.get(req.settleType());
        String summary = switch (req.settleType()) {
            case "PERIOD" -> "周期结息（期间 " + ym + "）";
            case "TRANSFER" -> "结息入账（期间 " + ym + "）";
            default -> "转实收（逾期转应收利息，期间 " + ym + "）";
        };
        List<AccountingDtos.EntryLine> entries = List.of(
                new AccountingDtos.EntryLine(pair[0], "DEBIT", interest, summary),
                new AccountingDtos.EntryLine(pair[1], "CREDIT", interest, summary));
        String voucherNo = nextVoucherNo();
        String settleId = nextId("STL");
        return new AccountingDtos.SettleResult("SUCCESS", settleId, req.settleType(), interest, rate, entries,
                voucherNo, "SETTLED", OffsetDateTime.now().format(POST_TIME));
    }

    // ----------------------------------------------------------------- query

    /** 记账查询：entryId 精确或组合条件过滤（loanNo/科目/事件/状态/日期范围），按时间倒序分页；无记录 ACC_1004 */
    public AccountingDtos.QueryResult query(AccountingDtos.QueryRequest req, String requestId) {
        validateQuery(req);
        String entryId = trimToNull(req.entryId());
        String loanNo = trimToNull(req.loanNo());
        String subject = trimToNull(req.subject());
        String eventType = trimToNull(req.eventType());
        String glStatus = trimToNull(req.glStatus());
        String startDate = trimToNull(req.startDate());
        String endDate = trimToNull(req.endDate());
        int page = req.page() == null ? 1 : req.page();
        int size = req.size() == null ? 20 : req.size();

        List<LedgerRecord> matched = new ArrayList<>();
        for (LedgerRecord r : ledgerStore.values()) {
            if (entryId != null && !r.id().equals(entryId)) {
                continue;
            }
            if (loanNo != null && !r.loanNo().equals(loanNo)) {
                continue;
            }
            if (subject != null && !subjectMatches(r, subject)) {
                continue;
            }
            if (eventType != null && !r.eventType().equals(eventType)) {
                continue;
            }
            if (glStatus != null && !r.status().equals(glStatus)) {
                continue;
            }
            if (startDate != null && r.createdAt().substring(0, 10).compareTo(startDate) < 0) {
                continue;
            }
            if (endDate != null && r.createdAt().substring(0, 10).compareTo(endDate) > 0) {
                continue;
            }
            matched.add(r);
        }
        if (matched.isEmpty()) {
            throw new BaiException(AccountingErrorCodes.NOT_FOUND, "ACC_1004 无匹配记账/计提/结息记录，请调整查询条件");
        }
        matched.sort(Comparator.comparing(LedgerRecord::createdAt).reversed().thenComparing(LedgerRecord::id));
        int total = matched.size();
        int from = Math.min((page - 1) * size, total);
        int to = Math.min(from + size, total);
        List<AccountingDtos.QueryItem> items = new ArrayList<>(to - from);
        for (LedgerRecord r : matched.subList(from, to)) {
            items.add(new AccountingDtos.QueryItem(r.type(), r.id(), r.loanNo(), r.eventType(), r.subject(),
                    r.amount(), r.entries(), r.voucherNo(), r.status(), r.createdAt()));
        }
        return new AccountingDtos.QueryResult(total, items, page, size);
    }

    private void validateQuery(AccountingDtos.QueryRequest req) {
        if (req == null) {
            throw new BaiException(AccountingErrorCodes.PARAM_INVALID, "ACC_1001 参数缺失：查询条件为空");
        }
        if (req.page() != null && req.page() < 1) {
            throw new BaiException(AccountingErrorCodes.PARAM_INVALID, "ACC_1001 参数非法：page 须 ≥ 1");
        }
        if (req.size() != null && (req.size() < 1 || req.size() > 100)) {
            throw new BaiException(AccountingErrorCodes.PARAM_INVALID, "ACC_1001 参数非法：size 须在 [1,100] 内");
        }
        if (req.startDate() != null && !req.startDate().isBlank()) {
            try {
                LocalDate.parse(req.startDate(), DAY);
            } catch (DateTimeParseException e) {
                throw new BaiException(AccountingErrorCodes.PARAM_INVALID,
                        "ACC_1001 参数非法：startDate 须为 yyyy-MM-dd");
            }
        }
        if (req.endDate() != null && !req.endDate().isBlank()) {
            try {
                LocalDate.parse(req.endDate(), DAY);
            } catch (DateTimeParseException e) {
                throw new BaiException(AccountingErrorCodes.PARAM_INVALID,
                        "ACC_1001 参数非法：endDate 须为 yyyy-MM-dd");
            }
        }
        if (req.startDate() != null && !req.startDate().isBlank()
                && req.endDate() != null && !req.endDate().isBlank()
                && req.startDate().compareTo(req.endDate()) > 0) {
            throw new BaiException(AccountingErrorCodes.PARAM_INVALID, "ACC_1001 参数非法：startDate 不得晚于 endDate");
        }
    }

    /** 科目匹配：主科目或分录任一科目命中（记账记录分录含多科目） */
    private boolean subjectMatches(LedgerRecord r, String subject) {
        if (subject.equals(r.subject())) {
            return true;
        }
        for (AccountingDtos.EntryLine e : r.entries()) {
            if (subject.equals(e.account())) {
                return true;
            }
        }
        return false;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** 记账/计提/结息统一留痕（type=ENTRY|ACCRUAL|SETTLE；id=entryId/accrualId/settleId） */
    private record LedgerRecord(String type, String id, String requestId, String loanNo, String eventType,
                                String subject, BigDecimal amount, List<AccountingDtos.EntryLine> entries,
                                String voucherNo, String status, String createdAt) {}

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
