package com.bai.tradedoc;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 贸易单证服务（BIA-CAL-063，UCP600/ISBP745 审单口径）。
 * <p>处理链：校验信用证与单据要素 → 阻断类校验（ruleVersion 不存在 TRD_4101；lcNo 命中 SANCTION 制裁 TRD_4104；
 * 单据日期距今超 5 日审单超期 TRD_4102，简化按日历日）→ 单单相符：发票/装箱单/汇票金额两两比对容差 ±5%；
 * 单证相符：单据金额 ≤ 信用证金额、装运期 ≤ latestShipmentDate、交单在有效期内、docsRequired 单据种类全覆盖 →
 * 不符点清单（MAJOR→DISCREPANT，仅 MINOR→COMPLIANT）→ FORFAITING 买断试算：
 * discountInterest = 票面 × buyoutRate/100 × 剩余天数/360，buyPrice = 票面 − 利息 → 生成审单编号落审计。
 * <p>幂等：requestId 为键、审单要素哈希防冲突，同键返回首次结果（缓存 24h）；冲突抛 TRD_1005。
 */
@Service
public class TradeDocService {

    private static final Logger log = LoggerFactory.getLogger(TradeDocService.class);

    private static final Set<String> DOC_TYPES = Set.of("INVOICE", "PACKING_LIST", "BILL_OF_LADING",
            "INSURANCE", "CERTIFICATE_ORIGIN", "INSPECTION", "DRAFT");
    private static final Set<String> BUSINESS_TYPES = Set.of("LC_NEGOTIATION", "LC_PAYMENT", "FORFAITING",
            "PACKING", "IMPORT_PAYMENT");
    /** 审单规则版本（只增不可回写，变更须审批） */
    private static final Set<String> RULE_VERSIONS = Set.of("UCP600-ISBP745-2026", "UCP600-ISBP745-2025");
    private static final String DEFAULT_RULE_VERSION = "UCP600-ISBP745-2026";
    /** 单单金额容差 ±5% */
    private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.05");
    /** 审单超期阈值（日历日，简化） */
    private static final long CHECK_OVERDUE_DAYS = 5;
    /** 计息基准 360 天 */
    private static final BigDecimal DAYS_BASIS = BigDecimal.valueOf(360);
    private static final int MONEY_SCALE = 2;

    /** 审单编号序号（TRD+yyyyMMdd+4 位序号） */
    private final AtomicInteger checkSeq = new AtomicInteger();
    private final IdempotencyStore idempotency = new IdempotencyStore();

