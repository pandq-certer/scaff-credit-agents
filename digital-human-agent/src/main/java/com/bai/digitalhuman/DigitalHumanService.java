package com.bai.digitalhuman;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数字员工渲染与控制服务（BIA-INT-039）。数字员工"怎么演"的确定性仿真实现。
 * <p>处理链：参数校验 → 形象状态校验（仅 PUBLISHED 可渲染）→ 内容合规检查 → 驱动适配
 * （DIALOG/TTS/TEXT/EMOTION 归一为统一展示指令：SPEECH/LIP_SYNC/EXPRESSION）→ 状态机流转
 * （READY→PLAYING↔PAUSED→ENDED，RESET 复位，冲突 DHM_1005）→ 展示留痕（渲染/控制记录）
 * <p>幂等：同 requestId+avatarId+content 指纹 5min 内不重复渲染；形象资产审核（UPLOAD_ASSET→AUDITING）
 */
@Service
public class DigitalHumanService {

    private static final Logger log = LoggerFactory.getLogger(DigitalHumanService.class);

    private static final int MAX_CONTENT_LEN = 2000; // 驱动内容 ≤2000 字
    private static final int MAX_NAME_LEN = 64;      // 形象名 ≤64 位
    private static final Set<String> DRIVERS = Set.of("DIALOG", "TTS", "TEXT", "EMOTION");
    private static final Set<String> ACTIONS = Set.of("START", "PAUSE", "RESUME", "END", "RESET");
    private static final Set<String> AVATAR_ACTIONS = Set.of("CREATE", "UPDATE", "DELETE", "PUBLISH", "UPLOAD_ASSET");
    private static final Set<String> SCENARIOS = Set.of("LOBBY", "VIDEO_CS", "BROADCAST");

    /** 内容合规词表（展示内容合规检查，禁止违规话术，联动 text-gen 词表口径） */
    private static final Set<String> FORBIDDEN_WORDS = Set.of(
            "保本", "稳赚", "保证收益", "高收益", "无条件免息", "威胁", "辱骂", "赌博", "洗钱");

    /** 表情映射表：情感标签 → 表情/动作（FRIENDLY→微笑点头、WARN→严肃、SORRY→歉意、HAPPY→大笑） */
    private static final Map<String, Map<String, String>> EXPRESSION_MAP = Map.of(
            "FRIENDLY", Map.of("expression", "smile", "action", "nod"),
            "WARN", Map.of("expression", "serious", "action", "straighten"),
            "SORRY", Map.of("expression", "apologetic", "action", "bow"),
            "HAPPY", Map.of("expression", "smile-big", "action", "clap"));

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 幂等：同 requestId+avatarId+content 指纹 5min 不重复渲染（生产换 Redis） */
    private final IdempotencyStore idempotency = new IdempotencyStore(300_000L);

    /** 渲染会话：renderId → 会话（状态 READY/PLAYING/PAUSED/ENDED） */
    private final Map<String, RenderSession> renders = new ConcurrentHashMap<>();
    /** 形象资产：avatarId → 形象（状态 DRAFT/AUDITING/PUBLISHED/DISABLED） */
    private final Map<String, Avatar> avatars = new ConcurrentHashMap<>();

    // ------------------------------------------------------------ 渲染驱动（4.2）

