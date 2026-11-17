package com.bai.sensitivemask;

import java.util.List;

/**
 * 敏感脱敏输出统一结构（提示词库 A-47：MaskResult/Detection/DetectResult/VerifyResult/RuleInfo）
 */
public final class SensitiveMaskDtos {

    private SensitiveMaskDtos() {}

    /** 敏感项检出明细 */
    public record Detection(String type, int start, int end, String masked, double confidence) {}

    /** 脱敏结果（maskedFields 与 maskedText 按数据类型二选一返回） */
    public record MaskResult(String maskId, Object maskedFields, String maskedText, int detectedCount,
                             List<String> ruleVersions, int elapsedMs) {}

    /** 敏感识别结果 */
    public record DetectResult(List<Detection> detections, int detectedCount,
                               List<String> ruleVersions, int elapsedMs) {}

    /** 脱敏抽检结果（残敏感检测） */
    public record VerifyResult(String targetId, int checkedCount, boolean clean,
                               List<Detection> residuals, int elapsedMs) {}

    /** 脱敏规则概要 */
    public record RuleInfo(String ruleId, String name, String sensitiveType, String algorithm,
                           String version, String status) {}
}
