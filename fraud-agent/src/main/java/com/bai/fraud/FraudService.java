package com.bai.fraud;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 反欺诈服务（BIA-RSK-042）。规则+图+模型融合的实时欺诈决策，风控中台第一道实时闸口。
 * <p>处理链：特征加载（申请/关联/交易/身份四类）→ 规则执行（黑白灰名单/评分卡/决策表，黑名单即时 REJECT）
 * → 图计算（团伙识别与 n 度关联穿透）→ 模型推理（GBDT 0-100 欺诈分）→ 级联融合决策（规则→图→模型逐级过滤）
 * → 决策解释 → 案例留痕（特征指纹、三层结论、规则/模型版本快照）。
 * <p>MVP 为确定性仿真（实体哈希驱动三层信号），生产图谱来自 graph-build-agent/graph-penetrate-agent、
 * 模型推理由 model-infer-agent 承担；降级时规则加图兜底、拒绝转人工防欺诈绕过。
 */
@Service
public class FraudService {

    private static final Logger log = LoggerFactory.getLogger(FraudService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final List<String> SCENES = List.of("PRE_LOAN", "TRANS_MONITOR", "APPLY_GUARD");
    private static final List<String> ENTITY_TYPES = List.of("PERSON", "ORG");
    private static final int MAX_BATCH = 100;                 // 批量筛查上限，超长 FRD_1006
    private static final String RULE_VERSION = "FRD-RULE-2026.10";
    private static final String MODEL_VERSION = "FRD-GBDT-v3.2";
    /** 黑名单（密文/摘要存储仿真，命中即 REJECT） */
    private static final Set<String> BLACKLIST = Set.of("E-BLACK-0001", "E-BLACK-0002", "E-BLACK-0003");

    private final IdempotencyStore idempotency = new IdempotencyStore();

    /** 决策案例：caseId → 案例（供 /explain、/feedback） */
    private final Map<String, Case> cases = new ConcurrentHashMap<>();
    /** 批次：batchId → 案例列表 */
    private final Map<String, List<String>> batches = new ConcurrentHashMap<>();
    /** 人工标注样本（按月回流重训） */
    private final Map<String, String> feedbacks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    private static final class Case {
        final String caseId;
        final String scene;
        final String entityType;
        final String entityId;
        final String txnRef;
        final FraudDtos.CheckResult result;
        final String decidedAt;

        Case(String caseId, String scene, String entityType, String entityId, String txnRef,
             FraudDtos.CheckResult result, String decidedAt) {
            this.caseId = caseId;
            this.scene = scene;
            this.entityType = entityType;
            this.entityId = entityId;
            this.txnRef = txnRef;
            this.result = result;
            this.decidedAt = decidedAt;
        }
    }

    /** 实时欺诈决策（幂等键 requestId+entity；交易监控场景按 txnRef 幂等） */
    public FraudDtos.CheckResult check(Map<String, Object> body, String requestId) {
        if (body == null || isBlank(body.get("scene")) || body.get("entity") == null) {
            throw new BaiException(FraudErrorCodes.PARAM_INVALID,
                    "FRD_1001 参数缺失：scene 与 entity{entityType,entityId} 必填");
        }
        String scene = str(body.get("scene")).toUpperCase();
        if (!SCENES.contains(scene)) {
            throw new BaiException(FraudErrorCodes.SCENE_INVALID,
                    "FRD_1007 场景不支持：" + scene + "（仅支持 PRE_LOAN/TRANS_MONITOR/APPLY_GUARD）");
        }
        Map<String, Object> entity = cast(body.get("entity"));
        if (entity == null || isBlank(entity.get("entityId"))) {
            throw new BaiException(FraudErrorCodes.PARAM_INVALID,
                    "FRD_1001 参数非法：entity 须为 {entityType:PERSON/ORG, entityId, deviceId?}");
        }
        String entityType = strOr(entity.get("entityType"), "PERSON").toUpperCase();
        if (!ENTITY_TYPES.contains(entityType)) {
            throw new BaiException(FraudErrorCodes.PARAM_INVALID,
                    "FRD_1001 参数非法：entityType 仅支持 PERSON/ORG");
        }
        String entityId = str(entity.get("entityId"));
        String txnRef = strOrNull(body.get("txnRef"));
        if ("TRANS_MONITOR".equals(scene) && txnRef == null) {
            throw new BaiException(FraudErrorCodes.PARAM_INVALID,
                    "FRD_1001 参数缺失：交易监控场景 txnRef（交易引用号）必填");
        }
        String idemKey = "TRANS_MONITOR".equals(scene) ? "check:" + txnRef : "check:" + entityId;
        FraudDtos.CheckResult cached = idempotency.get(idemKey.split(":", 2)[0],
                requestId + ":" + idemKey.split(":", 2)[1]);
        if (cached != null) {
            return cached;
        }
        long begin = System.nanoTime();
        FraudDtos.CheckResult result = decide(scene, entityType, entityId, txnRef,
                (int) ((System.nanoTime() - begin) / 1_000_000));
        idempotency.put(idemKey.split(":", 2)[0], requestId + ":" + idemKey.split(":", 2)[1], result);
        return result;
    }

    /** 批量筛查（异步，MVP 同步计算并登记批次；单批 ≤100 条） */
    public FraudDtos.BatchResult batchCheck(Map<String, Object> body, String requestId) {
        if (body == null || !(body.get("items") instanceof List<?> items) || items.isEmpty()) {
            throw new BaiException(FraudErrorCodes.PARAM_INVALID,
                    "FRD_1001 参数缺失：items（scene+entity 列表）必填");
        }
        if (items.size() > MAX_BATCH) {
            throw new BaiException(FraudErrorCodes.TOO_LONG,
                    "FRD_1006 批量超长：" + items.size() + " 条（≤" + MAX_BATCH + " 条/批），请缩减输入");
        }
        String batchId = "FB" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        List<String> caseIds = new ArrayList<>();
        List<FraudDtos.CheckResult> results = new ArrayList<>();
        int idx = 0;
        for (Object o : items) {
            Map<String, Object> item = cast(o);
            if (item == null) {
                throw new BaiException(FraudErrorCodes.PARAM_INVALID,
                        "FRD_1001 参数非法：items[" + idx + "] 须为 {scene, entity, txnRef?}");
            }
            Map<String, Object> withReq = new java.util.LinkedHashMap<>(item);
            withReq.putIfAbsent("requestId", requestId + "-" + idx++);
            results.add(check(withReq, requestId + "-" + caseIds.size()));
            caseIds.add(results.get(results.size() - 1).caseId());
        }
        batches.put(batchId, caseIds);
        log.info("批量欺诈筛查: batchId={}, total={}", batchId, results.size());
        return new FraudDtos.BatchResult(batchId, results.size(), results);
    }

    /** 决策解释：命中明细 + 可读理由 + 规则/模型版本快照 */
    public FraudDtos.ExplainResult explain(String caseId) {
        Case c = caseOf(caseId);
        return new FraudDtos.ExplainResult(c.caseId, c.result.decision(), c.result.fraudScore(),
                c.result.riskLevel(), c.result.hits(), c.result.reason(), RULE_VERSION, MODEL_VERSION, c.decidedAt);
    }

    /** 人工标注样本回流（FRAUD/NORMAL，按月增量训练防污染：同案例仅可标注一次） */
    public FraudDtos.FeedbackResult feedback(Map<String, Object> body) {
        if (body == null || isBlank(body.get("caseId")) || isBlank(body.get("label"))) {
            throw new BaiException(FraudErrorCodes.PARAM_INVALID, "FRD_1001 参数缺失：caseId/label 必填");
        }
        String caseId = str(body.get("caseId"));
        String label = str(body.get("label")).toUpperCase();
        if (!List.of("FRAUD", "NORMAL").contains(label)) {
            throw new BaiException(FraudErrorCodes.PARAM_INVALID, "FRD_1001 参数非法：label 仅支持 FRAUD/NORMAL");
        }
        Case c = caseOf(caseId);
        if (feedbacks.containsKey(caseId)) {
            throw new BaiException(FraudErrorCodes.CONFLICT,
                    "FRD_1005 重复提交：案例 " + caseId + " 已标注 " + feedbacks.get(caseId));
        }
        feedbacks.put(caseId, label);
        log.info("样本标注回流: caseId={}, label={}", caseId, label);
        return new FraudDtos.FeedbackResult(caseId, label, "ACCEPTED", LocalDateTime.now().toString());
    }

    /** 级联融合决策（规则→图→模型逐级过滤，确定性） */
    private FraudDtos.CheckResult decide(String scene, String entityType, String entityId, String txnRef, int elapsedMs) {
        List<FraudDtos.Hit> hits = new ArrayList<>();
        String fingerprint = UUID.nameUUIDFromBytes((scene + "|" + entityType + "|" + entityId + "|" + txnRef)
                .getBytes()).toString();
        int h = fingerprint.hashCode();
        int score = 0;
        // 第 1 层：规则执行（黑白灰名单/评分卡）
        if (BLACKLIST.contains(entityId)) {
            hits.add(new FraudDtos.Hit("RULE", "黑名单即时命中", 100));
            return record(scene, entityType, entityId, txnRef, hits, 99, "REJECT",
                    "黑名单命中，即时拒绝", elapsedMs);
        }
        if (Math.abs(h % 10) >= 8) {
            hits.add(new FraudDtos.Hit("RULE", "多头借贷数≥5家", 25));
            score += 25;
        }
        if (Math.abs((h >> 4) % 13) >= 12) {
            hits.add(new FraudDtos.Hit("RULE", "关联黑名单数≥2", 20));
            score += 20;
        }
        if ("TRANS_MONITOR".equals(scene) && Math.abs((h >> 8) % 11) >= 9) {
            hits.add(new FraudDtos.Hit("RULE", "交易金额频次异常", 15));
            score += 15;
        }
        // 第 2 层：图计算（团伙识别与 n 度关联穿透）
        int gangSize = 2 + Math.abs((h >> 12) % 18);
        if (gangSize >= 10) {
            hits.add(new FraudDtos.Hit("GRAPH", "关联团伙规模" + gangSize + "人", 40));
            score += 40;
        }
        // 第 3 层：模型推理（GBDT 0-100 欺诈分）
        int modelScore = 10 + Math.abs((h >> 16) % 91);
        hits.add(new FraudDtos.Hit("MODEL", "欺诈评分模型（" + MODEL_VERSION + "）", modelScore));
        int fraudScore = Math.min(99, score + modelScore / 3);
        // 融合决策：黑名单 REJECT；团伙高危或模型高分 MANUAL；综合中风险 ESCALATE；其余 PASS
        String decision;
        String reason;
        if (gangSize >= 10 || fraudScore >= 75) {
            decision = "MANUAL";
            reason = gangSize >= 10 ? "图团伙高危叠加模型评分 " + fraudScore + "，转人工复核"
                    : "模型高分（" + fraudScore + "），转人工复核";
        } else if (fraudScore >= 50) {
            decision = "ESCALATE";
            reason = "综合中风险（评分 " + fraudScore + "），升级风控复核";
        } else {
            decision = "PASS";
            reason = "三层信号综合低风险（评分 " + fraudScore + "），准入通过";
        }
        return record(scene, entityType, entityId, txnRef, hits, fraudScore, decision, reason, elapsedMs);
    }

    private FraudDtos.CheckResult record(String scene, String entityType, String entityId, String txnRef,
                                         List<FraudDtos.Hit> hits, int fraudScore, String decision,
                                         String reason, int elapsedMs) {
        String caseId = "FRC" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        String riskLevel = fraudScore >= 85 ? "CRITICAL" : fraudScore >= 70 ? "HIGH"
                : fraudScore >= 50 ? "MEDIUM" : "LOW";
        FraudDtos.CheckResult result = new FraudDtos.CheckResult(caseId, scene, entityId, decision,
                fraudScore, riskLevel, List.copyOf(hits), reason, elapsedMs);
        cases.put(caseId, new Case(caseId, scene, entityType, entityId, txnRef, result,
                LocalDateTime.now().toString()));
        log.info("欺诈决策: caseId={}, scene={}, entityId={}, decision={}, score={}, riskLevel={}",
                caseId, scene, entityId, decision, fraudScore, riskLevel);
        return result;
    }

    private Case caseOf(String caseId) {
        Case c = cases.get(caseId);
        if (c == null) {
            throw new BaiException(FraudErrorCodes.NOT_FOUND, "FRD_1004 决策案例不存在：" + caseId);
        }
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static boolean isBlank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String strOrNull(Object o) {
        String s = str(o);
        return s == null || s.isBlank() ? null : s;
    }

    private static String strOr(Object o, String defaultValue) {
        String s = str(o);
        return s == null || s.isBlank() ? defaultValue : s;
    }
}