    /** 渲染驱动：会话/语音/文本/情感 → 展示指令序列（确定性仿真） */
    public DigitalHumanDtos.RenderResult render(String avatarId, String driverType, Object content,
                                                String sessionId, String requestId) {
        long start = System.currentTimeMillis();
        if (requestId == null || requestId.isBlank()) {
            throw new BaiException(DigitalHumanErrorCodes.PARAM_INVALID, "DHM_1001 参数缺失：requestId 必填");
        }
        if (requestId.length() > 32) {
            throw new BaiException(DigitalHumanErrorCodes.PARAM_INVALID, "DHM_1001 参数非法：requestId 须 32 位以内");
        }
        if (avatarId == null || avatarId.isBlank()) {
            throw new BaiException(DigitalHumanErrorCodes.PARAM_INVALID, "DHM_1001 参数缺失：avatarId 必填");
        }
        if (driverType == null || driverType.isBlank() || !DRIVERS.contains(driverType.toUpperCase())) {
            throw new BaiException(DigitalHumanErrorCodes.CONTENT_INVALID,
                    "DHM_1007 驱动类型非法：driverType 须为 DIALOG/TTS/TEXT/EMOTION");
        }
        Avatar avatar = avatars.get(avatarId);
        if (avatar == null || !"PUBLISHED".equals(avatar.status)) {
            throw new BaiException(DigitalHumanErrorCodes.NOT_FOUND,
                    "DHM_1004 形象不存在或未发布，请校验 avatarId");
        }
        String text = textOf(content);
        if (content == null || (text == null && audioOf(content) == null && emotionOf(content) == null)) {
            throw new BaiException(DigitalHumanErrorCodes.PARAM_INVALID, "DHM_1001 参数缺失：content 必填");
        }
        if (text != null && text.length() > MAX_CONTENT_LEN) {
            throw new BaiException(DigitalHumanErrorCodes.CONTENT_TOO_LONG,
                    "DHM_1006 内容超长（" + text.length() + " > 2000 字），请缩减输入");
        }
        String hit = hitForbidden(text);
        if (hit != null) {
            log.info("渲染合规拦截: avatarId={}, hit={}, requestId={}", avatarId, hit, requestId);
            throw new BaiException(DigitalHumanErrorCodes.CONTENT_INVALID,
                    "DHM_1007 内容合规未过：驱动内容命中违规话术（" + hit + "），已拦截");
        }
        String fingerprint = avatarId + ":" + driverType + ":" + (text != null ? text : audioOf(content));
        DigitalHumanDtos.RenderResult cached = idempotency.get(fingerprint, requestId);
        if (cached != null) {
            log.info("渲染幂等命中: renderId={}, requestId={}", cached.renderId(), requestId);
            return cached;
        }
        String driver = driverType.toUpperCase();
        List<DigitalHumanDtos.RenderCommand> commands = buildCommands(driver, content);
        String renderId = "DH" + LocalDateTime.now().format(DAY)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        renders.put(renderId, new RenderSession(renderId, avatarId, driver, sessionId, "READY", commands));
        DigitalHumanDtos.RenderResult result = new DigitalHumanDtos.RenderResult(renderId, avatarId, driver,
                commands, "READY", System.currentTimeMillis() - start);
        idempotency.put(fingerprint, requestId, result);
        log.info("渲染受理完成: renderId={}, avatarId={}, driverType={}, commands={}, elapsed={}ms, requestId={}",
                renderId, avatarId, driver, commands.size(), result.elapsedMs(), requestId);
        return result;
    }

    // ------------------------------------------------------------ 展示控制（4.3）

    /** 展示控制：状态机流转（READY→PLAYING↔PAUSED→ENDED，RESET 复位，冲突 DHM_1005） */
    public DigitalHumanDtos.ControlResult control(String renderId, String action, String reason) {
        if (renderId == null || renderId.isBlank()) {
            throw new BaiException(DigitalHumanErrorCodes.PARAM_INVALID, "DHM_1001 参数缺失：renderId 必填");
        }
        if (action == null || !ACTIONS.contains(action.toUpperCase())) {
            throw new BaiException(DigitalHumanErrorCodes.PARAM_INVALID,
                    "DHM_1001 参数非法：action 须为 START/PAUSE/RESUME/END/RESET");
        }
        RenderSession session = renders.get(renderId);
        if (session == null) {
            throw new BaiException(DigitalHumanErrorCodes.NOT_FOUND, "DHM_1004 渲染不存在，请校验 renderId");
        }
        String prev = session.status;
        String next = transition(prev, action.toUpperCase());
        session.status = next;
        session.updatedAt = LocalDateTime.now();
        log.info("展示控制: renderId={}, action={}, reason={}, 状态 {} -> {}", renderId, action, reason, prev, next);
        return new DigitalHumanDtos.ControlResult(renderId, next);
    }

    // ------------------------------------------------------------ 形象管理（4.4）

