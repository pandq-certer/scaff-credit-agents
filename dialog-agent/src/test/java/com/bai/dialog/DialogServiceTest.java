package com.bai.dialog;

import com.bai.common.BaiException;
import com.spatix.scaff.iagent.sse.SessionEventPublisher;
import com.spatix.scaff.iagent.store.SessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DialogService 单元测试：会话创建/恢复/结束、意图识别与槽位填充、策略决策（CLARIFY/CONFIRM/ANSWER/HANDOFF）、
 * 敏感拦截、越权与状态校验、幂等。
 * <p>SessionStore/SessionEventPublisher 用 Mockito mock（scaff-iagent 会话管理契约）。
 */
class DialogServiceTest {

    private SessionStore sessionStore;
    private SessionEventPublisher eventPublisher;
    private DialogService service;

    @BeforeEach
    void setUp() {
        sessionStore = Mockito.mock(SessionStore.class);
        eventPublisher = Mockito.mock(SessionEventPublisher.class);
        service = new DialogService(sessionStore, eventPublisher, "MOCK");
    }

    private DialogDtos.SessionInfo createSession(String userId) {
        return service.manageSession("CREATE", null, userId, "TEXT", null, "req-s0");
    }

    // ------------------------------------------------------------ 会话管理

    @Test
    void 创建会话_返回ACTIVE() {
        DialogDtos.SessionInfo s = createSession("u1");
        assertTrue(s.sessionId().startsWith("SES"));
        assertEquals("ACTIVE", s.status());
        assertEquals("u1", s.userId());
        assertEquals("TEXT", s.channel());
        assertEquals(1, service.listSessions("u1").size());
        // 会话已注册到 SessionStore 契约
        Mockito.verify(sessionStore).save(Mockito.any());
    }

    @Test
    void 结束会话_状态ENDED且历史可查() {
        DialogDtos.SessionInfo s = createSession("u1");
        DialogDtos.SessionInfo ended = service.manageSession("END", s.sessionId(), "u1", null, null, "req-e1");
        assertEquals("ENDED", ended.status());
        DialogDtos.DialogHistory h = service.getHistory(s.sessionId(), "u1");
        assertEquals("ENDED", h.status());
        // 已结束会话不可再发消息
        BaiException e = assertThrows(BaiException.class,
                () -> service.handleMessage(s.sessionId(), "还款计划", "TEXT", "u1", "req-e2"));
        assertEquals("DLG_1004", e.getCode());
    }

    @Test
    void 重复创建已存在会话_抛DLG1005() {
        DialogDtos.SessionInfo s = createSession("u1");
        BaiException e = assertThrows(BaiException.class,
                () -> service.manageSession("CREATE", s.sessionId(), "u1", "TEXT", null, "req-e3"));
        assertEquals("DLG_1005", e.getCode());
    }

