package com.bai.digitalhuman;

import java.util.List;
import java.util.Map;

/**
 * 数字员工输出统一结构（设计文档 4.2.2/4.3/4.4/4.5）
 * <p>指令类型：SPEECH/LIP_SYNC/EXPRESSION/ACTION；渲染状态 READY/RENDERING/PAUSED；
 * 控制状态 PLAYING/PAUSED/ENDED；形象状态 DRAFT/AUDITING/PUBLISHED/DISABLED
 */
public final class DigitalHumanDtos {

    private DigitalHumanDtos() {}

    /** 展示指令（type：SPEECH/LIP_SYNC/EXPRESSION/ACTION，attrs 按类型承载 text/audio/timeline/emotion/action） */
    public record RenderCommand(String type, Map<String, Object> attrs) {}

    /** 渲染驱动结果（渲染记录 ID/指令序列/受理状态/受理耗时） */
    public record RenderResult(String renderId, String avatarId, String driverType,
                               List<RenderCommand> commands, String status, long elapsedMs) {}

    /** 展示控制结果 */
    public record ControlResult(String renderId, String status) {}

    /** 形象管理结果 */
    public record AvatarResult(String avatarId, int version, String status) {}

    /** 形象信息（列表查询） */
    public record AvatarInfo(String avatarId, String name, String style, String scenario,
                             int version, String status, String updatedAt) {}

    /** 形象列表响应 */
    public record AvatarList(List<AvatarInfo> avatars, int total) {}
}
