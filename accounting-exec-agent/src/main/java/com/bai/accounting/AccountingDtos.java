package com.bai.accounting;

import java.math.BigDecimal;
import java.util.List;

/**
 * 账务执行输入/输出结构（提示词库 A-58：POST /api/bai/accounting/entry 字段表逐条对齐）
 */
public final class AccountingDtos {

    private AccountingDtos() {}

    /** 分录行：科目代码/借贷方向(DEBIT|CREDIT)/金额/摘要 */
    public record EntryLine(String account, String direction, BigDecimal amount, String summary) {}

    /** 记账入参：eventType(LOAN|REPAY|SETTLE|WRITEOFF|IMPAIR|CAPITALIZE|FEE)，计提 accrualType、结息 settleType 可选 */
    public record EntryRequest(String eventType, String contractId, List<EntryLine> entries, BigDecimal amount,
                               String accrualType, String settleType, String requestId) {}

    /** 记账结果：entryId 记账记录 ID，借贷平衡（PASS/FAIL）后过账（glStatus: POSTED/PENDING），凭证号唯一 */
    public record EntryResult(String code, String entryId, String voucherNo, String eventType, String balanceCheck,
                              String glStatus, String postingTime, List<EntryLine> entries) {}

    /** 计提业务要素：contract 贷款号 / account 科目 / period 计提期间（yyyy-MM） */
    public record AccrualBusiness(String contract, String account, String period) {}

    /** 计提参数：rate 年利率小数（利息计提）、ecl 减值额（减值计提，ECL 仿真） */
    public record AccrualParams(BigDecimal rate, BigDecimal ecl) {}

    /** 计提入参：accrualType=INTEREST|IMPAIR|RECEIVABLE_PAYABLE；amount 计提金额必填且 > 0 */
    public record AccrualRequest(String accrualType, AccrualBusiness business, AccrualParams params,
                                 BigDecimal amount, String requestId) {}

    /** 计提结果：accrualId 计提单号，status=ACCRUED|REVERSED */
    public record AccrualResult(String code, String accrualId, String accrualType, BigDecimal amount,
                                List<EntryLine> entries, String voucherNo, String status, String createdAt) {}

    /** 结息业务要素：contract 贷款号 / account 科目 / period 结息期间（yyyy-MM） */
    public record SettleBusiness(String contract, String account, String period) {}

    /** 结息参数：rate 结息年利率小数（0<rate≤1） */
    public record SettleParams(BigDecimal rate) {}

    /** 结息入参：settleType=PERIOD|TRANSFER|REALIZE；principal 贷款本金、settleDate 结息日期（默认当日） */
    public record SettleRequest(String settleType, SettleBusiness business, SettleParams params,
                                BigDecimal principal, String settleDate, String requestId) {}

    /** 结息结果：settleId 结息单号，interest=本金×利率×天数/360，status=SETTLED|REVERSED */
    public record SettleResult(String code, String settleId, String settleType, BigDecimal interest,
                               BigDecimal rate, List<EntryLine> entries, String voucherNo, String status,
                               String createdAt) {}

    /** 记账查询入参（GET query string）：entryId 精确或 loanNo/科目/事件/状态/日期范围组合条件分页 */
    public record QueryRequest(String entryId, String loanNo, String subject, String eventType, String glStatus,
                               String startDate, String endDate, Integer page, Integer size) {}

    /** 查询记录项：type=ENTRY|ACCRUAL|SETTLE；id=entryId/accrualId/settleId；status=POSTED/ACCRUED/SETTLED */
    public record QueryItem(String type, String id, String loanNo, String eventType, String subject,
                            BigDecimal amount, List<EntryLine> entries, String voucherNo, String status,
                            String createdAt) {}

    /** 查询结果：total 命中总数 + 分页 items */
    public record QueryResult(int total, List<QueryItem> items, int page, int size) {}
}