    /** 形象/资产管理：CREATE/UPDATE/DELETE/PUBLISH/UPLOAD_ASSET（资产上传进入审核） */
    public DigitalHumanDtos.AvatarResult manageAvatar(String action, String avatarId,
                                                      Map<String, Object> avatarDef, String requestId) {
        if (action == null || !AVATAR_ACTIONS.contains(action.toUpperCase())) {
            throw new BaiException(DigitalHumanErrorCodes.PARAM_INVALID,
                    "DHM_1001 参数非法：action 须为 CREATE/UPDATE/DELETE/PUBLISH/UPLOAD_ASSET");
        }
        String act = action.toUpperCase();
        if ("CREATE".equals(act)) {
            String name = str(avatarDef == null ? null : avatarDef.get("name"));
            if (name == null || name.isBlank()) {
                throw new BaiException(DigitalHumanErrorCodes.PARAM_INVALID, "DHM_1001 参数缺失：avatar.name 必填");
            }
            if (name.length() > MAX_NAME_LEN) {
                throw new BaiException(DigitalHumanErrorCodes.PARAM_INVALID, "DHM_1001 参数非法：avatar.name 须 64 位以内");
            }
            String scenario = str(avatarDef.get("scenario"));
            if (scenario == null || !SCENARIOS.contains(scenario.toUpperCase())) {
                throw new BaiException(DigitalHumanErrorCodes.PARAM_INVALID,
                        "DHM_1001 参数非法：scenario 须为 LOBBY/VIDEO_CS/BROADCAST");
            }
            if (avatars.values().stream().anyMatch(a -> name.equals(a.name))) {
                throw new BaiException(DigitalHumanErrorCodes.CONFLICT, "DHM_1005 形象名冲突：形象 " + name + " 已存在");
            }
            String newId = "AV" + LocalDateTime.now().format(DAY)
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            Avatar avatar = new Avatar(newId, name, str(avatarDef.get("style")), scenario.toUpperCase(),
                    assetIds(avatarDef), "DRAFT", 1);
            avatars.put(newId, avatar);
            log.info("形象注册: avatarId={}, name={}, scenario={}, requestId={}", newId, name, avatar.scenario, requestId);
            return avatar.toResult();
        }
        if (avatarId == null || avatarId.isBlank()) {
            throw new BaiException(DigitalHumanErrorCodes.PARAM_INVALID, "DHM_1001 参数缺失：avatarId 必填");
        }
        Avatar avatar = avatars.get(avatarId);
        if (avatar == null) {
            throw new BaiException(DigitalHumanErrorCodes.NOT_FOUND, "DHM_1004 形象不存在，请校验 avatarId");
        }
        switch (act) {
            case "UPDATE" -> {
                String name = str(avatarDef == null ? null : avatarDef.get("name"));
                if (name != null && !name.isBlank()) {
                    if (avatars.values().stream().anyMatch(a -> !a.avatarId.equals(avatarId) && name.equals(a.name))) {
                        throw new BaiException(DigitalHumanErrorCodes.CONFLICT, "DHM_1005 形象名冲突：形象 " + name + " 已存在");
                    }
                    avatar.name = name;
                }
                if (avatarDef != null) {
                    if (avatarDef.get("style") != null) {
                        avatar.style = str(avatarDef.get("style"));
                    }
                    String scenario = str(avatarDef.get("scenario"));
                    if (scenario != null && !scenario.isBlank()) {
                        avatar.scenario = scenario.toUpperCase();
                    }
                    if (avatarDef.get("assetIds") != null) {
                        avatar.assetIds = assetIds(avatarDef);
                    }
                }
                avatar.version++;
                log.info("形象更新: avatarId={}, version={}, requestId={}", avatarId, avatar.version, requestId);
            }
            case "DELETE" -> {
                avatar.status = "DISABLED";
                log.info("形象停用: avatarId={}, requestId={}", avatarId, requestId);
            }
            case "PUBLISH" -> {
                if (!"DRAFT".equals(avatar.status) && !"AUDITING".equals(avatar.status)) {
                    throw new BaiException(DigitalHumanErrorCodes.CONFLICT,
                            "DHM_1005 形象状态冲突：当前状态 " + avatar.status + " 不允许发布");
                }
                avatar.status = "PUBLISHED";
                log.info("形象发布: avatarId={}, version={}, requestId={}", avatarId, avatar.version, requestId);
            }
            case "UPLOAD_ASSET" -> {
                List<String> ids = assetIds(avatarDef);
                if (ids.isEmpty()) {
                    throw new BaiException(DigitalHumanErrorCodes.PARAM_INVALID, "DHM_1001 参数缺失：avatar.assetIds 必填");
                }
                List<String> merged = new ArrayList<>(avatar.assetIds);
                for (String id : ids) {
                    if (!merged.contains(id)) {
                        merged.add(id);
                    }
                }
                avatar.assetIds = merged;
                avatar.version++;
                avatar.status = "AUDITING"; // 资产变更强制审核
                log.info("形象资产上传: avatarId={}, assets={}, version={}, 进入审核", avatarId, ids, avatar.version);
            }
            default -> throw new BaiException(DigitalHumanErrorCodes.PARAM_INVALID,
                    "DHM_1001 参数非法：action 须为 CREATE/UPDATE/DELETE/PUBLISH/UPLOAD_ASSET");
        }
        avatar.updatedAt = LocalDateTime.now();
        return avatar.toResult();
    }

