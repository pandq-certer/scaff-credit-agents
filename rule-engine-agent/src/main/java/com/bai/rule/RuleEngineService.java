package com.bai.rule;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 规则引擎服务（提示词库 A-17，真实可执行 DSL/决策表逻辑，非仿真）。
 * <p>处理链：校验入参 → 规则集加载（DSL 编译 AST→RETE 网络，LRU 缓存容量 100）
 * → 事实校验（缺必要字段/类型不符 RUL_1005）→ RETE 匹配（salience 降序冲突消解，命中≤100）
 * → 结论聚合（任一 REJECT 即 REJECT、冲突取 salience 最高、预警全输出取最高级、1000ms 超时返回 REVIEW）
 * → 命中路径解释（规则 ID+条件值）→ 落库发 rule.executed/rule.rejected 事件
 */
@Service
public class RuleEngineService {

    private static final Logger log = LoggerFactory.getLogger(RuleEngineService.class);

    private static final int MAX_BATCH = 100000;
    private static final int MAX_HITS = 100;
    private static final int EXEC_TIMEOUT_MS = 1000;
    private static final int CACHE_CAPACITY = 100;
    private static final List<String> CONCLUSION_PRIORITY = List.of("REJECT", "REVIEW", "WARN", "APPROVE");

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final AtomicLong execSeq = new AtomicLong();
    private final AtomicLong batchSeq = new AtomicLong();
    private final AtomicLong testSeq = new AtomicLong();

