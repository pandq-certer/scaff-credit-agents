package com.bai.tradedoc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 贸易单证输入/输出结构（BIA-CAL-063：POST /api/bai/tradedoc/check 字段表逐条对齐）
 */
public final class TradeDocDtos {

    private TradeDocDtos() {}

    /** 单据：type(INVOICE|PACKING_LIST|BILL_OF_LADING|INSURANCE|CERTIFICATE_ORIGIN|INSPECTION|DRAFT)、docId、elements{amount,goodsDesc,party,shipmentDate,expiryDate,docDate} */
    public record DocDto(String type, String docId, Map<String, Object> elements) {}

    /** 信用证条款：amount/goodsDesc/beneficiary/applicant/latestShipmentDate/expiryDate/docsRequired，FORFAITING 另需 buyoutRate（年化%） */
    public record LcTerms(BigDecimal amount, String goodsDesc, String beneficiary, String applicant,
                          String latestShipmentDate, String expiryDate, List<String> docsRequired, BigDecimal buyoutRate) {}

    /** 审单入参：lcNo/documents/lcTerms/businessType(LC_NEGOTIATION|LC_PAYMENT|FORFAITING|PACKING|IMPORT_PAYMENT)/ruleVersion 可选 */
    public record CheckRequest(String lcNo, List<DocDto> documents, LcTerms lcTerms, String businessType,
                               String ruleVersion, String requestId) {}

    /** 不符点：序号/类别/描述/严重度(MAJOR|MINOR)/处理建议 */
    public record Discrepancy(int no, String category, String description, String severity, String advice) {}

    /** 一致性结论：docToDoc 单单相符、docToLc 单证相符（MATCH/MISMATCH） */
    public record Consistency(String docToDoc, String docToLc) {}

    /** 审单结果：有 MAJOR 不符点 → DISCREPANT，否则 COMPLIANT；FORFAITING 另返回贴现利息与买断价 */
    public record CheckResult(String checkResult, List<Discrepancy> discrepancyList, Consistency consistency,
                              BigDecimal discountInterest, BigDecimal buyPrice, String checkNo) {}

    // ==================== 4.3 单单/单证相符校验 ====================

    /** 单据间比对项：左右单据类型/字段/取值/是否一致/容差说明 */
    public record PairResult(String leftType, String rightType, String field, String leftValue, String rightValue,
                             boolean consistent, String toleranceApplied) {}

    /** 单单比对结果：MATCH/MISMATCH + pairs[] */
    public record DocToDocResult(String result, List<PairResult> pairs) {}

    /** 条款命中明细：clause/状态(OK|WARN|FAIL)/说明 */
    public record ClauseHit(String clause, String status, String detail) {}

    /** 单证比对结果：MATCH/MISMATCH + clauseHits[] */
    public record DocToLcResult(String result, List<ClauseHit> clauseHits) {}

    /** 容差适用记录：字段/比对对/容差/是否触发 */
    public record ToleranceApplied(String field, String pair, BigDecimal tolerance, boolean applied) {}

    /** 相符校验入参：lcNo/documents/lcTerms/tolerance 可选（默认 ±5%，UCP600 30 条） */
    public record ConsistencyRequest(String lcNo, List<DocDto> documents, LcTerms lcTerms,
                                     BigDecimal tolerance, String requestId) {}

    /** 相符校验结果：docToDoc 单单比对、docToLc 单证比对、toleranceApplied 容差适用记录 */
    public record ConsistencyResult(String lcNo, DocToDocResult docToDoc, DocToLcResult docToLc,
                                    List<ToleranceApplied> toleranceApplied) {}

    // ==================== 4.4 不符点清单生成 ====================

    /** 差异记录（输入）：类别(AMOUNT/DATE/GOODS/DOC_TYPE/COPIES/ENDORSEMENT/SIGNATURE/SPELLING)/字段/描述/原等级 */
    public record DiffRecord(String category, String field, String description, String severity) {}

    /** 不符点清单生成入参：lcNo/checkNo/diffs 差异记录 */
    public record DiscrepancyRequest(String lcNo, String checkNo, List<DiffRecord> diffs, String requestId) {}

    /** 不符点清单生成结果：discrepancyList（编号/类别/描述/severity/处理建议）+ 拒付通知草稿（UCP600 16 条格式） */
    public record DiscrepancyGenResult(String lcNo, String checkNo, List<Discrepancy> discrepancyList,
                                       String rejectionNoticeDraft) {}

    // ==================== 4.5 信用证处理 ====================

