package com.bai.recommend;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 智能推荐服务（提示词库 A-59）。MVP 为规则/协同打分仿真（确定性，无随机数），后续对接 AI 能力中台排序模型。
 * <p>处理链：加载客户画像与场景策略 → 按 algo 选择排序模型：
 * RULE 纯规则分、CF 规则分×0.6 + 协同热度×0.4、MAB 规则分 + 探索加成 0.05/√(1+曝光数)、
 * MODEL 为 CF/MAB/RULE 集成（0.5/0.3/0.2）→ 适当性过滤（高风险产品不向低风险承受客户推荐）→
 * 截取 topN 并为每项生成 reason → 模型不可用（context.modelDown=true）降级为规则推荐并标记 → 落审计、反馈回流。
 * <p>幂等：requestId + 场景 + 上下文哈希为键；同 requestId 不同要素视为策略冲突（RCM_1005）。
 */
@Service
public class RecommendService {

    private static final Logger log = LoggerFactory.getLogger(RecommendService.class);

    private static final Set<String> SCENES = Set.of("PRODUCT", "DISPOSAL", "SPEECH");
    private static final Set<String> ALGOS = Set.of("CF", "MAB", "RULE", "MODEL");
    private static final String STRATEGY_VERSION = "RCM-STR-2026-Q3";

    /** 候选项静态画像：base 规则先验分、risk 风险等级、popularity 协同热度（离线统计）、pulls 曝光数（MAB 探索项） */
    private record Candidate(String id, String name, double base, String risk, Set<String> tags,
                             double popularity, int pulls) {}

    private static final Map<String, List<Candidate>> POOLS = Map.of(
            "PRODUCT", List.of(
                    new Candidate("PROD-REVOLVING-LOAN", "循环额度经营贷", 0.80, "MEDIUM", Set.of("经营", "结算"), 0.82, 120),
                    new Candidate("PROD-CREDIT-CARD", "商务信用卡", 0.72, "LOW", Set.of("结算", "高频"), 0.78, 200),
                    new Candidate("PROD-DEPOSIT-PLUS", "结构性存款", 0.65, "LOW", Set.of("稳健", "理财"), 0.70, 90),
                    new Candidate("PROD-MORTGAGE", "个人住房贷款", 0.68, "MEDIUM", Set.of("住房", "长期"), 0.60, 150),
                    new Candidate("PROD-EQUIP-LEASE", "设备融资租赁", 0.60, "HIGH", Set.of("经营", "设备"), 0.45, 40)),
            "DISPOSAL", List.of(
                    new Candidate("DISP-AUCTION", "司法拍卖", 0.75, "MEDIUM", Set.of("处置", "变现"), 0.72, 60),
                    new Candidate("DISP-AMC", "资产包转让", 0.68, "MEDIUM", Set.of("处置", "批量"), 0.58, 45),
                    new Candidate("DISP-RESTRUCTURE", "债务重组", 0.62, "LOW", Set.of("处置", "重组"), 0.50, 30),
                    new Candidate("DISP-OFFSET", "以物抵债", 0.55, "HIGH", Set.of("处置", "抵债"), 0.38, 25)),
            "SPEECH", List.of(
                    new Candidate("SPH-URGE", "还款提醒", 0.78, "LOW", Set.of("催收", "提醒"), 0.80, 300),
                    new Candidate("SPH-PLAN", "还款方案沟通", 0.74, "LOW", Set.of("催收", "方案"), 0.66, 180),
                    new Candidate("SPH-GREETING", "开场问候", 0.70, "LOW", Set.of("服务", "开场"), 0.75, 260),
                    new Candidate("SPH-CARE", "客户关怀", 0.60, "LOW", Set.of("服务", "关怀"), 0.55, 140)));

    private final IdempotencyStore idempotency = new IdempotencyStore();

