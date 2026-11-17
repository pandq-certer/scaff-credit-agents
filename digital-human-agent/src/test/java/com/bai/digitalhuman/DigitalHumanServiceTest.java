package com.bai.digitalhuman;

import com.bai.common.BaiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DigitalHumanService 单元测试：渲染驱动（DIALOG/TTS/EMOTION 指令组装）、幂等（5min 指纹）、
 * 错误码（形象缺失/驱动非法/内容超长/合规拦截/状态冲突）、展示状态机、形象管理（注册/发布/资产审核/重名）。
 */
class DigitalHumanServiceTest {

    private DigitalHumanService service;

    @BeforeEach
    void setUp() {
        service = new DigitalHumanService();
        service.manageAvatar("CREATE", null, Map.of("name", "大堂数字员工", "style", "商务",
                "scenario", "LOBBY"), "req-a0");
        service.manageAvatar("PUBLISH", avatarIdOf("大堂数字员工"), null, "req-a1");
    }

    private String avatarIdOf(String name) {
        DigitalHumanDtos.AvatarList list = service.listAvatars(name, null, 1);
        assertEquals(1, list.total());
        return list.avatars().get(0).avatarId();
    }

    // ------------------------------------------------------------ 渲染驱动

    @Test
    void DIALOG渲染_返回SPEECH指令序列() {
        DigitalHumanDtos.RenderResult r = service.render(avatarIdOf("大堂数字员工"), "DIALOG",
                Map.of("text", "您好，欢迎光临，请问需要办理什么业务？"), "SES-DEMO-001", "req-r1");
        assertTrue(r.renderId().startsWith("DH"));
        assertEquals("DIALOG", r.driverType());
        assertEquals("READY", r.status());
        assertTrue(r.commands().stream().anyMatch(c -> "SPEECH".equals(c.type())));
        assertTrue(r.commands().stream().anyMatch(c -> "LIP_SYNC".equals(c.type())));
        assertTrue(r.commands().stream().anyMatch(c -> "EXPRESSION".equals(c.type())));
        assertTrue(r.elapsedMs() >= 0);
    }

    @Test
    void TTS渲染_生成口型时间线() {
        DigitalHumanDtos.RenderResult r = service.render(avatarIdOf("大堂数字员工"), "TTS",
                Map.of("text", "您的贷款申请已受理", "audio", "https://oss.example.com/tts/audio1.wav"),
                null, "req-r2");
        DigitalHumanDtos.RenderCommand lipSync = r.commands().stream()
                .filter(c -> "LIP_SYNC".equals(c.type())).findFirst().orElseThrow();
        assertTrue(((List<?>) lipSync.attrs().get("timeline")).size() > 0);
        DigitalHumanDtos.RenderCommand speech = r.commands().stream()
                .filter(c -> "SPEECH".equals(c.type())).findFirst().orElseThrow();
        assertEquals("https://oss.example.com/tts/audio1.wav", speech.attrs().get("audio"));
    }

    @Test
    void EMOTION渲染_表情映射FRIENDLY微笑点头() {
        DigitalHumanDtos.RenderResult r = service.render(avatarIdOf("大堂数字员工"), "EMOTION",
                Map.of("emotion", "FRIENDLY"), null, "req-r3");
        DigitalHumanDtos.RenderCommand expr = r.commands().stream()
                .filter(c -> "EXPRESSION".equals(c.type())).findFirst().orElseThrow();
        assertEquals("smile", expr.attrs().get("expression"));
        assertEquals("nod", expr.attrs().get("action"));
    }

    @Test
    void 渲染幂等_同requestId同内容返回首次结果() {
        String rid = "req-idem";
        Map<String, Object> content = Map.of("text", "您好，欢迎光临");
        DigitalHumanDtos.RenderResult a = service.render(avatarIdOf("大堂数字员工"), "TEXT", content, null, rid);
        DigitalHumanDtos.RenderResult b = service.render(avatarIdOf("大堂数字员工"), "TEXT", content, null, rid);
        assertEquals(a.renderId(), b.renderId());
        assertEquals(a.commands().size(), b.commands().size());
    }

