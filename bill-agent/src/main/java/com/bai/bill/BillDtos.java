package com.bai.bill;

import java.math.BigDecimal;
import java.util.List;

/**
 * 票据业务输入/输出结构（BIA-CAL-062：4.2~4.9 接口字段对齐，字段与设计文档表 4-2/4-3 等一致）
 */
public final class BillDtos {

    private BillDtos() {}

    /** 贴现入参：billNo/billType(BANK_ACCEPTANCE|COMMERCIAL_ACCEPTANCE)/amount>0/issueDate/dueDate/drawer/acceptor/discountRate(年化%) */
    public record DiscountRequest(String billNo, String billType, BigDecimal amount, String issueDate,
                                  String dueDate, String drawer, String acceptor, BigDecimal discountRate,
                                  String requestId) {}

    /** 贴现结果：贴现利息 = amount×rate/100×剩余天数/360，实付 = amount − 利息 */
    public record DiscountResult(String discountNo, BigDecimal discountRate, BigDecimal interestAmount,
                                 BigDecimal payableAmount, String billStatus, String verifyResult, long remainingDays) {}

    /** 票据登记信息（内存票据库，仿真用于联调） */
    public record BillInfo(String billNo, String billType, BigDecimal amount, String issueDate, String dueDate,
                           String drawer, String acceptor, String status) {}

    // ---------------------------------------------------------- 4.2 票据验真

    /** 背书链条目：seq 顺序/背书人/被背书人/背书日期/限制性记载（不得转让等） */
    public record Endorsement(Integer seq, String endorser, String endorsee, String endorseDate, String limitedMark) {}

    /** 验真入参：票号/票面要素/影像引用（ocr-agent 输出），背书链与影像为可选要素 */
    public record VerifyRequest(String billNo, String billType, Boolean electronic, BigDecimal amount,
                                String issueDate, String dueDate, String drawer, String acceptor, String payee,
                                List<Endorsement> endorsements, List<String> imageRefs, String requestId) {}

    /** 验真通道明细：票交所/防伪/背书链/限制性 */
    public record ChannelResult(String channel, String status, String detail) {}

    /** 背书链校验结论：连续性/限制性清单/瑕疵清单 */
    public record EndorsementCheck(boolean continuous, List<String> limitMarked, List<String> defects) {}

    /** 验真结果：verifyResult=PASS/FAIL/SUSPECT（任一通道 FAIL 即 FAIL，票交所超时/影像缺失转人工复核） */
    public record VerifyResult(String verifyNo, String verifyResult, List<ChannelResult> channelResults,
                               String billStatus, EndorsementCheck endorsementCheck) {}

    // ---------------------------------------------------------- 4.3 承兑管理

    /** 承兑方案：保证金比例（可选，缺省按承兑人评级查表）/敞口限额/产品参数 */
    public record AcceptanceApply(BigDecimal marginRate, BigDecimal exposureLimit, String productParams) {}

    /** 承兑入参：billNo/drawer/承兑方案/承兑人评级（AA 20%、A 30%、BBB 50%） */
    public record AcceptRequest(String billNo, String drawer, AcceptanceApply acceptanceApply,
                                String acceptorRating, String requestId) {}

    /** 敞口占用校验：占用率 = 敞口/限额，>90% 预警、>100% 阻断 */
    public record ExposureCheck(BigDecimal exposure, BigDecimal limit, BigDecimal occupancy, boolean blocked) {}

    /** 签发要素校验明细 */
    public record IssueCheckItem(String item, boolean passed, String detail) {}

    /** 承兑结果：保证金 = 票面×比例，敞口 = 票面 − 保证金 */
    public record AcceptResult(String acceptanceNo, BigDecimal marginAmount, BigDecimal exposure,
                               ExposureCheck exposureCheck, List<IssueCheckItem> issueCheck, String status) {}

    // ---------------------------------------------------------- 4.5 兑付管理

    /** 兑付申请：兑付账户/兑付日期（缺省到期日或当日）/余额查询引用 */
    public record PayApply(String account, String payDate, String balanceRef) {}

