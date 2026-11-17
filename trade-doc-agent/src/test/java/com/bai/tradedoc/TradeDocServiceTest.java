package com.bai.tradedoc;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TradeDocService 单元测试：单证审核（单单/单证相符）、相符校验、不符点清单、信用证处理、福费廷买断、贸易融资核验、参数校验、幂等。
 */
class TradeDocServiceTest {

    private final TradeDocService service = new TradeDocService();

    private String today() {
        return LocalDate.now().toString();
    }

    private String plusDays(long days) {
        return LocalDate.now().plusDays(days).toString();
    }

    private String minusDays(long days) {
        return LocalDate.now().minusDays(days).toString();
    }

    private TradeDocDtos.DocDto doc(String type, String docId, Map<String, Object> elements) {
        return new TradeDocDtos.DocDto(type, docId, elements);
    }

    private TradeDocDtos.LcTerms lc(BigDecimal amount, String goodsDesc, String beneficiary, String applicant,
                                    String latestShipment, String expiry, List<String> docsRequired, BigDecimal buyoutRate) {
        return new TradeDocDtos.LcTerms(amount, goodsDesc, beneficiary, applicant, latestShipment, expiry,
                docsRequired, buyoutRate);
    }

    private TradeDocDtos.CheckRequest checkReq(String lcNo, List<TradeDocDtos.DocDto> documents,
                                               TradeDocDtos.LcTerms lcTerms, String businessType, String ruleVersion) {
        return new TradeDocDtos.CheckRequest(lcNo, documents, lcTerms, businessType, ruleVersion, null);
    }

    // ------------------------------------------------------------ 单证审核（check）