    // ------------------------------------------------------------ 形象列表查询（4.5）

    /** 形象列表查询（keyword/scenario 过滤 + 分页，按更新时间倒序） */
    public DigitalHumanDtos.AvatarList listAvatars(String keyword, String scenario, int page) {
        int pageSize = 20;
        int pageNo = Math.max(1, page);
        List<DigitalHumanDtos.AvatarInfo> filtered = new ArrayList<>();
        for (Avatar a : avatars.values()) {
            if (keyword != null && !keyword.isBlank() && !a.name.contains(keyword)) {
                continue;
            }
            if (scenario != null && !scenario.isBlank() && !scenario.equalsIgnoreCase(a.scenario)) {
                continue;
            }
            filtered.add(a.toInfo());
        }
        filtered.sort(java.util.Comparator.comparing(DigitalHumanDtos.AvatarInfo::updatedAt).reversed());
        int total = filtered.size();
        int from = Math.min((pageNo - 1) * pageSize, total);
        int to = Math.min(from + pageSize, total);
        return new DigitalHumanDtos.AvatarList(new ArrayList<>(filtered.subList(from, to)), total);
    }

    // ------------------------------------------------------------ 内部：指令组装

    /** 驱动适配：四种驱动归一为统一展示指令（口型文本兜底/音频帧对齐，表情映射） */
    private List<DigitalHumanDtos.RenderCommand> buildCommands(String driver, Object content) {
        List<DigitalHumanDtos.RenderCommand> commands = new ArrayList<>();
        String text = textOf(content);
        String audio = audioOf(content);
        String emotion = emotionOf(content);
        switch (driver) {
            case "DIALOG", "TEXT" -> {
                Map<String, Object> speech = new LinkedHashMap<>();
                speech.put("text", text == null ? "" : text);
                if (audio != null) {
                    speech.put("audio", audio);
                }
                commands.add(new DigitalHumanDtos.RenderCommand("SPEECH", speech));
                commands.add(new DigitalHumanDtos.RenderCommand("LIP_SYNC",
                        Map.of("timeline", textFallbackTimeline(text == null ? "" : text))));
                commands.add(new DigitalHumanDtos.RenderCommand("EXPRESSION", expressionOf(emotion)));
            }
            case "TTS" -> {
                Map<String, Object> speech = new LinkedHashMap<>();
                if (text != null) {
                    speech.put("text", text);
                }
                speech.put("audio", audio == null ? "" : audio);
                commands.add(new DigitalHumanDtos.RenderCommand("SPEECH", speech));
                commands.add(new DigitalHumanDtos.RenderCommand("LIP_SYNC",
                        Map.of("timeline", audioTimeline())));
                commands.add(new DigitalHumanDtos.RenderCommand("EXPRESSION", expressionOf(emotion)));
            }
            case "EMOTION" -> commands.add(new DigitalHumanDtos.RenderCommand("EXPRESSION", expressionOf(emotion)));
            default -> throw new BaiException(DigitalHumanErrorCodes.CONTENT_INVALID,
                    "DHM_1007 驱动类型非法：driverType 须为 DIALOG/TTS/TEXT/EMOTION");
        }
        return commands;
    }

    /** 文本兜底口型时间线：按字数估算时长（每字约 80ms，10 字/帧） */
    private List<Map<String, Object>> textFallbackTimeline(String text) {
        List<Map<String, Object>> timeline = new ArrayList<>();
        int frames = Math.max(1, (text.length() + 9) / 10);
        for (int i = 0; i < frames; i++) {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("t", (i + 1) * 80L);
            frame.put("phoneme", "p" + i);
            timeline.add(frame);
        }
        return timeline;
    }

