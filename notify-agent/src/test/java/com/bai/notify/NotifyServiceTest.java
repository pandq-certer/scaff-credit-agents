package com.bai.notify;

import com.bai.common.BaiException;
import com.spatix.scaff.iprov.ProviderResponse;
import com.spatix.scaff.iprov.provider.NotifyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * NotifyService 单元测试：模板渲染、渠道选择优先级、频控（NOTIFY 日 10 条）、退订拦截、
 * 跨机构群发拦截、幂等（5 分钟）。NotifyProvider 用 Mockito mock（P14 通知通道）。
 */
class NotifyServiceTest {

    private NotifyProvider notifyProvider;
    private NotifyService service;

    @BeforeEach
    void setUp() {
        notifyProvider = Mockito.mock(NotifyProvider.class);
        service = new NotifyService(notifyProvider);
        when(notifyProvider.send(any(), anyString())).thenReturn(ProviderResponse.success("req",
                new NotifyProvider.NotifyResult("N1", true,
                        List.of(new NotifyProvider.Receipt("INBOX", "ACCEPTED")))));
    }

    private Map<String, Object> receiver() {
        return Map.of("phone", "13800000000");
    }

    private Map<String, Object> taskParams() {
        return Map.of("customerName", "张三", "taskContent", "逾期催收", "deadline", "2026-11-20");
    }

    // ------------------------------------------------------------ 发送

    @Test
    void 发送_渠道按优先级选择且P1站内信直发() {
        NotifyDtos.SendResult r = service.send("TASK_URGE", receiver(), taskParams(), null, "P1",
                "ntf-1", "ops");
        assertTrue(r.messageId().startsWith("MSG"));
        assertEquals("SENT", r.status());   // INBOX 已发送 + SMS 排队
        assertEquals(2, r.channels().size());
        assertEquals("INBOX", r.channels().get(0).channel());   // 站内信优先级最高
        assertEquals("SENT", r.channels().get(0).status());
        assertEquals("SMS", r.channels().get(1).channel());
        assertEquals("QUEUED", r.channels().get(1).status());
    }

    @Test
    void 模板渲染_占位符缺失_抛NTF1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.send("REPAY_DUE_SMS", receiver(), Map.of("customerName", "张三"), null, "P1",
                        "ntf-2", "ops"));
        assertEquals("NTF_1006", e.getCode());
    }

    @Test
    void 模板不存在_抛NTF1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.send("NO_TEMPLATE", receiver(), Map.of(), null, "P1", "ntf-3", "ops"));
        assertEquals("NTF_1004", e.getCode());
    }

    @Test
    void 渠道交集为空_抛NTF1006() {
        // VERIFY_CODE 仅允许 SMS，指定 EMAIL → 交集为空
        BaiException e = assertThrows(BaiException.class,
                () -> service.send("VERIFY_CODE", receiver(), Map.of("customerName", "张三", "code", "123456"),
                        List.of("EMAIL"), "P1", "ntf-4", "ops"));
        assertEquals("NTF_1006", e.getCode());
    }

    @Test
    void 营销退订_拦截抛NTF1006() {
        service.optOut(receiver(), "MARKETING");
        BaiException e = assertThrows(BaiException.class,
                () -> service.send("MARKETING_PROMO", receiver(), Map.of("customerName", "张三"), null, "P1",
                        "ntf-5", "ops"));
        assertEquals("NTF_1006", e.getCode());
    }

    @Test
    void 频控_NOTIFY日10条_第11次抛NTF1007() {
        for (int i = 1; i <= 10; i++) {
            NotifyDtos.SendResult r = service.send("TASK_URGE", receiver(), taskParams(), null, "P1",
                    "ntf-f" + i, "ops");
            assertEquals("SENT", r.status());
        }
        BaiException e = assertThrows(BaiException.class,
                () -> service.send("TASK_URGE", receiver(), taskParams(), null, "P1", "ntf-f11", "ops"));
        assertEquals("NTF_1007", e.getCode());
    }

    // ------------------------------------------------------------ 群发与幂等

    @Test
    void 跨机构群发_抛NTF1003_批量超1000_抛NTF1001() {
        Map<String, Object> crossOrg = Map.of("list", List.of(
                Map.of("phone", "13800000000", "orgId", "ORG1"),
                Map.of("phone", "13900000000", "orgId", "ORG2")));
        BaiException e1 = assertThrows(BaiException.class,
                () -> service.send("TASK_URGE", crossOrg, taskParams(), null, "P1", "ntf-c1", "ops"));
        assertEquals("NTF_1003", e1.getCode());
        List<Object> many = new java.util.ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            many.add(Map.of("phone", "13800000000", "orgId", "ORG1"));
        }
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.send("TASK_URGE", Map.of("list", many), taskParams(), null, "P1", "ntf-c2", "ops"));
        assertEquals("NTF_1001", e2.getCode());
    }

    @Test
    void 幂等_同requestId同模板同接收人_返回首次messageId() {
        String rid = "ntf-idem";
        NotifyDtos.SendResult a = service.send("TASK_URGE", receiver(), taskParams(), null, "P1", rid, "ops");
        NotifyDtos.SendResult b = service.send("TASK_URGE", receiver(), taskParams(), null, "P1", rid, "ops");
        assertEquals(a.messageId(), b.messageId());
        // 留痕可查
        NotifyDtos.SendRecord rec = service.getRecord(null, rid);
        assertEquals(rid, rec.requestId());
        assertEquals("TASK_URGE", rec.templateCode());
    }

    @Test
    void 优先级非法_抛NTF1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.send("TASK_URGE", receiver(), taskParams(), null, "P9", "ntf-p1", "ops"));
        assertEquals("NTF_1001", e.getCode());
        BaiException e2 = assertThrows(BaiException.class,
                () -> service.send(null, receiver(), taskParams(), null, "P1", "ntf-p2", "ops"));
        assertEquals("NTF_1001", e2.getCode());
    }
}
