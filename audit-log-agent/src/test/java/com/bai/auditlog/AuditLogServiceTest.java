package com.bai.auditlog;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuditLogService 单元测试：审计写入受理、哈希链防篡改、多维查询/导出、参数校验、幂等去重。
 * <p>确定性仿真：append-only 内存链 + SHA-256 哈希链，可直接断言受理计数与链校验结果。
 */
class AuditLogServiceTest {

    private final AuditLogService service = new AuditLogService();

    /** 构造一个合法审计事件（who/what/when 必填） */
    private Map<String, Object> event(String userId, String action) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("who", Map.of("userId", userId, "role", "OPERATOR", "orgId", "ORG001"));
        e.put("what", Map.of("action", action, "objectType", "LOAN", "objectId", "LN001"));
        e.put("when", "2026-11-10T10:00:00");
        e.put("where", Map.of("channel", "WEB", "ip", "10.1.1.1", "traceId", "T-001"));
        e.put("result", Map.of("code", "0", "message", "ok"));
        e.put("sensitive", false);
        e.put("masked", true);
        return e;
    }

    // ------------------------------------------------------------ 主能力

    @Test
    void 写入合法事件_受理并返回logId() {
        AuditLogDtos.WriteResult r = service.write(List.of(event("u1", "LOGIN")), "req-w1", "caller-1");
        assertEquals(1, r.accepted());
        assertEquals(0, r.rejected());
        assertEquals(1, r.logIds().size());
        assertTrue(r.logIds().get(0).startsWith("LOG"));
        // 事件含敏感标记
        AuditLogDtos.QueryResult q = service.query(Map.of("userId", "u1"));
        assertEquals(1, q.total());
        assertTrue(q.logs().get(0).masked());
    }

    @Test
    void 写入后哈希链校验_valid() {
        service.write(List.of(event("u1", "LOGIN")), "req-c1", "c1");
        service.write(List.of(event("u2", "EXPORT")), "req-c2", "c2");
        AuditLogDtos.TamperCheckResult t = service.tamperCheck();
        assertTrue(t.valid());
        assertNull(t.brokenAt());
        assertEquals(2, t.checked());
    }

    @Test
    void 非法事件_计入rejected() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("what", Map.of("action", "LOGIN"));   // 缺 who/when
        bad.put("when", "2026-11-10T10:00:00");
        AuditLogDtos.WriteResult r = service.write(List.of(bad, event("u1", "LOGIN")), "req-r1", "c1");
        assertEquals(1, r.accepted());
        assertEquals(1, r.rejected());
    }

    @Test
    void 导出_生成CSV且导出本身留痕() {
        service.write(List.of(event("u1", "LOGIN")), "req-e1", "u1");
        AuditLogDtos.ExportResult r = service.export(Map.of("userId", "u1"), "req-e2", "auditor");
        assertEquals(1, r.count());
        assertEquals("CSV", r.format());
        assertTrue(r.csv().contains("LOGIN"));
        assertTrue(r.csv().startsWith("logId,requestId,when"));
        // 导出操作本身留痕（审计的审计）：链仍完整
        AuditLogDtos.QueryResult q = service.query(Map.of("action", "EXPORT"));
        assertEquals(1, q.total());
        assertTrue(service.tamperCheck().valid());
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    void 空事件列表_抛ALG1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.write(List.of(), "req-x1", "c1"));
        assertEquals("ALG_1001", e.getCode());
    }

    @Test
    void 批量超1000条_抛ALG1005() {
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            events.add(event("u" + i, "LOGIN"));
        }
        BaiException e = assertThrows(BaiException.class,
                () -> service.write(events, "req-x2", "c1"));
        assertEquals("ALG_1005", e.getCode());
    }

    @Test
    void 查询页号非法_抛ALG1001() {
        service.write(List.of(event("u1", "LOGIN")), "req-q1", "c1");
        BaiException e = assertThrows(BaiException.class,
                () -> service.query(Map.of("page", 0)));
        assertEquals("ALG_1001", e.getCode());
    }

    @Test
    void 查询跨度超90天_抛ALG1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.query(Map.of("from", "2026-01-01", "to", "2026-06-30")));
        assertEquals("ALG_1001", e.getCode());
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void 同requestId重复写入_返回首次结果() {
        String rid = "idem-1";
        List<Map<String, Object>> events = List.of(event("u1", "LOGIN"));
        AuditLogDtos.WriteResult first = service.write(events, rid, "c1");
        AuditLogDtos.WriteResult second = service.write(events, rid, "c1");
        assertEquals(first.accepted(), second.accepted());
        assertEquals(first.logIds(), second.logIds());
        // 同事件唯一键不重复落库：链中只应有 1 条
        assertEquals(1, service.query(Map.of()).total());
    }

    @Test
    void 同requestId同eventId_复用原logId() {
        Map<String, Object> e1 = event("u1", "LOGIN");
        e1.put("eventId", "EVT-FIXED-001");
        // 同一批次内同 eventId 重复：不重复落库，复用首次 logId
        AuditLogDtos.WriteResult r = service.write(List.of(e1, e1), "req-d1", "c1");
        assertEquals(2, r.accepted());
        assertEquals(r.logIds().get(0), r.logIds().get(1));
        assertEquals(1, service.query(Map.of()).total());
    }
}
