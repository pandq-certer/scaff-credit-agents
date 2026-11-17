package com.bai.uiops;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UiOpsService 单元测试：元素定位（三级策略）、页面操作（白名单/表单校验/P0 双人确认）、
 * 交易同步（txnRef 防重/一致性）、回滚（幂等防重）、参数校验。
 * <p>确定性仿真：哈希驱动定位失败分支（sessionId+element 组合已避开命中），可直接断言结果。
 */
class UiOpsServiceTest {

    private final UiOpsService service = new UiOpsService();

    /** 有效会话号（≥8 位且 UIO_ 前缀，且与测试元素组合不触发哈希定位失败） */
    private static final String SID = "UIO_20261110";

    private Map<String, Object> locateBody(String element, String strategy) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("sessionId", SID);
        b.put("element", element);
        if (strategy != null) {
            b.put("strategy", strategy);
        }
        return b;
    }

    private Map<String, Object> opBody(String element, String action, Map<String, Object> value) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("sessionId", SID);
        b.put("element", element);
        b.put("action", action);
        if (value != null) {
            b.put("value", value);
        }
        return b;
    }

    // ------------------------------------------------------------ 元素定位

    @Test
    void 定位_AUTO命中ID定位器() {
        UiOpsDtos.LocateResult r = service.locate(locateBody("loanNo", "AUTO"), "req-l1");
        assertEquals("ID", r.strategy());
        assertEquals(0.99, r.confidence());
        assertTrue(r.located());
        assertNull(r.reason());
    }

    @Test
    void 定位_视觉兜底置信度不足() {
        UiOpsDtos.LocateResult r = service.locate(locateBody("loanNo", "VISUAL"), "req-l2");
        assertEquals("VISUAL", r.strategy());
        assertEquals(0.88, r.confidence());
        assertFalse(r.located());
        assertNotNull(r.reason());
    }

    @Test
    void 定位_策略非法抛UIO1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.locate(locateBody("loanNo", "CSS"), "req-e1"));
        assertEquals("UIO_1001", e.getCode());
    }

    @Test
    void 定位_元素未注册抛UIO1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.locate(locateBody("hackBtn", "AUTO"), "req-e2"));
        assertEquals("UIO_1004", e.getCode());
    }

    @Test
    void 定位_会话号无效抛UIO1004() {
        Map<String, Object> b = locateBody("loanNo", "AUTO");
        b.put("sessionId", "SHORT");
        BaiException e = assertThrows(BaiException.class, () -> service.locate(b, "req-e3"));
        assertEquals("UIO_1004", e.getCode());
    }

    // ------------------------------------------------------------ 页面操作

    @Test
    void 操作_表单填充成功() {
        UiOpsDtos.OpResult r = service.operate(opBody("loanNo", "FILL", Map.of("value", "LN001")), "req-o1");
        assertEquals("SUCCESS", r.result());
        assertNull(r.reason());
        assertNotNull(r.opId());
        assertNotNull(r.pageSnapshot().hash());
    }

    @Test
    void 操作_金额非法表单校验未过() {
        UiOpsDtos.OpResult r = service.operate(opBody("repayAmount", "FILL", Map.of("value", "-500")), "req-o2");
        assertEquals("VALIDATION_FAILED", r.result());
        assertNotNull(r.reason());
    }

    @Test
    void 操作_P0资金类未双人确认_BLOCKED() {
        UiOpsDtos.OpResult r = service.operate(opBody("submitBtn", "CLICK", null), "req-o3");
        assertEquals("BLOCKED", r.result());
        assertNotNull(r.reason());
        // 携带 confirm=true 则放行
        UiOpsDtos.OpResult ok = service.operate(opBody("submitBtn", "CLICK", Map.of("confirm", true)), "req-o4");
        assertEquals("SUCCESS", ok.result());
    }

    @Test
    void 操作_action非法抛UIO1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.operate(opBody("loanNo", "DRAG", Map.of("value", "1")), "req-e4"));
        assertEquals("UIO_1001", e.getCode());
    }

    @Test
    void 操作_FILL缺value抛UIO1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.operate(opBody("loanNo", "FILL", null), "req-e5"));
        assertEquals("UIO_1001", e.getCode());
    }

    // ------------------------------------------------------------ 交易同步

    @Test
    void 同步_一致性MATCHED() {
        UiOpsDtos.SyncResult r = service.sync(
                Map.of("txnRef", "TXN-001", "formData", Map.of("loanNo", "LN001", "amount", 100)), "req-s1");
        assertEquals("MATCHED", r.consistency());
        assertEquals("SYNCED", r.status());
        assertNotNull(r.backendTxnNo());
        assertTrue(r.backendTxnNo().startsWith("BK"));
    }

    @Test
    void 同步_同一txnRef重复提交抛UIO1005() {
        Map<String, Object> body = Map.of("txnRef", "TXN-002", "formData", Map.of("loanNo", "LN1"));
        service.sync(body, "req-s2");
        BaiException e = assertThrows(BaiException.class, () -> service.sync(body, "req-s3"));
        assertEquals("UIO_1005", e.getCode());
    }

    @Test
    void 同步_一致性差异抛UIO1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.sync(Map.of("txnRef", "TXN-003", "formData", Map.of("forceDiff", true)), "req-s4"));
        assertEquals("UIO_1007", e.getCode());
    }

    @Test
    void 同步_P0确认缺失抛UIO1003() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.sync(Map.of("txnRef", "TXN-004", "confirmRequired", true,
                        "formData", Map.of("loanNo", "LN1")), "req-s5"));
        assertEquals("UIO_1003", e.getCode());
    }

    // ------------------------------------------------------------ 回滚

    @Test
    void 回滚_业务逆向补偿成功() {
        service.sync(Map.of("txnRef", "TXN-010", "formData", Map.of("loanNo", "LN1")), "req-r1");
        UiOpsDtos.RollbackResult r = service.rollback(
                Map.of("rollbackType", "BUSINESS", "txnRef", "TXN-010"), "req-r2", "Bearer admin");
        assertEquals("ROLLED_BACK", r.status());
        assertEquals("BUSINESS", r.rollbackType());
        // 幂等防重复回滚
        BaiException e = assertThrows(BaiException.class,
                () -> service.rollback(Map.of("rollbackType", "BUSINESS", "txnRef", "TXN-010"), "req-r3", "Bearer admin"));
        assertEquals("UIO_1005", e.getCode());
    }

    @Test
    void 回滚_记录不存在抛UIO1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.rollback(Map.of("rollbackType", "DATA", "txnRef", "TXN-GHOST"), "req-r4", "Bearer admin"));
        assertEquals("UIO_1004", e.getCode());
    }

    @Test
    void 回滚_缺授权抛UIO1003() {
        service.sync(Map.of("txnRef", "TXN-011", "formData", Map.of("loanNo", "LN1")), "req-r5");
        BaiException e = assertThrows(BaiException.class,
                () -> service.rollback(Map.of("rollbackType", "DATA", "txnRef", "TXN-011"), "req-r6", null));
        assertEquals("UIO_1003", e.getCode());
    }

    @Test
    void 回滚_类型非法抛UIO1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.rollback(Map.of("rollbackType", "NUKED", "txnRef", "TXN-1"), "req-r7", "Bearer x"));
        assertEquals("UIO_1001", e.getCode());
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void 同requestId同操作_返回首次opId() {
        String rid = "idem-u1";
        Map<String, Object> body = opBody("loanNo", "FILL", Map.of("value", "LN001"));
        UiOpsDtos.OpResult a = service.operate(body, rid);
        UiOpsDtos.OpResult b = service.operate(body, rid);
        assertEquals(a.opId(), b.opId());
    }
}
