package com.bai.aml;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AmlService 单元测试：四类可疑规则（大额/频繁/拆分/快进快出）、可疑评分与 SAR 报告、参数校验、幂等、冲突。
 * <p>阈值：现金单笔 5 万/转账单笔 20 万/窗口累计 100 万；评分≥75 可疑建议上报。
 */
class AmlServiceTest {

    private final AmlService service = new AmlService();

    private Map<String, Object> txn(double amount, String counterparty, String direction, String purpose) {
        Map<String, Object> t = new java.util.LinkedHashMap<>();
        t.put("amount", amount);
        t.put("counterparty", counterparty);
        t.put("direction", direction);
        t.put("purpose", purpose);
        return t;
    }

    @Test
    void 多笔大额转账_触发LARGE并生成SAR() {
        AmlDtos.MonitorResult r = service.monitor(Map.of(
                "window", "T1D", "scene", "TRADING",
                "transactions", List.of(txn(300000, "A公司", "OUT", "货款"),
                        txn(300000, "B公司", "OUT", "货款"),
                        txn(300000, "C公司", "OUT", "货款"))), "m1");
        assertEquals(3, r.triggers().stream().filter(t -> "R_LARGE_001".equals(t.ruleId())).count());
        assertEquals(900000.0, r.totalAmount());
        assertEquals(3, r.txnCount());
        // 3 笔大额(75 分) + 多对手加权(5) + 扰动 ≥ 80 → 恒可疑
        assertTrue(r.suspicious());
        assertTrue(r.reportRequired());
        AmlDtos.ReportResult rep = service.report(Map.of("monitorId", r.monitorId(), "reportType", "SUSPICIOUS"));
        assertEquals("DRAFT_PENDING_REVIEW", rep.status());
        assertEquals("SUSPICIOUS", rep.reportType());
    }

    @Test
    void 现金大额_5万阈值触发LARGE() {
        AmlDtos.MonitorResult r = service.monitor(Map.of(
                "window", "T1D",
                "transactions", List.of(txn(60000, "现金客户", "IN", "现金存入"))), "m2");
        assertTrue(r.triggers().stream().anyMatch(t -> "R_LARGE_001".equals(t.ruleId())
                && "现金单笔≥5万".equals(t.threshold())
                && t.actual().contains("单笔 60000.00 元")));
    }

    @Test
    void 快进快出_金额比不低于80触发FLASH() {
        AmlDtos.MonitorResult r = service.monitor(Map.of(
                "window", "T1D",
                "transactions", List.of(txn(100000, "X公司", "IN", "结算"),
                        txn(100000, "X公司", "OUT", "结算"))), "m3");
        assertTrue(r.triggers().stream().anyMatch(t -> "R_FLASH_002".equals(t.ruleId())));
    }

    @Test
    void 拆分交易_3笔接近大额阈值触发SPLIT() {
        AmlDtos.MonitorResult r = service.monitor(Map.of(
                "window", "T1D",
                "transactions", List.of(txn(190000, "D公司", "OUT", "货款"),
                        txn(190000, "E公司", "OUT", "货款"),
                        txn(190000, "F公司", "OUT", "货款"))), "m4");
        assertTrue(r.triggers().stream().anyMatch(t -> "R_SPLIT_007".equals(t.ruleId())));
    }

    @Test
    void window缺失或非法_抛AML_1001与AML_1007() {
        BaiException missing = assertThrows(BaiException.class,
                () -> service.monitor(Map.of("transactions", List.of(txn(1000, "A", "OUT", null))), "e1"));
        assertEquals("AML_1001", missing.getCode());
        BaiException illegal = assertThrows(BaiException.class,
                () -> service.monitor(Map.of("window", "T90D",
                        "transactions", List.of(txn(1000, "A", "OUT", null))), "e2"));
        assertEquals("AML_1007", illegal.getCode());
    }

    @Test
    void 交易金额非正_抛AML_1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.monitor(Map.of("window", "T1D",
                        "transactions", List.of(txn(0, "A", "OUT", null))), "e3"));
        assertEquals("AML_1001", e.getCode());
    }

    @Test
    void 同requestId_监测幂等复用首次结果() {
        String rid = "idem-1";
        Map<String, Object> body = Map.of("window", "T1D",
                "transactions", List.of(txn(100000, "A", "IN", "结算"), txn(100000, "A", "OUT", "结算")));
        AmlDtos.MonitorResult a = service.monitor(body, rid);
        AmlDtos.MonitorResult b = service.monitor(body, rid);
        assertEquals(a.monitorId(), b.monitorId());
        assertEquals(a.modelScore(), b.modelScore());
    }

    @Test
    void 非可疑记录_生成SAR抛AML_1005() {
        AmlDtos.MonitorResult r = service.monitor(Map.of(
                "window", "T1D",
                "transactions", List.of(txn(1000, "A", "OUT", null))), "m5");
        assertFalse(r.suspicious());
        BaiException e = assertThrows(BaiException.class,
                () -> service.report(Map.of("monitorId", r.monitorId(), "reportType", "SUSPICIOUS")));
        assertEquals("AML_1005", e.getCode());
    }

    @Test
    void 人工结论_重复回流抛AML_1005() {
        AmlDtos.MonitorResult r = service.monitor(Map.of(
                "window", "T1D",
                "transactions", List.of(txn(100000, "A", "IN", "结算"), txn(100000, "A", "OUT", "结算"))), "m6");
        AmlDtos.FeedbackResult f = service.feedback(Map.of("monitorId", r.monitorId(), "conclusion", "NORMAL"));
        assertEquals("ACCEPTED", f.status());
        BaiException e = assertThrows(BaiException.class,
                () -> service.feedback(Map.of("monitorId", r.monitorId(), "conclusion", "SUSPICIOUS")));
        assertEquals("AML_1005", e.getCode());
    }
}
