package com.bai.textgen;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TextGenService 单元测试：模板渲染、自由/混合生成、红线合规拦截、长度控制、参数校验、幂等。
 * <p>确定性仿真：模板引擎不调 LLM，同入参必得同文本，可直接断言。
 */
class TextGenServiceTest {

    private final TextGenService service = new TextGenService();

    // ------------------------------------------------------------ 主能力

    @Test
    void 模板模式_事实注入渲染成功() {
        TextGenDtos.GenerateResult r = service.generate("TEMPLATE", "TMPL_NOTICE_001", null,
                Map.of("customerName", "张三", "loanNo", "LN20261110001", "date", "2026-11-10", "amount", "100000"),
                "FORMAL", null, "biz-t1", "req-t1");
        assertTrue(r.compliance().passed());
        assertEquals("TEMPLATE", r.mode());
        assertTrue(r.text().startsWith("张三，您申请的贷款 LN20261110001 已于 2026-11-10 放款成功，金额 100000 元"));
        // 默认 maxLength=200，长度控制 ±10% 补齐后落在 [180, 220]
        assertTrue(r.text().length() >= 180 && r.text().length() <= 220);
        assertEquals(r.text().length(), r.length());
    }

    @Test
    void 自由模式_要点编号组装() {
        TextGenDtos.GenerateResult r = service.generate("FREE", null,
                List.of("经营正常", "无逾期"), null, "FORMAL", 100, "biz-t2", "req-t2");
        assertTrue(r.compliance().passed());
        assertTrue(r.text().startsWith("根据以下要点：1)经营正常；2)无逾期"));
        assertTrue(r.text().contains("特此说明"));
        assertTrue(r.text().length() >= 90);   // maxLength=100，下限 90
    }

    @Test
    void 混合模式_模板加要点() {
        TextGenDtos.GenerateResult r = service.generate("MIXED", "TMPL_NOTICE_001",
                List.of("请留意入账信息"), Map.of("customerName", "李四", "loanNo", "LN2", "date", "2026-11-10", "amount", "50"),
                "FORMAL", null, "biz-t3", "req-t3");
        assertTrue(r.compliance().passed());
        assertTrue(r.text().contains("李四"));
        assertTrue(r.text().contains("1)请留意入账信息"));
    }

    // ------------------------------------------------------------ 红线合规

    @Test
    void 命中RED红线_拒绝且不返回文本() {
        TextGenDtos.GenerateResult r = service.generate("FREE", null,
                List.of("保本理财，稳赚不赔"), null, "MARKET", 200, "biz-t4", "req-t4");
        assertFalse(r.compliance().passed());
        assertEquals("", r.text());
        assertEquals(0, r.length());
        assertTrue(r.compliance().riskWords().contains("保本"));
    }

    @Test
    void 模板变量缺失_抛TXG1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate("TEMPLATE", "TMPL_COLLECT_001", null,
                        Map.of("loanNo", "LN1", "days", "5"), "WARN", null, "biz-t5", "req-t5"));
        assertEquals("TXG_1007", e.getCode());
    }

    // ------------------------------------------------------------ 参数校验

    @Test
    void mode非法_抛TXG1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate("LLM", null, List.of("a"), null, "FORMAL", 100, "biz-e1", "req-e1"));
        assertEquals("TXG_1001", e.getCode());
    }

    @Test
    void style非法_抛TXG1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate("FREE", null, List.of("a"), null, "CASUAL", 100, "biz-e2", "req-e2"));
        assertEquals("TXG_1007", e.getCode());
    }

    @Test
    void 模板不存在_抛TXG1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate("TEMPLATE", "TMPL_NOPE", null, Map.of("a", "1"), "FORMAL", 100, "biz-e3", "req-e3"));
        assertEquals("TXG_1004", e.getCode());
    }

    @Test
    void 要点超20条_抛TXG1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate("FREE", null, List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
                        "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21"),
                        null, "FORMAL", 100, "biz-e4", "req-e4"));
        assertEquals("TXG_1006", e.getCode());
    }

    @Test
    void FREE模式要点为空_抛TXG1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.generate("FREE", null, List.of(), null, "FORMAL", 100, "biz-e5", "req-e5"));
        assertEquals("TXG_1001", e.getCode());
    }

    // ------------------------------------------------------------ 幂等/确定性

    @Test
    void 同requestId同bizKey_返回首次genId() {
        String rid = "idem-t1";
        TextGenDtos.GenerateResult a = service.generate("FREE", null, List.of("经营正常"),
                null, "FORMAL", 100, "biz-idem", rid);
        TextGenDtos.GenerateResult b = service.generate("FREE", null, List.of("经营正常"),
                null, "FORMAL", 100, "biz-idem", rid);
        assertEquals(a.genId(), b.genId());
        assertEquals(a.text(), b.text());
    }

    @Test
    void 同入参两次调用_文本完全一致() {
        TextGenDtos.GenerateResult a = service.generate("FREE", null, List.of("经营正常", "无逾期"),
                null, "FRIENDLY", 120, "biz-det", "req-d1");
        TextGenDtos.GenerateResult b = service.generate("FREE", null, List.of("经营正常", "无逾期"),
                null, "FRIENDLY", 120, "biz-det", "req-d2");
        assertEquals(a.text(), b.text());
    }

    @Test
    void 模板注册后可用() {
        service.registerTemplate("TMPL_TEST_001", "测试模板", "TEST", "通知：{{name}} 您好。");
        TextGenDtos.GenerateResult r = service.generate("TEMPLATE", "TMPL_TEST_001", null,
                Map.of("name", "王五"), "FORMAL", 100, "biz-t6", "req-t6");
        assertTrue(r.text().startsWith("通知：王五 您好。"));
    }
}