    @Test
    void 非法action_抛DLG1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.manageSession("PAUSE", null, "u1", "TEXT", null, "req-e4"));
        assertEquals("DLG_1001", e.getCode());
    }

    @Test
    void 场景非白名单_抛DLG1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.manageSession("CREATE", null, "u1", "TEXT", "HACK_SCENE", "req-e5"));
        assertEquals("DLG_1007", e.getCode());
    }

    // ------------------------------------------------------------ 对话流程

    @Test
    void 还款查询三轮回话_CLARIFY至CONFIRM再ANSWER() {
        DialogDtos.SessionInfo s = createSession("u1");
        String sid = s.sessionId();
        // 第一轮：缺贷款编号 → CLARIFY
        DialogDtos.DialogReply r1 = service.handleMessage(sid, "我想查一下还款计划", "TEXT", "u1", "req-m1");
        assertEquals("repay_query", r1.intent());
        assertEquals("CLARIFY", r1.action().type());
        assertTrue(r1.reply().contains("贷款编号"));
        // 第二轮：提供贷款编号 → CONFIRM 关键信息
        DialogDtos.DialogReply r2 = service.handleMessage(sid, "还款计划查询，贷款编号 LN20261110001", "TEXT", "u1", "req-m2");
        assertEquals("repay_query", r2.intent());
        assertEquals("CONFIRM", r2.action().type());
        assertEquals("LN20261110001", r2.slots().get("loanNo"));
        // 第三轮：确认 → 上轮 CONFIRM + 确认词 → ANSWER（intent 被"确认"消息覆盖为 general_qa，走默认业务答复）
        DialogDtos.DialogReply r3 = service.handleMessage(sid, "确认", "TEXT", "u1", "req-m3");
        assertEquals("ANSWER", r3.action().type());
        assertEquals("您好，请问有什么可以帮您？", r3.reply());
        assertFalse(r3.handoff().required());
        // 记忆更新：最近 20 轮滑动窗口（3 轮 → 6 条）
        DialogDtos.DialogHistory h = service.getHistory(sid, "u1");
        assertEquals(6, h.messages().size());
    }

    @Test
    void 用户主动要求转人工_HANDOFF() {
        DialogDtos.SessionInfo s = createSession("u1");
        DialogDtos.DialogReply r = service.handleMessage(s.sessionId(), "我要转人工", "TEXT", "u1", "req-h1");
        assertEquals("HANDOFF", r.action().type());
        assertTrue(r.handoff().required());
        assertTrue(r.reply().contains("转接人工"));
    }

    @Test
    void 敏感话题_抛DLG1007并发布事件() {
        DialogDtos.SessionInfo s = createSession("u1");
        BaiException e = assertThrows(BaiException.class,
                () -> service.handleMessage(s.sessionId(), "保证收益的理财产品", "TEXT", "u1", "req-s1"));
        assertEquals("DLG_1007", e.getCode());
        assertTrue(e.getMessage().contains("保证收益"));
    }

    // ------------------------------------------------------------ 参数校验/越权

    @Test
    void 消息超2000字_抛DLG1006() {
        DialogDtos.SessionInfo s = createSession("u1");
        String longMsg = "a".repeat(2001);
        BaiException e = assertThrows(BaiException.class,
                () -> service.handleMessage(s.sessionId(), longMsg, "TEXT", "u1", "req-l1"));
        assertEquals("DLG_1006", e.getCode());
    }

    @Test
    void 越权访问他人会话_抛DLG1003() {
        DialogDtos.SessionInfo s = createSession("u1");
        BaiException e = assertThrows(BaiException.class,
                () -> service.handleMessage(s.sessionId(), "还款计划", "TEXT", "u2", "req-p1"));
        assertEquals("DLG_1003", e.getCode());
    }

    @Test
    void 会话不存在_抛DLG1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.handleMessage("SES-GHOST", "还款计划", "TEXT", "u1", "req-g1"));
        assertEquals("DLG_1004", e.getCode());
    }

    @Test
    void 渠道非法_抛DLG1007() {
        DialogDtos.SessionInfo s = createSession("u1");
        BaiException e = assertThrows(BaiException.class,
                () -> service.handleMessage(s.sessionId(), "还款计划", "WECHAT", "u1", "req-c1"));
        assertEquals("DLG_1007", e.getCode());
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void 同requestId同消息_返回首次回复() {
        DialogDtos.SessionInfo s = createSession("u1");
        String rid = "idem-d1";
        DialogDtos.DialogReply a = service.handleMessage(s.sessionId(), "我想查一下还款计划", "TEXT", "u1", rid);
        DialogDtos.DialogReply b = service.handleMessage(s.sessionId(), "我想查一下还款计划", "TEXT", "u1", rid);
        assertEquals(a.reply(), b.reply());
        assertEquals(a.action().type(), b.action().type());
    }

    // ------------------------------------------------------------ LLM 降级

    @Test
    void llm不可用_开放问题转人工() {
        DialogService degraded = new DialogService(sessionStore, eventPublisher, "UNAVAILABLE");
        DialogDtos.SessionInfo s = degraded.manageSession("CREATE", null, "u1", "TEXT", null, "req-d0");
        DialogDtos.DialogReply r = degraded.handleMessage(s.sessionId(), "你好", "TEXT", "u1", "req-d1");
        assertEquals("ANSWER", r.action().type());
        assertTrue(r.handoff().required());
        assertTrue(r.reply().contains("人工"));
        assertEquals("TEMPLATE_FALLBACK", r.action().detail().get("source"));
    }
}