    @Test
    void 渲染_形象不存在抛DHM1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.render("AV-GHOST", "DIALOG", Map.of("text", "hi"), null, "req-r5"));
        assertEquals("DHM_1004", e.getCode());
    }

    @Test
    void 渲染_驱动类型非法抛DHM1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.render(avatarIdOf("大堂数字员工"), "DANCE", Map.of("text", "hi"), null, "req-r6"));
        assertEquals("DHM_1007", e.getCode());
    }

    @Test
    void 渲染_内容超长抛DHM1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.render(avatarIdOf("大堂数字员工"), "TEXT",
                        Map.of("text", "a".repeat(2001)), null, "req-r7"));
        assertEquals("DHM_1006", e.getCode());
    }

    @Test
    void 渲染_内容命中违规话术抛DHM1007() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.render(avatarIdOf("大堂数字员工"), "TEXT",
                        Map.of("text", "保证收益的理财推荐"), null, "req-r8"));
        assertEquals("DHM_1007", e.getCode());
    }

    @Test
    void 渲染_requestId缺失抛DHM1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.render(avatarIdOf("大堂数字员工"), "TEXT", Map.of("text", "hi"), null, null));
        assertEquals("DHM_1001", e.getCode());
    }

    // ------------------------------------------------------------ 展示控制

    @Test
    void 控制状态机_START至ENDED全链路() {
        DigitalHumanDtos.RenderResult r = service.render(avatarIdOf("大堂数字员工"), "TEXT",
                Map.of("text", "业务播报"), null, "req-c0");
        String rid = r.renderId();
        assertEquals("PLAYING", service.control(rid, "START", null).status());
        assertEquals("PAUSED", service.control(rid, "PAUSE", null).status());
        assertEquals("PLAYING", service.control(rid, "RESUME", null).status());
        assertEquals("ENDED", service.control(rid, "END", null).status());
    }

    @Test
    void 控制状态冲突_ENDED后START抛DHM1005() {
        DigitalHumanDtos.RenderResult r = service.render(avatarIdOf("大堂数字员工"), "TEXT",
                Map.of("text", "播报一次"), null, "req-c1");
        service.control(r.renderId(), "START", null);
        service.control(r.renderId(), "END", null);
        BaiException e = assertThrows(BaiException.class,
                () -> service.control(r.renderId(), "START", null));
        assertEquals("DHM_1005", e.getCode());
        assertTrue(e.getMessage().contains("状态冲突"));
    }

    @Test
    void 控制_渲染不存在抛DHM1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.control("DH-GHOST", "END", null));
        assertEquals("DHM_1004", e.getCode());
    }

    // ------------------------------------------------------------ 形象管理

    @Test
    void 形象注册发布_列表可查() {
        DigitalHumanDtos.AvatarResult created = service.manageAvatar("CREATE", null,
                Map.of("name", "视频客服", "style", "亲切", "scenario", "VIDEO_CS"), "req-v0");
        assertEquals("DRAFT", created.status());
        assertEquals(1, created.version());
        DigitalHumanDtos.AvatarResult published = service.manageAvatar("PUBLISH", created.avatarId(), null, "req-v1");
        assertEquals("PUBLISHED", published.status());
        DigitalHumanDtos.AvatarList list = service.listAvatars(null, "VIDEO_CS", 1);
        assertTrue(list.avatars().stream().anyMatch(a -> a.avatarId().equals(created.avatarId())));
        assertEquals(1, list.total());
    }

    @Test
    void 形象重名_抛DHM1005() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.manageAvatar("CREATE", null,
                        Map.of("name", "大堂数字员工", "scenario", "LOBBY"), "req-v2"));
        assertEquals("DHM_1005", e.getCode());
    }

    @Test
    void 形象上传资产_状态转AUDITING且版本递增() {
        DigitalHumanDtos.AvatarResult created = service.manageAvatar("CREATE", null,
                Map.of("name", "数字人播报", "style", "正式", "scenario", "BROADCAST"), "req-v3");
        DigitalHumanDtos.AvatarResult audited = service.manageAvatar("UPLOAD_ASSET", created.avatarId(),
                Map.of("assetIds", List.of("asset-mesh-01", "asset-lip-01")), "req-v4");
        assertEquals("AUDITING", audited.status());
        assertEquals(2, audited.version());
        DigitalHumanDtos.AvatarInfo info = service.listAvatars("数字人播报", null, 1).avatars().get(0);
        assertEquals("AUDITING", info.status());
    }

    @Test
    void 形象删除_状态转DISABLED() {
        DigitalHumanDtos.AvatarResult created = service.manageAvatar("CREATE", null,
                Map.of("name", "移动展业助手", "style", "亲和", "scenario", "LOBBY"), "req-v5");
        DigitalHumanDtos.AvatarResult deleted = service.manageAvatar("DELETE", created.avatarId(), null, "req-v6");
        assertEquals("DISABLED", deleted.status());
    }
}