    /** 费用参数：negotiationFee 议付费/discountRate 贴现年化率%/advanceRatio 押汇比例 */
    public record FeeParams(BigDecimal negotiationFee, BigDecimal discountRate, BigDecimal advanceRatio) {}

    /** 信用证处理入参：operation(ISSUE_CHECK|ADVICE|NEGOTIATION|EXPORT_BILL|IMPORT_BILL)/lcNo/lcTerms/documents/feeParams */
    public record LcRequest(String operation, String lcNo, LcTerms lcTerms, List<DocDto> documents,
                            FeeParams feeParams, String requestId) {}

    /** 软条款：条款原文/风险级别(HIGH|MEDIUM|LOW)/处理建议 */
    public record SoftClause(String clause, String riskLevel, String advice) {}

    /** 开证条款检查报告：可撤销性/保兑要求/软条款清单/单据要求完整性 */
    public record IssueCheckReport(String revocable, String confirmation, List<SoftClause> softClauses,
                                   String docsRequirement) {}

    /** 议付结果：汇票金额/费用（贴现息+手续费）/议付金额/日期 */
    public record NegotiationResult(BigDecimal draftAmount, BigDecimal fee, BigDecimal amount, String date) {}

    /** 押汇结果：押汇金额/期限(天) */
    public record BillResult(BigDecimal amount, Integer tenorDays) {}

    /** 信用证处理结果：operation/status(REGISTERED|ADVISED|NEGOTIATED|BILLED)/报告与结果 */
    public record LcResult(String lcNo, String operation, String status, IssueCheckReport issueCheckReport,
                           NegotiationResult negotiationResult, BillResult billResult) {}

    // ==================== 4.6 福费廷买断审核 ====================

    /** 汇票要素：draftNo/faceAmount 票面/acceptor 承兑人/acceptanceDate 承兑日/endorsement 背书 */
    public record DraftDto(String draftNo, BigDecimal faceAmount, String acceptor, String acceptanceDate,
                           String endorsement) {}

    /** 福费廷买断审核入参：lcNo/draft/documents/buyRate 买断利率年化%/maturity 到期日/acceptorRating 承兑行评级 */
    public record ForfeitRequest(String lcNo, DraftDto draft, List<DocDto> documents, BigDecimal buyRate,
                                 String maturity, String acceptorRating, String requestId) {}

    /** 无追索权条件确认：背书有效/承兑有效/承诺函确认/说明 */
    public record RecourseFreeCheck(boolean endorsementValid, boolean acceptanceValid, boolean commitmentConfirmed,
                                    String detail) {}

    /** 承诺函要素：letterNo/受益方/承兑方/票面/买断价/到期日/承兑行评级 */
    public record CommitmentLetter(String letterNo, String beneficiary, String acceptor, BigDecimal faceAmount,
                                   BigDecimal buyPrice, String maturity, String acceptorRating) {}

    /** 福费廷买断审核结果：buyResult(COMPLIANT|REJECT)/discountInterest 贴现利息/buyPrice 买断价/无追索权确认/承诺函 */
    public record ForfeitResult(String lcNo, String buyResult, BigDecimal discountInterest, BigDecimal buyPrice,
                                RecourseFreeCheck recourseFreeCheck, CommitmentLetter commitmentLetter) {}

    // ==================== 4.7 贸易融资单据核验 ====================

    /** 贸易背景：合同号/报关单号/物流单号/风险等级(客户，HIGH 可调降融资比例) */
    public record TradeBackground(String contractNo, String customsDeclarationNo, String logisticsNo,
                                  String riskLevel) {}

    /** 贸易融资单据核验入参：financeType(PACKING|EXPORT_BILL|IMPORT_BILL)/lcNo/documents/lcTerms 可选/tradeBackground */
    public record TradeFinanceRequest(String financeType, String lcNo, List<DocDto> documents, LcTerms lcTerms,
                                      TradeBackground tradeBackground, String requestId) {}

    /** 审查项：item/结果(PASS|FAIL)/说明 */
    public record ReviewItem(String item, String result, String detail) {}

    /** 审查报告：核验项清单（贸易背景/重复融资/保险覆盖/单据相符） */
    public record ReviewReport(List<ReviewItem> items) {}

    /** 贸易融资核验结果：verifyResult(VERIFIED|REJECTED)/availableAmount 可融金额/ratio 融资比例/reviewReport */
    public record TradeFinanceResult(String financeType, String lcNo, String verifyResult, BigDecimal availableAmount,
                                     BigDecimal ratio, ReviewReport reviewReport) {}
}
