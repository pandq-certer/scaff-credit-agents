package com.bai.modelinfer;

import java.util.List;
import java.util.Map;

/**
 * 模型推理输出统一结构（提示词库 A-54：ScoreResult/Explanation/ModelInfo）
 */
public final class ModelInferDtos {

    private ModelInferDtos() {}

    /** 特征贡献度解释 */
    public record Explanation(String feature, String direction, double weight) {}

    /** 推理结果（评分/概率/等级/置信度/解释/版本/链路号，置信度低标记需人工复核） */
    public record ScoreResult(Double score, double probability, String grade, double confidence,
                              boolean needReview, List<Explanation> explanation, String modelVersion,
                              String traceId) {}

    /** 模型注册概要 */
    public record ModelInfo(String modelId, String modelType, String scene, String version,
                            List<String> requiredFeatures, String status) {}

    /** 聚类分群请求（4.3：modelId + scene + features + k?） */
    public record ClusterRequest(String modelId, String scene, Map<String, Object> features, Integer k) {}

    /** 聚类分群结果（4.3：分群标签 + 分群画像 + 簇心距离 + 耗时） */
    public record ClusterResult(String inferId, String clusterId, String clusterName,
                                Map<String, Object> clusterProfile, double distance, long elapsedMs) {}

    /** 模型注册/治理请求（4.4：action + model 元数据） */
    public record ModelManageRequest(String action, ModelMeta model) {}

    /** 模型元数据（4.4 请求体 model 对象：name/type/scene/version/artifactRef/metrics/owner） */
    public record ModelMeta(String name, String type, String scene, String version,
                            String artifactRef, Map<String, Object> metrics, String owner) {}

    /** 模型注册/治理结果（4.4：modelId + 版本 + 状态） */
    public record ModelManageResult(String modelId, String modelVersion, String status) {}

    /** 可解释查询结果（4.5：inferId + 方法 + 贡献明细 + 结论） */
    public record ExplainResult(String inferId, String method, List<Contribution> contributions,
                                String conclusion) {}

    /** 特征贡献明细（4.5：feature/value/contribution/direction） */
    public record Contribution(String feature, Object value, double contribution, String direction) {}
}
