package com.bai.classify;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClassifyService 单元测试（感知类代表性原子）：关键词规则多标签分类（确定性仿真）、规则增强、合规标记、幂等、参数校验。
 */
class ClassifyServiceTest {

    private final ClassifyService service = new ClassifyService();

    private ClassifyDtos.ClassifyResult classify(String text, String task, Integer topK, String requestId) {
        return service.classify(new ClassifyDtos.ClassifyRequest(text, task, topK, null, null), requestId);
    }

    @Test
    void 投诉分类_态度投诉高置信() {
        ClassifyDtos.ClassifyResult r = classify("服务态度太差，辱骂客户", "complaint_ticket", null, "txc-1");
        assertFalse(r.labels().isEmpty());
        ClassifyDtos.Label top = r.labels().get(0);
        assertEquals("service_complaint", top.level1());
        assertEquals("attitude_complaint", top.level2());
        assertTrue(top.confidence() >= 0.87, "双关键词命中应输出高置信");
        assertNotNull(r.reasons());
        assertTrue(r.reasons().stream().anyMatch(re -> re.type().equals("RULE_KEYWORD")));
    }

    @Test
    void 规则增强_标签含RULE来源() {
        // 同时命中态度与费用两规则：主类别保留 MODEL，次命中规则以 RULE 来源并入
        ClassifyDtos.ClassifyResult r = classify("服务态度太差，辱骂客户，还乱收费", "complaint_ticket", null, "txc-2");
        assertTrue(r.labels().stream().anyMatch(l -> l.source().equals("RULE")),
                "次命中规则应以 RULE 来源并入标签");
        assertTrue(r.labels().stream().anyMatch(l -> l.source().equals("MODEL")),
                "主类别应保留 MODEL 来源");
        assertFalse(r.candidates().isEmpty());
    }

    @Test
    void 合规检测_涉诈标记() {
        ClassifyDtos.ClassifyResult r = classify("有人诱导我参与诈骗活动", "compliance_text", null, "txc-3");
        assertTrue(r.compliance().flagged());
        assertTrue(r.compliance().categories().contains("fraud"));
        assertFalse(r.compliance().evidence().isEmpty());
        assertEquals("compliance", r.labels().get(0).level1());
        assertEquals("fraud", r.labels().get(0).level2());
    }

    @Test
    void 无规则命中_输出OTHER() {
        ClassifyDtos.ClassifyResult r = classify("今天天气不错", "complaint_ticket", null, "txc-4");
        assertEquals("OTHER", r.labels().get(0).level1());
        assertEquals(0.45, r.labels().get(0).confidence());
        assertFalse(r.compliance().flagged());
    }

    @Test
    void 空文本_抛TXC1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> classify("   ", "complaint_ticket", null, "txc-x1"));
        assertEquals(ClassifyErrorCodes.EMPTY_TEXT, e.getCode());
    }

    @Test
    void 任务不存在_抛TXC1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> classify("态度太差", "no-such-task", null, "txc-x2"));
        assertEquals(ClassifyErrorCodes.TASK_NOT_FOUND, e.getCode());
    }

    @Test
    void topK非法_抛TXC1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> classify("态度太差", "complaint_ticket", 0, "txc-x3"));
        assertEquals(ClassifyErrorCodes.PARAM_INVALID, e.getCode());
    }

    @Test
    void 同requestId_幂等复用首次结果() {
        String rid = "txc-idem";
        ClassifyDtos.ClassifyResult a = classify("态度太差", "complaint_ticket", null, rid);
        ClassifyDtos.ClassifyResult b = classify("态度太差", "complaint_ticket", null, rid);
        assertEquals(a.classifyId(), b.classifyId(), "同 requestId 应复用首次结果");
    }

    @Test
    void 批量分类_任务可查询() {
        ClassifyDtos.ClassifyTask t = service.batch(
                new ClassifyDtos.BatchRequest(List.of(
                        new ClassifyDtos.ClassifyRequest("态度太差", "complaint_ticket", null, null, null),
                        new ClassifyDtos.ClassifyRequest("感谢服务", "feedback_text", null, null, null))),
                "txc-b1");
        assertEquals("DONE", t.status());
        assertEquals(2, t.total());
        assertEquals(2, t.results().size());
        ClassifyDtos.ClassifyTask fetched = service.getTask(t.taskId());
        assertEquals(t.taskId(), fetched.taskId());
    }
}