    /** 单证审核（幂等：同 requestId 返回首次结果，缓存 24h） */
    public TradeDocDtos.CheckResult check(TradeDocDtos.CheckRequest req, String requestId) {
        validate(req);

        String hash = sha256(String.join("|",
                str(req.lcNo()), String.valueOf(req.documents()), String.valueOf(req.lcTerms()),
                str(req.businessType()), str(req.ruleVersion())));
        String prevHash = idempotency.get("check-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(TradeDocErrorCodes.DUPLICATE_CONFLICT,
                        "TRD_1005 重复提交/审单要素冲突，请使用原 requestId 查询首次审单结果");
            }
            return idempotency.get("check", requestId);
        }

        TradeDocDtos.CheckResult result = compute(req);
        idempotency.put("check-hash", requestId, hash);
        idempotency.put("check", requestId, result);
        log.info("单证审核完成: requestId={}, lcNo={}, businessType={}, checkResult={}, discrepancies={}, checkNo={}",
                requestId, req.lcNo(), req.businessType(), result.checkResult(),
                result.discrepancyList().size(), result.checkNo());
        return result;
    }

    // ==================== 4.3 单单/单证相符校验 ====================

    /** 单单/单证相符校验（幂等：同 requestId 返回首次结果）：
     * 单单比对（金额容差 tolerance 默认 ±5%、货物描述/当事人/唛头归一化全等）+ 单证比对
     * （金额 ≤ LC×（1+容差）、货物/当事人/装运期/效期/单据种类全覆盖，逐条款 OK/WARN/FAIL） */
    public TradeDocDtos.ConsistencyResult consistency(TradeDocDtos.ConsistencyRequest req, String requestId) {
        BigDecimal tolerance = req.tolerance() == null ? AMOUNT_TOLERANCE : req.tolerance();
        validateConsistency(req, tolerance);

        String hash = sha256(String.join("|", str(req.lcNo()), String.valueOf(req.documents()),
                String.valueOf(req.lcTerms()), money(tolerance)));
        String prevHash = idempotency.get("consistency-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(TradeDocErrorCodes.DUPLICATE_CONFLICT,
                        "TRD_1005 重复校验/校验要素冲突，请使用原 requestId 查询首次校验结果");
            }
            return idempotency.get("consistency", requestId);
        }

        TradeDocDtos.ConsistencyResult result = computeConsistency(req, tolerance);
        idempotency.put("consistency-hash", requestId, hash);
        idempotency.put("consistency", requestId, result);
        log.info("单单/单证相符校验: requestId={}, lcNo={}, docToDoc={}, docToLc={}, mismatchPairs={}",
                requestId, req.lcNo(), result.docToDoc().result(), result.docToLc().result(),
                result.docToDoc().pairs().stream().filter(p -> !p.consistent()).count());
        return result;
    }

    private void validateConsistency(TradeDocDtos.ConsistencyRequest req, BigDecimal tolerance) {
        if (req == null || blank(req.lcNo())) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：lcNo 必填");
        }
        if (req.documents() == null || req.documents().isEmpty()) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：documents 单据数组必填");
        }
        for (TradeDocDtos.DocDto doc : req.documents()) {
            if (doc == null || doc.type() == null || !DOC_TYPES.contains(doc.type())) {
                throw new BaiException(TradeDocErrorCodes.ELEMENT_ILLEGAL,
                        "TRD_1007 单据类型非法：type 须为 INVOICE/PACKING_LIST/BILL_OF_LADING/INSURANCE/CERTIFICATE_ORIGIN/INSPECTION/DRAFT");
            }
            if (blank(doc.docId())) {
                throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：documents.docId 必填");
            }
        }
        if (req.lcTerms() == null || req.lcTerms().amount() == null || req.lcTerms().amount().signum() <= 0) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数非法：lcTerms.amount 必填且 > 0");
        }
        if (blank(req.lcTerms().goodsDesc()) || blank(req.lcTerms().beneficiary())
                || blank(req.lcTerms().latestShipmentDate()) || blank(req.lcTerms().expiryDate())) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID,
                    "TRD_1001 参数缺失：lcTerms.goodsDesc/beneficiary/latestShipmentDate/expiryDate 必填");
        }
        parseDate(req.lcTerms().latestShipmentDate(), "lcTerms.latestShipmentDate");
        parseDate(req.lcTerms().expiryDate(), "lcTerms.expiryDate");
        if (tolerance.signum() < 0 || tolerance.compareTo(BigDecimal.ONE) > 0) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID,
                    "TRD_1001 参数非法：tolerance 容差须在 [0,1] 内（小数，默认 0.05）");
        }
    }

    private TradeDocDtos.ConsistencyResult computeConsistency(TradeDocDtos.ConsistencyRequest req, BigDecimal tolerance) {
        Map<String, TradeDocDtos.DocDto> byType = new java.util.LinkedHashMap<>();
        Set<String> presentTypes = new HashSet<>();
        for (TradeDocDtos.DocDto doc : req.documents()) {
            byType.putIfAbsent(doc.type(), doc);
            presentTypes.add(doc.type());
        }
        TradeDocDtos.LcTerms lc = req.lcTerms();
        List<TradeDocDtos.PairResult> pairs = new ArrayList<>();
        List<TradeDocDtos.ToleranceApplied> toleranceApplied = new ArrayList<>();

        // —— 单单比对：发票金额↔装箱单金额↔汇票金额（容差），货物描述/当事人/唛头归一化全等 ——
        List<String> amountTypes = List.of("INVOICE", "PACKING_LIST", "DRAFT");
        for (int i = 0; i < amountTypes.size(); i++) {
            for (int j = i + 1; j < amountTypes.size(); j++) {
                TradeDocDtos.DocDto a = byType.get(amountTypes.get(i));
                TradeDocDtos.DocDto b = byType.get(amountTypes.get(j));
                if (a == null || b == null) {
                    continue;
                }
                BigDecimal amtA = optAmount(a.elements());
                BigDecimal amtB = optAmount(b.elements());
                if (amtA == null || amtB == null || amtA.signum() <= 0 || amtB.signum() <= 0) {
                    pairs.add(new TradeDocDtos.PairResult(a.type(), b.type(), "amount",
                            money(amtA), money(amtB), false, "金额要素缺失"));
                    continue;
                }
                BigDecimal diffRatio = amtA.subtract(amtB).abs().divide(amtA, 6, RoundingMode.HALF_UP);
                boolean consistent = diffRatio.compareTo(tolerance) <= 0;
                pairs.add(new TradeDocDtos.PairResult(a.type(), b.type(), "amount",
                        amtA.toPlainString(), amtB.toPlainString(), consistent,
                        diffRatio.signum() == 0 ? "金额相等" : "容差 ±" + tolerance + " 内"));
                toleranceApplied.add(new TradeDocDtos.ToleranceApplied("amount",
                        a.type() + "↔" + b.type(), tolerance, diffRatio.signum() != 0));
            }
        }
        pairText(pairs, byType.get("INVOICE"), byType.get("PACKING_LIST"), "goodsDesc", "货物描述");
        pairText(pairs, byType.get("INVOICE"), byType.get("BILL_OF_LADING"), "party", "当事人");
        pairText(pairs, byType.get("INVOICE"), byType.get("PACKING_LIST"), "marks", "唛头");

        // —— 单证比对：逐条款命中 OK/WARN/FAIL ——
        List<TradeDocDtos.ClauseHit> clauseHits = new ArrayList<>();
        BigDecimal maxDocAmount = maxDocAmount(req.documents(), BigDecimal.ZERO);
        boolean amountOk = maxDocAmount.signum() <= 0
                || maxDocAmount.compareTo(lc.amount().multiply(BigDecimal.ONE.add(tolerance))) <= 0;
        clauseHits.add(new TradeDocDtos.ClauseHit("AMOUNT", amountOk ? "OK" : "FAIL",
                maxDocAmount.signum() <= 0 ? "单据金额缺失，未比对"
                        : "单据金额 " + maxDocAmount + " ≤ 信用证金额 " + lc.amount() + "×（1+容差" + tolerance + "）"));
        TradeDocDtos.DocDto invoice = byType.get("INVOICE");
        String invGoods = str(invoice == null || invoice.elements() == null ? null : invoice.elements().get("goodsDesc"));
        clauseHits.add(new TradeDocDtos.ClauseHit("GOODS",
                invGoods.isBlank() || normalize(invGoods).equals(normalize(lc.goodsDesc())) ? "OK" : "FAIL",
                invGoods.isBlank() ? "发票货物描述缺失"
                        : "发票货物描述与信用证" + (normalize(invGoods).equals(normalize(lc.goodsDesc())) ? "一致" : "不一致（UCP600 14(e)）")));
        String invParty = str(invoice == null || invoice.elements() == null ? null : invoice.elements().get("party"));
        clauseHits.add(new TradeDocDtos.ClauseHit("PARTY",
                invParty.isBlank() || normalize(invParty).equals(normalize(lc.beneficiary())) ? "OK" : "WARN",
                invParty.isBlank() ? "发票当事人缺失" : "发票当事人与信用证受益" +
                        (normalize(invParty).equals(normalize(lc.beneficiary())) ? "一致" : "不一致（可凭统称）")));
        String shipmentDate = str(invoice == null || invoice.elements() == null ? null : invoice.elements().get("shipmentDate"));
        boolean shipmentOk = shipmentDate.isBlank()
                || !parseDate(shipmentDate, "elements.shipmentDate").isAfter(LocalDate.parse(lc.latestShipmentDate()));
        clauseHits.add(new TradeDocDtos.ClauseHit("SHIPMENT", shipmentOk ? "OK" : "FAIL",
                shipmentDate.isBlank() ? "装运期缺失"
                        : "装运期 " + shipmentDate + (shipmentOk ? " ≤ 最迟装运日 " + lc.latestShipmentDate()
                        : " 晚于最迟装运日 " + lc.latestShipmentDate())));
        LocalDate today = LocalDate.now();
        boolean expiryOk = !today.isAfter(LocalDate.parse(lc.expiryDate()));
        clauseHits.add(new TradeDocDtos.ClauseHit("EXPIRY", expiryOk ? "OK" : "FAIL",
                "交单日 " + today + (expiryOk ? " 在效期 " + lc.expiryDate() + " 内" : " 晚于效期 " + lc.expiryDate())));
        boolean docsOk = lc.docsRequired() != null && presentTypes.containsAll(lc.docsRequired());
        clauseHits.add(new TradeDocDtos.ClauseHit("DOC_TYPES", docsOk ? "OK" : "FAIL",
                "单据种类" + (docsOk ? "满足信用证要求" : "未全覆盖：缺失 " + (lc.docsRequired() == null ? "—"
                        : lc.docsRequired().stream().filter(t -> !presentTypes.contains(t)).toList()))));

        boolean docToDocOk = pairs.stream().allMatch(TradeDocDtos.PairResult::consistent);
        boolean docToLcOk = clauseHits.stream().noneMatch(h -> "FAIL".equals(h.status()));
        return new TradeDocDtos.ConsistencyResult(req.lcNo(),
                new TradeDocDtos.DocToDocResult(docToDocOk ? "MATCH" : "MISMATCH", List.copyOf(pairs)),
                new TradeDocDtos.DocToLcResult(docToLcOk ? "MATCH" : "MISMATCH", List.copyOf(clauseHits)),
                List.copyOf(toleranceApplied));
    }

    /** 单据间文本字段比对（归一化全等），缺字段视为不一致 */
    private static void pairText(List<TradeDocDtos.PairResult> pairs, TradeDocDtos.DocDto a, TradeDocDtos.DocDto b,
                                 String field, String label) {
        if (a == null || b == null) {
            return;
        }
        String va = str(a.elements() == null ? null : a.elements().get(field));
        String vb = str(b.elements() == null ? null : b.elements().get(field));
        if (va.isBlank() && vb.isBlank()) {
            return;
        }
        if (va.isBlank() || vb.isBlank()) {
            pairs.add(new TradeDocDtos.PairResult(a.type(), b.type(), field, va, vb, false, label + "要素缺失"));
            return;
        }
        boolean consistent = normalize(va).equals(normalize(vb));
        pairs.add(new TradeDocDtos.PairResult(a.type(), b.type(), field, va, vb, consistent,
                consistent ? "一致" : "不一致"));
    }

    // ==================== 4.4 不符点清单生成 ====================

    private static final Set<String> DIFF_CATEGORIES = Set.of("AMOUNT", "DATE", "GOODS", "DOC_TYPE", "COPIES",
            "ENDORSEMENT", "SIGNATURE", "SPELLING");

    /** 不符点清单生成（幂等）：按类别重判严重等级（金额/日期/单据种类/份数 → MAJOR 拒付；描述/背书 → MINOR 改单或担保；
     * 拼写/格式 → MINOR 提示），按 UCP600 16 条组织拒付通知草稿 */
    public TradeDocDtos.DiscrepancyGenResult discrepancy(TradeDocDtos.DiscrepancyRequest req, String requestId) {
        validateDiscrepancy(req);

        String hash = sha256(String.join("|", str(req.lcNo()), str(req.checkNo()), String.valueOf(req.diffs())));
        String prevHash = idempotency.get("discrepancy-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(TradeDocErrorCodes.DUPLICATE_CONFLICT,
                        "TRD_1005 重复生成/差异要素冲突，请使用原 requestId 查询首次结果");
            }
            return idempotency.get("discrepancy", requestId);
        }

        TradeDocDtos.DiscrepancyGenResult result = computeDiscrepancy(req);
        idempotency.put("discrepancy-hash", requestId, hash);
        idempotency.put("discrepancy", requestId, result);
        log.info("不符点清单生成: requestId={}, lcNo={}, checkNo={}, discrepancies={}, major={}",
                requestId, req.lcNo(), req.checkNo(), result.discrepancyList().size(),
                result.discrepancyList().stream().filter(d -> "MAJOR".equals(d.severity())).count());
        return result;
    }

    private void validateDiscrepancy(TradeDocDtos.DiscrepancyRequest req) {
        if (req == null || blank(req.lcNo())) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：lcNo 必填");
        }
        if (blank(req.checkNo())) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：checkNo 审核流水号必填");
        }
        if (req.diffs() == null || req.diffs().isEmpty()) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：diffs 差异记录必填");
        }
        for (TradeDocDtos.DiffRecord d : req.diffs()) {
            if (d == null || d.category() == null || !DIFF_CATEGORIES.contains(d.category())) {
                throw new BaiException(TradeDocErrorCodes.ELEMENT_ILLEGAL,
                        "TRD_1007 差异类别非法：category 须为 AMOUNT/DATE/GOODS/DOC_TYPE/COPIES/ENDORSEMENT/SIGNATURE/SPELLING");
            }
            if (blank(d.description())) {
                throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：diffs.description 必填");
            }
        }
    }

    private TradeDocDtos.DiscrepancyGenResult computeDiscrepancy(TradeDocDtos.DiscrepancyRequest req) {
        List<TradeDocDtos.Discrepancy> list = new ArrayList<>();
        for (int i = 0; i < req.diffs().size(); i++) {
            TradeDocDtos.DiffRecord d = req.diffs().get(i);
            String severity;
            String advice;
            switch (d.category()) {
                case "AMOUNT", "DATE", "DOC_TYPE", "COPIES" -> {
                    severity = "MAJOR";
                    advice = switch (d.category()) {
                        case "AMOUNT" -> "拒付或修改单据/信用证";
                        case "DATE" -> "联系申请人接受不符点或改证";
                        default -> "补齐缺失单据/份数后重新交单";
                    };
                }
                case "GOODS", "PARTY" -> {
                    severity = "MINOR";
                    advice = "改单或申请人出具担保接受";
                }
                case "ENDORSEMENT", "SIGNATURE" -> {
                    severity = "MINOR";
                    advice = "核实背书/签字瑕疵，可凭担保接受";
                }
                default -> { // SPELLING / FORMAT
                    severity = "MINOR";
                    advice = "提示修正拼写/格式差异，不影响议付";
                }
            }
            list.add(new TradeDocDtos.Discrepancy(i + 1, d.category(), d.description(), severity, advice));
        }
        boolean major = list.stream().anyMatch(x -> "MAJOR".equals(x.severity()));
        StringBuilder sb = new StringBuilder();
        if (major) {
            sb.append("拒付通知草稿（UCP600 第 16 条）：本行凭本通知拒付信用证 ").append(req.lcNo())
                    .append(" 项下单据（审核流水 ").append(req.checkNo()).append("）。下列不符点构成拒付理由：");
        } else {
            sb.append("不符点提示（无需拒付，UCP600 第 16 条）：信用证 ").append(req.lcNo())
                    .append("（审核流水 ").append(req.checkNo()).append("）项下单据存在下列提示性差异：");
        }
        for (TradeDocDtos.Discrepancy d : list) {
            sb.append(d.no()).append(") ").append(d.category()).append("：").append(d.description()).append("；");
        }
        sb.append("单据代为保管，听候处理（拒付通知须自收到单据之日起 5 个银行工作日内发出）。");
        return new TradeDocDtos.DiscrepancyGenResult(req.lcNo(), req.checkNo(), list, sb.toString());
    }

    // ==================== 4.5 信用证处理 ====================

    private static final Set<String> OPERATIONS = Set.of("ISSUE_CHECK", "ADVICE", "NEGOTIATION", "EXPORT_BILL",
            "IMPORT_BILL");
    private static final List<String> SOFT_CLAUSE_KEYWORDS = List.of("以买方验货合格为准", "以买方检验", "收到申请人付款",
            "凭申请人收据", "凭申请人证明", "另行通知装运");
    /** 出口押汇默认比例 0.85（6.3(5) 出口押汇默认 0.8~0.9） */
    private static final BigDecimal DEFAULT_EXPORT_RATIO = new BigDecimal("0.85");

    /** 信用证处理（幂等）：ISSUE_CHECK 开证条款检查（软条款识别/可撤销性/单据要求完整性）→ REGISTERED；
     * ADVICE 通知登记 → ADVISED；NEGOTIATION 议付金额 = 汇票金额 −（贴现息+手续费）→ NEGOTIATED；
     * EXPORT_BILL/IMPORT_BILL 押汇金额 = 单据金额 × 押汇比例（进口按到期应付）→ BILLED */
    public TradeDocDtos.LcResult lc(TradeDocDtos.LcRequest req, String requestId) {
        validateLc(req);

        String hash = sha256(String.join("|", str(req.operation()), str(req.lcNo()), String.valueOf(req.lcTerms()),
                String.valueOf(req.documents()), String.valueOf(req.feeParams())));
        String prevHash = idempotency.get("lc-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(TradeDocErrorCodes.DUPLICATE_CONFLICT,
                        "TRD_1005 重复提交/信用证处理要素冲突，请使用原 requestId 查询首次结果");
            }
            return idempotency.get("lc", requestId);
        }

        TradeDocDtos.LcResult result = computeLc(req);
        idempotency.put("lc-hash", requestId, hash);
        idempotency.put("lc", requestId, result);
        log.info("信用证处理: requestId={}, lcNo={}, operation={}, status={}, negotiationAmount={}, billAmount={}",
                requestId, req.lcNo(), req.operation(), result.status(),
                result.negotiationResult() == null ? null : result.negotiationResult().amount(),
                result.billResult() == null ? null : result.billResult().amount());
        return result;
    }

    private void validateLc(TradeDocDtos.LcRequest req) {
        if (req == null || blank(req.lcNo())) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：lcNo 必填");
        }
        if (req.operation() == null || !OPERATIONS.contains(req.operation())) {
            throw new BaiException(TradeDocErrorCodes.ELEMENT_ILLEGAL,
                    "TRD_1007 操作类型非法：operation 须为 ISSUE_CHECK/ADVICE/NEGOTIATION/EXPORT_BILL/IMPORT_BILL");
        }
        if (req.lcTerms() == null || req.lcTerms().amount() == null || req.lcTerms().amount().signum() <= 0) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数非法：lcTerms.amount 必填且 > 0");
        }
        if (blank(req.lcTerms().beneficiary()) || blank(req.lcTerms().applicant())) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID,
                    "TRD_1001 参数缺失：lcTerms.beneficiary/applicant 必填");
        }
        if (blank(req.lcTerms().expiryDate())) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：lcTerms.expiryDate 必填");
        }
        parseDate(req.lcTerms().expiryDate(), "lcTerms.expiryDate");
        if ("ISSUE_CHECK".equals(req.operation())
                && (req.lcTerms().docsRequired() == null || req.lcTerms().docsRequired().isEmpty())) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID,
                    "TRD_1001 参数缺失：ISSUE_CHECK 要求 lcTerms.docsRequired 必填");
        }
        if ("NEGOTIATION".equals(req.operation()) && maxDocAmount(req.documents(), BigDecimal.ZERO).signum() <= 0) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID,
                    "TRD_1001 参数非法：NEGOTIATION 要求 documents 含有效单据金额（汇票/发票）");
        }
    }

    private TradeDocDtos.LcResult computeLc(TradeDocDtos.LcRequest req) {
        TradeDocDtos.LcTerms lc = req.lcTerms();
        TradeDocDtos.FeeParams fee = req.feeParams();
        LocalDate today = LocalDate.now();
        TradeDocDtos.IssueCheckReport issue = null;
        TradeDocDtos.NegotiationResult negotiation = null;
        TradeDocDtos.BillResult bill = null;
        String status;
        switch (req.operation()) {
            case "ISSUE_CHECK" -> {
                status = "REGISTERED";
                issue = buildIssueCheckReport(lc);
            }
            case "ADVICE" -> status = "ADVISED";
            case "NEGOTIATION" -> {
                status = "NEGOTIATED";
                BigDecimal draftAmount = maxDocAmount(req.documents(), lc.amount());
                BigDecimal discountRate = fee != null && fee.discountRate() != null ? fee.discountRate() : BigDecimal.ZERO;
                long days = Math.max(0, ChronoUnit.DAYS.between(today, LocalDate.parse(lc.expiryDate())));
                BigDecimal discount = discountRate.signum() > 0
                        ? draftAmount.multiply(discountRate).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(days)).divide(DAYS_BASIS, MONEY_SCALE, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                BigDecimal feeAmt = fee == null || fee.negotiationFee() == null ? BigDecimal.ZERO : fee.negotiationFee();
                BigDecimal totalFee = discount.add(feeAmt).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                negotiation = new TradeDocDtos.NegotiationResult(
                        draftAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP), totalFee,
                        draftAmount.subtract(totalFee).max(BigDecimal.ZERO).setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                        today.toString());
            }
            default -> { // EXPORT_BILL / IMPORT_BILL
                status = "BILLED";
                BigDecimal base = maxDocAmount(req.documents(), lc.amount());
                BigDecimal ratio = fee != null && fee.advanceRatio() != null ? fee.advanceRatio()
                        : "EXPORT_BILL".equals(req.operation()) ? DEFAULT_EXPORT_RATIO : BigDecimal.ONE;
                if (ratio.signum() <= 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
                    throw new BaiException(TradeDocErrorCodes.PARAM_INVALID,
                            "TRD_1001 参数非法：feeParams.advanceRatio 押汇比例须在 (0,1] 内");
                }
                BigDecimal billAmount = "IMPORT_BILL".equals(req.operation()) ? base.min(lc.amount())
                        : base.multiply(ratio);
                int tenorDays = (int) Math.max(0, ChronoUnit.DAYS.between(today, LocalDate.parse(lc.expiryDate())));
                bill = new TradeDocDtos.BillResult(billAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP), tenorDays);
            }
        }
        return new TradeDocDtos.LcResult(req.lcNo(), req.operation(), status, issue, negotiation, bill);
    }

    /** 开证条款检查：可撤销性/保兑要求/软条款识别（模糊条件关键词）/单据要求完整性 */
    private TradeDocDtos.IssueCheckReport buildIssueCheckReport(TradeDocDtos.LcTerms lc) {
        String revocable = containsAny(lc, "可撤销", "REVOCABLE") ? "REVOCABLE" : "IRREVOCABLE";
        String confirmation = containsAny(lc, "保兑", "CONFIRM") ? "CONFIRMATION_REQUIRED" : "NO_CONFIRMATION";
        List<TradeDocDtos.SoftClause> soft = new ArrayList<>();
        for (String kw : SOFT_CLAUSE_KEYWORDS) {
            if (containsAny(lc, kw)) {
                soft.add(new TradeDocDtos.SoftClause(kw, "HIGH",
                        "软条款风险：以第三方/买方意愿为付款前提，与 UCP600 相符性冲突，建议删除或改证"));
            }
        }
        boolean complete = lc.docsRequired() != null && !lc.docsRequired().isEmpty()
                && lc.docsRequired().stream().allMatch(DOC_TYPES::contains);
        return new TradeDocDtos.IssueCheckReport(revocable, confirmation, soft,
                complete ? "COMPLETE" : "INCOMPLETE");
    }

    private static boolean containsAny(TradeDocDtos.LcTerms lc, String... keywords) {
        for (String kw : keywords) {
            if (str(lc.goodsDesc()).contains(kw) || str(lc.beneficiary()).contains(kw)
                    || str(lc.applicant()).contains(kw)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 4.6 福费廷买断审核 ====================

    private static final Set<String> ACCEPTOR_RATINGS = Set.of("AAA", "AA", "A", "BBB", "BB", "B", "CCC", "C", "D");
    /** 承兑行评级门槛：低于 BBB 拒买断 */
    private static final Set<String> LOW_RATINGS = Set.of("BB", "B", "CCC", "C", "D");
    /** 承诺函序号（FORF+yyyyMMdd+4 位序号） */
    private final AtomicInteger forfeitSeq = new AtomicInteger();

    /** 福费廷买断审核（幂等）：贴现利息 = 票面 × buyRate/100 ×（到期日−承兑日）/360，买断价 = 票面 − 利息；
     * 无追索权条件确认（背书/承兑/承诺函）；承兑行评级低于 BBB 或背书/承兑缺失 → REJECT */
    public TradeDocDtos.ForfeitResult forfeit(TradeDocDtos.ForfeitRequest req, String requestId) {
        validateForfeit(req);

        String hash = sha256(String.join("|", str(req.lcNo()), String.valueOf(req.draft()),
                String.valueOf(req.documents()), money(req.buyRate()), str(req.maturity()), str(req.acceptorRating())));
        String prevHash = idempotency.get("forfeit-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(TradeDocErrorCodes.DUPLICATE_CONFLICT,
                        "TRD_1005 重复提交/买断要素冲突，请使用原 requestId 查询首次结果");
            }
            return idempotency.get("forfeit", requestId);
        }

        TradeDocDtos.ForfeitResult result = computeForfeit(req);
        idempotency.put("forfeit-hash", requestId, hash);
        idempotency.put("forfeit", requestId, result);
        log.info("福费廷买断审核: requestId={}, lcNo={}, draftNo={}, buyResult={}, discountInterest={}, buyPrice={}",
                requestId, req.lcNo(), req.draft().draftNo(), result.buyResult(),
                result.discountInterest(), result.buyPrice());
        return result;
    }

    private void validateForfeit(TradeDocDtos.ForfeitRequest req) {
        if (req == null || blank(req.lcNo())) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：lcNo 必填");
        }
        if (req.draft() == null || req.draft().faceAmount() == null || req.draft().faceAmount().signum() <= 0) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数非法：draft.faceAmount 必填且 > 0");
        }
        if (blank(req.draft().draftNo()) || blank(req.draft().acceptor())) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID,
                    "TRD_1001 参数缺失：draft.draftNo/acceptor 必填");
        }
        if (blank(req.draft().acceptanceDate())) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：draft.acceptanceDate 承兑日必填");
        }
        parseDate(req.draft().acceptanceDate(), "draft.acceptanceDate");
        if (req.buyRate() == null || req.buyRate().signum() <= 0) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数非法：buyRate 买断利率必填且 > 0");
        }
        if (blank(req.maturity())) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：maturity 到期日必填");
        }
        LocalDate maturity = parseDate(req.maturity(), "maturity");
        if (!maturity.isAfter(LocalDate.parse(req.draft().acceptanceDate()))) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID,
                    "TRD_1001 参数非法：maturity 到期日须晚于承兑日");
        }
        if (req.acceptorRating() == null || !ACCEPTOR_RATINGS.contains(req.acceptorRating())) {
            throw new BaiException(TradeDocErrorCodes.ELEMENT_ILLEGAL,
                    "TRD_1007 承兑行评级非法：acceptorRating 须为 AAA/AA/A/BBB/BB/B/CCC/C/D");
        }
    }

    private TradeDocDtos.ForfeitResult computeForfeit(TradeDocDtos.ForfeitRequest req) {
        if (req.lcNo().toUpperCase().contains("SANCTION")) {
            throw new BaiException(TradeDocErrorCodes.SANCTION_HIT,
                    "TRD_4104 制裁名单命中：lcNo=" + req.lcNo() + "，买断阻断，转合规人工处置");
        }
        TradeDocDtos.DraftDto draft = req.draft();
        LocalDate acceptance = LocalDate.parse(draft.acceptanceDate());
        LocalDate maturity = LocalDate.parse(req.maturity());
        long days = Math.max(0, ChronoUnit.DAYS.between(acceptance, maturity));
        BigDecimal discountInterest = draft.faceAmount().multiply(req.buyRate())
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(days)).divide(DAYS_BASIS, MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal buyPrice = draft.faceAmount().subtract(discountInterest).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        boolean endorsementValid = !blank(draft.endorsement());
        boolean acceptanceValid = !blank(draft.acceptanceDate());
        boolean lowRating = LOW_RATINGS.contains(req.acceptorRating());
        String buyResult;
        String detail;
        if (!endorsementValid || !acceptanceValid) {
            buyResult = "REJECT";
            detail = "无追索权条件不满足：背书/承兑要素缺失，不能确认无追索权买断";
        } else if (lowRating) {
            buyResult = "REJECT";
            detail = "承兑行评级 " + req.acceptorRating() + " 低于 BBB，风险审查未通过";
        } else {
            buyResult = "COMPLIANT";
            detail = "相符单据 + 有效承兑 + 背书完整，承诺函要素已生成";
        }
        TradeDocDtos.RecourseFreeCheck check = new TradeDocDtos.RecourseFreeCheck(
                endorsementValid, acceptanceValid, !lowRating, detail);
        TradeDocDtos.CommitmentLetter letter = "COMPLIANT".equals(buyResult)
                ? new TradeDocDtos.CommitmentLetter(
                "FORF" + LocalDate.now().toString().replace("-", "") + String.format("%04d", forfeitSeq.incrementAndGet()),
                firstElement(req.documents(), "INVOICE", "party"), draft.acceptor(), draft.faceAmount(),
                buyPrice, req.maturity(), req.acceptorRating())
                : null;
        return new TradeDocDtos.ForfeitResult(req.lcNo(), buyResult, discountInterest, buyPrice, check, letter);
    }

    // ==================== 4.7 贸易融资单据核验 ====================

    private static final Set<String> FINANCE_TYPES = Set.of("PACKING", "EXPORT_BILL", "IMPORT_BILL");
    /** 融资比例：打包贷款 ≤ 信用证金额 × 70%；出口押汇 ≤ 单据金额 × 90%；进口押汇 ≤ 到期应付（6.3(7)） */
    private static final BigDecimal PACKING_RATIO = new BigDecimal("0.70");
    private static final BigDecimal EXPORT_BILL_RATIO = new BigDecimal("0.90");
    private static final BigDecimal IMPORT_BILL_RATIO = BigDecimal.ONE;

    /** 贸易融资单据核验（幂等）：打包贷款（信用证有效/受益人一致/未重复融资）、出口押汇（单据相符/装运完成/保险覆盖）、
     * 进口押汇（到单相符/单据质押）+ 贸易真实性（合同/报关单/物流）；欺诈风险 HIGH → TRD_4103 阻断；
     * availableAmount = 融资基数 × 比例（客户风险等级 HIGH 比例调降 20%） */
    public TradeDocDtos.TradeFinanceResult tradeFinance(TradeDocDtos.TradeFinanceRequest req, String requestId) {
        validateTradeFinance(req);

        String hash = sha256(String.join("|", str(req.financeType()), str(req.lcNo()),
                String.valueOf(req.documents()), String.valueOf(req.lcTerms()), String.valueOf(req.tradeBackground())));
        String prevHash = idempotency.get("trade-finance-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(TradeDocErrorCodes.DUPLICATE_CONFLICT,
                        "TRD_1005 重复核验/融资要素冲突，请使用原 requestId 查询首次结果");
            }
            return idempotency.get("trade-finance", requestId);
        }

        TradeDocDtos.TradeFinanceResult result = computeTradeFinance(req);
        idempotency.put("trade-finance-hash", requestId, hash);
        idempotency.put("trade-finance", requestId, result);
        log.info("贸易融资单据核验: requestId={}, financeType={}, lcNo={}, verifyResult={}, availableAmount={}, ratio={}",
                requestId, req.financeType(), req.lcNo(), result.verifyResult(),
                result.availableAmount(), result.ratio());
        return result;
    }

    private void validateTradeFinance(TradeDocDtos.TradeFinanceRequest req) {
        if (req == null || blank(req.lcNo())) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：lcNo 必填");
        }
        if (req.financeType() == null || !FINANCE_TYPES.contains(req.financeType())) {
            throw new BaiException(TradeDocErrorCodes.ELEMENT_ILLEGAL,
                    "TRD_1007 融资类型非法：financeType 须为 PACKING/EXPORT_BILL/IMPORT_BILL");
        }
        if (req.documents() == null || req.documents().isEmpty()) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：documents 单据数组必填");
        }
        for (TradeDocDtos.DocDto doc : req.documents()) {
            if (doc == null || doc.type() == null || !DOC_TYPES.contains(doc.type())) {
                throw new BaiException(TradeDocErrorCodes.ELEMENT_ILLEGAL,
                        "TRD_1007 单据类型非法：type 须为 INVOICE/PACKING_LIST/BILL_OF_LADING/INSURANCE/CERTIFICATE_ORIGIN/INSPECTION/DRAFT");
            }
            if (blank(doc.docId())) {
                throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：documents.docId 必填");
            }
        }
        if (req.tradeBackground() != null && !blank(req.tradeBackground().riskLevel())
                && !Set.of("LOW", "MEDIUM", "HIGH").contains(req.tradeBackground().riskLevel().toUpperCase())) {
            throw new BaiException(TradeDocErrorCodes.ELEMENT_ILLEGAL,
                    "TRD_1007 风险等级非法：tradeBackground.riskLevel 须为 LOW/MEDIUM/HIGH");
        }
        if (req.lcTerms() != null && (req.lcTerms().amount() == null || req.lcTerms().amount().signum() <= 0)) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数非法：lcTerms.amount 须 > 0");
        }
    }

    private TradeDocDtos.TradeFinanceResult computeTradeFinance(TradeDocDtos.TradeFinanceRequest req) {
        if (req.lcNo().toUpperCase().contains("SANCTION")) {
            throw new BaiException(TradeDocErrorCodes.SANCTION_HIT,
                    "TRD_4104 制裁名单命中：lcNo=" + req.lcNo() + "，融资核验阻断，转合规人工处置");
        }
        // 欺诈仿真：lcNo 含 FRAUD，或客户风险 HIGH 且贸易背景缺失 → 欺诈风险阻断
        boolean backgroundFail = req.tradeBackground() == null
                || (blank(req.tradeBackground().contractNo()) && blank(req.tradeBackground().customsDeclarationNo()));
        boolean fraudHit = req.lcNo().toUpperCase().contains("FRAUD")
                || (req.tradeBackground() != null && "HIGH".equalsIgnoreCase(req.tradeBackground().riskLevel())
                && backgroundFail);
        if (fraudHit) {
            throw new BaiException(TradeDocErrorCodes.FRAUD_RISK,
                    "TRD_4103 单据欺诈风险 HIGH：同一单据多次融资/贸易背景异常，阻断并转反欺诈调查：lcNo=" + req.lcNo());
        }

        Map<String, TradeDocDtos.DocDto> byType = new java.util.LinkedHashMap<>();
        for (TradeDocDtos.DocDto doc : req.documents()) {
            byType.putIfAbsent(doc.type(), doc);
        }
        boolean insuranceCovered = byType.containsKey("INSURANCE");
        boolean billOfLadingPresent = byType.containsKey("BILL_OF_LADING");
        boolean docsValid = req.documents().stream()
                .allMatch(d -> DOC_TYPES.contains(d.type()) && !blank(d.docId()));

        List<TradeDocDtos.ReviewItem> items = new ArrayList<>();
        items.add(new TradeDocDtos.ReviewItem("TRADE_BACKGROUND", backgroundFail ? "FAIL" : "PASS",
                backgroundFail ? "贸易背景材料缺失（合同/报关单引用必填）" : "贸易背景已核实（合同/报关单/物流引用齐全）"));
        items.add(new TradeDocDtos.ReviewItem("DUPLICATE_FINANCING",
                req.lcNo().toUpperCase().contains("DUP") ? "FAIL" : "PASS",
                req.lcNo().toUpperCase().contains("DUP") ? "信用证项下已融资，存在重复融资风险" : "未重复融资"));
        items.add(new TradeDocDtos.ReviewItem("DOC_COMPLIANCE", docsValid ? "PASS" : "FAIL",
                docsValid ? "单据要素完整" : "单据要素不完整/不一致"));
        items.add(new TradeDocDtos.ReviewItem("INSURANCE_COVERED",
                "EXPORT_BILL".equals(req.financeType()) && !insuranceCovered ? "FAIL" : "PASS",
                "EXPORT_BILL".equals(req.financeType()) && !insuranceCovered
                        ? "出口押汇要求保险覆盖，INSURANCE 单据缺失" : "保险覆盖已确认"));
        items.add(new TradeDocDtos.ReviewItem("SHIPMENT_DONE",
                "EXPORT_BILL".equals(req.financeType()) && !billOfLadingPresent ? "FAIL" : "PASS",
                "EXPORT_BILL".equals(req.financeType()) && !billOfLadingPresent
                        ? "出口押汇要求装运完成，BILL_OF_LADING 单据缺失" : "装运状态已确认"));

        BigDecimal base;
        BigDecimal ratio;
        switch (req.financeType()) {
            case "PACKING" -> {
                base = req.lcTerms() != null ? req.lcTerms().amount() : maxDocAmount(req.documents(), BigDecimal.ZERO);
                ratio = PACKING_RATIO;
            }
            case "EXPORT_BILL" -> {
                base = maxDocAmount(req.documents(), BigDecimal.ZERO);
                ratio = EXPORT_BILL_RATIO;
            }
            default -> { // IMPORT_BILL：按到期应付
                base = req.lcTerms() != null ? req.lcTerms().amount() : maxDocAmount(req.documents(), BigDecimal.ZERO);
                ratio = IMPORT_BILL_RATIO;
            }
        }
        boolean verified = items.stream().allMatch(i -> "PASS".equals(i.result()));
        if (base.signum() <= 0) {
            items.add(new TradeDocDtos.ReviewItem("BASE_AMOUNT", "FAIL",
                    req.financeType() + " 融资基数缺失（lcTerms.amount/单据金额必填）"));
            verified = false;
            base = BigDecimal.ZERO;
        }
        if (req.tradeBackground() != null && "HIGH".equalsIgnoreCase(req.tradeBackground().riskLevel())) {
            ratio = ratio.multiply(new BigDecimal("0.8")); // 高风险客户融资比例调降 20%
        }
        BigDecimal availableAmount = base.multiply(ratio).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new TradeDocDtos.TradeFinanceResult(req.financeType(), req.lcNo(),
                verified ? "VERIFIED" : "REJECTED", availableAmount, ratio.setScale(4, RoundingMode.HALF_UP),
                new TradeDocDtos.ReviewReport(items));
    }

    /** 单据金额最大值（发票/汇票/装箱单），无有效金额回退 fallback */
    private static BigDecimal maxDocAmount(List<TradeDocDtos.DocDto> documents, BigDecimal fallback) {
        BigDecimal max = BigDecimal.ZERO;
        if (documents != null) {
            for (TradeDocDtos.DocDto doc : documents) {
                BigDecimal amt = optAmount(doc.elements());
                if (amt != null && amt.signum() > 0 && amt.compareTo(max) > 0) {
                    max = amt;
                }
            }
        }
        return max.signum() > 0 ? max : fallback;
    }

    /** 取指定单据类型的首个要素（如 INVOICE 的 party），缺失返回空串 */
    private static String firstElement(List<TradeDocDtos.DocDto> documents, String type, String key) {
        if (documents != null) {
            for (TradeDocDtos.DocDto doc : documents) {
                if (type.equals(doc.type()) && doc.elements() != null && doc.elements().get(key) != null) {
                    return String.valueOf(doc.elements().get(key));
                }
            }
        }
        return "";
    }

    private static String money(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private void validate(TradeDocDtos.CheckRequest req) {
        if (req == null || req.lcNo() == null || req.lcNo().isBlank()) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：lcNo 必填");
        }
        if (req.documents() == null || req.documents().isEmpty()) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：documents 单据数组必填");
        }
        for (TradeDocDtos.DocDto doc : req.documents()) {
            if (doc == null || doc.type() == null || !DOC_TYPES.contains(doc.type())) {
                throw new BaiException(TradeDocErrorCodes.ELEMENT_ILLEGAL,
                        "TRD_1007 单据类型非法：type 须为 INVOICE/PACKING_LIST/BILL_OF_LADING/INSURANCE/CERTIFICATE_ORIGIN/INSPECTION/DRAFT，当前 "
                                + (doc == null ? null : doc.type()));
            }
            if (doc.docId() == null || doc.docId().isBlank()) {
                throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：documents.docId 必填（type=" + doc.type() + "）");
            }
        }
        if (req.lcTerms() == null) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：lcTerms 信用证条款必填");
        }
        if (req.lcTerms().amount() == null || req.lcTerms().amount().signum() <= 0) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数非法：lcTerms.amount 必填且 > 0");
        }
        if (blank(req.lcTerms().goodsDesc()) || blank(req.lcTerms().beneficiary()) || blank(req.lcTerms().applicant())) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID,
                    "TRD_1001 参数缺失：lcTerms.goodsDesc/beneficiary/applicant 必填");
        }
        if (blank(req.lcTerms().latestShipmentDate()) || blank(req.lcTerms().expiryDate())) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID,
                    "TRD_1001 参数缺失：lcTerms.latestShipmentDate/expiryDate 必填");
        }
        parseDate(req.lcTerms().latestShipmentDate(), "lcTerms.latestShipmentDate");
        parseDate(req.lcTerms().expiryDate(), "lcTerms.expiryDate");
        if (req.lcTerms().docsRequired() == null || req.lcTerms().docsRequired().isEmpty()) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID, "TRD_1001 参数缺失：lcTerms.docsRequired 必填");
        }
        if (req.businessType() == null || !BUSINESS_TYPES.contains(req.businessType())) {
            throw new BaiException(TradeDocErrorCodes.ELEMENT_ILLEGAL,
                    "TRD_1007 业务类型非法：businessType 须为 LC_NEGOTIATION/LC_PAYMENT/FORFAITING/PACKING/IMPORT_PAYMENT，当前 "
                            + req.businessType());
        }
        if (req.ruleVersion() != null && !req.ruleVersion().isBlank() && !RULE_VERSIONS.contains(req.ruleVersion())) {
            throw new BaiException(TradeDocErrorCodes.RULE_NOT_FOUND,
                    "TRD_4101 审单规则版本不存在：ruleVersion=" + req.ruleVersion());
        }
        if ("FORFAITING".equals(req.businessType())) {
            BigDecimal buyoutRate = req.lcTerms().buyoutRate();
            if (buyoutRate == null || buyoutRate.signum() <= 0) {
                throw new BaiException(TradeDocErrorCodes.PARAM_INVALID,
                        "TRD_1001 参数非法：FORFAITING 要求 lcTerms.buyoutRate 必填且 > 0（年化%）");
            }
        }
    }

    /** 阻断校验 + 单单/单证相符 + 福费廷试算 */
    private TradeDocDtos.CheckResult compute(TradeDocDtos.CheckRequest req) {
        // 制裁名单命中（仿真，用于联调：lcNo 含 SANCTION 即命中）
        if (req.lcNo().toUpperCase().contains("SANCTION")) {
            throw new BaiException(TradeDocErrorCodes.SANCTION_HIT,
                    "TRD_4104 制裁名单命中：lcNo=" + req.lcNo() + "，审单阻断，转合规人工处置");
        }
        // 审单超期（简化按日历日）：任一单据 docDate 距今超 5 日阻断
        LocalDate today = LocalDate.now();
        for (TradeDocDtos.DocDto doc : req.documents()) {
            String docDate = str(doc.elements() == null ? null : doc.elements().get("docDate"));
            if (!docDate.isBlank()) {
                LocalDate dd = parseDate(docDate, "documents.docDate");
                if (ChronoUnit.DAYS.between(dd, today) > CHECK_OVERDUE_DAYS) {
                    throw new BaiException(TradeDocErrorCodes.CHECK_OVERDUE,
                            "TRD_4102 审单超期：单据 " + doc.docId() + " 日期 " + docDate + " 距今超 " + CHECK_OVERDUE_DAYS
                                    + " 日，阻断，请说明原因后重新审单");
                }
            }
        }

        List<TradeDocDtos.Discrepancy> discrepancies = new ArrayList<>();
        Map<String, TradeDocDtos.DocDto> byType = new java.util.LinkedHashMap<>();
        Set<String> presentTypes = new HashSet<>();
        for (TradeDocDtos.DocDto doc : req.documents()) {
            byType.putIfAbsent(doc.type(), doc);
            presentTypes.add(doc.type());
        }

        // —— 单单相符：发票/装箱单/汇票金额两两比对，容差 ±5% ——
        List<String> amountTypes = List.of("INVOICE", "PACKING_LIST", "DRAFT");
        for (int i = 0; i < amountTypes.size(); i++) {
            for (int j = i + 1; j < amountTypes.size(); j++) {
                TradeDocDtos.DocDto a = byType.get(amountTypes.get(i));
                TradeDocDtos.DocDto b = byType.get(amountTypes.get(j));
                if (a == null || b == null) {
                    continue;
                }
                BigDecimal amtA = optAmount(a.elements());
                BigDecimal amtB = optAmount(b.elements());
                if (amtA == null || amtB == null || amtA.signum() <= 0) {
                    continue;
                }
                BigDecimal diffRatio = amtA.subtract(amtB).abs().divide(amtA, 6, RoundingMode.HALF_UP);
                if (diffRatio.compareTo(AMOUNT_TOLERANCE) > 0) {
                    discrepancies.add(new TradeDocDtos.Discrepancy(0, "DOC_TO_DOC_AMOUNT",
                            a.type() + " 金额 " + amtA + " 与 " + b.type() + " 金额 " + amtB + " 偏差 "
                                    + diffRatio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "% 超出 ±5% 容差",
                            "MAJOR", "核对商业发票、装箱单与汇票金额后重新交单"));
                }
            }
        }

        // —— 单证相符（对信用证条款）：金额上限/货物描述/当事人/装运期/效期/单据种类 ——
        TradeDocDtos.DocDto invoice = byType.get("INVOICE");
        TradeDocDtos.LcTerms lc = req.lcTerms();
        for (TradeDocDtos.DocDto doc : req.documents()) {
            BigDecimal amt = optAmount(doc.elements());
            if (amt != null && amt.compareTo(lc.amount()) > 0) {
                discrepancies.add(new TradeDocDtos.Discrepancy(0, "DOC_TO_LC_AMOUNT",
                        doc.type() + " 金额 " + amt + " 超过信用证金额 " + lc.amount(), "MAJOR",
                        "单据金额不得超过信用证金额，修改单据或信用证"));
            }
        }
        if (invoice != null) {
            String invGoods = str(invoice.elements() == null ? null : invoice.elements().get("goodsDesc"));
            if (!invGoods.isBlank() && !normalize(invGoods).equals(normalize(lc.goodsDesc()))) {
                discrepancies.add(new TradeDocDtos.Discrepancy(0, "DOC_TO_LC_GOODS",
                        "发票货物描述与信用证不一致：" + invGoods, "MAJOR",
                        "货物描述须与信用证条款一致（UCP600 第 18 条）"));
            }
            String invParty = str(invoice.elements() == null ? null : invoice.elements().get("party"));
            if (!invParty.isBlank() && !normalize(invParty).equals(normalize(lc.beneficiary()))) {
                discrepancies.add(new TradeDocDtos.Discrepancy(0, "DOC_TO_LC_PARTY",
                        "发票当事人与信用证受益人不一致：" + invParty, "MINOR",
                        "核实出单当事人与受益人名称拼写及抬头"));
            }
            String shipmentDate = str(invoice.elements() == null ? null : invoice.elements().get("shipmentDate"));
            if (!shipmentDate.isBlank() && parseDate(shipmentDate, "elements.shipmentDate")
                    .isAfter(LocalDate.parse(lc.latestShipmentDate()))) {
                discrepancies.add(new TradeDocDtos.Discrepancy(0, "LATE_SHIPMENT",
                        "装运期 " + shipmentDate + " 晚于最迟装运日 " + lc.latestShipmentDate(), "MAJOR",
                        "迟期装运构成不符点，联系申请人接受不符点或改证"));
            }
        }
        // 交单在有效期内：审单日（当前日期）不得晚于效期
        if (today.isAfter(LocalDate.parse(lc.expiryDate()))) {
            discrepancies.add(new TradeDocDtos.Discrepancy(0, "LATE_PRESENTATION",
                    "交单日 " + today + " 晚于信用证效期 " + lc.expiryDate(), "MAJOR",
                    "过期交单构成不符点（UCP600 第 6 条）"));
        }
        // docsRequired 单据种类全覆盖
        for (String required : lc.docsRequired()) {
            if (!presentTypes.contains(required)) {
                discrepancies.add(new TradeDocDtos.Discrepancy(0, "MISSING_DOC",
                        "信用证要求单据缺失：" + required, "MAJOR", "补交 " + required + " 后重新交单"));
            }
        }

        // 编号与结论
        for (int i = 0; i < discrepancies.size(); i++) {
            TradeDocDtos.Discrepancy d = discrepancies.get(i);
            discrepancies.set(i, new TradeDocDtos.Discrepancy(i + 1, d.category(), d.description(), d.severity(), d.advice()));
        }
        boolean docToDocMismatch = discrepancies.stream().anyMatch(d -> d.category().startsWith("DOC_TO_DOC"));
        boolean docToLcMismatch = discrepancies.stream()
                .anyMatch(d -> !d.category().startsWith("DOC_TO_DOC"));
        TradeDocDtos.Consistency consistency = new TradeDocDtos.Consistency(
                docToDocMismatch ? "MISMATCH" : "MATCH", docToLcMismatch ? "MISMATCH" : "MATCH");
        String checkResult = discrepancies.stream().anyMatch(d -> "MAJOR".equals(d.severity()))
                ? "DISCREPANT" : "COMPLIANT";

        // FORFAITING 买断试算：贴现利息 = 票面 × buyoutRate/100 × 剩余天数/360（真实公式）
        BigDecimal discountInterest = null;
        BigDecimal buyPrice = null;
        if ("FORFAITING".equals(req.businessType())) {
            long remainingDays = Math.max(0, ChronoUnit.DAYS.between(today, LocalDate.parse(lc.expiryDate())));
            discountInterest = lc.amount().multiply(lc.buyoutRate())
                    .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(remainingDays))
                    .divide(DAYS_BASIS, MONEY_SCALE, RoundingMode.HALF_UP);
            buyPrice = lc.amount().subtract(discountInterest).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        String checkNo = String.format("TRD%s%04d", today.toString().replace("-", ""), checkSeq.incrementAndGet());
        return new TradeDocDtos.CheckResult(checkResult, discrepancies, consistency, discountInterest, buyPrice, checkNo);
    }

    /** 金额要素（数值或可解析字符串），缺失/非法返回 null 交由具体规则决定是否跳过 */
    private static BigDecimal optAmount(Map<String, Object> elements) {
        if (elements == null) {
            return null;
        }
        Object v = elements.get("amount");
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 规范化全等：去首尾空白、压缩连续空白、统一小写（含全角空格） */
    private static String normalize(String s) {
        return s.trim().replace("\u3000", " ").replaceAll("\\s+", " ").toLowerCase();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static LocalDate parseDate(String date, String field) {
        try {
            return LocalDate.parse(date.trim());
        } catch (DateTimeParseException e) {
            throw new BaiException(TradeDocErrorCodes.PARAM_INVALID,
                    "TRD_1001 参数非法：" + field + " 须为 ISO 日期（yyyy-MM-dd），当前 " + date);
        }
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
