package com.bai.modelinfer;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型推理服务（BIA-CAL-052）。统一模型推理出口：按场景加载评分卡/机器学习/大模型/聚类模型，
 * 输出评分、概率、等级与可解释性结论；不做训练、不做特征工程、不做审批结论。
 * <p>MVP 为确定性仿真：评分卡按特征公式打分（规则打分引擎路径），ML/LLM/聚类按哈希推导，
 * 评分与 modelVersion 一一对应、不可用模型返回 MDI_2001 不臆造结果。
 * 等级分段：≥90 AAA、≥80 AA、≥65 BBB、≥55 BB、≥45 B、否则 CCC；PD≈(100-score)×0.00072。
 */
@Service
public class ModelInferService {

    private static final Logger log = LoggerFactory.getLogger(ModelInferService.class);

    private static final DateTimeFormatter TS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int MAX_FEATURES = 200;                        // 特征集超长 MDI_1006
    private static final Set<String> SCENES = Set.of("RATING", "APPROVAL", "RISK", "ECL", "MARKETING", "SEGMENT");
    private static final Set<String> MODEL_TYPES = Set.of("SCORECARD", "ML", "LLM", "CLUSTER");
    private static final Set<String> CUSTOMER_REQUIRED_SCENES = Set.of("RATING", "MARKETING", "SEGMENT");
    private static final Set<String> ACTIONS = Set.of("REGISTER", "UPDATE", "GRAY", "PROMOTE", "ROLLBACK", "DISABLE");
    private static final Set<String> EXPLAIN_METHODS = Set.of("SHAP", "SCORECARD_CONTRIB");
    private static final int DEFAULT_K = 5;                             // 聚类默认分群数
    private static final int MAX_K = 20;                                // 分群数上限
    /** 分群名称（按簇索引取模，确定性仿真） */
    private static final String[] CLUSTER_NAMES = {
            "高净值客群", "稳健型客群", "成长型客群", "关注客群", "风险客群",
            "流失倾向客群", "下沉客群", "活跃客群"};

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final Map<String, Model> registry = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 内部模型注册条目 */
    private record Model(String modelId, String modelType, Set<String> scenes, String version,
                         List<String> requiredFeatures, String status, String artifactRef,
                         Map<String, Object> metrics, String owner) {}

    public ModelInferService() {
        // 模型注册表（ACTIVE 只增版本不可回写；DEPRECATED 用于校验 MDI_1004）
        put(new Model("CRB-SC-2025-V3", "SCORECARD", Set.of("RATING"), "CRB-SC-2025-V3.2",
                List.of("annualIncome", "assetLiabRatio", "netCashRatio"), "ACTIVE", null, null, null));
        put(new Model("APD-ML-2026-V1", "ML", Set.of("APPROVAL"), "APD-ML-2026-V1.5",
                List.of("applicationAmount", "creditHistoryMonths", "debtRatio"), "ACTIVE", null, null, null));
        put(new Model("RSK-LLM-2026-V1", "LLM", Set.of("RISK"), "RSK-LLM-2026-V1.1",
                List.of("overdueTimes", "litigationCount"), "ACTIVE", null, null, null));
        put(new Model("ECL-CL-2026-V1", "CLUSTER", Set.of("ECL"), "ECL-CL-2026-V1.0",
                List.of("exposure", "pd"), "ACTIVE", null, null, null));
        put(new Model("MKT-SEG-2026-V1", "CLUSTER", Set.of("MARKETING", "SEGMENT"), "MKT-SEG-2026-V1.2",
                List.of("aum", "activeMonths"), "ACTIVE", null, null, null));
        put(new Model("CRB-SC-2024-V2", "SCORECARD", Set.of("RATING"), "CRB-SC-2024-V2.0",
                List.of("annualIncome", "assetLiabRatio", "netCashRatio"), "DEPRECATED", null, null, null));
    }

    private void put(Model model) {
        registry.put(model.modelId(), model);
    }