    /** 兑付入参 */
    public record PayRequest(String billNo, PayApply payApply, String requestId) {}

    /** 垫款登记：金额/利率/期限 */
    public record AdvanceInfo(BigDecimal advanceAmount, BigDecimal advanceRate, Integer termDays) {}

    /** 追索任务：按背书链逆序，被拒付款之日起 6 个月时效 */
    public record RecourseTask(String order, Integer statuteMonths, String status) {}

    /** 兑付结果：payResult=PAID/OVERDUE/ADVANCE */
    public record PayResult(String payResult, BigDecimal payAmount, String payDate,
                            AdvanceInfo advanceInfo, RecourseTask recourseTask) {}

    // ---------------------------------------------------------- 4.6 票据池管理

    /** 质押率方案：承兑人等级 + 质押率（可选，覆盖默认比例） */
    public record PledgeRatePlan(String grade, BigDecimal rate) {}

    /** 换票明细：入池/出池票据清单 */
    public record SwapDetail(List<String> inBillNos, List<String> outBillNos) {}

    /** 池操作入参：operation=IN_POOL/PLEDGE/SWAP/COLLECT */
    public record PoolRequest(String operation, List<String> billNos, String customerId,
                              PledgeRatePlan pledgeRatePlan, SwapDetail swapDetail, String requestId) {}

    /** 换票结果：入池/出池计数与质押额度差额 */
    public record SwapResult(int inCount, int outCount, BigDecimal valueDelta, boolean passed) {}

    /** 票据池结果：质押额度 = Σ(票面×质押率)，质押率银承 0.7~0.9/商票 0.5~0.7 */
    public record PoolResult(String poolId, List<String> inPoolList, BigDecimal pledgeAmount,
                             BigDecimal availableAmount, SwapResult swapResult, String status) {}

    // ---------------------------------------------------------- 4.7 背书审查

    /** 背书审查入参：背书链 + 被背书人（当前持票人，可选）+ 审查要求 */
    public record EndorseCheckRequest(String billNo, List<Endorsement> endorsements,
                                      String endorsee, String requirement, String requestId) {}

    /** 背书审查结果：checkResult=PASS/REJECT/REVIEW */
    public record EndorseCheckResult(boolean continuous, List<String> limitMarked,
                                     List<String> defects, String checkResult) {}

    // ---------------------------------------------------------- 4.8 票据质押

    /** 质押入参：票据清单/客户/融资金额/质押率（可选，缺省按承兑人等级）/盯市价 */
    public record PledgeRequest(List<String> billNos, String customerId, BigDecimal loanAmount,
                                BigDecimal pledgeRate, BigDecimal marketPrice, String requestId) {}

    /** 质押率校验：执行质押率/上限（银承 0.9、商票 0.7）/是否通过 */
    public record RateCheck(BigDecimal appliedRate, BigDecimal limitRate, boolean passed) {}

    /** 质押结果：可融额度 = Σ(票面×质押率) − 融资金额，超限阻断 */
    public record PledgeResult(String pledgeNo, BigDecimal availableAmount, RateCheck rateCheck,
                               String marginCall, String disposalAdvice) {}

    // ---------------------------------------------------------- 4.9 贴现利率定价

    /** 利率定价入参：品种/承兑人类别/期限（天）/客户评级/市场基准利率 */
    public record RateRequest(String billType, String acceptorType, Integer termDays, String customerRating,
                              BigDecimal marketRate, String productParams, String requestId) {}

    /** 利率构成：基准 + 客户溢价 + 承兑人溢价 + 期限溢价 */
    public record RateBreakdown(BigDecimal baseRate, BigDecimal customerPremium, BigDecimal acceptorPremium,
                                BigDecimal termPremium, BigDecimal totalRate) {}

    /** 利率上下限校验 */
    public record RateLimitCheck(BigDecimal minRate, BigDecimal maxRate, boolean passed) {}

    /** 利率定价结果：r = 基准 + 溢价（年化%，步长 0.01%），版本化可复算 */
    public record RateResult(String rateVersion, BigDecimal discountRate, RateBreakdown rateBreakdown,
                             RateLimitCheck rateCheck) {}
}