    /** 反馈动作（设计文档 4.4 / 表 5-1） */
    private static final Set<String> FEEDBACK_ACTIONS = Set.of("CLICK", "ACCEPT", "REJECT", "CONVERT");
    /** 策略动作（设计文档 4.5，兼容任务口径 ENABLE|DISABLE） */
    private static final Set<String> STRATEGY_ACTIONS = Set.of("CREATE", "UPDATE", "ACTIVATE", "ROLLBACK", "ENABLE", "DISABLE");
    /** 上下文超长阈值（设计文档 RCM_1006 上下文超长） */
    private static final int MAX_CONTEXT_CHARS = 8192;
    /** 默认生效策略：四算法加权融合（design doc 6.1 融合排序），algoUsed=HYBRID */
    private static final StrategyEntry DEFAULT_STRATEGY = new StrategyEntry(
            "RCM-STRATEGY-DEFAULT", null, List.of("CF", "MAB", "RULE", "MODEL"),
            Map.of("CF", 0.3, "MAB", 0.3, "RULE", 0.2, "MODEL", 0.2), Map.of(), 50, 1, "ACTIVE");
    private static final AtomicLong SEQ = new AtomicLong(0);

    /** 推荐记录留痕（设计文档 5.3 rcm_record 内存版），供 4.3 查询检索 */
    private record RecommendRecord(String recommendId, String requestId, String scene, String customerId,
                                   String strategyId, String algoUsed,
                                   List<RecommendDtos.RecommendRankedItem> items, long createdAt) {}

    /** 反馈登记（设计文档 5.3 rcm_feedback 内存版） */
    private record FeedbackRecord(String feedbackId, String recommendId, String itemId, String action,
                                  Map<String, Object> feedback, long createdAt) {}

    /** 策略（设计文档 5.3 rcm_strategy 内存版）：status 为 ACTIVE|DRAFT|DISABLED */
    private record StrategyEntry(String strategyId, String scene, List<String> algo,
                                 Map<String, Double> weights, Map<String, Object> rules, int topNMax,
                                 int version, String status) {}

    private final Map<String, RecommendRecord> records = new ConcurrentHashMap<>();
    private final Map<String, FeedbackRecord> feedbacks = new ConcurrentHashMap<>();
    private final Map<String, StrategyEntry> strategies = new ConcurrentHashMap<>();

