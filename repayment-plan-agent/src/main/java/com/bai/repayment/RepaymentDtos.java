package com.bai.repayment;

import java.math.BigDecimal;
import java.util.List;

/**
 * 还款计划输入/输出结构（提示词库 A-60：POST /api/bai/repayment/plan 字段表逐条对齐）
 */
public final class RepaymentDtos {

    private RepaymentDtos() {}

    /** 计划入参：principal>0、rate 为年利率小数（0<rate≤1）、terms(1~360)、method 四法、recalcType 四类重算可选 */
    public record PlanRequest(String contractId, BigDecimal principal, BigDecimal rate, Integer terms, String method,
                              String startDate, String recalcType, String requestId) {}

    /** 逐期计划：期次/应还本金/应还利息/月供/还款日期 */
    public record ScheduleItem(int period, BigDecimal principal, BigDecimal interest, BigDecimal payment, String date) {}

    /** 计划结果：逐期本金合计=本金、逐期利息合计=totalInterest（误差为 0）；recalcType 首次生成为 null */
    public record PlanResult(String code, String contractId, String method, BigDecimal monthlyPayment,
                             BigDecimal totalInterest, List<ScheduleItem> schedule, String recalcType) {}

    // -------------------------------------------------------------- 计划重算（4.3）

    /** 重算触发要素：amount 提前还款金额 / newRate 新利率 / newEndDate 新到期日（按重算类型择用） */
    public record RecalcTrigger(BigDecimal amount, BigDecimal newRate, String newEndDate) {}

    /** 计划重算入参：planId 原计划、recalcType 四类重算、trigger 触发要素 */
    public record RecalcRequest(String planId, String recalcType, RecalcTrigger trigger, String requestId) {}

    /** 重算概要：新计划月供/总利息与新要素（newRate/newTerms 仅重算生效时非空） */
    public record RecalcOutcome(String recalcType, String contractId, String method, BigDecimal monthlyPayment,
                                BigDecimal totalInterest, BigDecimal newRate, Integer newTerms) {}

    /** 计划重算结果：newPlanId 新计划、oldPlanId 原计划、details 逐期明细、recalcResult 重算概要 */
    public record RecalcResult(String newPlanId, String oldPlanId, List<ScheduleItem> details,
                               RecalcOutcome recalcResult) {}

    // -------------------------------------------------------------- 计划查询（4.4）

    /** 三息明细：应计/应收/实收（仿真：计划生成时均为 0，随还款执行流转） */
    public record InterestDetail(BigDecimal accrued, BigDecimal receivable, BigDecimal received) {}

    /** 计划查询入参（GET 端点 @RequestParam 解析；planId/contractId 至少其一） */
    public record PlanQueryRequest(String planId, String contractId, String method, String status,
                                   Integer page, Integer size) {}

    /** 计划查询条目 */
    public record PlanItem(String planId, String contractId, String method, String status,
                           List<ScheduleItem> details, InterestDetail interestDetail, String createdAt) {}

    /** 计划查询结果：total 命中总数、items 分页条目 */
    public record PlanQueryResult(int total, List<PlanItem> items) {}

    // -------------------------------------------------------------- 罚息试算（4.5）

    /** 罚息试算入参：planId 计划、overduePrincipal 逾期本金、overdueInterest 逾期利息（默认 0）、
     * overdueDays 逾期天数、penaltyRate 罚息年利率、compoundRate 复利年利率（默认 0） */
    public record PenaltyRequest(String planId, BigDecimal overduePrincipal, BigDecimal overdueInterest,
                                 Integer overdueDays, BigDecimal penaltyRate, BigDecimal compoundRate, String requestId) {}

    /** 罚息逐日明细：day 逾期日序、penalty 当日罚息、compound 当日复利、cumulative 累计 */
    public record PenaltyItem(int day, BigDecimal penalty, BigDecimal compound, BigDecimal cumulative) {}

    /** 罚息试算结果：penalty 罚息、compound 复利、total 合计、details 逐日明细 */
    public record PenaltyResult(BigDecimal penalty, BigDecimal compound, BigDecimal total, List<PenaltyItem> details) {}
}
