package com.bai.external;

import java.util.List;
import java.util.Map;

/**
 * 外部数据接入输出统一结构（提示词库 A-27：ExternalFetchResult）
 * <p>令牌仅返回引用 ID 绝不回传明文；query 只存哈希；映射缺失字段必须显式标注 mappingWarnings
 */
public final class ExternalDtos {

    private ExternalDtos() {}

    /** 统一外部数据取数结果 */
    public record ExternalFetchResult(String sourceType, String sourceVersion, String callId, String status,
                                      Map<String, Object> standardData, List<String> mappingWarnings,
                                      boolean masked) {}

    /** 数据源注册信息（sources 管理） */
    public record SourceInfo(String sourceType, String name, String sourceVersion, boolean enabled,
                             boolean authorized, int dailyQuota, long usedToday) {}

    /** 调用记录（留痕：源/场景/参数哈希/状态/时延/费用/脱敏标记） */
    public record CallRecord(String callId, String requestId, String sourceType, String scenario,
                             String caller, String paramHash, String status, long elapsedMs,
                             long cost, boolean masked, String createdAt) {}

    /** 令牌托管凭据（KMS 密文托管，仅返回引用 ID，绝不回传明文） */
    public record CredentialInfo(String credentialId, String sourceType, String credentialRef,
                                 String status, String createdAt) {}
}
