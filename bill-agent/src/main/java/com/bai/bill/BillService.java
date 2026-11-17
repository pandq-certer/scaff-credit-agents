package com.bai.bill;

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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 票据业务服务（BIA-CAL-062）。
 * <p>处理链：校验票据要素 → 票交所状态核验（仿真，用于联调：billNo 确定性规则——含 FROZEN→冻结、
 * 含 LOST→挂失止付，均 FAIL 阻断；其余存续，哈希命中 1/10 概率 SUSPECT 存疑）→
 * 剩余天数 = dueDate − 当前日期（为负抛 BILL_4101 票据已到期）→
 * 贴现利息 = amount × discountRate/100 × 剩余天数/360（2 位），实付 = amount − 利息 →
 * 实付 ≥ amount×0.9 校验（贴现率过高违反下限抛 BILL_4103）→ 生成贴现编号落审计。
 * <p>幂等：requestId 为键、贴现要素哈希防冲突，同键返回首次结果（缓存 24h）；冲突抛 BILL_1005。
 */
@Service
public class BillService {

    private static final Logger log = LoggerFactory.getLogger(BillService.class);

    private static final Set<String> BILL_TYPES = Set.of("BANK_ACCEPTANCE", "COMMERCIAL_ACCEPTANCE");
    /** 承兑人评级集合（保证金比例查表：AA 20%/A 30%/BBB 50%，设计文档表 7-2） */
    private static final Set<String> ACCEPTOR_RATINGS = Set.of("AA", "A", "BBB");
    /** 保证金比例表：按承兑人评级（可被承兑方案 marginRate 覆盖） */
    private static final Map<String, BigDecimal> MARGIN_RATE_BY_RATING = Map.of(
            "AA", new BigDecimal("0.2"), "A", new BigDecimal("0.3"), "BBB", new BigDecimal("0.5"));
    /** 票据池操作集合（IN_POOL/PLEDGE/SWAP/COLLECT） */
    private static final Set<String> POOL_OPERATIONS = Set.of("IN_POOL", "PLEDGE", "SWAP", "COLLECT");
    /** 利率定价客户评级/承兑人类别集合 */
    private static final Set<String> CUSTOMER_RATINGS = Set.of("AAA", "AA", "A", "BBB", "BB");
    private static final Set<String> ACCEPTOR_TYPES = Set.of("BANK", "LARGE", "SME");
    /** 客户风险溢价表（年化百分点，基准利率之上叠加） */
    private static final Map<String, BigDecimal> CUSTOMER_PREMIUM = Map.of(
            "AAA", new BigDecimal("0.3"), "AA", new BigDecimal("0.6"), "A", new BigDecimal("1.0"),
            "BBB", new BigDecimal("1.5"), "BB", new BigDecimal("2.5"));
    /** 承兑人溢价表（年化百分点）：银行/大型企业/中小企业 */
    private static final Map<String, BigDecimal> ACCEPTOR_PREMIUM = Map.of(
            "BANK", new BigDecimal("0.2"), "LARGE", new BigDecimal("0.5"), "SME", new BigDecimal("1.2"));
    /** 计息基准 360 天 */
    private static final BigDecimal DAYS_BASIS = BigDecimal.valueOf(360);
    /** 实付下限：票面 × 90%（对应贴现率上限 36%） */
    private static final BigDecimal PAYABLE_FLOOR_RATE = new BigDecimal("0.9");
    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MONEY_SCALE = 2;

    /** 贴现编号序号（DIS+yyyyMMdd+4 位序号） */
    private final AtomicInteger discountSeq = new AtomicInteger();
    /** 验真流水号序号（VRF+yyyyMMdd+4 位序号） */
    private final AtomicInteger verifySeq = new AtomicInteger();
    /** 承兑编号序号（ACC+yyyyMMdd+4 位序号） */
    private final AtomicInteger acceptSeq = new AtomicInteger();
    /** 票据池编号序号（POOL+yyyyMMdd+4 位序号） */
    private final AtomicInteger poolSeq = new AtomicInteger();
    /** 质押登记号序号（PLD+yyyyMMdd+4 位序号） */
    private final AtomicInteger pledgeSeq = new AtomicInteger();
    /** 贴现利率版本序号（R+yyyyMMdd-N） */
    private final AtomicInteger rateSeq = new AtomicInteger();
    /** 内存票据登记库（仿真，用于联调；生产对接票交所 ECDS） */
    private final Map<String, BillDtos.BillInfo> registry = new ConcurrentHashMap<>();
    private final IdempotencyStore idempotency = new IdempotencyStore();

