package com.bai.recommend;

import java.util.List;
import java.util.Map;

/**
 * 智能推荐输入/输出结构（提示词库 A-59：POST /api/bai/recommend/items 字段表逐条对齐）
 */
public final class RecommendDtos {

    private RecommendDtos() {}

    /** 推荐入参：scene(PRODUCT|DISPOSAL|SPEECH)、algo(CF|MAB|RULE|MODEL)、topN(1~50)、context 可选 */
    public record RecommendRequest(String customerId, String scene, String algo, Integer topN,
                                   Map<String, Object> context, String requestId) {}

    /** 推荐项：itemId/name/score/reason，每项必须含 reason */
    public record RecommendItem(String itemId, String name, double score, String reason) {}

    /** 推荐结果：items 长度 ≤ topN；模型降级时 algoUsed=RULE 且 strategyVersion 带 -DEGRADED 标记 */
    public record RecommendResult(String code, String customerId, String scene, List<RecommendItem> items,
                                  String algoUsed, String strategyVersion) {}

    /** 推荐执行入参（4.2）：scene/context/topN 必填，strategyId 可选（缺省用当前生效策略） */
    public record RecommendExecuteRequest(String scene, Map<String, Object> context, Integer topN,
                                          String strategyId, String requestId) {}

    /** 推荐项（4.2）：rank 自 1 起，score 保留两位小数，每项必须含 reason */
    public record RecommendRankedItem(String itemId, String name, int rank, double score, String reason) {}

    /** 推荐执行结果（4.2）：recommendId 留痕可查，algoUsed 含 HYBRID 融合 */
    public record RecommendExecuteResult(String recommendId, String scene, String strategyId,
                                         List<RecommendRankedItem> items, String algoUsed, long elapsedMs) {}

    /** 推荐查询入参（4.3）：recommendId 精确查询，或按 scene/customerId/algoUsed 过滤 + 分页 */
    public record RecommendQueryRequest(String recommendId, String scene, String customerId,
                                        String algoUsed, Integer page, Integer size) {}

    /** 推荐记录条目（4.3 响应 items 元素） */
    public record RecommendRecordItem(String recommendId, String scene, List<RecommendRankedItem> items,
                                      String algoUsed, String strategyId, String createdAt) {}

    /** 推荐查询结果（4.3）：total 为命中总数，items 为分页结果 */
    public record RecommendQueryResult(int total, List<RecommendRecordItem> items) {}

    /** 推荐反馈入参（4.4）：action 为 CLICK|ACCEPT|REJECT|CONVERT，feedback 可选 */
    public record FeedbackRequest(String recommendId, String itemId, String action,
                                  Map<String, Object> feedback, String requestId) {}

    /** 推荐反馈结果（4.4）：status=RECEIVED 表示受理成功 */
    public record FeedbackResult(String feedbackId, String status) {}

    /** 策略配置（4.5）：algo 为算法列表、weights 为融合权重、topNMax(1~50) 可选 */
    public record StrategyConfig(String strategyId, String scene, List<String> algo,
                                 Map<String, Double> weights, Map<String, Object> rules, Integer topNMax) {}

    /** 策略管理入参（4.5）：action 为 CREATE|UPDATE|ACTIVATE|ROLLBACK（兼容 ENABLE|DISABLE） */
    public record StrategyRequest(String action, StrategyConfig strategy, String requestId) {}

    /** 策略管理结果（4.5）：status 为 ACTIVE|DRAFT|DISABLED */
    public record StrategyResult(String strategyId, int version, String status) {}
}