    /** 音频帧级口型时间线（50ms 帧对齐，模拟 1s 音频） */
    private List<Map<String, Object>> audioTimeline() {
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("t", (i + 1) * 50L);
            frame.put("phoneme", "f" + i);
            timeline.add(frame);
        }
        return timeline;
    }

    /** 情感 → 表情/动作映射（缺省 FRIENDLY 微笑点头） */
    private Map<String, Object> expressionOf(String emotion) {
        Map<String, String> mapped = EXPRESSION_MAP.get(emotion == null ? "FRIENDLY" : emotion.toUpperCase());
        if (mapped == null) {
            mapped = EXPRESSION_MAP.get("FRIENDLY");
        }
        return new LinkedHashMap<>(mapped);
    }

    /** 内容合规检查：命中违规话术返回命中词，未命中返回 null */
    private String hitForbidden(String text) {
        if (text == null) {
            return null;
        }
        for (String word : FORBIDDEN_WORDS) {
            if (text.contains(word)) {
                return word;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 内部：状态机

    /** 展示状态机：READY→PLAYING↔PAUSED→ENDED；任意→READY（RESET）；ENDED 后 START 冲突 DHM_1005 */
    private String transition(String status, String action) {
        String next = switch (action) {
            case "START" -> "READY".equals(status) ? "PLAYING" : null;
            case "PAUSE" -> "PLAYING".equals(status) ? "PAUSED" : null;
            case "RESUME" -> "PAUSED".equals(status) ? "PLAYING" : null;
            case "END" -> ("PLAYING".equals(status) || "PAUSED".equals(status)) ? "ENDED" : null;
            case "RESET" -> "READY";
            default -> null;
        };
        if (next == null) {
            throw new BaiException(DigitalHumanErrorCodes.CONFLICT,
                    "DHM_1005 渲染状态冲突：当前状态 " + status + " 不允许动作 " + action + "（ENDED 后需重建渲染）");
        }
        return next;
    }

    // ------------------------------------------------------------ 内部：辅助

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String textOf(Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof Map<?, ?> m) {
            Object t = m.get("text");
            return t == null ? null : String.valueOf(t);
        }
        return null;
    }

    private String audioOf(Object content) {
        if (content instanceof Map<?, ?> m) {
            Object a = m.get("audio");
            return a == null ? null : String.valueOf(a);
        }
        return null;
    }

    private String emotionOf(Object content) {
        if (content instanceof Map<?, ?> m) {
            Object e = m.get("emotion");
            return e == null ? null : String.valueOf(e);
        }
        return null;
    }

    private List<String> assetIds(Map<String, Object> def) {
        if (def == null) {
            return new ArrayList<>();
        }
        Object ids = def.get("assetIds");
        if (ids instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                if (o != null) {
                    result.add(String.valueOf(o));
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    /** 渲染会话域模型（状态 READY/PLAYING/PAUSED/ENDED 一致流转） */
    private static final class RenderSession {
        final String renderId;
        final String avatarId;
        final String driverType;
        final String sessionId;
        final List<DigitalHumanDtos.RenderCommand> commands;
        String status;
        LocalDateTime updatedAt = LocalDateTime.now();

        RenderSession(String renderId, String avatarId, String driverType, String sessionId,
                      String status, List<DigitalHumanDtos.RenderCommand> commands) {
            this.renderId = renderId;
            this.avatarId = avatarId;
            this.driverType = driverType;
            this.sessionId = sessionId;
            this.status = status;
            this.commands = commands;
        }
    }

    /** 形象域模型（状态 DRAFT→AUDITING→PUBLISHED→DISABLED，版本化） */
    private static final class Avatar {
        final String avatarId;
        String name;
        String style;
        String scenario;
        List<String> assetIds;
        String status;
        int version;
        LocalDateTime updatedAt = LocalDateTime.now();

        Avatar(String avatarId, String name, String style, String scenario,
               List<String> assetIds, String status, int version) {
            this.avatarId = avatarId;
            this.name = name;
            this.style = style;
            this.scenario = scenario;
            this.assetIds = assetIds;
            this.status = status;
            this.version = version;
        }

        DigitalHumanDtos.AvatarResult toResult() {
            return new DigitalHumanDtos.AvatarResult(avatarId, version, status);
        }

        DigitalHumanDtos.AvatarInfo toInfo() {
            return new DigitalHumanDtos.AvatarInfo(avatarId, name, style, scenario, version, status,
                    updatedAt.toString());
        }
    }
}