    @Test
    void 相符单据_审单通过无不符点() {
        TradeDocDtos.CheckResult r = service.check(checkReq("LC001", List.of(
                doc("INVOICE", "I1", Map.of("amount", 1000000, "goodsDesc", "钢材", "party", "受益人公司", "docDate", today())),
                doc("PACKING_LIST", "P1", Map.of("amount", 1000000, "docDate", today())),
                doc("DRAFT", "D1", Map.of("amount", 1000000, "docDate", today()))),
                lc(new BigDecimal("1100000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                        List.of("INVOICE", "PACKING_LIST", "DRAFT"), null),
                "LC_NEGOTIATION", null), "ch-1");
        assertEquals("COMPLIANT", r.checkResult());
        assertTrue(r.discrepancyList().isEmpty());
        assertEquals("MATCH", r.consistency().docToDoc());
        assertEquals("MATCH", r.consistency().docToLc());
        assertTrue(r.checkNo().startsWith("TRD"));
    }

    @Test
    void 单单金额超出容差_构成主要不符点() {
        TradeDocDtos.CheckResult r = service.check(checkReq("LC002", List.of(
                doc("INVOICE", "I2", Map.of("amount", 1000000, "goodsDesc", "钢材", "docDate", today())),
                doc("PACKING_LIST", "P2", Map.of("amount", 2000000, "docDate", today()))),
                lc(new BigDecimal("3000000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                        List.of("INVOICE", "PACKING_LIST"), null),
                "LC_NEGOTIATION", null), "ch-2");
        assertEquals("DISCREPANT", r.checkResult());
        assertTrue(r.discrepancyList().stream().anyMatch(d -> d.category().equals("DOC_TO_DOC_AMOUNT")));
        assertEquals("MISMATCH", r.consistency().docToDoc());
    }

    @Test
    void 单据日期超期_抛TRD4102() {
        BaiException e = assertThrows(BaiException.class, () -> service.check(checkReq("LC003", List.of(
                doc("INVOICE", "I3", Map.of("amount", 1000000, "goodsDesc", "钢材", "docDate", minusDays(10)))),
                lc(new BigDecimal("2000000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                        List.of("INVOICE"), null),
                "LC_NEGOTIATION", null), "ch-x"));
        assertEquals("TRD_4102", e.getCode());
    }

    @Test
    void 制裁名单命中_抛TRD4104() {
        BaiException e = assertThrows(BaiException.class, () -> service.check(checkReq("LC-SANCTION-1", List.of(
                doc("INVOICE", "I4", Map.of("amount", 1000000, "docDate", today()))),
                lc(new BigDecimal("2000000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                        List.of("INVOICE"), null),
                "LC_NEGOTIATION", null), "ch-y"));
        assertEquals("TRD_4104", e.getCode());
    }

    @Test
    void 规则版本不存在_抛TRD4101() {
        BaiException e = assertThrows(BaiException.class, () -> service.check(checkReq("LC004", List.of(
                doc("INVOICE", "I5", Map.of("amount", 1000000, "docDate", today()))),
                lc(new BigDecimal("2000000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                        List.of("INVOICE"), null),
                "LC_NEGOTIATION", "UCP600-OLD"), "ch-z"));
        assertEquals("TRD_4101", e.getCode());
    }

    @Test
    void 业务类型非法_抛TRD1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.check(checkReq("LC005", List.of(
                doc("INVOICE", "I6", Map.of("amount", 1000000, "docDate", today()))),
                lc(new BigDecimal("2000000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                        List.of("INVOICE"), null),
                "SWIFT", null), "ch-w"));
        assertEquals("TRD_1007", e.getCode());
    }

    @Test
    void 福费廷买断试算_贴现利息与买断价() {
        TradeDocDtos.CheckResult r = service.check(checkReq("LC006", List.of(
                doc("INVOICE", "I7", Map.of("amount", 1000000, "goodsDesc", "钢材", "docDate", today()))),
                lc(new BigDecimal("1000000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                        List.of("INVOICE"), new BigDecimal("3.5")),
                "FORFAITING", null), "ch-7");
        // 利息 = 100万×3.5%×60/360 = 5833.33；买断价 = 994166.67
        assertEquals(0, new BigDecimal("5833.33").compareTo(r.discountInterest()));
        assertEquals(0, new BigDecimal("994166.67").compareTo(r.buyPrice()));
    }

    @Test
    void 福费廷缺买断利率_抛TRD1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.check(checkReq("LC007", List.of(
                doc("INVOICE", "I8", Map.of("amount", 1000000, "docDate", today()))),
                lc(new BigDecimal("1000000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                        List.of("INVOICE"), null),
                "FORFAITING", null), "ch-v"));
        assertEquals("TRD_1001", e.getCode());
    }

    @Test
    void 审单幂等_同requestId不同要素_抛TRD1005() {
        String rid = "idem-1";
        service.check(checkReq("LC100", List.of(
                doc("INVOICE", "I9", Map.of("amount", 1000000, "docDate", today()))),
                lc(new BigDecimal("2000000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                        List.of("INVOICE"), null),
                "LC_NEGOTIATION", null), rid);
        BaiException e = assertThrows(BaiException.class, () -> service.check(checkReq("LC101", List.of(
                doc("INVOICE", "I9", Map.of("amount", 1000000, "docDate", today()))),
                lc(new BigDecimal("2000000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                        List.of("INVOICE"), null),
                "LC_NEGOTIATION", null), rid));
        assertEquals("TRD_1005", e.getCode());
    }

    // ------------------------------------------------------------ 单单/单证相符（4.3）

    @Test
    void 相符校验_金额一致返回MATCH() {
        TradeDocDtos.ConsistencyResult r = service.consistency(new TradeDocDtos.ConsistencyRequest("LC200",
                List.of(doc("INVOICE", "I10", Map.of("amount", 1000000, "goodsDesc", "钢材")),
                        doc("PACKING_LIST", "P10", Map.of("amount", 1000000, "goodsDesc", "钢材"))),
                lc(new BigDecimal("1100000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                        List.of("INVOICE", "PACKING_LIST"), null), null, null), "cs-1");
        assertEquals("MATCH", r.docToDoc().result());
        assertEquals("MATCH", r.docToLc().result());
    }

    @Test
    void 相符校验_金额不一致返回MISMATCH() {
        TradeDocDtos.ConsistencyResult r = service.consistency(new TradeDocDtos.ConsistencyRequest("LC201",
                List.of(doc("INVOICE", "I11", Map.of("amount", 1000000, "goodsDesc", "钢材")),
                        doc("PACKING_LIST", "P11", Map.of("amount", 2000000, "goodsDesc", "钢材"))),
                lc(new BigDecimal("3000000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                        List.of("INVOICE", "PACKING_LIST"), null), null, null), "cs-2");
        assertEquals("MISMATCH", r.docToDoc().result());
    }

    @Test
    void 容差超出范围_抛TRD1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.consistency(
                new TradeDocDtos.ConsistencyRequest("LC202",
                        List.of(doc("INVOICE", "I12", Map.of("amount", 1000000, "goodsDesc", "钢材"))),
                        lc(new BigDecimal("2000000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                                List.of("INVOICE"), null), new BigDecimal("1.5"), null), "cs-x"));
        assertEquals("TRD_1001", e.getCode());
    }

    // ------------------------------------------------------------ 不符点清单（4.4）

    @Test
    void 主要不符点_生成拒付通知草稿() {
        TradeDocDtos.DiscrepancyGenResult r = service.discrepancy(new TradeDocDtos.DiscrepancyRequest("LC300",
                "TRD001", List.of(new TradeDocDtos.DiffRecord("AMOUNT", "invoice", "发票金额超信用证金额", "HIGH")), null), "dc-1");
        assertEquals(1, r.discrepancyList().size());
        assertEquals("MAJOR", r.discrepancyList().get(0).severity());
        assertTrue(r.rejectionNoticeDraft().contains("拒付通知草稿（UCP600 第 16 条）"));
    }

    @Test
    void 提示性差异_不构成拒付() {
        TradeDocDtos.DiscrepancyGenResult r = service.discrepancy(new TradeDocDtos.DiscrepancyRequest("LC301",
                "TRD002", List.of(new TradeDocDtos.DiffRecord("SPELLING", "invoice", "抬头拼写差异", "LOW")), null), "dc-2");
        assertEquals("MINOR", r.discrepancyList().get(0).severity());
        assertTrue(r.rejectionNoticeDraft().contains("不符点提示"));
    }

    @Test
    void 差异记录为空_抛TRD1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.discrepancy(
                new TradeDocDtos.DiscrepancyRequest("LC302", "TRD003", List.of(), null), "dc-x"));
        assertEquals("TRD_1001", e.getCode());
    }

    // ------------------------------------------------------------ 信用证处理（4.5）

    @Test
    void 开证检查_注册并出具检查报告() {
        TradeDocDtos.LcResult r = service.lc(new TradeDocDtos.LcRequest("ISSUE_CHECK", "LC400",
                lc(new BigDecimal("1000000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                        List.of("INVOICE"), null),
                List.of(), null, null), "lc-1");
        assertEquals("REGISTERED", r.status());
        assertNotNull(r.issueCheckReport());
        assertEquals("IRREVOCABLE", r.issueCheckReport().revocable());
    }

    @Test
    void 开证检查缺单据要求_抛TRD1001() {
        BaiException e = assertThrows(BaiException.class, () -> service.lc(
                new TradeDocDtos.LcRequest("ISSUE_CHECK", "LC401",
                        lc(new BigDecimal("1000000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                                List.of(), null),
                        List.of(), null, null), "lc-x"));
        assertEquals("TRD_1001", e.getCode());
    }

    @Test
    void 议付_按汇票金额减费用() {
        TradeDocDtos.LcResult r = service.lc(new TradeDocDtos.LcRequest("NEGOTIATION", "LC402",
                lc(new BigDecimal("1000000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                        List.of(), null),
                List.of(doc("DRAFT", "D40", Map.of("amount", 1000000))), null, null), "lc-2");
        assertEquals("NEGOTIATED", r.status());
        assertEquals(0, new BigDecimal("1000000.00").compareTo(r.negotiationResult().amount()));
    }

    @Test
    void 操作类型非法_抛TRD1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.lc(
                new TradeDocDtos.LcRequest("PAY", "LC403",
                        lc(new BigDecimal("1000000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                                List.of(), null),
                        List.of(), null, null), "lc-y"));
        assertEquals("TRD_1007", e.getCode());
    }

    // ------------------------------------------------------------ 福费廷买断（4.6）

    @Test
    void 福费廷买断_相符通过并出具承诺函() {
        TradeDocDtos.ForfeitResult r = service.forfeit(new TradeDocDtos.ForfeitRequest("LC500",
                new TradeDocDtos.DraftDto("D500", new BigDecimal("1000000"), "承兑行X", today(), "已背书"),
                List.of(), new BigDecimal("3.5"), plusDays(90), "AAA", null), "ff-1");
        assertEquals("COMPLIANT", r.buyResult());
        // 利息 = 100万×3.5%×90/360 = 8750.00；买断价 = 991250.00
        assertEquals(0, new BigDecimal("8750.00").compareTo(r.discountInterest()));
        assertEquals(0, new BigDecimal("991250.00").compareTo(r.buyPrice()));
        assertNotNull(r.commitmentLetter());
    }

    @Test
    void 承兑行评级低于BBB_拒买断() {
        TradeDocDtos.ForfeitResult r = service.forfeit(new TradeDocDtos.ForfeitRequest("LC501",
                new TradeDocDtos.DraftDto("D501", new BigDecimal("1000000"), "承兑行Y", today(), "已背书"),
                List.of(), new BigDecimal("3.5"), plusDays(90), "BB", null), "ff-2");
        assertEquals("REJECT", r.buyResult());
        assertNull(r.commitmentLetter());
    }

    @Test
    void 背书缺失_拒买断() {
        TradeDocDtos.ForfeitResult r = service.forfeit(new TradeDocDtos.ForfeitRequest("LC502",
                new TradeDocDtos.DraftDto("D502", new BigDecimal("1000000"), "承兑行Z", today(), null),
                List.of(), new BigDecimal("3.5"), plusDays(90), "AAA", null), "ff-3");
        assertEquals("REJECT", r.buyResult());
    }

    @Test
    void 制裁命中_抛TRD4104() {
        BaiException e = assertThrows(BaiException.class, () -> service.forfeit(
                new TradeDocDtos.ForfeitRequest("LC-SANCTION-2",
                        new TradeDocDtos.DraftDto("D503", new BigDecimal("1000000"), "承兑行", today(), "已背书"),
                        List.of(), new BigDecimal("3.5"), plusDays(90), "AAA", null), "ff-x"));
        assertEquals("TRD_4104", e.getCode());
    }

    // ------------------------------------------------------------ 贸易融资核验（4.7）

    @Test
    void 打包贷款_核验通过并按七成放款() {
        TradeDocDtos.TradeFinanceResult r = service.tradeFinance(new TradeDocDtos.TradeFinanceRequest("PACKING",
                "LC600", List.of(doc("INVOICE", "I60", Map.of("amount", 1000000))),
                lc(new BigDecimal("1000000"), "钢材", "受益人公司", "申请人公司", plusDays(30), plusDays(60),
                        List.of(), null),
                new TradeDocDtos.TradeBackground("CT600", null, null, null), null), "tf-1");
        assertEquals("VERIFIED", r.verifyResult());
        // 可融 = 100万 × 70% = 70万
        assertEquals(0, new BigDecimal("700000.00").compareTo(r.availableAmount()));
    }

    @Test
    void 欺诈风险命中_抛TRD4103() {
        BaiException e = assertThrows(BaiException.class, () -> service.tradeFinance(
                new TradeDocDtos.TradeFinanceRequest("PACKING", "LC-FRAUD-1",
                        List.of(doc("INVOICE", "I61", Map.of("amount", 1000000))), null,
                        new TradeDocDtos.TradeBackground("CT601", null, null, null), null), "tf-x"));
        assertEquals("TRD_4103", e.getCode());
    }

    @Test
    void 出口押汇缺保险覆盖_核验拒绝() {
        TradeDocDtos.TradeFinanceResult r = service.tradeFinance(new TradeDocDtos.TradeFinanceRequest("EXPORT_BILL",
                "LC601", List.of(doc("INVOICE", "I62", Map.of("amount", 1000000))), null,
                new TradeDocDtos.TradeBackground("CT602", null, null, null), null), "tf-2");
        assertEquals("REJECTED", r.verifyResult());
        assertTrue(r.reviewReport().items().stream()
                .anyMatch(i -> i.item().equals("INSURANCE_COVERED") && i.result().equals("FAIL")));
    }

    @Test
    void 融资类型非法_抛TRD1007() {
        BaiException e = assertThrows(BaiException.class, () -> service.tradeFinance(
                new TradeDocDtos.TradeFinanceRequest("HOUSING", "LC602",
                        List.of(doc("INVOICE", "I63", Map.of("amount", 1000000))), null,
                        new TradeDocDtos.TradeBackground("CT603", null, null, null), null), "tf-y"));
        assertEquals("TRD_1007", e.getCode());
    }
}
