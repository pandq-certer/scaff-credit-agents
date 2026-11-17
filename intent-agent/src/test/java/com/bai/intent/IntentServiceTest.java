package com.bai.intent;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IntentService 单元测试（感知类代表性原子）：关键词规则意图分类（确定性仿真）、槽位抽取、OOD 拒识、幂等、参数校验。
 */
class IntentServiceTest {

    private final IntentService service = new IntentService();

    private IntentDtos.IntentResult classify(String text, Integer topK, String requestId) {
        return service.classify(new IntentDtos.ClassifyRequest(text, null, null, null, null, topK), requestId);
    }

    @Test
    void 意图识别_承诺还款高置信() {
        IntentDtos.IntentResult r = classify("我一定还，下周还", null, "int-1");
        assertEquals("collection_response", r.intent().level1());
        assertEquals("promise_pay", r.intent().level2());
        assertTrue(r.intent().confidence() >= 0.86 && r.intent().confidence() <= 0.98,
                "双关键词命中应输出高置信");
        assertFalse(r.ood().flagged());
    }

    @Test
    void 槽位抽取_金额期限合同号() {
        IntentDtos.IntentResult r = classify("我想申请经营贷20万元36个月贷款编号HT2026000123", null, "int-2");
        assertEquals("loan_apply", r.intent().level1());
        assertNotNull(r.slots());
        assertTrue(r.slots().stream().anyMatch(s -> s.name().equals("product") && s.value().equals("经营贷")));
        assertTrue(r.slots().stream().anyMatch(s -> s.name().equals("amount") && s.value().equals("20万")),
                "P_AMOUNT 口径为 \\d+[万元] 单字符单位");
        assertTrue(r.slots().stream().anyMatch(s -> s.name().equals("term") && s.value().equals("36个月")));
        assertTrue(r.slots().stream().anyMatch(s -> s.name().equals("loan_no") && s.value().equals("HT2026000123")),
                "合同号槽位仅取 [A-Za-z0-9] 连续段");
    }

    @Test
    void OOD拒识_短句澄清LOW() {
        IntentDtos.IntentResult r = classify("今天天气不错", null, "int-3");
        assertTrue(r.ood().flagged());
        assertEquals("LOW", r.ood().level());
        assertEquals("other", r.intent().level1());
    }

    @Test
    void 空文本_抛INT1006() {
        BaiException e = assertThrows(BaiException.class, () -> classify("   ", null, "int-x1"));
        assertEquals(IntentErrorCodes.EMPTY_TEXT, e.getCode());
    }

    @Test
    void 文本超长_抛INT1005() {
        BaiException e = assertThrows(BaiException.class,
                () -> classify("a".repeat(501), null, "int-x2"));
        assertEquals(IntentErrorCodes.TEXT_TOO_LONG, e.getCode());
    }

    @Test
    void topK非法_抛INT1001() {
        BaiException e = assertThrows(BaiException.class, () -> classify("一定还", 0, "int-x3"));
        assertEquals(IntentErrorCodes.PARAM_INVALID, e.getCode());
    }

    @Test
    void 同requestId_幂等复用首次结果() {
        String rid = "int-idem";
        IntentDtos.IntentResult a = classify("我一定还", null, rid);
        IntentDtos.IntentResult b = classify("我一定还", null, rid);
        assertEquals(a.intentId(), b.intentId(), "同 requestId 应复用首次结果");
    }

    @Test
    void 批量受理_返回ACCEPTED() {
        IntentDtos.BatchResult r = service.batch(
                new IntentDtos.BatchRequest(List.of(
                        new IntentDtos.ClassifyRequest("我一定还", null, null, null, null, null),
                        new IntentDtos.ClassifyRequest("我想申请经营贷", null, null, null, null, null))),
                "int-b1");
        assertEquals("ACCEPTED", r.status());
        assertEquals(2, r.total());
    }
}
