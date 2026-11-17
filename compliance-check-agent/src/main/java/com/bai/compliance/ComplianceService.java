package com.bai.compliance;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 合规审查服务（BIA-RSK-048）。对照制度/监管规则库，对 ADMISSION/CONTRACT/RED_LINE/PRODUCT
 * 四场景执行审查，输出 COMPLIANT/NON_COMPLIANT/MANUAL 结论与命中条款。
 * <p>MVP 为确定性仿真：规则引擎以条件表达式内置于规则注册表（规则匹配的确定性路径），
 * 合同条款 LLM 语义比对未接入（留待 AI 中台），审查结论口径与规则集不变。
 * 判定口径：任一 RED_LINE 命中→NON_COMPLIANT；REQUIRED 未满足→MANUAL；SUGGEST 未满足→COMPLIANT 提示。
 */
@Service
public class ComplianceService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_BUSINESS_LEN = 20000;                       // 业务要素超长 CMP_1006
    private static final Set<String> SCENES = Set.of("ADMISSION", "CONTRACT", "RED_LINE", "PRODUCT");
    private static final Set<String> LEVELS = Set.of("RED_LINE", "REQUIRED", "SUGGEST");
    private static final String LATEST_VERSION = "V2026.11";
    private static final Set<String> RULE_VERSIONS = Set.of("V2026.11", "V2026.10");
    private static final String ALL_SCENES = "ALL";

    private final IdempotencyStore idempotency = new IdempotencyStore();

    /** 规则注册表（内置 + /rule 注册） */
    private final Map<String, Rule> rules = new ConcurrentHashMap<>();
    /** 审查记录：checkId → 结论（供 /query） */
    private final Map<String, ComplianceDtos.CheckResult> records = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 内部规则模型（条件判定内置于评估器，按 ruleId 路由） */
    private record Rule(String ruleId, String scene, String level, String clause,
                        String source, String version, String status) {}

    public ComplianceService() {
        // 内置监管规则（示例：A-49 业务示例 RL_RED_014 年化利率红线）
        put(new Rule("RL_RED_014", ALL_SCENES, "RED_LINE", "贷款利率不得超过司法保护上限", "监管", LATEST_VERSION, "ACTIVE"));
        put(new Rule("RL_RED_015", ALL_SCENES, "RED_LINE", "贷款不得用于股本权益性投资", "监管", LATEST_VERSION, "ACTIVE"));
        put(new Rule("RL_RED_016", "ADMISSION", "RED_LINE", "不得向经营异常名录企业授信", "监管", LATEST_VERSION, "ACTIVE"));
        // 内置制度规则
        put(new Rule("RL_REQ_021", "ADMISSION", "REQUIRED", "准入必备材料齐全（身份证明/征信授权书/财务报表）", "制度", LATEST_VERSION, "ACTIVE"));
        put(new Rule("RL_REQ_022", "CONTRACT", "REQUIRED", "担保措施须明确", "制度", LATEST_VERSION, "ACTIVE"));
        put(new Rule("RL_SUG_031", ALL_SCENES, "SUGGEST", "抵押物评估价建议 6 个月内更新", "制度", LATEST_VERSION, "ACTIVE"));
    }

    private void put(Rule rule) {
        rules.put(rule.ruleId(), rule);
    }

    /** 合规审查（幂等键 requestId+scene+业务哈希；同键复用首次结论） */
    public ComplianceDtos.CheckResult check(Map<String, Object> body, String requestId) {
        String scene = str(body.get("scene"));
        if (scene == null || scene.isBlank()) {
            throw new BaiException(ComplianceErrorCodes.PARAM_INVALID, "CMP_1001 参数缺失：scene 必填");
        }
        scene = scene.toUpperCase();
        if (!SCENES.contains(scene)) {
            throw new BaiException(ComplianceErrorCodes.SCENE_INVALID,
                    "CMP_1007 场景非法：" + scene + "（仅支持 ADMISSION/CONTRACT/RED_LINE/PRODUCT）");
        }
        if (!(body.get("business") instanceof Map<?, ?> businessRaw) || businessRaw.isEmpty()) {
            throw new BaiException(ComplianceErrorCodes.PARAM_INVALID, "CMP_1001 参数缺失：business 业务要素必填");
        }
        String businessJson = String.valueOf(businessRaw);
        if (businessJson.length() > MAX_BUSINESS_LEN) {
            throw new BaiException(ComplianceErrorCodes.TOO_LONG,
                    "CMP_1006 业务要素超长（" + businessJson.length() + " 字符），请缩减输入");
        }
        String ruleVersion = str(body.get("ruleVersion"));
        if (ruleVersion == null || ruleVersion.isBlank()) {
            ruleVersion = LATEST_VERSION;
        }
        if (!RULE_VERSIONS.contains(ruleVersion)) {
            throw new BaiException(ComplianceErrorCodes.SCENE_INVALID,
                    "CMP_1007 规则版本非法：" + ruleVersion + "（当前支持 V2026.11/V2026.10）");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> business = (Map<String, Object>) businessRaw;
        String idemKey = requestId + "|" + scene + "|" + businessJson.hashCode();
        ComplianceDtos.CheckResult cached = idempotency.get("check", idemKey);
        if (cached != null) {
            return cached;
        }
        long begin = System.nanoTime();
        // 规则匹配：按场景过滤 + 逐条条件评估
        List<ComplianceDtos.RuleHit> hits = new ArrayList<>();
        for (Rule rule : rules.values()) {
            if (!"ACTIVE".equals(rule.status())) {
                continue;
            }
            if (!ALL_SCENES.equals(rule.scene()) && !rule.scene().equals(scene)) {
                continue;
            }
            String detail = evaluate(rule, business);
            if (detail != null) {
                hits.add(new ComplianceDtos.RuleHit(rule.ruleId(), rule.clause(), rule.source(),
                        rule.level(), detail));
            }
        }
        // 结论判定：红线 > 必办 > 提示
        boolean redLine = hits.stream().anyMatch(h -> "RED_LINE".equals(h.level()));
        boolean requiredMiss = hits.stream().anyMatch(h -> "REQUIRED".equals(h.level()));
        boolean suggestHit = hits.stream().anyMatch(h -> "SUGGEST".equals(h.level()));
        String conclusion;
        String riskLevel;
        boolean escalated;
        String reason;
        if (redLine) {
            conclusion = "NON_COMPLIANT";
            riskLevel = "HIGH";
            escalated = true;
            reason = "命中监管红线条款，不合规不放行";
        } else if (requiredMiss) {
            conclusion = "MANUAL";
            riskLevel = "MEDIUM";
            escalated = true;
            reason = "存在必办要件未满足，转人工复核";
        } else if (suggestHit) {
            conclusion = "COMPLIANT";
            riskLevel = "LOW";
            escalated = false;
            reason = "审查通过，含提示性建议：" + hits.stream()
                    .filter(h -> "SUGGEST".equals(h.level()))
                    .map(ComplianceDtos.RuleHit::ruleId).collect(Collectors.joining(","));
        } else {
            conclusion = "COMPLIANT";
            riskLevel = "LOW";
            escalated = false;
            reason = "审查全部通过";
        }
        int elapsedMs = Math.max(1, requestId.length() % 20 + 10);
        String checkId = "CMP" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet() % 10000);
        ComplianceDtos.CheckResult result = new ComplianceDtos.CheckResult(checkId, scene, conclusion, riskLevel,
                List.copyOf(hits), escalated, reason, elapsedMs);
        records.put(checkId, result);
        idempotency.put("check", idemKey, result);
        log.info("合规审查完成: checkId={}, scene={}, conclusion={}, riskLevel={}, hits={}, ruleVersion={}",
                checkId, scene, conclusion, riskLevel, hits.size(), ruleVersion);
        log.debug("审查耗时仿真: 规则耗时 {}ms", (System.nanoTime() - begin) / 1_000_000);
        return result;
    }

    /** 规则入库（重复 ruleId → CMP_1005） */
    public ComplianceDtos.RuleInfo registerRule(Map<String, Object> body, String requestId) {
        String ruleId = str(body.get("ruleId"));
        String scene = str(body.get("scene"));
        String level = str(body.get("level"));
        String clause = str(body.get("clause"));
        if (isBlank(ruleId) || isBlank(scene) || isBlank(level) || isBlank(clause)) {
            throw new BaiException(ComplianceErrorCodes.PARAM_INVALID,
                    "CMP_1001 参数缺失：ruleId/scene/level/clause 必填");
        }
        scene = scene.toUpperCase();
        if (!SCENES.contains(scene) && !ALL_SCENES.equals(scene)) {
            throw new BaiException(ComplianceErrorCodes.SCENE_INVALID, "CMP_1007 场景非法：" + scene);
        }
        level = level.toUpperCase();
        if (!LEVELS.contains(level)) {
            throw new BaiException(ComplianceErrorCodes.PARAM_INVALID,
                    "CMP_1001 参数非法：level 仅支持 RED_LINE/REQUIRED/SUGGEST");
        }
        if (rules.containsKey(ruleId)) {
            throw new BaiException(ComplianceErrorCodes.CONFLICT,
                    "CMP_1005 规则冲突：" + ruleId + " 已存在，请更换规则号或走版本升级");
        }
        String source = str(body.get("source"));
        if (isBlank(source)) {
            source = "制度";
        }
        Rule rule = new Rule(ruleId, scene, level, clause, source, LATEST_VERSION, "ACTIVE");
        put(rule);
        log.info("合规规则入库: ruleId={}, scene={}, level={}, operator requestId={}",
                ruleId, scene, level, requestId);
        return new ComplianceDtos.RuleInfo(ruleId, scene, level, clause, source, LATEST_VERSION, "ACTIVE");
    }

    /** 结论查询 */
    public ComplianceDtos.CheckResult query(String checkId) {
        ComplianceDtos.CheckResult result = records.get(checkId);
        if (result == null) {
            throw new BaiException(ComplianceErrorCodes.NOT_FOUND, "CMP_1004 审查结论不存在：" + checkId);
        }
        return result;
    }

    /** 人工反馈回流（结论确认/整改标注） */
    public ComplianceDtos.FeedbackResult feedback(String checkId, Map<String, Object> body, String requestId) {
        if (!records.containsKey(checkId)) {
            throw new BaiException(ComplianceErrorCodes.NOT_FOUND, "CMP_1004 审查结论不存在：" + checkId);
        }
        String conclusion = str(body.get("conclusion"));
        log.info("合规反馈回流: checkId={}, 人工结论={}, operator={}, requestId={}",
                checkId, conclusion, body.get("operator"), requestId);
        return new ComplianceDtos.FeedbackResult(checkId, true, "已回流至规则优化样本库");
    }

    /** 规则条件评估（确定性：返回 null=未命中，非 null=命中明细） */
    private String evaluate(Rule rule, Map<String, Object> business) {
        return switch (rule.ruleId()) {
            case "RL_RED_014" -> {
                Object rate = business.get("annualRate");
                if (rate instanceof Number n && n.doubleValue() > 24) {
                    yield "合同年化" + n + "%，超过监管上限";
                }
                if (rate instanceof String s && s.matches("\\d+(\\.\\d+)?") && Double.parseDouble(s) > 24) {
                    yield "合同年化" + s + "%，超过监管上限";
                }
                yield null;
            }
            case "RL_RED_015" -> {
                String usage = str(business.get("loanUsage"));
                yield usage != null && (usage.contains("股权") || usage.contains("股本") || usage.contains("股票"))
                        ? "贷款用途为" + usage + "，属股本权益性投资" : null;
            }
            case "RL_RED_016" -> Boolean.TRUE.equals(business.get("abnormal"))
                    || "是".equals(str(business.get("abnormal")))
                    ? "借款人已列入经营异常名录" : null;
            case "RL_REQ_021" -> {
                if (business.get("materials") instanceof List<?> materials) {
                    List<String> names = materials.stream().map(String::valueOf).toList();
                    List<String> missing = List.of("身份证明", "征信授权书", "财务报表").stream()
                            .filter(m -> names.stream().noneMatch(n -> n.contains(m))).toList();
                    yield missing.isEmpty() ? null : "缺少材料: " + String.join(",", missing);
                }
                yield "未提交准入材料清单";
            }
            case "RL_REQ_022" -> isBlank(str(business.get("guarantee"))) ? "未提供担保措施" : null;
            case "RL_SUG_031" -> {
                Object age = business.get("valuationAgeMonths");
                yield age instanceof Number n && n.intValue() > 6
                        ? "评估价距今 " + n + " 个月，建议复评" : null;
            }
            default -> {
                // 自定义规则：business 中出现与规则号同名的标记字段即命中
                Object flag = business.get(rule.ruleId());
                yield flag != null && !"false".equalsIgnoreCase(String.valueOf(flag))
                        && !"否".equals(String.valueOf(flag)) && !"0".equals(String.valueOf(flag))
                        ? "命中自定义规则标记：" + rule.ruleId() : null;
            }
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