    /** 票据贴现（幂等：同 requestId 返回首次结果，缓存 24h） */
    public BillDtos.DiscountResult discount(BillDtos.DiscountRequest req, String requestId) {
        validate(req);

        String hash = sha256(String.join("|",
                str(req.billNo()), str(req.billType()), money(req.amount()), str(req.dueDate()), money(req.discountRate())));
        String prevHash = idempotency.get("discount-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(BillErrorCodes.DUPLICATE_CONFLICT,
                        "BILL_1005 重复提交/贴现要素冲突，请使用原 requestId 查询首次贴现结果");
            }
            return idempotency.get("discount", requestId);
        }

        BillDtos.DiscountResult result = compute(req);
        idempotency.put("discount-hash", requestId, hash);
        idempotency.put("discount", requestId, result);
        log.info("票据贴现完成: requestId={}, billNo={}, interest={}, payable={}, status={}, verify={}",
                requestId, req.billNo(), result.interestAmount(), result.payableAmount(),
                result.billStatus(), result.verifyResult());
        return result;
    }

    /** 票据登记信息查询：已登记票据返回登记项，未登记的按 billNo 生成仿真登记信息（确定性，用于联调） */
    public BillDtos.BillInfo bill(String billNo) {
        if (billNo == null || billNo.isBlank()) {
            throw new BaiException(BillErrorCodes.PARAM_INVALID, "BILL_1001 参数缺失：billNo 必填");
        }
        BillDtos.BillInfo cached = registry.get(billNo);
        if (cached != null) {
            return cached;
        }
        BillDtos.BillInfo info = simulateBill(billNo);
        registry.put(billNo, info);
        return info;
    }

    // ---------------------------------------------------------------- 4.2 票据验真

    /** 票据验真（幂等）：多通道核验（票交所状态/防伪特征/背书链/限制性），任一 FAIL 整体 FAIL，超时/影像缺失转 SUSPECT */
    public BillDtos.VerifyResult verify(BillDtos.VerifyRequest req, String requestId) {
        validateVerify(req);
        String hash = sha256(String.join("|", str(req.billNo()), str(req.billType()),
                String.valueOf(req.electronic()), money(req.amount()), str(req.issueDate()), str(req.dueDate()),
                str(req.drawer()), str(req.acceptor()), str(req.payee())));
        String prevHash = idempotency.get("verify-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(BillErrorCodes.IDEMPOTENT_CONFLICT,
                        "BILL_4004 幂等冲突：requestId 重复且验真要素不同，请使用原 requestId 查询首次结果");
            }
            return idempotency.get("verify", requestId);
        }
        BillDtos.VerifyResult result = computeVerify(req);
        idempotency.put("verify-hash", requestId, hash);
        idempotency.put("verify", requestId, result);
        log.info("票据验真完成: requestId={}, billNo={}, verifyResult={}, billStatus={}, verifyNo={}",
                requestId, req.billNo(), result.verifyResult(), result.billStatus(), result.verifyNo());
        return result;
    }

    private void validateVerify(BillDtos.VerifyRequest req) {
        if (req == null || isBlank(req.billNo())) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数缺失：billNo 必填");
        }
        if (req.billType() == null || !BILL_TYPES.contains(req.billType())) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED,
                    "BILL_4001 参数非法：billType 须为 BANK_ACCEPTANCE/COMMERCIAL_ACCEPTANCE，当前 " + req.billType());
        }
        if (req.electronic() == null) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数缺失：electronic 必填");
        }
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数非法：amount 必填且 > 0");
        }
        if (isBlank(req.drawer()) || isBlank(req.acceptor()) || isBlank(req.payee())) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED,
                    "BILL_4001 参数缺失：drawer/acceptor/payee 必填");
        }
        parseDateChecked(req.issueDate(), "issueDate");
        parseDateChecked(req.dueDate(), "dueDate");
        if (req.issueDate().compareTo(req.dueDate()) > 0) {
            throw new BaiException(BillErrorCodes.ELEMENT_INCONSISTENT,
                    "BILL_4002 票面要素不一致：出票日 " + req.issueDate() + " 晚于到期日 " + req.dueDate());
        }
    }

    /** 验真计算（票交所状态为确定性仿真，用于联调：billNo 含 FROZEN/LOST/PUBLIC/PAID 判定异常状态） */
    private BillDtos.VerifyResult computeVerify(BillDtos.VerifyRequest req) {
        List<BillDtos.ChannelResult> channels = new ArrayList<>();
        String upper = req.billNo().toUpperCase();
        // 通道 1：票交所状态核验（ECDS，电子票实时状态仿真）
        String billStatus;
        String ecdsStatus;
        if (upper.contains("FROZEN")) {
            billStatus = "冻结";
            ecdsStatus = "FAIL";
        } else if (upper.contains("LOST")) {
            billStatus = "挂失止付";
            ecdsStatus = "FAIL";
        } else if (upper.contains("PUBLIC")) {
            billStatus = "公示催告";
            ecdsStatus = "FAIL";
        } else if (upper.contains("PAID")) {
            billStatus = "已兑付";
            ecdsStatus = "FAIL";
        } else {
            billStatus = "存续";
            ecdsStatus = Math.abs(req.billNo().hashCode()) % 10 == 7 ? "SUSPECT" : "PASS";
        }
        channels.add(new BillDtos.ChannelResult("票交所核验", ecdsStatus,
                "FAIL".equals(ecdsStatus) ? "ECDS 状态[" + billStatus + "]非存续，核验不通过"
                        : "SUSPECT".equals(ecdsStatus) ? "票交所实时查询超时，状态待确认"
                        : "ECDS 状态[存续]，核验通过"));

        // 通道 2：防伪特征校验（纸质票：水印/微缩文字/荧光/纸张；电子票以 ECDS 登记为准，免检）
        String antiStatus;
        String antiDetail;
        if (Boolean.TRUE.equals(req.electronic())) {
            antiStatus = "PASS";
            antiDetail = "电子票免防伪特征校验（以 ECDS 登记为准）";
        } else if (req.imageRefs() == null || req.imageRefs().isEmpty()) {
            antiStatus = "SUSPECT";
            antiDetail = "纸质票影像缺失（imageRefs 为空），防伪特征无法校验";
        } else if (Math.abs(req.billNo().hashCode()) % 10 == 3) {
            antiStatus = "FAIL";
            antiDetail = "防伪特征缺失（水印/微缩文字/荧光任一项缺失）";
        } else {
            antiStatus = "PASS";
            antiDetail = "防伪特征校验通过（水印/微缩文字/荧光/纸张）";
        }
        channels.add(new BillDtos.ChannelResult("防伪特征", antiStatus, antiDetail));

        // 通道 3/4：背书链校验 + 限制性核查（背书链为可选要素）
        BillDtos.EndorsementCheck endorsementCheck;
        if (req.endorsements() == null || req.endorsements().isEmpty()) {
            endorsementCheck = new BillDtos.EndorsementCheck(true, List.of(), List.of());
            channels.add(new BillDtos.ChannelResult("背书链校验", "PASS", "未提供背书链（可选要素），不校验"));
            channels.add(new BillDtos.ChannelResult("限制性核查", "PASS", "未提供背书链，无限制性记载"));
        } else {
            endorsementCheck = checkChain(req.endorsements(), null);
            boolean limitFollowed = endorsementCheck.defects().stream().anyMatch(d -> d.startsWith("限制性背书后"));
            channels.add(new BillDtos.ChannelResult("背书链校验",
                    endorsementCheck.continuous() && !limitFollowed ? "PASS" : "FAIL",
                    endorsementCheck.continuous()
                            ? (limitFollowed ? "限制性背书后仍继续背书，后续背书无效" : "背书链连续完整")
                            : "背书链不连续（背书人 ≠ 前手被背书人）"));
            channels.add(new BillDtos.ChannelResult("限制性核查",
                    endorsementCheck.limitMarked().isEmpty() ? "PASS" : limitFollowed ? "FAIL" : "SUSPECT",
                    endorsementCheck.limitMarked().isEmpty() ? "无限制性背书记载"
                            : limitFollowed ? "限制性背书（不得转让）后继续背书，阻断"
                            : "存在限制性背书，需人工复核"));
        }

        // 结论汇总（设计文档 6.3（1））：任一通道 FAIL → 整体 FAIL；任一 SUSPECT → SUSPECT；否则 PASS
        boolean anyFail = channels.stream().anyMatch(c -> "FAIL".equals(c.status()));
        boolean anySuspect = channels.stream().anyMatch(c -> "SUSPECT".equals(c.status()));
        String verifyResult = anyFail ? "FAIL" : anySuspect ? "SUSPECT" : "PASS";
        String verifyNo = String.format("VRF%s%04d", LocalDate.now().format(NO_DATE), verifySeq.incrementAndGet());
        // 验真结论登记入内存票据库（仿真），供承兑/兑付/池/质押后续环节引用
        registry.put(req.billNo(), new BillDtos.BillInfo(req.billNo(), req.billType(), req.amount(),
                req.issueDate(), req.dueDate(), req.drawer(), req.acceptor(), billStatus));
        return new BillDtos.VerifyResult(verifyNo, verifyResult, channels, billStatus, endorsementCheck);
    }

    // ---------------------------------------------------------------- 4.3 承兑管理

    /** 承兑管理（幂等）：保证金 = 票面×比例（按承兑人评级查表，可被方案覆盖），敞口 = 票面 − 保证金，超限额阻断 */
    public BillDtos.AcceptResult accept(BillDtos.AcceptRequest req, String requestId) {
        validateAccept(req);
        BillDtos.BillInfo bill = requireBill(req.billNo());
        BigDecimal planRate = req.acceptanceApply() == null ? null : req.acceptanceApply().marginRate();
        BigDecimal planLimit = req.acceptanceApply() == null ? null : req.acceptanceApply().exposureLimit();
        String hash = sha256(String.join("|", str(req.billNo()), str(req.drawer()), str(req.acceptorRating()),
                money(planRate), money(planLimit)));
        String prevHash = idempotency.get("accept-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(BillErrorCodes.IDEMPOTENT_CONFLICT,
                        "BILL_4004 幂等冲突：requestId 重复且承兑要素不同，请使用原 requestId 查询首次结果");
            }
            return idempotency.get("accept", requestId);
        }
        BillDtos.AcceptResult result = computeAccept(req, bill);
        idempotency.put("accept-hash", requestId, hash);
        idempotency.put("accept", requestId, result);
        log.info("承兑登记完成: requestId={}, billNo={}, margin={}, exposure={}, occupancy={}, status={}",
                requestId, req.billNo(), result.marginAmount(), result.exposure(),
                result.exposureCheck().occupancy(), result.status());
        return result;
    }

    private void validateAccept(BillDtos.AcceptRequest req) {
        if (req == null || isBlank(req.billNo())) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数缺失：billNo 必填");
        }
        if (isBlank(req.drawer())) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数缺失：drawer 必填");
        }
        if (req.acceptorRating() == null || !ACCEPTOR_RATINGS.contains(req.acceptorRating())) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED,
                    "BILL_4001 参数非法：acceptorRating 须为 AA/A/BBB，当前 " + req.acceptorRating());
        }
    }

    private BillDtos.AcceptResult computeAccept(BillDtos.AcceptRequest req, BillDtos.BillInfo bill) {
        BigDecimal planRate = req.acceptanceApply() == null ? null : req.acceptanceApply().marginRate();
        BigDecimal marginRate = planRate != null ? planRate : MARGIN_RATE_BY_RATING.get(req.acceptorRating());
        if (marginRate.signum() <= 0 || marginRate.compareTo(BigDecimal.ONE) > 0) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED,
                    "BILL_4001 参数非法：保证金比例须在 0~1 之间（10%~100%），当前 " + marginRate);
        }
        BigDecimal margin = bill.amount().multiply(marginRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal exposure = bill.amount().subtract(margin);
        BigDecimal limit = req.acceptanceApply() != null && req.acceptanceApply().exposureLimit() != null
                ? req.acceptanceApply().exposureLimit() : bill.amount().multiply(BigDecimal.valueOf(5));
        BigDecimal occupancy = exposure.divide(limit, 4, RoundingMode.HALF_UP);
        if (exposure.compareTo(limit) > 0) {
            throw new BaiException(BillErrorCodes.LIMIT_BREACH,
                    "BILL_4103 承兑敞口超限：敞口 " + exposure + " > 限额 " + limit + "（占用率 " + occupancy + "），承兑阻断");
        }
        List<BillDtos.IssueCheckItem> issueCheck = new ArrayList<>();
        issueCheck.add(new BillDtos.IssueCheckItem("票号格式",
                req.billNo().matches("[A-Za-z0-9]+") && req.billNo().length() >= 10,
                "电子票 30 位/统一编码"));
        issueCheck.add(new BillDtos.IssueCheckItem("日期要素",
                bill.issueDate().compareTo(bill.dueDate()) < 0,
                "出票日须早于到期日"));
        issueCheck.add(new BillDtos.IssueCheckItem("金额要素",
                bill.amount() != null && bill.amount().signum() > 0,
                "票面金额须为正数"));
        issueCheck.add(new BillDtos.IssueCheckItem("当事人要素",
                !isBlank(req.drawer()) && !isBlank(bill.acceptor()),
                "出票人/承兑人须完整"));
        String acceptanceNo = String.format("ACC%s%04d", LocalDate.now().format(NO_DATE), acceptSeq.incrementAndGet());
        return new BillDtos.AcceptResult(acceptanceNo, margin, exposure,
                new BillDtos.ExposureCheck(exposure, limit, occupancy, false), issueCheck, "ACCEPTED");
    }

    // ---------------------------------------------------------------- 4.5 兑付管理

    /** 兑付管理（幂等）：到期判定 → 余额检查（T-3 预警窗口）→ PAID/OVERDUE/ADVANCE（垫款登记 + 追索任务） */
    public BillDtos.PayResult pay(BillDtos.PayRequest req, String requestId) {
        validatePay(req);
        BillDtos.BillInfo bill = requireBill(req.billNo());
        String payDate = req.payApply() != null && !isBlank(req.payApply().payDate())
                ? req.payApply().payDate() : LocalDate.now().toString();
        String account = req.payApply() == null ? null : req.payApply().account();
        String balanceRef = req.payApply() == null ? null : req.payApply().balanceRef();
        String hash = sha256(String.join("|", str(req.billNo()), str(payDate), str(account), str(balanceRef)));
        String prevHash = idempotency.get("pay-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(BillErrorCodes.IDEMPOTENT_CONFLICT,
                        "BILL_4004 幂等冲突：requestId 重复且兑付要素不同，请使用原 requestId 查询首次结果");
            }
            return idempotency.get("pay", requestId);
        }
        BillDtos.PayResult result = computePay(req, bill, payDate);
        idempotency.put("pay-hash", requestId, hash);
        idempotency.put("pay", requestId, result);
        log.info("兑付登记完成: requestId={}, billNo={}, payResult={}, payAmount={}, payDate={}",
                requestId, req.billNo(), result.payResult(), result.payAmount(), result.payDate());
        return result;
    }

    private void validatePay(BillDtos.PayRequest req) {
        if (req == null || isBlank(req.billNo())) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数缺失：billNo 必填");
        }
        if (req.payApply() != null && !isBlank(req.payApply().payDate())) {
            parseDateChecked(req.payApply().payDate(), "payDate");
        }
    }

    private BillDtos.PayResult computePay(BillDtos.PayRequest req, BillDtos.BillInfo bill, String payDate) {
        String upper = req.billNo().toUpperCase();
        if (upper.contains("PAID")) {
            return new BillDtos.PayResult("PAID", bill.amount(), bill.dueDate(), null, null);
        }
        if (upper.contains("FROZEN") || upper.contains("LOST")) {
            throw new BaiException(BillErrorCodes.BILL_STATUS_ABNORMAL,
                    "BILL_4101 票据状态异常（冻结/挂失止付），兑付阻断");
        }
        long daysUntilDue = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(bill.dueDate()));
        BigDecimal balance = deterministicBalance(bill);
        if (balance.compareTo(bill.amount()) >= 0) {
            return new BillDtos.PayResult("PAID", bill.amount(), payDate, null, null);
        }
        // 余额不足：垫款登记（垫款金额/利率 5%/期限 90 天）+ 追索任务（背书链逆序，6 个月时效）
        BigDecimal advanceAmount = bill.amount().subtract(balance);
        BillDtos.AdvanceInfo advanceInfo = new BillDtos.AdvanceInfo(advanceAmount, BigDecimal.valueOf(5.0), 90);
        BillDtos.RecourseTask recourseTask = new BillDtos.RecourseTask(
                "背书链逆序追索（持票人→直接前手→再前手）", 6, "CREATED");
        String payResult = daysUntilDue < 0 ? "OVERDUE" : "ADVANCE";
        return new BillDtos.PayResult(payResult, balance, payDate, advanceInfo, recourseTask);
    }

    // ---------------------------------------------------------------- 4.6 票据池管理

    /** 票据池管理（幂等）：IN_POOL/PLEDGE/SWAP/COLLECT，质押额度 = Σ(票面×质押率)，换票差额校验阻断 */
    public BillDtos.PoolResult pool(BillDtos.PoolRequest req, String requestId) {
        validatePool(req);
        String hash = sha256(String.join("|", str(req.operation()), str(req.customerId()),
                str(req.billNos() == null ? "" : String.join(",", req.billNos())),
                str(req.pledgeRatePlan() == null || req.pledgeRatePlan().rate() == null
                        ? "" : req.pledgeRatePlan().rate().toPlainString()),
                str(req.swapDetail() == null ? ""
                        : String.join(",", req.swapDetail().inBillNos()) + ";" + String.join(",", req.swapDetail().outBillNos()))));
        String prevHash = idempotency.get("pool-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(BillErrorCodes.IDEMPOTENT_CONFLICT,
                        "BILL_4004 幂等冲突：requestId 重复且池操作要素不同，请使用原 requestId 查询首次结果");
            }
            return idempotency.get("pool", requestId);
        }
        BillDtos.PoolResult result = computePool(req);
        idempotency.put("pool-hash", requestId, hash);
        idempotency.put("pool", requestId, result);
        log.info("票据池操作完成: requestId={}, operation={}, pledgeAmount={}, availableAmount={}, status={}",
                requestId, req.operation(), result.pledgeAmount(), result.availableAmount(), result.status());
        return result;
    }

    private void validatePool(BillDtos.PoolRequest req) {
        if (req == null || isBlank(req.operation()) || !POOL_OPERATIONS.contains(req.operation())) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED,
                    "BILL_4001 参数非法：operation 须为 IN_POOL/PLEDGE/SWAP/COLLECT");
        }
        if (isBlank(req.customerId())) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数缺失：customerId 必填");
        }
        if ("SWAP".equals(req.operation()) && (req.swapDetail() == null
                || req.swapDetail().inBillNos() == null || req.swapDetail().inBillNos().isEmpty()
                || req.swapDetail().outBillNos() == null || req.swapDetail().outBillNos().isEmpty())) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED,
                    "BILL_4001 参数缺失：SWAP 须提供 swapDetail（inBillNos/outBillNos 非空）");
        }
        if (!"SWAP".equals(req.operation()) && (req.billNos() == null || req.billNos().isEmpty())) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数缺失：billNos 必填且非空");
        }
    }

    private BillDtos.PoolResult computePool(BillDtos.PoolRequest req) {
        String poolId = String.format("POOL%s%04d", LocalDate.now().format(NO_DATE), poolSeq.incrementAndGet());
        BigDecimal planRate = req.pledgeRatePlan() == null ? null : req.pledgeRatePlan().rate();
        switch (req.operation()) {
            case "IN_POOL" -> {
                BigDecimal pledge = pledgeAmount(req.billNos(), planRate);
                return new BillDtos.PoolResult(poolId, List.copyOf(req.billNos()), pledge, pledge, null, "IN_POOL");
            }
            case "PLEDGE" -> {
                BigDecimal pledge = pledgeAmount(req.billNos(), planRate);
                return new BillDtos.PoolResult(poolId, List.copyOf(req.billNos()), pledge, pledge, null, "PLEDGED");
            }
            case "SWAP" -> {
                BigDecimal inValue = pledgeAmount(req.swapDetail().inBillNos(), planRate);
                BigDecimal outValue = pledgeAmount(req.swapDetail().outBillNos(), planRate);
                if (inValue.compareTo(outValue) < 0) {
                    throw new BaiException(BillErrorCodes.LIMIT_BREACH,
                            "BILL_4103 换票差额校验不通过：入池质押额度 " + inValue + " < 出池质押额度 " + outValue + "，换票阻断");
                }
                BillDtos.SwapResult swapResult = new BillDtos.SwapResult(req.swapDetail().inBillNos().size(),
                        req.swapDetail().outBillNos().size(), inValue.subtract(outValue), true);
                return new BillDtos.PoolResult(poolId, List.copyOf(req.swapDetail().inBillNos()),
                        inValue, inValue.subtract(outValue), swapResult, "SWAPPED");
            }
            default -> { // COLLECT：托收后池额度释放
                BigDecimal pledge = pledgeAmount(req.billNos(), planRate);
                return new BillDtos.PoolResult(poolId, List.copyOf(req.billNos()), pledge,
                        BigDecimal.ZERO, null, "COLLECTED");
            }
        }
    }

    // ---------------------------------------------------------------- 4.7 背书审查

    /** 背书审查（幂等）：seq 递增 + 背书人=前手被背书人连续性校验，限制性背书清单/瑕疵清单，REJECT 阻断贴现/质押 */
    public BillDtos.EndorseCheckResult endorseCheck(BillDtos.EndorseCheckRequest req, String requestId) {
        validateEndorseCheck(req);
        String endorsementsKey = req.endorsements().stream()
                .map(e -> str(e.seq()) + "|" + str(e.endorser()) + "|" + str(e.endorsee()) + "|" + str(e.limitedMark()))
                .reduce("", (a, b) -> a + ";" + b);
        String hash = sha256(String.join("|", str(req.billNo()), endorsementsKey, str(req.endorsee())));
        String prevHash = idempotency.get("endorse-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(BillErrorCodes.IDEMPOTENT_CONFLICT,
                        "BILL_4004 幂等冲突：requestId 重复且背书审查要素不同，请使用原 requestId 查询首次结果");
            }
            return idempotency.get("endorse", requestId);
        }
        BillDtos.EndorsementCheck check = checkChain(req.endorsements(), req.endorsee());
        boolean limitFollowed = check.defects().stream().anyMatch(d -> d.startsWith("限制性背书后"));
        String checkResult = !check.continuous() || limitFollowed ? "REJECT"
                : check.limitMarked().isEmpty() ? "PASS" : "REVIEW";
        BillDtos.EndorseCheckResult result = new BillDtos.EndorseCheckResult(
                check.continuous(), check.limitMarked(), check.defects(), checkResult);
        idempotency.put("endorse-hash", requestId, hash);
        idempotency.put("endorse", requestId, result);
        log.info("背书审查完成: requestId={}, billNo={}, continuous={}, limitMarked={}, checkResult={}",
                requestId, req.billNo(), check.continuous(), check.limitMarked().size(), checkResult);
        return result;
    }

    private void validateEndorseCheck(BillDtos.EndorseCheckRequest req) {
        if (req == null || isBlank(req.billNo())) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数缺失：billNo 必填");
        }
        if (req.endorsements() == null || req.endorsements().isEmpty()) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数缺失：endorsements 背书链必填");
        }
    }

    // ---------------------------------------------------------------- 4.8 票据质押

    /** 票据质押（幂等）：质押要素校验（未登记拦截）→ 质押率校验（超限阻断）→ 盯市与处置建议 */
    public BillDtos.PledgeResult pledge(BillDtos.PledgeRequest req, String requestId) {
        validatePledge(req);
        String hash = sha256(String.join("|", String.join(",", req.billNos()), str(req.customerId()),
                money(req.loanAmount()), money(req.pledgeRate()), money(req.marketPrice())));
        String prevHash = idempotency.get("pledge-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(BillErrorCodes.IDEMPOTENT_CONFLICT,
                        "BILL_4004 幂等冲突：requestId 重复且质押要素不同，请使用原 requestId 查询首次结果");
            }
            return idempotency.get("pledge", requestId);
        }
        BillDtos.PledgeResult result = computePledge(req);
        idempotency.put("pledge-hash", requestId, hash);
        idempotency.put("pledge", requestId, result);
        log.info("票据质押登记完成: requestId={}, billNos={}, loanAmount={}, availableAmount={}, pledgeNo={}",
                requestId, String.join(",", req.billNos()), req.loanAmount(),
                result.availableAmount(), result.pledgeNo());
        return result;
    }

    private void validatePledge(BillDtos.PledgeRequest req) {
        if (req == null || req.billNos() == null || req.billNos().isEmpty()) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数缺失：billNos 必填且非空");
        }
        if (isBlank(req.customerId())) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数缺失：customerId 必填");
        }
        if (req.loanAmount() == null || req.loanAmount().signum() <= 0) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数非法：loanAmount 必填且 > 0");
        }
        if (req.pledgeRate() != null && (req.pledgeRate().signum() <= 0 || req.pledgeRate().compareTo(BigDecimal.ONE) > 0)) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数非法：pledgeRate 须在 0~1 之间");
        }
    }

    private BillDtos.PledgeResult computePledge(BillDtos.PledgeRequest req) {
        BigDecimal totalFace = BigDecimal.ZERO;
        String firstType = null;
        for (String billNo : req.billNos()) {
            BillDtos.BillInfo bill = requireBill(billNo);
            if (firstType == null) {
                firstType = bill.billType();
            }
            totalFace = totalFace.add(bill.amount());
        }
        BigDecimal appliedRate = req.pledgeRate() != null ? req.pledgeRate() : pledgeRatioFor(firstType);
        BigDecimal limitRate = "BANK_ACCEPTANCE".equals(firstType) ? new BigDecimal("0.9") : new BigDecimal("0.7");
        if (appliedRate.compareTo(limitRate) > 0) {
            throw new BaiException(BillErrorCodes.LIMIT_BREACH,
                    "BILL_4103 质押率 " + appliedRate + " 超出上限 " + limitRate + "（按承兑人等级），质押阻断");
        }
        BigDecimal collateral = BigDecimal.ZERO;
        for (String billNo : req.billNos()) {
            BillDtos.BillInfo bill = requireBill(billNo);
            collateral = collateral.add(bill.amount().multiply(appliedRate));
        }
        collateral = collateral.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (req.loanAmount().compareTo(collateral) > 0) {
            throw new BaiException(BillErrorCodes.LIMIT_BREACH,
                    "BILL_4103 融资金额 " + req.loanAmount() + " 超出可融额度 " + collateral + "（质押率超限），质押阻断");
        }
        BigDecimal available = collateral.subtract(req.loanAmount());
        String marginCall = req.loanAmount().compareTo(collateral.multiply(new BigDecimal("0.9"))) > 0
                ? "质押率接近上限：建议补票/补缴保证金" : "无";
        String disposalAdvice = req.marketPrice() != null
                && req.marketPrice().compareTo(totalFace.multiply(new BigDecimal("0.8"))) < 0
                ? "盯市贬值（市价低于票面 80%）：建议追索/变现处置" : "正常持有，盯市无异常";
        String pledgeNo = String.format("PLD%s%04d", LocalDate.now().format(NO_DATE), pledgeSeq.incrementAndGet());
        return new BillDtos.PledgeResult(pledgeNo, available,
                new BillDtos.RateCheck(appliedRate, limitRate, true), marginCall, disposalAdvice);
    }

    // ---------------------------------------------------------------- 4.9 贴现利率定价

    /** 贴现利率定价（幂等）：r = 市场基准 + 客户溢价 + 承兑人溢价 + 期限溢价（年化%，步长 0.01%），版本化可复算 */
    public BillDtos.RateResult rate(BillDtos.RateRequest req, String requestId) {
        validateRate(req);
        String hash = sha256(String.join("|", str(req.billType()), str(req.acceptorType()),
                str(req.termDays()), str(req.customerRating()), money(req.marketRate())));
        String prevHash = idempotency.get("rate-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(BillErrorCodes.IDEMPOTENT_CONFLICT,
                        "BILL_4004 幂等冲突：requestId 重复且定价要素不同，请使用原 requestId 查询首次结果");
            }
            return idempotency.get("rate", requestId);
        }
        BillDtos.RateResult result = computeRate(req);
        idempotency.put("rate-hash", requestId, hash);
        idempotency.put("rate", requestId, result);
        log.info("贴现利率定价完成: requestId={}, discountRate={}, rateVersion={}, rateCheck={}",
                requestId, result.discountRate(), result.rateVersion(), result.rateCheck().passed());
        return result;
    }

    private void validateRate(BillDtos.RateRequest req) {
        if (req == null || req.billType() == null || !BILL_TYPES.contains(req.billType())) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED,
                    "BILL_4001 参数非法：billType 须为 BANK_ACCEPTANCE/COMMERCIAL_ACCEPTANCE");
        }
        if (req.acceptorType() == null || !ACCEPTOR_TYPES.contains(req.acceptorType())) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED,
                    "BILL_4001 参数非法：acceptorType 须为 BANK/LARGE/SME");
        }
        if (req.termDays() == null || req.termDays() <= 0) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数非法：termDays 必填且 > 0");
        }
        if (req.customerRating() == null || !CUSTOMER_RATINGS.contains(req.customerRating())) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED,
                    "BILL_4001 参数非法：customerRating 须为 AAA/AA/A/BBB/BB");
        }
        if (req.marketRate() == null || req.marketRate().signum() <= 0) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数非法：marketRate 必填且 > 0（年化%）");
        }
    }

    private BillDtos.RateResult computeRate(BillDtos.RateRequest req) {
        BigDecimal customerPremium = CUSTOMER_PREMIUM.get(req.customerRating());
        BigDecimal acceptorPremium = ACCEPTOR_PREMIUM.get(req.acceptorType());
        BigDecimal termPremium = req.termDays() <= 90 ? new BigDecimal("0.1")
                : req.termDays() <= 180 ? new BigDecimal("0.2")
                : req.termDays() <= 270 ? new BigDecimal("0.3") : new BigDecimal("0.5");
        BigDecimal discountRate = req.marketRate().add(customerPremium).add(acceptorPremium).add(termPremium)
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal minRate = new BigDecimal("0.5");
        BigDecimal maxRate = new BigDecimal("36.0");
        boolean passed = discountRate.compareTo(minRate) >= 0 && discountRate.compareTo(maxRate) <= 0;
        String rateVersion = "R" + LocalDate.now().format(NO_DATE) + "-" + rateSeq.incrementAndGet();
        BillDtos.RateBreakdown breakdown = new BillDtos.RateBreakdown(req.marketRate(), customerPremium,
                acceptorPremium, termPremium, discountRate);
        return new BillDtos.RateResult(rateVersion, discountRate, breakdown,
                new BillDtos.RateLimitCheck(minRate, maxRate, passed));
    }

    // ---------------------------------------------------------------- 公共校验/工具

    /** 从内存票据库取票据，未登记抛 BILL_4003（先经验真/查询登记主档） */
    private BillDtos.BillInfo requireBill(String billNo) {
        BillDtos.BillInfo info = registry.get(billNo);
        if (info == null) {
            throw new BaiException(BillErrorCodes.BILL_NOT_REGISTERED,
                    "BILL_4003 票据不存在/未登记：billNo=" + billNo + "，请先调用验真/查询登记");
        }
        return info;
    }

    /** 背书链校验（设计文档 6.3（2））：seq 递增 + 背书人=前手被背书人；限制性记载后继续背书无效 */
    private BillDtos.EndorsementCheck checkChain(List<BillDtos.Endorsement> endorsements, String endorsee) {
        List<String> limitMarked = new ArrayList<>();
        List<String> defects = new ArrayList<>();
        boolean continuous = true;
        if (endorsements == null || endorsements.isEmpty()) {
            defects.add("背书链为空：无背书记载");
            return new BillDtos.EndorsementCheck(false, limitMarked, defects);
        }
        for (int i = 1; i < endorsements.size(); i++) {
            BillDtos.Endorsement prev = endorsements.get(i - 1);
            BillDtos.Endorsement cur = endorsements.get(i);
            if (prev.endorsee() == null || !prev.endorsee().equals(cur.endorser())) {
                continuous = false;
                defects.add("背书链断裂：第 " + i + " 手被背书人[" + prev.endorsee() + "] ≠ 第 " + (i + 1)
                        + " 手背书人[" + cur.endorser() + "]");
            }
            if (prev.seq() != null && cur.seq() != null && cur.seq() <= prev.seq()) {
                continuous = false;
                defects.add("背书序号不递增：seq " + prev.seq() + "→" + cur.seq());
            }
        }
        int lastLimited = -1;
        for (int i = 0; i < endorsements.size(); i++) {
            BillDtos.Endorsement e = endorsements.get(i);
            if (e.limitedMark() != null && (e.limitedMark().contains("不得转让")
                    || e.limitedMark().contains("禁止转让") || e.limitedMark().contains("限制转让"))) {
                limitMarked.add(e.endorser() + "→" + e.endorsee() + "（" + e.limitedMark() + "）");
                lastLimited = i;
            }
        }
        if (lastLimited >= 0 && lastLimited < endorsements.size() - 1) {
            defects.add("限制性背书后仍继续背书，后续背书无效");
        }
        if (endorsee != null && !endorsee.isBlank()) {
            BillDtos.Endorsement last = endorsements.get(endorsements.size() - 1);
            if (last.endorsee() == null || !endorsee.equals(last.endorsee())) {
                defects.add("被背书人[" + endorsee + "]与背书链末手被背书人[" + last.endorsee() + "]不一致");
            }
        }
        return new BillDtos.EndorsementCheck(continuous, limitMarked, defects);
    }

    /** 池质押额度 = Σ(票面金额×质押率)；质押率默认银承 0.8/商票 0.6，可被质押率方案覆盖 */
    private BigDecimal pledgeAmount(List<String> billNos, BigDecimal planRate) {
        BigDecimal sum = BigDecimal.ZERO;
        for (String billNo : billNos) {
            BillDtos.BillInfo bill = requireBill(billNo);
            BigDecimal rho = planRate != null ? planRate : pledgeRatioFor(bill.billType());
            sum = sum.add(bill.amount().multiply(rho));
        }
        return sum.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal pledgeRatioFor(String billType) {
        return "BANK_ACCEPTANCE".equals(billType) ? new BigDecimal("0.8") : new BigDecimal("0.6");
    }

    /** 承兑人账户余额仿真（用于联调）：票面 ×(0.5~1.5) 由 billNo 哈希确定，覆盖足额/不足两类兑付场景 */
    private BigDecimal deterministicBalance(BillDtos.BillInfo bill) {
        long h = Math.abs((long) bill.billNo().hashCode());
        BigDecimal factor = BigDecimal.valueOf(0.5 + (h % 11) / 10.0);
        return bill.amount().multiply(factor).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static void parseDateChecked(String date, String field) {
        if (date == null || date.isBlank()) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED, "BILL_4001 参数缺失：" + field + " 必填");
        }
        try {
            LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new BaiException(BillErrorCodes.PARAM_CHECK_FAILED,
                    "BILL_4001 参数非法：" + field + " 须为 ISO 日期（yyyy-MM-dd）");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void validate(BillDtos.DiscountRequest req) {
        if (req == null || req.billNo() == null || req.billNo().isBlank()) {
            throw new BaiException(BillErrorCodes.PARAM_INVALID, "BILL_1001 参数缺失：billNo 必填");
        }
        if (req.billType() == null || !BILL_TYPES.contains(req.billType())) {
            throw new BaiException(BillErrorCodes.ELEMENT_ILLEGAL,
                    "BILL_1007 票据类型非法：billType 须为 BANK_ACCEPTANCE/COMMERCIAL_ACCEPTANCE，当前 " + req.billType());
        }
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new BaiException(BillErrorCodes.PARAM_INVALID, "BILL_1001 参数非法：amount 必填且 > 0");
        }
        parseDate(req.issueDate(), "issueDate");
        parseDate(req.dueDate(), "dueDate");
        if (req.drawer() == null || req.drawer().isBlank()) {
            throw new BaiException(BillErrorCodes.PARAM_INVALID, "BILL_1001 参数缺失：drawer 必填");
        }
        if (req.acceptor() == null || req.acceptor().isBlank()) {
            throw new BaiException(BillErrorCodes.PARAM_INVALID, "BILL_1001 参数缺失：acceptor 必填");
        }
        if (req.discountRate() == null || req.discountRate().signum() <= 0) {
            throw new BaiException(BillErrorCodes.PARAM_INVALID, "BILL_1001 参数非法：discountRate 必填且 > 0（年化%）");
        }
    }

    /** 核验 + 贴现计算（贴现利息公式真实实现；票交所状态为确定性仿真，用于联调） */
    private BillDtos.DiscountResult compute(BillDtos.DiscountRequest req) {
        LocalDate dueDate = LocalDate.parse(req.dueDate());
        long remainingDays = ChronoUnit.DAYS.between(LocalDate.now(), dueDate);
        if (remainingDays < 0) {
            throw new BaiException(BillErrorCodes.BILL_STATUS_ABNORMAL,
                    "BILL_4101 票据已到期（到期日 " + req.dueDate() + "），贴现阻断");
        }
        // 票交所状态仿真（用于联调）：billNo 确定性规则判定状态与核验结果
        String upper = req.billNo().toUpperCase();
        String billStatus;
        String verifyResult;
        if (upper.contains("FROZEN")) {
            billStatus = "冻结";
            verifyResult = "FAIL";
        } else if (upper.contains("LOST")) {
            billStatus = "挂失止付";
            verifyResult = "FAIL";
        } else {
            billStatus = "存续";
            verifyResult = Math.abs(req.billNo().hashCode()) % 10 == 7 ? "SUSPECT" : "PASS";
        }
        if ("FAIL".equals(verifyResult)) {
            throw new BaiException(BillErrorCodes.BILL_STATUS_ABNORMAL,
                    "BILL_4101 票交所核验不通过：票据状态[" + billStatus + "]，贴现阻断");
        }

        // 贴现利息 = amount × discountRate/100 × 剩余天数/360
        BigDecimal interest = req.amount().multiply(req.discountRate())
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(remainingDays))
                .divide(DAYS_BASIS, MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal payable = req.amount().subtract(interest);
        // 实付下限校验：payable ≥ amount×0.9（贴现率 >36% 时违反）
        if (payable.compareTo(req.amount().multiply(PAYABLE_FLOOR_RATE)) < 0) {
            throw new BaiException(BillErrorCodes.DISCOUNT_FLOOR_BREACH,
                    "BILL_4103 贴现实付 " + payable + " 低于票面 90% 下限（贴现率过高），贴现阻断");
        }

        String discountNo = String.format("DIS%s%04d", LocalDate.now().format(NO_DATE), discountSeq.incrementAndGet());
        BillDtos.DiscountResult result = new BillDtos.DiscountResult(discountNo, req.discountRate(),
                interest, payable, billStatus, verifyResult, remainingDays);
        // 登记入内存票据库（仿真）
        registry.put(req.billNo(), new BillDtos.BillInfo(req.billNo(), req.billType(), req.amount(),
                req.issueDate(), req.dueDate(), req.drawer(), req.acceptor(), billStatus));
        return result;
    }

    /** 按 billNo 哈希确定性生成仿真登记信息（用于联调） */
    private BillDtos.BillInfo simulateBill(String billNo) {
        long h = Math.abs((long) billNo.hashCode());
        String status = billNo.toUpperCase().contains("FROZEN") ? "冻结"
                : billNo.toUpperCase().contains("LOST") ? "挂失止付" : "存续";
        return new BillDtos.BillInfo(billNo, h % 2 == 0 ? "BANK_ACCEPTANCE" : "COMMERCIAL_ACCEPTANCE",
                BigDecimal.valueOf(100000 + h % 900000).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                LocalDate.now().minusDays(90).toString(),
                LocalDate.now().plusDays(h % 180).toString(),
                "仿真出票人" + (h % 900 + 100), "仿真承兑人" + (h % 900 + 100), status);
    }

    private static void parseDate(String date, String field) {
        if (date == null || date.isBlank()) {
            throw new BaiException(BillErrorCodes.PARAM_INVALID, "BILL_1001 参数缺失：" + field + " 必填");
        }
        try {
            LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new BaiException(BillErrorCodes.PARAM_INVALID,
                    "BILL_1001 参数非法：" + field + " 须为 ISO 日期（yyyy-MM-dd）");
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
}