    /** 推荐排序（幂等：同 requestId+场景+上下文哈希返回首次结果，缓存 24h） */
    public RecommendDtos.RecommendResult items(RecommendDtos.RecommendRequest req, String requestId) {
        validate(req, requestId);

        String hash = sha256(String.join("|",
                str(req.customerId()), str(req.scene()), str(req.algo()), String.valueOf(req.topN()),
                String.valueOf(req.context())));
        String prevHash = idempotency.get("items-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(RecommendErrorCodes.DUPLICATE_CONFLICT,
                        "RCM_1005 重复推荐/策略冲突，请使用原 requestId 查询首次推荐结果");
            }
            return idempotency.get("items", requestId);
        }

        RecommendDtos.RecommendResult result = recommend(req);
        idempotency.put("items-hash", requestId, hash);
        idempotency.put("items", requestId, result);
        log.info("推荐完成: requestId={}, customerId={}, scene={}, algoUsed={}, items={}, strategyVersion={}",
                requestId, result.customerId(), result.scene(), result.algoUsed(),
                result.items().size(), result.strategyVersion());
        return result;
    }

    /** 推荐执行（4.2）：场景/上下文/topN 必填，strategyId 缺省用当前生效策略；加权融合打分，输出 TopN+理由并留痕供查询 */
    public RecommendDtos.RecommendExecuteResult recommend(RecommendDtos.RecommendExecuteRequest req, String requestId) {
        validateRecommend(req);

        String hash = sha256(String.join("|",
                str(req.scene()), String.valueOf(req.context()), String.valueOf(req.topN()), str(req.strategyId())));
        String prevHash = idempotency.get("recommend-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(RecommendErrorCodes.DUPLICATE_CONFLICT,
                        "RCM_1005 重复推荐/策略冲突，请使用原 requestId 查询首次推荐结果");
            }
            return idempotency.get("recommend", requestId);
        }

        long start = System.nanoTime();
        String scene = req.scene();
        Map<String, Object> ctx = req.context() == null ? Map.of() : req.context();
        String customerId = customerOf(ctx);
        String pref = str(ctx.getOrDefault("riskPreference", "平衡"));
        String prefLevel = normalizePref(pref);
        double activity = num(ctx.get("activity90d"), 0.5);
        int overdue = (int) num(ctx.get("overdueCount"), 0);
        StrategyEntry strategy = resolveStrategy(req.strategyId(), scene);

        List<RecommendDtos.RecommendRankedItem> scored = new ArrayList<>(POOLS.get(scene).size());
        for (Candidate c : POOLS.get(scene)) {
            // 适当性管理：高风险产品不向低风险承受客户推荐
            if ("HIGH".equals(c.risk()) && "LOW".equals(prefLevel)) {
                continue;
            }
            double rule = ruleScore(c, prefLevel, activity, overdue, scene);
            double cf = 0.6 * rule + 0.4 * c.popularity();
            double mab = rule + 0.05 / Math.sqrt(1 + c.pulls());
            double model = 0.5 * cf + 0.3 * mab + 0.2 * rule;
            double score = 0.0;
            for (String algo : strategy.algo()) {
                double s = switch (algo) {
                    case "CF" -> cf;
                    case "MAB" -> mab;
                    case "MODEL" -> model;
                    default -> rule; // RULE
                };
                score += strategy.weights().getOrDefault(algo, 0.0) * s;
            }
            scored.add(new RecommendDtos.RecommendRankedItem(c.id(), c.name(), 0,
                    Math.round(score * 100.0) / 100.0, buildReason(c, pref, activity, overdue, scene)));
        }
        scored.sort(Comparator.comparingDouble(RecommendDtos.RecommendRankedItem::score).reversed()
                .thenComparing(RecommendDtos.RecommendRankedItem::itemId));
        List<RecommendDtos.RecommendRankedItem> top = new ArrayList<>(Math.min(req.topN(), scored.size()));
        for (int i = 0; i < scored.size() && i < req.topN(); i++) {
            RecommendDtos.RecommendRankedItem item = scored.get(i);
            top.add(new RecommendDtos.RecommendRankedItem(item.itemId(), item.name(), i + 1, item.score(), item.reason()));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        String recommendId = nextId("RCM");
        String algoUsed = strategy.algo().size() == 1 ? strategy.algo().get(0) : "HYBRID";
        RecommendDtos.RecommendExecuteResult result = new RecommendDtos.RecommendExecuteResult(
                recommendId, scene, strategy.strategyId(), top, algoUsed, elapsedMs);
        records.put(recommendId, new RecommendRecord(recommendId, requestId, scene, customerId,
                strategy.strategyId(), algoUsed, top, System.currentTimeMillis()));
        idempotency.put("recommend-hash", requestId, hash);
        idempotency.put("recommend", requestId, result);
        log.info("推荐执行: requestId={}, recommendId={}, customerId={}, scene={}, algoUsed={}, strategyId={}, items={}, elapsedMs={}",
                requestId, recommendId, customerId, scene, algoUsed, strategy.strategyId(), top.size(), elapsedMs);
        return result;
    }

    /** 推荐查询（4.3）：recommendId 精确查询或按 scene/customerId/algoUsed 过滤分页；无记录返回 RCM_1004 */
    public RecommendDtos.RecommendQueryResult query(RecommendDtos.RecommendQueryRequest req, String requestId) {
        if (req.recommendId() != null && !req.recommendId().isBlank()) {
            RecommendRecord rec = records.get(req.recommendId());
            if (rec == null) {
                throw new BaiException(RecommendErrorCodes.NOT_FOUND,
                        "RCM_1004 推荐记录不存在：recommendId=" + req.recommendId());
            }
            log.info("推荐查询: requestId={}, recommendId={}, hit=1", requestId, req.recommendId());
            return new RecommendDtos.RecommendQueryResult(1, List.of(toRecordItem(rec)));
        }

        List<RecommendRecord> filtered = records.values().stream()
                .filter(rec -> req.scene() == null || req.scene().isBlank() || req.scene().equals(rec.scene()))
                .filter(rec -> req.customerId() == null || req.customerId().isBlank() || req.customerId().equals(rec.customerId()))
                .filter(rec -> req.algoUsed() == null || req.algoUsed().isBlank() || req.algoUsed().equals(rec.algoUsed()))
                .sorted(Comparator.comparingLong(RecommendRecord::createdAt).reversed())
                .toList();
        if (filtered.isEmpty()) {
            throw new BaiException(RecommendErrorCodes.NOT_FOUND, "RCM_1004 无匹配推荐记录，请调整查询条件");
        }
        int page = req.page() == null || req.page() < 1 ? 1 : req.page();
        int size = req.size() == null || req.size() < 1 ? 20 : Math.min(req.size(), 100);
        List<RecommendDtos.RecommendRecordItem> items = filtered.stream()
                .skip((long) (page - 1) * size).limit(size).map(this::toRecordItem).toList();
        log.info("推荐查询: requestId={}, scene={}, customerId={}, algoUsed={}, total={}, page={}, size={}",
                requestId, req.scene(), req.customerId(), req.algoUsed(), filtered.size(), page, size);
        return new RecommendDtos.RecommendQueryResult(filtered.size(), items);
    }

    /** 推荐反馈（4.4）：CLICK|ACCEPT|REJECT|CONVERT 受理登记，返回 feedbackId/status */
    public RecommendDtos.FeedbackResult feedback(RecommendDtos.FeedbackRequest req, String requestId) {
        validateFeedback(req);

        String hash = sha256(String.join("|",
                str(req.recommendId()), str(req.itemId()), str(req.action()), String.valueOf(req.feedback())));
        String prevHash = idempotency.get("feedback-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(RecommendErrorCodes.DUPLICATE_CONFLICT,
                        "RCM_1005 重复反馈/冲突，请使用原 requestId 查询首次受理结果");
            }
            return idempotency.get("feedback", requestId);
        }

        String feedbackId = nextId("FBD");
        feedbacks.put(feedbackId, new FeedbackRecord(feedbackId, req.recommendId(), req.itemId(), req.action(),
                req.feedback() == null ? Map.of() : req.feedback(), System.currentTimeMillis()));
        RecommendDtos.FeedbackResult result = new RecommendDtos.FeedbackResult(feedbackId, "RECEIVED");
        idempotency.put("feedback-hash", requestId, hash);
        idempotency.put("feedback", requestId, result);
        log.info("推荐反馈: requestId={}, feedbackId={}, recommendId={}, itemId={}, action={}, feedback={}",
                requestId, feedbackId, req.recommendId(), req.itemId(), req.action(), req.feedback());
        return result;
    }

    /** 策略管理（4.5）：CREATE/UPDATE/ACTIVATE/ROLLBACK（兼容 ENABLE/DISABLE），内存存储，action 非法返回 RCM_1007 */
    public RecommendDtos.StrategyResult strategy(RecommendDtos.StrategyRequest req, String requestId) {
        validateStrategyAction(req);

        String hash = sha256(String.join("|", str(req.action()), String.valueOf(req.strategy())));
        String prevHash = idempotency.get("strategy-hash", requestId);
        if (prevHash != null) {
            if (!prevHash.equals(hash)) {
                throw new BaiException(RecommendErrorCodes.DUPLICATE_CONFLICT,
                        "RCM_1005 重复策略变更/冲突，请使用原 requestId 查询首次变更结果");
            }
            return idempotency.get("strategy", requestId);
        }

        RecommendDtos.StrategyResult result = switch (req.action()) {
            case "CREATE" -> createStrategy(req.strategy());
            case "UPDATE" -> updateStrategy(req.strategy());
            case "ACTIVATE", "ENABLE" -> changeStrategyStatus(req.strategy(), "ACTIVE");
            default -> changeStrategyStatus(req.strategy(), "DISABLED"); // ROLLBACK/DISABLE（MVP 回滚=停用当前策略）
        };
        idempotency.put("strategy-hash", requestId, hash);
        idempotency.put("strategy", requestId, result);
        log.info("策略管理: requestId={}, action={}, strategyId={}, version={}, status={}",
                requestId, req.action(), result.strategyId(), result.version(), result.status());
        return result;
    }

    private void validateRecommend(RecommendDtos.RecommendExecuteRequest req) {
        if (req == null) {
            throw new BaiException(RecommendErrorCodes.PARAM_INVALID, "RCM_1001 参数缺失：请求体必填");
        }
        if (req.scene() == null || !SCENES.contains(req.scene())) {
            throw new BaiException(RecommendErrorCodes.ELEMENT_ILLEGAL,
                    "RCM_1007 推荐场景非法：scene 须为 PRODUCT/DISPOSAL/SPEECH");
        }
        if (req.context() == null || req.context().isEmpty()) {
            throw new BaiException(RecommendErrorCodes.PARAM_INVALID, "RCM_1001 参数缺失：context 场景上下文必填");
        }
        if (req.context().toString().length() > MAX_CONTEXT_CHARS) {
            throw new BaiException(RecommendErrorCodes.CONTEXT_TOO_LONG,
                    "RCM_1006 上下文超长：context 超过 " + MAX_CONTEXT_CHARS + " 字符，请缩减输入");
        }
        if (req.topN() == null || req.topN() < 1 || req.topN() > 50) {
            throw new BaiException(RecommendErrorCodes.PARAM_INVALID, "RCM_1001 参数非法：topN 须在 1~50 内");
        }
    }

    private void validateFeedback(RecommendDtos.FeedbackRequest req) {
        if (req == null || req.recommendId() == null || req.recommendId().isBlank()) {
            throw new BaiException(RecommendErrorCodes.PARAM_INVALID, "RCM_1001 参数缺失：recommendId 必填");
        }
        if (!records.containsKey(req.recommendId())) {
            throw new BaiException(RecommendErrorCodes.NOT_FOUND,
                    "RCM_1004 推荐记录不存在：recommendId=" + req.recommendId());
        }
        if (req.itemId() == null || req.itemId().isBlank()) {
            throw new BaiException(RecommendErrorCodes.PARAM_INVALID, "RCM_1001 参数缺失：itemId 必填");
        }
        if (req.action() == null || !FEEDBACK_ACTIONS.contains(req.action())) {
            throw new BaiException(RecommendErrorCodes.ELEMENT_ILLEGAL,
                    "RCM_1007 反馈动作非法：action 须为 CLICK/ACCEPT/REJECT/CONVERT");
        }
    }

    private void validateStrategyAction(RecommendDtos.StrategyRequest req) {
        if (req == null || req.action() == null || req.action().isBlank()) {
            throw new BaiException(RecommendErrorCodes.PARAM_INVALID, "RCM_1001 参数缺失：action 必填");
        }
        if (!STRATEGY_ACTIONS.contains(req.action())) {
            throw new BaiException(RecommendErrorCodes.ELEMENT_ILLEGAL,
                    "RCM_1007 策略动作非法：action 须为 CREATE/UPDATE/ACTIVATE/ROLLBACK（兼容 ENABLE/DISABLE）");
        }
        if (req.strategy() == null) {
            throw new BaiException(RecommendErrorCodes.PARAM_INVALID, "RCM_1001 参数缺失：strategy 策略配置必填");
        }
    }

    private RecommendDtos.StrategyResult createStrategy(RecommendDtos.StrategyConfig cfg) {
        validateStrategyConfig(cfg);
        String strategyId = nextId("STR");
        strategies.put(strategyId, new StrategyEntry(strategyId, cfg.scene(), List.copyOf(cfg.algo()),
                Map.copyOf(cfg.weights()), cfg.rules() == null ? Map.of() : cfg.rules(), cfg.topNMax(), 1, "DRAFT"));
        return new RecommendDtos.StrategyResult(strategyId, 1, "DRAFT");
    }

    private RecommendDtos.StrategyResult updateStrategy(RecommendDtos.StrategyConfig cfg) {
        validateStrategyConfig(cfg);
        StrategyEntry cur = requireStrategy(cfg.strategyId());
        StrategyEntry updated = new StrategyEntry(cur.strategyId(), cfg.scene(), List.copyOf(cfg.algo()),
                Map.copyOf(cfg.weights()), cfg.rules() == null ? Map.of() : cfg.rules(), cfg.topNMax(),
                cur.version() + 1, cur.status());
        strategies.put(cur.strategyId(), updated);
        return new RecommendDtos.StrategyResult(cur.strategyId(), updated.version(), updated.status());
    }

    private RecommendDtos.StrategyResult changeStrategyStatus(RecommendDtos.StrategyConfig cfg, String status) {
        StrategyEntry cur = requireStrategy(cfg.strategyId());
        StrategyEntry updated = new StrategyEntry(cur.strategyId(), cur.scene(), cur.algo(), cur.weights(),
                cur.rules(), cur.topNMax(), cur.version(), status);
        strategies.put(cur.strategyId(), updated);
        return new RecommendDtos.StrategyResult(cur.strategyId(), updated.version(), status);
    }

    private void validateStrategyConfig(RecommendDtos.StrategyConfig cfg) {
        if (cfg.scene() == null || !SCENES.contains(cfg.scene())) {
            throw new BaiException(RecommendErrorCodes.ELEMENT_ILLEGAL,
                    "RCM_1007 策略场景非法：scene 须为 PRODUCT/DISPOSAL/SPEECH");
        }
        if (cfg.algo() == null || cfg.algo().isEmpty() || !ALGOS.containsAll(cfg.algo())) {
            throw new BaiException(RecommendErrorCodes.ELEMENT_ILLEGAL,
                    "RCM_1007 策略算法非法：algo 须为 CF/MAB/RULE/MODEL 的非空子集");
        }
        if (cfg.weights() == null || cfg.weights().isEmpty()) {
            throw new BaiException(RecommendErrorCodes.PARAM_INVALID, "RCM_1001 参数缺失：weights 融合权重必填");
        }
        if (cfg.topNMax() == null || cfg.topNMax() < 1 || cfg.topNMax() > 50) {
            throw new BaiException(RecommendErrorCodes.PARAM_INVALID, "RCM_1001 参数非法：topNMax 须在 1~50 内");
        }
    }

    private StrategyEntry requireStrategy(String strategyId) {
        if (strategyId == null || strategyId.isBlank()) {
            throw new BaiException(RecommendErrorCodes.PARAM_INVALID, "RCM_1001 参数缺失：strategy.strategyId 必填");
        }
        StrategyEntry s = strategies.get(strategyId);
        if (s == null) {
            throw new BaiException(RecommendErrorCodes.NOT_FOUND, "RCM_1004 策略不存在：strategyId=" + strategyId);
        }
        return s;
    }

    /** 策略解析：缺省返回默认生效策略；指定 strategyId 须存在且 ACTIVE，且场景匹配 */
    private StrategyEntry resolveStrategy(String strategyId, String scene) {
        if (strategyId == null || strategyId.isBlank()) {
            return DEFAULT_STRATEGY;
        }
        StrategyEntry s = strategies.get(strategyId);
        if (s == null || !"ACTIVE".equals(s.status())) {
            throw new BaiException(RecommendErrorCodes.NOT_FOUND,
                    "RCM_1004 策略不存在或未生效：strategyId=" + strategyId);
        }
        if (s.scene() != null && !s.scene().equals(scene)) {
            throw new BaiException(RecommendErrorCodes.NOT_FOUND,
                    "RCM_1004 策略不适用于场景：" + scene + "（策略场景 " + s.scene() + "）");
        }
        return s;
    }

    /** 客户标识提取：优先 context.customerId，其次 context.customer.customerId，缺省按上下文哈希生成伪标识 */
    private static String customerOf(Map<String, Object> ctx) {
        Object cid = ctx.get("customerId");
        if (cid != null && !str(cid).isBlank()) {
            return str(cid);
        }
        Object customer = ctx.get("customer");
        if (customer instanceof Map<?, ?> m) {
            Object id = m.get("customerId");
            if (id != null && !str(id).isBlank()) {
                return str(id);
            }
        }
        return "CUST-" + sha256(String.valueOf(ctx)).substring(0, 12);
    }

    private RecommendDtos.RecommendRecordItem toRecordItem(RecommendRecord rec) {
        return new RecommendDtos.RecommendRecordItem(rec.recommendId(), rec.scene(), rec.items(),
                rec.algoUsed(), rec.strategyId(), Instant.ofEpochMilli(rec.createdAt()).toString());
    }

    /** 标识生成：前缀 + 毫秒时间戳十六进制 + 递增序号（≤32 位） */
    private static String nextId(String prefix) {
        return prefix + Long.toHexString(System.currentTimeMillis()) + SEQ.incrementAndGet();
    }

    private void validate(RecommendDtos.RecommendRequest req, String requestId) {
        if (req == null || req.customerId() == null || req.customerId().isBlank()) {
            throw new BaiException(RecommendErrorCodes.NOT_FOUND, "RCM_1004 客户不存在：customerId 必填");
        }
        if (req.scene() == null || !SCENES.contains(req.scene())) {
            throw new BaiException(RecommendErrorCodes.ELEMENT_ILLEGAL,
                    "RCM_1007 推荐场景非法：scene 须为 PRODUCT/DISPOSAL/SPEECH");
        }
        if (req.algo() == null || !ALGOS.contains(req.algo())) {
            throw new BaiException(RecommendErrorCodes.ELEMENT_ILLEGAL,
                    "RCM_1007 推荐算法非法：algo 须为 CF/MAB/RULE/MODEL");
        }
        if (req.topN() == null || req.topN() < 1 || req.topN() > 50) {
            throw new BaiException(RecommendErrorCodes.PARAM_INVALID, "RCM_1001 参数非法：topN 须在 1~50 内");
        }
    }

    /** 规则/协同打分 + 适当性过滤 + topN 截取 + 理由生成 */
    private RecommendDtos.RecommendResult recommend(RecommendDtos.RecommendRequest req) {
        Map<String, Object> ctx = req.context() == null ? Map.of() : req.context();
        String pref = str(ctx.getOrDefault("riskPreference", "平衡"));
        String prefLevel = normalizePref(pref);
        double activity = num(ctx.get("activity90d"), 0.5);
        int overdue = (int) num(ctx.get("overdueCount"), 0);
        boolean modelDown = Boolean.parseBoolean(str(ctx.get("modelDown")));

        String algoUsed = req.algo();
        String strategyVersion = STRATEGY_VERSION;
        String reasonPrefix = "";
        if ("MODEL".equals(algoUsed) && modelDown) {
            // 模型不可用降级为规则推荐并在结果中标记
            algoUsed = "RULE";
            strategyVersion = STRATEGY_VERSION + "-DEGRADED";
            reasonPrefix = "（模型降级·规则推荐）";
        }

        List<Candidate> pool = POOLS.get(req.scene());
        List<RecommendDtos.RecommendItem> scored = new ArrayList<>(pool.size());
        for (Candidate c : pool) {
            // 适当性管理：高风险产品不得向低风险承受客户推荐
            if ("HIGH".equals(c.risk()) && "LOW".equals(prefLevel)) {
                continue;
            }
            double rule = ruleScore(c, prefLevel, activity, overdue, req.scene());
            double score = switch (algoUsed) {
                case "CF" -> 0.6 * rule + 0.4 * c.popularity();
                case "MAB" -> rule + 0.05 / Math.sqrt(1 + c.pulls());
                case "MODEL" -> {
                    double cf = 0.6 * rule + 0.4 * c.popularity();
                    double mab = rule + 0.05 / Math.sqrt(1 + c.pulls());
                    yield 0.5 * cf + 0.3 * mab + 0.2 * rule;
                }
                default -> rule; // RULE
            };
            scored.add(new RecommendDtos.RecommendItem(c.id(), c.name(),
                    Math.round(score * 100.0) / 100.0, reasonPrefix + buildReason(c, pref, activity, overdue, req.scene())));
        }
        scored.sort(Comparator.comparingDouble(RecommendDtos.RecommendItem::score).reversed()
                .thenComparing(RecommendDtos.RecommendItem::itemId));
        List<RecommendDtos.RecommendItem> top = scored.stream().limit(req.topN()).toList();
        return new RecommendDtos.RecommendResult("SUCCESS", req.customerId(), req.scene(), top, algoUsed, strategyVersion);
    }

    /** 规则打分：先验分 + 风险偏好拟合 + 结算/经营活跃度拟合 + 场景事件拟合（全确定性） */
    private double ruleScore(Candidate c, String prefLevel, double activity, int overdue, String scene) {
        double score = c.base();
        if ("LOW".equals(prefLevel) && "LOW".equals(c.risk())) {
            score += 0.05;
        } else if ("MEDIUM".equals(prefLevel) && "MEDIUM".equals(c.risk())) {
            score += 0.03;
        } else if ("HIGH".equals(prefLevel) && "HIGH".equals(c.risk())) {
            score += 0.06;
        }
        if (c.tags().contains("结算") || c.tags().contains("经营")) {
            score += 0.10 * activity;
        }
        if (overdue > 0) {
            if ("DISPOSAL".equals(scene) && c.tags().contains("处置")) {
                score += 0.08;
            }
            if ("SPEECH".equals(scene) && c.tags().contains("催收")) {
                score += 0.10;
            }
        }
        return score;
    }

    /** 理由生成：基于画像与上下文的主导因子描述（每项必须含 reason） */
    private String buildReason(Candidate c, String pref, double activity, int overdue, String scene) {
        StringBuilder sb = new StringBuilder();
        if (overdue > 0 && ("DISPOSAL".equals(scene) || ("SPEECH".equals(scene) && c.tags().contains("催收")))) {
            sb.append("匹配逾期处置/催收场景");
        } else if ((c.tags().contains("结算") || c.tags().contains("经营")) && activity >= 0.6) {
            sb.append("匹配其高频结算/经营场景（近 90 天活跃度 ").append(activity).append("）");
        } else if ("LOW".equals(normalizePref(pref)) && "LOW".equals(c.risk())) {
            sb.append("匹配").append(pref).append("型风险偏好");
        } else {
            sb.append("按客户画像与场景策略综合评分推荐");
        }
        return sb.toString();
    }

    /** 风险承受等级归一：稳健/保守 → LOW；激进 → HIGH；其余 → MEDIUM */
    private static String normalizePref(String pref) {
        String p = pref == null ? "" : pref.toLowerCase(Locale.ROOT);
        if (p.contains("稳健") || p.contains("保守") || p.contains("low")) {
            return "LOW";
        }
        if (p.contains("激进") || p.contains("high")) {
            return "HIGH";
        }
        return "MEDIUM";
    }

    private static double num(Object v, double def) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? def : Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(s.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