    /** 统一推理（幂等键 requestId+modelId+特征哈希；同键复用首次结果） */
    public ModelInferDtos.ScoreResult score(Map<String, Object> body, String requestId) {
        String scene = str(body.get("scene"));
        if (scene == null || scene.isBlank()) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID, "MDI_1001 参数缺失：scene 必填");
        }
        scene = scene.toUpperCase(Locale.ROOT);
        if (!SCENES.contains(scene)) {
            throw new BaiException(ModelInferErrorCodes.SCENE_INVALID,
                    "MDI_1007 场景非法：" + scene + "（仅支持 RATING/APPROVAL/RISK/ECL/MARKETING/SEGMENT）");
        }
        String modelType = str(body.get("modelType"));
        if (modelType == null || modelType.isBlank()) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID, "MDI_1001 参数缺失：modelType 必填");
        }
        modelType = modelType.toUpperCase(Locale.ROOT);
        if (!MODEL_TYPES.contains(modelType)) {
            throw new BaiException(ModelInferErrorCodes.SCENE_INVALID,
                    "MDI_1007 模型类型非法：" + modelType + "（仅支持 SCORECARD/ML/LLM/CLUSTER）");
        }
        String modelId = str(body.get("modelId"));
        if (modelId == null || modelId.isBlank()) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID, "MDI_1001 参数缺失：modelId 必填");
        }
        Model model = registry.get(modelId);
        if (model == null || !"ACTIVE".equals(model.status())
                || !model.modelType().equals(modelType) || !model.scenes().contains(scene)) {
            throw new BaiException(ModelInferErrorCodes.MODEL_NOT_FOUND,
                    "MDI_1004 模型不存在或非 ACTIVE/与 scene+modelType 不匹配：" + modelId);
        }
        if (!(body.get("features") instanceof Map<?, ?> featuresRaw) || featuresRaw.isEmpty()) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID,
                    "MDI_1001 参数缺失：features 特征键值对必填");
        }
        if (featuresRaw.size() > MAX_FEATURES) {
            throw new BaiException(ModelInferErrorCodes.TOO_LONG,
                    "MDI_1006 特征集超长（" + featuresRaw.size() + " > " + MAX_FEATURES + " 项），请缩减输入");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> features = (Map<String, Object>) featuresRaw;
        String customerId = str(body.get("customerId"));
        if (CUSTOMER_REQUIRED_SCENES.contains(scene) && isBlank(customerId)) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID,
                    "MDI_1001 参数缺失：customerId 在 " + scene + " 场景必填");
        }
        // 特征字典校验：缺失必填特征 → MDI_1001
        List<String> missing = model.requiredFeatures().stream()
                .filter(f -> !features.containsKey(f) || features.get(f) == null
                        || String.valueOf(features.get(f)).isBlank()).toList();
        if (!missing.isEmpty()) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID,
                    "MDI_1001 特征缺失：模型 " + modelId + " 必填特征 " + missing + " 未提供");
        }
        String featuresHash = String.valueOf(features.hashCode());
        String idemKey = requestId + "|" + modelId + "|" + featuresHash;
        ModelInferDtos.ScoreResult cached = idempotency.get("score", idemKey);
        if (cached != null) {
            return cached;
        }
        long begin = System.nanoTime();
        ModelInferDtos.ScoreResult result = infer(model, features, customerId, requestId);
        idempotency.put("score", idemKey, result);
        log.info("模型推理完成: modelId={}, modelVersion={}, scene={}, customerId={}, score={}, grade={}",
                modelId, result.modelVersion(), scene, customerId, result.score(), result.grade());
        log.debug("推理耗时仿真: {}ms", (System.nanoTime() - begin) / 1_000_000);
        return result;
    }

    /** 聚类分群（4.3，幂等键 requestId+modelId+特征哈希；按特征哈希推导分群） */
    public ModelInferDtos.ClusterResult cluster(Map<String, Object> body, String requestId) {
        String scene = str(body.get("scene"));
        if (scene == null || scene.isBlank()) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID, "MDI_1001 参数缺失：scene 必填（聚类场景 SEGMENT 等）");
        }
        scene = scene.toUpperCase(Locale.ROOT);
        if (!SCENES.contains(scene)) {
            throw new BaiException(ModelInferErrorCodes.SCENE_INVALID,
                    "MDI_1007 场景非法：" + scene + "（仅支持 RATING/APPROVAL/RISK/ECL/MARKETING/SEGMENT）");
        }
        String modelId = str(body.get("modelId"));
        if (modelId == null || modelId.isBlank()) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID, "MDI_1001 参数缺失：modelId 必填");
        }
        Model model = registry.get(modelId);
        if (model == null || !"ACTIVE".equals(model.status())
                || !"CLUSTER".equals(model.modelType()) || !model.scenes().contains(scene)) {
            throw new BaiException(ModelInferErrorCodes.MODEL_NOT_FOUND,
                    "MDI_1004 聚类模型不存在或非 ACTIVE/CLUSTER 类型/与 scene 不匹配：" + modelId);
        }
        if (!(body.get("features") instanceof Map<?, ?> featuresRaw) || featuresRaw.isEmpty()) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID,
                    "MDI_1001 参数缺失：features 特征键值对必填");
        }
        if (featuresRaw.size() > MAX_FEATURES) {
            throw new BaiException(ModelInferErrorCodes.TOO_LONG,
                    "MDI_1006 特征集超长（" + featuresRaw.size() + " > " + MAX_FEATURES + " 项），请缩减输入");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> features = (Map<String, Object>) featuresRaw;
        int k = DEFAULT_K;
        if (body.get("k") != null) {
            k = (int) Math.round(numOf(body.get("k")));
            if (k < 1 || k > MAX_K) {
                throw new BaiException(ModelInferErrorCodes.PARAM_INVALID,
                        "MDI_1001 参数非法：k 分群数须在 1~" + MAX_K + " 之间，实际 '" + body.get("k") + "'");
            }
        }
        String featuresHash = String.valueOf(features.hashCode());
        String idemKey = requestId + "|" + modelId + "|" + featuresHash;
        ModelInferDtos.ClusterResult cached = idempotency.get("cluster", idemKey);
        if (cached != null) {
            return cached;
        }
        // 确定性分群：按 modelId+特征哈希推导簇索引，簇画像取字典序前 5 项特征
        int clusterIdx = Math.abs((modelId + featuresHash).hashCode()) % k;
        String clusterId = "CL-" + (clusterIdx + 1);
        String clusterName = CLUSTER_NAMES[clusterIdx % CLUSTER_NAMES.length];
        Map<String, Object> profile = new java.util.LinkedHashMap<>();
        features.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(5)
                .forEach(e -> profile.put(e.getKey(), e.getValue()));
        double distance = round4(0.05 + (clusterIdx + 1) * 0.03 + Math.abs(featuresHash.hashCode()) % 100 / 10000.0);
        long elapsedMs = 5 + Math.abs((modelId + featuresHash).hashCode()) % 40;
        String inferId = "inf-" + TS_DATE.format(LocalDateTime.now()) + "-"
                + String.format("%08x", (modelId + requestId + featuresHash).hashCode());
        ModelInferDtos.ClusterResult result =
                new ModelInferDtos.ClusterResult(inferId, clusterId, clusterName, profile, distance, elapsedMs);
        idempotency.put("cluster", idemKey, result);
        log.info("聚类分群完成: inferId={}, modelId={}, scene={}, clusterId={}, clusterName={}, k={}",
                inferId, modelId, scene, clusterId, clusterName, k);
        return result;
    }

    /** 模型注册/治理（4.4，幂等键 requestId+action+modelId；REGISTER 注册/UPDATE 更新/GRAY 灰度/PROMOTE 全量/ROLLBACK 回滚/DISABLE 下线） */
    public ModelInferDtos.ModelManageResult modelManage(Map<String, Object> body, String requestId) {
        String action = str(body.get("action"));
        if (action == null || action.isBlank()) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID, "MDI_1001 参数缺失：action 必填");
        }
        action = action.toUpperCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            throw new BaiException(ModelInferErrorCodes.SCENE_INVALID,
                    "MDI_1007 动作非法：" + action + "（仅支持 REGISTER/UPDATE/GRAY/PROMOTE/ROLLBACK/DISABLE）");
        }
        if (!(body.get("model") instanceof Map<?, ?> modelRaw) || modelRaw.isEmpty()) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID,
                    "MDI_1001 参数缺失：model 模型元数据必填");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> modelMap = (Map<String, Object>) modelRaw;
        String name = str(modelMap.get("name"));
        if (name == null || name.isBlank()) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID, "MDI_1001 参数缺失：model.name 必填");
        }
        String modelId = normalizeModelId(name);
        if (modelId == null) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID,
                    "MDI_1001 参数非法：model.name 无法生成合法 modelId（仅支持字母/数字/下划线/连字符/中文）");
        }
        String idemKey = requestId + "|" + action + "|" + modelId;
        ModelInferDtos.ModelManageResult cached = idempotency.get("model", idemKey);
        if (cached != null) {
            return cached;
        }
        String type = str(modelMap.get("type"));
        String scene = str(modelMap.get("scene"));
        String version = str(modelMap.get("version"));
        String artifactRef = str(modelMap.get("artifactRef"));
        String owner = str(modelMap.get("owner"));
        Map<String, Object> metrics = modelMap.get("metrics") instanceof Map<?, ?> m
                ? new java.util.LinkedHashMap<>((Map<String, Object>) m) : null;
        ModelInferDtos.ModelManageResult result = switch (action) {
            case "REGISTER" -> register(modelId, name, type, scene, version, artifactRef, metrics, owner);
            case "UPDATE" -> update(modelId, type, scene, version, artifactRef, metrics, owner);
            case "GRAY" -> setStatus(modelId, "GRAY", "ACTIVE");
            case "PROMOTE" -> setStatus(modelId, "ACTIVE", "GRAY");
            case "ROLLBACK" -> rollback(modelId);
            default -> setStatus(modelId, "DISABLED", "ACTIVE"); // DISABLE
        };
        idempotency.put("model", idemKey, result);
        log.info("模型管理完成: action={}, modelId={}, modelVersion={}, status={}, requestId={}",
                action, result.modelId(), result.modelVersion(), result.status(), requestId);
        return result;
    }

    /** 可解释查询（4.5，幂等键 requestId+inferId+modelId+method；按模型类型生成确定性贡献） */
    public ModelInferDtos.ExplainResult explain(String inferId, String modelId, String method, String requestId) {
        if (modelId == null || modelId.isBlank()) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID, "MDI_1001 参数缺失：modelId 必填");
        }
        Model model = registry.get(modelId);
        if (model == null || !"ACTIVE".equals(model.status())) {
            throw new BaiException(ModelInferErrorCodes.MODEL_NOT_FOUND,
                    "MDI_1004 模型不存在或非 ACTIVE：" + modelId);
        }
        if (method == null || method.isBlank()) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID, "MDI_1001 参数缺失：method 必填");
        }
        method = method.toUpperCase(Locale.ROOT);
        if (!EXPLAIN_METHODS.contains(method)) {
            throw new BaiException(ModelInferErrorCodes.SCENE_INVALID,
                    "MDI_1007 解释方法非法：" + method + "（仅支持 SHAP/SCORECARD_CONTRIB）");
        }
        String idemKey = requestId + "|" + inferId + "|" + modelId + "|" + method;
        ModelInferDtos.ExplainResult cached = idempotency.get("explain", idemKey);
        if (cached != null) {
            return cached;
        }
        List<ModelInferDtos.Contribution> contributions = new ArrayList<>();
        if ("SCORECARD_CONTRIB".equals(method)) {
            // 评分卡贡献（4.5）：负债率负向主导，净现比/收入正向
            contributions = List.of(
                    new ModelInferDtos.Contribution("assetLiabRatio", null, 0.28, "负向"),
                    new ModelInferDtos.Contribution("netCashRatio", null, 0.21, "正向"),
                    new ModelInferDtos.Contribution("annualIncome", null, 0.18, "正向"));
        } else {
            // SHAP：按特征哈希生成确定性贡献值（0.10~0.35），奇偶定方向
            List<String> feats = model.requiredFeatures().isEmpty()
                    ? List.of("featureA", "featureB", "featureC") : model.requiredFeatures();
            for (String f : feats) {
                int h = Math.abs((modelId + f).hashCode());
                String direction = h % 2 == 0 ? "正向" : "负向";
                contributions.add(new ModelInferDtos.Contribution(f, null,
                        round4(0.10 + h % 26 / 100.0), direction));
            }
        }
        String conclusion = "模型 " + modelId + " 输出主要由 " + contributions.get(0).feature()
                + "（" + contributions.get(0).direction() + "，贡献 " + contributions.get(0).contribution() + "）主导";
        ModelInferDtos.ExplainResult result = new ModelInferDtos.ExplainResult(inferId, method, contributions, conclusion);
        idempotency.put("explain", idemKey, result);
        log.info("可解释查询完成: inferId={}, modelId={}, method={}, requestId={}",
                inferId, modelId, method, requestId);
        return result;
    }

    /** REGISTER：注册新模型（name/type/scene 必填，version 默认 1.0.0，注册即 ACTIVE；重名冲突 MDI_1005） */
    private ModelInferDtos.ModelManageResult register(String modelId, String name, String type, String scene,
                                                      String version, String artifactRef,
                                                      Map<String, Object> metrics, String owner) {
        if (registry.containsKey(modelId)) {
            throw new BaiException(ModelInferErrorCodes.CONFLICT,
                    "MDI_1005 模型冲突：modelId " + modelId + " 已存在，请更换 name 或改用 UPDATE");
        }
        if (type == null || type.isBlank()) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID, "MDI_1001 参数缺失：model.type 必填");
        }
        type = type.toUpperCase(Locale.ROOT);
        if (!MODEL_TYPES.contains(type)) {
            throw new BaiException(ModelInferErrorCodes.SCENE_INVALID,
                    "MDI_1007 模型类型非法：" + type + "（仅支持 SCORECARD/ML/LLM/CLUSTER）");
        }
        if (scene == null || scene.isBlank()) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID, "MDI_1001 参数缺失：model.scene 必填");
        }
        scene = scene.toUpperCase(Locale.ROOT);
        if (!SCENES.contains(scene)) {
            throw new BaiException(ModelInferErrorCodes.SCENE_INVALID,
                    "MDI_1007 场景非法：" + scene + "（仅支持 RATING/APPROVAL/RISK/ECL/MARKETING/SEGMENT）");
        }
        String ver = isBlank(version) ? "1.0.0" : version;
        put(new Model(modelId, type, Set.of(scene), ver, List.of(), "ACTIVE", artifactRef, metrics, owner));
        log.info("模型注册: modelId={}, name={}, type={}, scene={}, version={}, owner={}", modelId, name, type, scene, ver, owner);
        return new ModelInferDtos.ModelManageResult(modelId, ver, "ACTIVE");
    }

    /** UPDATE：更新模型元数据（version/scene/artifactRef/metrics/owner 可选，缺省保留原值） */
    private ModelInferDtos.ModelManageResult update(String modelId, String type, String scene, String version,
                                                    String artifactRef, Map<String, Object> metrics, String owner) {
        Model m = registry.get(modelId);
        if (m == null) {
            throw new BaiException(ModelInferErrorCodes.MODEL_NOT_FOUND,
                    "MDI_1004 模型不存在，无法更新：" + modelId);
        }
        String newVersion = isBlank(version) ? m.version() : version;
        Set<String> scenes = isBlank(scene) ? m.scenes() : Set.of(scene.toUpperCase(Locale.ROOT));
        String newType = isBlank(type) ? m.modelType() : type.toUpperCase(Locale.ROOT);
        String newArtifact = isBlank(artifactRef) ? m.artifactRef() : artifactRef;
        String newOwner = isBlank(owner) ? m.owner() : owner;
        Map<String, Object> newMetrics = metrics == null ? m.metrics() : metrics;
        put(new Model(modelId, newType, scenes, newVersion, m.requiredFeatures(), m.status(),
                newArtifact, newMetrics, newOwner));
        log.info("模型更新: modelId={}, version={}, scene={}, status={}", modelId, newVersion, scenes, m.status());
        return new ModelInferDtos.ModelManageResult(modelId, newVersion, m.status());
    }

    /** GRAY/PROMOTE/DISABLE 状态机迁移（状态不匹配 MDI_1005） */
    private ModelInferDtos.ModelManageResult setStatus(String modelId, String target, String requiredFrom) {
        Model m = registry.get(modelId);
        if (m == null) {
            throw new BaiException(ModelInferErrorCodes.MODEL_NOT_FOUND,
                    "MDI_1004 模型不存在：" + modelId);
        }
        if (!m.status().equals(requiredFrom)) {
            throw new BaiException(ModelInferErrorCodes.CONFLICT,
                    "MDI_1005 模型冲突：modelId " + modelId + " 当前状态 " + m.status()
                            + "，无法执行 " + requiredFrom + "→" + target + " 迁移");
        }
        put(new Model(m.modelId(), m.modelType(), m.scenes(), m.version(), m.requiredFeatures(), target,
                m.artifactRef(), m.metrics(), m.owner()));
        log.info("模型状态迁移: modelId={}, {}→{}", modelId, requiredFrom, target);
        return new ModelInferDtos.ModelManageResult(modelId, m.version(), target);
    }

    /** ROLLBACK：版本回退一级（次版本号 -1，下限 0）并恢复 ACTIVE */
    private ModelInferDtos.ModelManageResult rollback(String modelId) {
        Model m = registry.get(modelId);
        if (m == null) {
            throw new BaiException(ModelInferErrorCodes.MODEL_NOT_FOUND,
                    "MDI_1004 模型不存在，无法回滚：" + modelId);
        }
        String newVersion = rollVersion(m.version());
        put(new Model(m.modelId(), m.modelType(), m.scenes(), newVersion, m.requiredFeatures(), "ACTIVE",
                m.artifactRef(), m.metrics(), m.owner()));
        log.info("模型回滚: modelId={}, {}→{}", modelId, m.version(), newVersion);
        return new ModelInferDtos.ModelManageResult(modelId, newVersion, "ACTIVE");
    }

    /** 语义化版本回退一级：取末段数字 -1，下限 0；无数字段则追加 "-prev" */
    private static String rollVersion(String version) {
        String[] parts = version.split("\\.");
        if (parts.length >= 2) {
            try {
                int last = Integer.parseInt(parts[parts.length - 1]);
                parts[parts.length - 1] = String.valueOf(Math.max(0, last - 1));
                return String.join(".", parts);
            } catch (NumberFormatException ignored) {
                // 末段非数字，走兜底
            }
        }
        return version + "-prev";
    }

    /** modelId 归一化：大写、空白转连字符、仅保留字母/数字/下划线/连字符/中文、截断 32 位；空则返回 null */
    private static String normalizeModelId(String name) {
        String s = name.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "-")
                .replaceAll("[^A-Z0-9_\\-\\u4e00-\\u9fa5]", "");
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

    /** 按模型类型路由推理引擎（确定性仿真） */
    private ModelInferDtos.ScoreResult infer(Model model, Map<String, Object> features,
                                             String customerId, String requestId) {
        double score;
        String grade;
        double probability;
        List<ModelInferDtos.Explanation> explanation;
        switch (model.modelType()) {
            case "SCORECARD" -> {
                double income = numOf(features.get("annualIncome"));
                double liabRatio = numOf(features.get("assetLiabRatio"));
                double cashRatio = numOf(features.get("netCashRatio"));
                // 规则打分：基础 40 + 收入贡献(≤25) + 净现比贡献(≤20) − 负债率惩罚
                score = round1(40 + Math.min(25, income / 50) + Math.min(20, cashRatio * 25)
                        - Math.max(0, (liabRatio - 50) * 0.75));
                grade = gradeOf(score);
                probability = round4((100 - score) * 0.00072);
                explanation = List.of(
                        new ModelInferDtos.Explanation("assetLiabRatio", "负向", 0.28),
                        new ModelInferDtos.Explanation("netCashRatio", "正向", 0.21),
                        new ModelInferDtos.Explanation("annualIncome", "正向", 0.18));
            }
            case "ML" -> {
                score = 55 + Math.abs(features.hashCode()) % 30;
                grade = gradeOf(score);
                probability = round4((100 - score) / 100.0);
                List<String> keys = features.keySet().stream().limit(3).toList();
                List<ModelInferDtos.Explanation> exp = new ArrayList<>();
                double[] weights = {0.30, 0.25, 0.20};
                for (int i = 0; i < keys.size(); i++) {
                    String direction = Math.abs(keys.get(i).hashCode()) % 2 == 0 ? "正向" : "负向";
                    exp.add(new ModelInferDtos.Explanation(keys.get(i), direction, weights[i]));
                }
                explanation = List.copyOf(exp);
            }
            case "LLM" -> {
                score = 60 + Math.abs(requestId.hashCode()) % 25;
                grade = gradeOf(score);
                probability = round4((100 - score) / 100.0);
                explanation = List.of(new ModelInferDtos.Explanation("composite", "正向", 0.50));
            }
            default -> {
                // CLUSTER：分群场景输出分群标签与分群置信
                int cluster = Math.abs(String.valueOf(features).hashCode()
                        + (customerId == null ? 0 : customerId.hashCode())) % 5;
                score = 70.0 + cluster * 3;
                grade = "SEG-" + cluster;
                probability = round4(0.55 + cluster * 0.05);
                explanation = List.of(new ModelInferDtos.Explanation("clusterDistance", "特征", 0.40));
            }
        }
        double confidence = round4(0.85 + Math.abs(features.hashCode() + requestId.hashCode()) % 100 / 1000.0);
        boolean needReview = confidence < 0.90;
        String traceId = "trc-" + TS_DATE.format(LocalDateTime.now()) + "-"
                + String.format("%08x", (model.modelId() + requestId + seq.incrementAndGet()).hashCode());
        return new ModelInferDtos.ScoreResult(score, probability, grade, confidence, needReview,
                explanation, model.version(), traceId);
    }

    /** 等级分段：≥90 AAA、≥80 AA、≥65 BBB、≥55 BB、≥45 B、否则 CCC */
    private static String gradeOf(double score) {
        if (score >= 90) {
            return "AAA";
        }
        if (score >= 80) {
            return "AA";
        }
        if (score >= 65) {
            return "BBB";
        }
        if (score >= 55) {
            return "BB";
        }
        if (score >= 45) {
            return "B";
        }
        return "CCC";
    }

    private static double numOf(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        String s = String.valueOf(o);
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new BaiException(ModelInferErrorCodes.PARAM_INVALID,
                    "MDI_1001 参数非法：特征值须为数值，实际 '" + s + "'");
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
