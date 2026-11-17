package com.bai.ner;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NerService 单元测试（感知类代表性原子）：规则/词典实体抽取（确定性仿真）、敏感实体脱敏、数字归一、类型过滤、幂等。
 */
class NerServiceTest {

    private final NerService service = new NerService();

    private static final String TEXT =
            "借款人张三向我行申请经营贷50万元，手机号13812345678，借款日期2026年11月1日，年利率4.5%，期限36个月。";

    @Test
    void 实体抽取_手机号脱敏金额归一() {
        NerDtos.NerResult r = service.extract(new NerDtos.ExtractRequest(TEXT, null, null, null, null), "ner-1");
        assertTrue(r.entities().stream().anyMatch(e -> e.type().equals("PERSON") && e.text().equals("张三")));
        NerDtos.Entity phone = r.entities().stream().filter(e -> e.type().equals("PHONE")).findFirst().orElse(null);
        assertNotNull(phone);
        assertTrue(phone.text().contains("*"), "敏感实体应脱敏");
        assertEquals("RULE", phone.source());
        NerDtos.Entity amount = r.entities().stream().filter(e -> e.type().equals("AMOUNT")).findFirst().orElse(null);
        assertNotNull(amount);
        assertEquals(500000.0, amount.normalized().value(), "50万元应归一到元（三元表达式提升为 double）");
        assertEquals("yuan", amount.normalized().unit());
        assertTrue(amount.normalized().normalizable());
    }

    @Test
    void 日期与利率归一() {
        NerDtos.NerResult r = service.extract(new NerDtos.ExtractRequest(TEXT, null, null, null, null), "ner-2");
        NerDtos.Entity date = r.entities().stream().filter(e -> e.type().equals("DATE")).findFirst().orElse(null);
        assertNotNull(date);
        assertEquals("2026-11-01", date.normalized().value());
        NerDtos.Entity rate = r.entities().stream().filter(e -> e.type().equals("RATE")).findFirst().orElse(null);
        assertNotNull(rate);
        assertEquals(0.045, rate.normalized().value());
    }

    @Test
    void 类型过滤_仅返回指定类型() {
        NerDtos.NerResult r = service.extract(
                new NerDtos.ExtractRequest(TEXT, null, List.of("AMOUNT"), null, null), "ner-3");
        assertFalse(r.entities().isEmpty());
        assertTrue(r.entities().stream().allMatch(e -> e.type().equals("AMOUNT")));
    }

    @Test
    void 敏感实体_识别即脱敏() {
        NerDtos.NerResult r = service.extract(new NerDtos.ExtractRequest(TEXT, null, null, null, null), "ner-4");
        NerDtos.Entity phone = r.entities().stream().filter(e -> e.type().equals("PHONE")).findFirst().orElse(null);
        assertNotNull(phone);
        assertEquals("138****5678", phone.text(), "手机号应保留前3后4打码");
        assertTrue(phone.sensitive());
    }

    @Test
    void 空文本_抛NER1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.extract(new NerDtos.ExtractRequest("   ", null, null, null, null), "ner-x1"));
        assertEquals(NerErrorCodes.EMPTY_TEXT, e.getCode());
    }

    @Test
    void 文本超长_抛NER1005() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.extract(new NerDtos.ExtractRequest("a".repeat(20001), null, null, null, null), "ner-x2"));
        assertEquals(NerErrorCodes.TEXT_TOO_LONG, e.getCode());
    }

    @Test
    void 类型不存在_抛NER1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.extract(new NerDtos.ExtractRequest(TEXT, null, List.of("BAD_TYPE"), null, null), "ner-x3"));
        assertEquals(NerErrorCodes.NOT_FOUND, e.getCode());
    }

    @Test
    void 同requestId_幂等复用首次结果() {
        String rid = "ner-idem";
        NerDtos.NerResult a = service.extract(new NerDtos.ExtractRequest(TEXT, null, null, null, null), rid);
        NerDtos.NerResult b = service.extract(new NerDtos.ExtractRequest(TEXT, null, null, null, null), rid);
        assertEquals(a.nerId(), b.nerId(), "同 requestId 应复用首次结果");
    }

    @Test
    void 批量抽取_任务可查询() {
        NerDtos.BatchTask t = service.batch(
                new NerDtos.BatchRequest(List.of(
                        new NerDtos.ExtractRequest(TEXT, null, null, null, null),
                        new NerDtos.ExtractRequest(TEXT, null, null, null, null))),
                "ner-b1");
        assertEquals("DONE", t.status());
        assertEquals(2, t.total());
        assertEquals(2, t.results().size());
        NerDtos.BatchTask fetched = service.getTask(t.taskId());
        assertEquals(t.taskId(), fetched.taskId());
    }

    @Test
    void 任务不存在_抛NER1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.getTask("NO-TASK"));
        assertEquals(NerErrorCodes.NOT_FOUND, e.getCode());
    }
}