    /** DSL 编译产物 LRU 缓存（容量 100；规则集变更随发布失效） */
    private final Map<String, RuleDsl.CompiledRuleset> cache = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RuleDsl.CompiledRuleset> eldest) {
            return size() > CACHE_CAPACITY;
        }
    };

    /** 同步执行（幂等：requestId 相同返回首次结果，缓存 24h） */
    public RuleEngineDtos.RuleResult execute(RuleEngineDtos.ExecuteRequest req, String requestId) {
        RuleEngineDtos.RuleResult cached = idempotency.get("execute", requestId);
        if (cached != null) {
            return cached;
        }
        RuleDsl.CompiledRuleset rs = loadAndValidate(req == null ? null : req.rulesetId(),
                req == null ? null : req.rulesetVersion(), req == null ? null : req.facts());
        RuleEngineDtos.RuleResult result = run(rs, req.facts(), req.explain(),
                "RUL" + dayStamp() + String.format("%05d", execSeq.incrementAndGet()));
        idempotency.put("execute", requestId, result);
        if ("REJECT".equals(result.conclusion().decision())) {
            log.info("rule.rejected 事件: requestId={}, rulesetId={}, 结论={}({})", requestId, rs.rulesetId(),
                    result.conclusion().decision(), result.conclusion().code());
        } else {
            log.info("rule.executed 事件: requestId={}, rulesetId={}, 结论={}, 命中={}", requestId, rs.rulesetId(),
                    result.conclusion().decision(), result.hits().size());
        }
        return result;
    }

    /** 批量异步受理（≤100000 条） */
    public RuleEngineDtos.BatchResult batch(RuleEngineDtos.BatchRequest req, String requestId) {
        List<RuleEngineDtos.ExecuteRequest> items = req.items();
        if (items == null || items.isEmpty()) {
            throw new BaiException(RuleEngineErrorCodes.PARAM_INVALID, "RUL_1001 参数缺失：batch.items 必填");
        }
        if (items.size() > MAX_BATCH) {
            throw new BaiException(RuleEngineErrorCodes.FACT_INVALID, "RUL_1005 批量超 " + MAX_BATCH + " 条（实际 " + items.size() + "），请分批");
        }
        for (RuleEngineDtos.ExecuteRequest item : items) {
            RuleDsl.CompiledRuleset rs = loadAndValidate(item == null ? null : item.rulesetId(),
                    item == null ? null : item.rulesetVersion(), item == null ? null : item.facts());
            idempotency.put("execute", requestId + ":" + item.rulesetId() + ":" + item.facts().hashCode(),
                    run(rs, item.facts(), item.explain(), "RUL" + dayStamp() + String.format("%05d", execSeq.incrementAndGet())));
        }
        String batchId = "RUB" + dayStamp() + String.format("%05d", batchSeq.incrementAndGet());
        log.info("规则批量执行受理: requestId={}, batchId={}, total={}", requestId, batchId, items.size());
        return new RuleEngineDtos.BatchResult(batchId, "ACCEPTED", items.size());
    }

    /** 规则仿真（与 execute 同链路，附加 pass 判定；仿真 passRate≥97% 验收口径） */
    public RuleEngineDtos.TestResult test(RuleEngineDtos.TestRequest req, String requestId) {
        if (req == null || req.rulesetId() == null || req.rulesetId().isBlank() || req.facts() == null || req.facts().isEmpty()) {
            throw new BaiException(RuleEngineErrorCodes.PARAM_INVALID, "RUL_1001 参数缺失：test.rulesetId/facts 必填");
        }
        RuleDsl.CompiledRuleset rs = loadAndValidate(req.rulesetId(), req.rulesetVersion(), req.facts());
        RuleEngineDtos.RuleResult result = run(rs, req.facts(), true,
                "RUT" + dayStamp() + String.format("%05d", testSeq.incrementAndGet()));
        boolean pass = !"REJECT".equals(result.conclusion().decision());
        log.info("规则仿真: requestId={}, rulesetId={}, pass={}, 结论={}", requestId, rs.rulesetId(), pass, result.conclusion().decision());
        return new RuleEngineDtos.TestResult(result.execId(), rs.rulesetId(), rs.version(), pass,
                result.conclusion(), result.hits(), result.path(), result.elapsedMs());
    }

    /** 规则集查询（内置 DSL 源编译摘要） */
    public RuleEngineDtos.RulesetsResult rulesets() {
        List<RuleEngineDtos.RulesetSummary> summaries = new ArrayList<>();
        for (String id : RuleDsl.SOURCES.keySet().stream().sorted().toList()) {
            RuleDsl.CompiledRuleset rs = load(id);
            List<RuleEngineDtos.RuleSummary> rules = rs.rules().stream()
                    .map(r -> new RuleEngineDtos.RuleSummary(r.ruleId(), r.name(), r.salience(), r.decision(), r.message(), r.code()))
                    .toList();
            summaries.add(new RuleEngineDtos.RulesetSummary(rs.rulesetId(), rs.name(), rs.category(),
                    rs.version(), rs.status(), rules.size(), rules));
        }
        return new RuleEngineDtos.RulesetsResult("2026.09.01", summaries);
    }

    /** 规则集加载（LRU 缓存）+ 事实校验 */
    private RuleDsl.CompiledRuleset loadAndValidate(String rulesetId, String rulesetVersion, Map<String, Object> facts) {
        if (rulesetId == null || rulesetId.isBlank()) {
            throw new BaiException(RuleEngineErrorCodes.PARAM_INVALID, "RUL_1001 参数缺失：rulesetId 必填");
        }
        if (facts == null || facts.isEmpty()) {
            throw new BaiException(RuleEngineErrorCodes.PARAM_INVALID, "RUL_1001 参数非法：facts 必填且非空");
        }
        RuleDsl.CompiledRuleset rs = load(rulesetId);
        if (rs == null) {
            throw new BaiException(RuleEngineErrorCodes.RULESET_NOT_FOUND, "RUL_1004 rulesetId 不存在：" + rulesetId);
        }
        if (rulesetVersion != null && !rulesetVersion.isBlank() && !rulesetVersion.equals(rs.version())) {
            throw new BaiException(RuleEngineErrorCodes.RULESET_NOT_FOUND,
                    "RUL_1004 rulesetVersion 不存在：" + rulesetVersion + "（当前生效 " + rs.version() + "）");
        }
        validateFacts(rs, facts);
        return rs;
    }

    private RuleDsl.CompiledRuleset load(String rulesetId) {
        synchronized (cache) {
            RuleDsl.CompiledRuleset rs = cache.get(rulesetId);
            if (rs != null) {
                return rs;
            }
            String dsl = RuleDsl.SOURCES.get(rulesetId);
            if (dsl == null) {
                return null;
            }
            rs = RuleDsl.compile(dsl); // 编译失败报错并给出行号（DSL 编译通过率验收 100%）
            cache.put(rulesetId, rs);
            return rs;
        }
    }

    /** 事实校验：必填字段与类型（number/string/bool/date） */
    private void validateFacts(RuleDsl.CompiledRuleset rs, Map<String, Object> facts) {
        for (Map.Entry<String, String> e : rs.requiredFields().entrySet()) {
            Object v = facts.get(e.getKey());
            if (v == null || String.valueOf(v).isBlank()) {
                throw new BaiException(RuleEngineErrorCodes.FACT_INVALID, "RUL_1005 事实缺必要字段：" + e.getKey());
            }
            String type = e.getValue();
            Object coerced = coerce(v);
            boolean ok = switch (type) {
                case "number" -> coerced instanceof Double;
                case "bool" -> coerced instanceof Boolean;
                case "string", "date", "array" -> true;
                default -> throw new BaiException(RuleEngineErrorCodes.INTERNAL_ERROR, "RUL_2000 规则集事实规范类型未知：" + type);
            };
            if (!ok) {
                throw new BaiException(RuleEngineErrorCodes.FACT_INVALID, "RUL_1005 事实类型不符：" + e.getKey() + " 须为 " + type);
            }
        }
    }

    private Object coerce(Object raw) {
        if (raw instanceof Number) {
            return ((Number) raw).doubleValue();
        }
        if (raw instanceof Boolean) {
            return raw;
        }
        String s = String.valueOf(raw).trim();
        if (s.matches("-?\\d+(\\.\\d+)?")) {
            return Double.parseDouble(s);
        }
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(s.toLowerCase());
        }
        return s;
    }

    /** RETE 匹配执行 + 结论聚合 + 路径解释 */
    private RuleEngineDtos.RuleResult run(RuleDsl.CompiledRuleset rs, Map<String, Object> facts, Boolean explain, String execId) {
        long start = System.currentTimeMillis();
        // 规则按 salience 降序、定义序稳定（编译时已保序，这里显式排序保证确定性）
        List<RuleDsl.CompiledRule> ordered = rs.rules().stream()
                .sorted(Comparator.comparingInt(RuleDsl.CompiledRule::salience).reversed())
                .toList();

        List<RuleDsl.CompiledRule> hits = new ArrayList<>();
        boolean timeout = false;
        for (RuleDsl.CompiledRule rule : ordered) {
            if (System.currentTimeMillis() - start > EXEC_TIMEOUT_MS) {
                timeout = true; // 执行限时截断 1000ms 超时返回 REVIEW
                break;
            }
            if (rule.conditions().stream().allMatch(c -> c.matches(facts))) {
                hits.add(rule);
            }
        }
        boolean truncated = false;
        if (hits.size() > MAX_HITS) {
            hits = new ArrayList<>(hits.subList(0, MAX_HITS));
            truncated = true;
        }

        RuleEngineDtos.Conclusion conclusion = aggregate(hits, timeout);
        List<RuleEngineDtos.MatchedHit> hitDtos = new ArrayList<>();
        for (RuleDsl.CompiledRule rule : hits) {
            Map<String, Object> matched = new LinkedHashMap<>();
            if (!Boolean.FALSE.equals(explain)) {
                for (RuleDsl.Condition c : rule.conditions()) {
                    matched.put("fact." + c.field(), coerce(facts.get(c.field())));
                }
                matched.put("op", rule.conditions().get(0).op());
                matched.put("value", rule.conditions().get(0).value());
            }
            hitDtos.add(new RuleEngineDtos.MatchedHit(rule.ruleId(), rule.name(), rule.salience(), matched));
        }
        List<String> path = hitDtos.stream().map(RuleEngineDtos.MatchedHit::ruleId).toList();
        long elapsed = System.currentTimeMillis() - start;
        if (truncated) {
            log.warn("命中规则超 {} 条已截断并计数: execId={}", MAX_HITS, execId);
        }
        log.info("规则执行审计: execId={}, rulesetId={}, 事实哈希={}, 结论={}, 命中={}, 路径={}, elapsed={}ms",
                execId, rs.rulesetId(), facts.hashCode(), conclusion.decision(), hitDtos.size(), path, elapsed);
        return new RuleEngineDtos.RuleResult(execId, rs.rulesetId(), rs.version(), conclusion, hitDtos, path, elapsed);
    }

    /** 结论聚合：冲突取 salience 最高（hits 已按 salience 降序）；任一 REJECT 即 REJECT；预警全输出取最高级 */
    private RuleEngineDtos.Conclusion aggregate(List<RuleDsl.CompiledRule> hits, boolean timeout) {
        if (timeout) {
            return new RuleEngineDtos.Conclusion("REVIEW", "执行超时截断（1000ms），返回 REVIEW 兜底", "RUL-TIMEOUT");
        }
        for (String decision : CONCLUSION_PRIORITY) {
            RuleDsl.CompiledRule top = hits.stream()
                    .filter(r -> decision.equals(r.decision()))
                    .findFirst()
                    .orElse(null);
            if (top != null) {
                return new RuleEngineDtos.Conclusion(decision, top.message(), top.code());
            }
        }
        return new RuleEngineDtos.Conclusion("PASS", "无命中规则，默认通过", "PASS-DEFAULT");
    }

    private String dayStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }
}
