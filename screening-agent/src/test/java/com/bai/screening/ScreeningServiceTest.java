package com.bai.screening;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScreeningService 单元测试：EXACT/FUZZY/PHONETIC 三模匹配、命中结论、参数校验、幂等、名单入库冲突。
 * <p>内置名单含监管关注企业「北京鑫源商贸有限公司」（REGULATORY/MEDIUM，模糊命中示例）与黑名单「刘大成」。
 */
class ScreeningServiceTest {

    private final ScreeningService service = new ScreeningService();

    private Map<String, Object> subject(String name, String idNo, String pinyin) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("idNo", idNo);
        s.put("pinyin", pinyin);
        return s;
    }

    @Test
    void 精确命中_黑名单刘大成() {
        ScreeningDtos.ScreenResult r = service.screen(Map.of(
                "scene", "ADMISSION",
                "subject", subject("刘大成", "110101199001011234", null)), "s1");
        assertEquals("HIT", r.conclusion());
        assertTrue(r.escalated());
        assertEquals(1, r.hits().size());
        ScreeningDtos.ListHit hit = r.hits().get(0);
        assertEquals("BLACK", hit.listType());
        assertEquals("HIGH", hit.listLevel());
        assertEquals("EXACT", hit.matchMode());
        assertEquals(1.0, hit.score());
    }

    @Test
    void 模糊匹配_监管关注企业() {
        // 仅开 FUZZY 模式：名称与名单条目一致 → Jaro-Winkler=1.0 ≥ 0.90 判疑似
        ScreeningDtos.ScreenResult r = service.screen(Map.of(
                "scene", "DUE_DILIGENCE",
                "subject", subject("北京鑫源商贸有限公司", null, null),
                "matchModes", List.of("FUZZY")), "s2");
        assertEquals("HIT", r.conclusion());
        ScreeningDtos.ListHit hit = r.hits().get(0);
        assertEquals("REGULATORY", hit.listType());
        assertEquals("MEDIUM", hit.listLevel());
        assertEquals("FUZZY", hit.matchMode());
        assertEquals(1.0, hit.score());
    }

    @Test
    void 音形匹配_拼音命中() {
        // 仅开 PHONETIC：拼音与名单条目一致 → Jaro-Winkler=1.0 ≥ 0.85
        ScreeningDtos.ScreenResult r = service.screen(Map.of(
                "scene", "AML",
                "subject", subject("完全无关名称", null, "BEIJINGXINYUANSHANGMAO"),
                "matchModes", List.of("PHONETIC")), "s3");
        assertEquals("HIT", r.conclusion());
        assertEquals("PHONETIC", r.hits().get(0).matchMode());
        assertEquals("REGULATORY", r.hits().get(0).listType());
    }

    @Test
    void 无命中_NO_HIT不升级() {
        ScreeningDtos.ScreenResult r = service.screen(Map.of(
                "scene", "ADMISSION",
                "subject", subject("毫无关联的某公司", null, null)), "s4");
        assertEquals("NO_HIT", r.conclusion());
        assertFalse(r.escalated());
        assertEquals(0, r.hits().size());
    }

    @Test
    void 场景缺失_抛SCR_1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.screen(Map.of("subject", subject("张三", null, null)), "e1"));
        assertEquals("SCR_1001", e.getCode());
    }

    @Test
    void 名称与证件均缺_抛SCR_1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.screen(Map.of("scene", "ADMISSION", "subject", subject(null, null, null)), "e2"));
        assertEquals("SCR_1001", e.getCode());
    }

    @Test
    void 名单类型非法_抛SCR_1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.screen(Map.of("scene", "ADMISSION",
                        "subject", subject("张三", null, null),
                        "listTypes", List.of("XXX")), "e3"));
        assertEquals("SCR_1007", e.getCode());
    }

    @Test
    void 同requestId_筛查幂等复用首次结果() {
        String rid = "idem-1";
        Map<String, Object> body = Map.of("scene", "ADMISSION", "subject", subject("刘大成", null, null));
        ScreeningDtos.ScreenResult a = service.screen(body, rid);
        ScreeningDtos.ScreenResult b = service.screen(body, rid);
        assertEquals(a.screenId(), b.screenId());
    }

    @Test
    void 名单入库_同名主体重复抛SCR_1005() {
        ScreeningDtos.ListEntry e = service.registerList(Map.of(
                "listType", "BLACK", "subjectName", "测试主体", "level", "MEDIUM"), "r1");
        assertNotNull(e.listEntryId());
        BaiException dup = assertThrows(BaiException.class,
                () -> service.registerList(Map.of(
                        "listType", "BLACK", "subjectName", "测试主体", "level", "HIGH"), "r2"));
        assertEquals("SCR_1005", dup.getCode());
    }
}
