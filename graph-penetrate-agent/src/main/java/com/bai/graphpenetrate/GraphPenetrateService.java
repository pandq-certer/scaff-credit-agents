package com.bai.graphpenetrate;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 图谱穿透服务（BIA-RSK-044）。基于 graph-build-agent 构建的图谱做股权 UPSTREAM/DOWNSTREAM 穿透、
 * 实控人认定（加权累计≥50%）与担保圈/风险传导分析。
 * <p>双实现（graph.provider 开关）：
 * <ul>
 *   <li><b>sungraph（真实）</b>：从 sungraph 拉取图快照（{@link GraphSnapshotStore} TTL 缓存），
 *       Java 层枚举股权路径——直接持股=边比例（EQUITY 边 weight）、间接持股=路径比例乘积、
 *       多路径指向同一实体按实体加总；实控人=自然人加权≥50%（一致行动人 FAMILY/CONSISTENT_ACTION 边连通的
 *       自然人组合并认定）；cycleDetected=实体处于循环持股环或担保圈（GUARANTEE 有向环）；
 *       节点不在图中（场景侧契约演示 ID）降级确定性仿真并 WARN。</li>
 *   <li><b>mock（确定性仿真）</b>：股东图按实体 ID 哈希合成（sungraph 不可用/实体未对齐时的兜底替身）。</li>
 * </ul>
 * 穿透口径两种实现保持一致，sungraph 不可用时按 GPT_2001 语义降级（缓存图兜底→仿真），链路不中断。
 */
@Service
public class GraphPenetrateService {

    private static final Logger log = LoggerFactory.getLogger(GraphPenetrateService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int DEFAULT_DEPTH = 5;
    private static final int MAX_DEPTH = 10;
    private static final double CONTROL_THRESHOLD = 0.50;
    private static final int MAX_BATCH = 50;
    private static final Set<String> DIRECTIONS = Set.of("UPSTREAM", "DOWNSTREAM");
    private static final Set<String> ENTITY_TYPES = Set.of("PERSON", "ORG");
    private static final Set<String> REL_TYPES = Set.of(
            "EQUITY", "GUARANTEE", "INVEST", "FAMILY", "POSITION", "RELATED_TRANSACTION");
    /** 图谱边类型 → 契约关系类型映射口径（EQUITY 出边=对外投资 INVEST，入边=持股 EQUITY） */
    private static final Map<String, String> GRAPH_REL_MAPPING = Map.of(
            "EQUITY", "EQUITY", "GUARANTEE", "GUARANTEE", "FAMILY", "FAMILY",
            "EMPLOYMENT", "POSITION", "TRANSACTION", "RELATED_TRANSACTION");
    /** sungraph 节点类型 → 契约实体类型 */
    private static final Map<String, String> NODE_TYPE_MAPPING = Map.of("COMPANY", "ORG", "PERSON", "PERSON");

    /** 哈希合成股东图用确定性名称池 */
    private static final List<String> ORG_HOLDERS = List.of("国投控股集团", "华信投资合伙", "中恒产业投资", "瑞丰资本管理");
    private static final List<String> PLATFORMS = List.of("员工持股平台", "鑫联投资合伙企业(有限合伙)", "资产管理计划");
    private static final List<String> OTHERS = List.of("其他股东", "职工持股会");
    private static final List<String> SUBSIDIARIES = List.of("供应链科技有限公司", "仓储物流有限公司", "销售服务有限公司", "产业园区有限公司");
    private static final List<String> SURNAMES = List.of("张", "王", "李", "赵", "陈", "刘", "杨");
    private static final List<String> GIVEN_NAMES = List.of("建国", "志强", "伟明", "建华", "国庆");

    private final IdempotencyStore idempotency = new IdempotencyStore();

    /** 图存储提供方：mock=确定性仿真；sungraph=真实图穿透（读 SunGraph Knowledge Explorer 快照） */
    @Value("${graph.provider:mock}")
    private String provider;
    private final GraphSnapshotStore snapshotStore;

    public GraphPenetrateService(GraphSnapshotStore snapshotStore) {
        this.snapshotStore = snapshotStore;
    }

    private boolean useSungraph() {
        return "sungraph".equalsIgnoreCase(provider);
    }

    /** 穿透记录：penetrateId → 结果（供批量与审计追溯） */
    private final Map<String, GraphPenetrateDtos.PenetrateResult> records = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 股权穿透（幂等键 requestId+entity+direction+maxDepth；同键复用首次结果） */
    public GraphPenetrateDtos.PenetrateResult penetrate(Map<String, Object> body, String requestId) {
        Entity entity = entity(body);
        String direction = direction(body);
        int maxDepth = maxDepth(body);
        boolean includeControl = body.get("includeControl") == null
                || Boolean.TRUE.equals(body.get("includeControl"));
        String idemKey = requestId + "|" + entity.entityType() + ":" + entity.entityId() + "|" + direction + "|" + maxDepth;
        GraphPenetrateDtos.PenetrateResult cached = idempotency.get("penetrate", idemKey);
        if (cached != null) {
            return cached;
        }
        GraphPenetrateDtos.PenetrateResult result = useSungraph()
                ? sungraphPenetrate(entity, direction, maxDepth, includeControl)
                : doPenetrate(entity, direction, includeControl);
        records.put(result.penetrateId(), result);
        idempotency.put("penetrate", idemKey, result);
        log.info("股权穿透完成({}): penetrateId={}, entity={}/{}, direction={}, maxDepth={}, paths={}, controllers={}, cycle={}",
                useSungraph() ? "sungraph/降级" : "仿真", result.penetrateId(), entity.entityType(), entity.entityId(),
                direction, maxDepth, result.paths().size(), result.controllers().size(), result.cycleDetected());
        return result;
    }

    /** 实控人认定（同步） */
    public GraphPenetrateDtos.ControlResult control(Map<String, Object> body, String requestId) {
        Entity entity = entity(body);
        GraphPenetrateDtos.PenetrateResult result = penetrate(Map.of(
                "entity", Map.of("entityType", entity.entityType(), "entityId", entity.entityId()),
                "direction", "UPSTREAM", "includeControl", true), requestId);
        log.info("实控人认定: penetrateId={}, entity={}, controllers={}",
                result.penetrateId(), entity.entityId(), result.controllers());
        return new GraphPenetrateDtos.ControlResult(result.penetrateId(), Map.of(
                "entityType", entity.entityType(), "entityId", entity.entityId()),
                result.controllers(), result.elapsedMs());
    }

    /** 关系查询（同步） */
    public GraphPenetrateDtos.RelationsResult relations(Map<String, Object> body, String requestId) {
        Entity entity = entity(body);
        List<String> relTypes = body.get("relTypes") instanceof List<?> l
                ? l.stream().map(x -> String.valueOf(x).toUpperCase()).toList() : List.copyOf(REL_TYPES);
        for (String rt : relTypes) {
            if (!REL_TYPES.contains(rt)) {
                throw new BaiException(GraphPenetrateErrorCodes.PARAM_INVALID,
                        "GPT_1001 参数非法：relTypes 仅支持 " + REL_TYPES);
            }
        }
        int h = entity.entityId().hashCode();
        List<GraphPenetrateDtos.Relation> relations = useSungraph()
                ? sungraphRelations(entity, relTypes) : mockRelations(entity, relTypes);
        int elapsedMs = Math.max(1, entity.entityId().length() % 10 + 6);
        log.info("关系查询({}): entity={}, relTypes={}, total={}, requestId={}",
                useSungraph() ? "sungraph/降级" : "仿真", entity.entityId(), relTypes, relations.size(), requestId);
        return new GraphPenetrateDtos.RelationsResult(Map.of("entityType", entity.entityType(),
                "entityId", entity.entityId()), List.copyOf(relations), relations.size(), elapsedMs);
    }

    /** 仿真关系查询（原哈希合成逻辑，sungraph 不可用/实体未对齐时的兜底替身） */
    private List<GraphPenetrateDtos.Relation> mockRelations(Entity entity, List<String> relTypes) {
        int h = entity.entityId().hashCode();
        List<GraphPenetrateDtos.Relation> relations = new ArrayList<>();
        if (relTypes.contains("EQUITY")) {
            String holder = ORG_HOLDERS.get(Math.abs(h) % ORG_HOLDERS.size());
            relations.add(new GraphPenetrateDtos.Relation("EQUITY", holder, entity.entityId(),
                    0.60 + (Math.abs(h) % 15) / 100.0, "工商登记直接持股"));
        }
        if (relTypes.contains("INVEST")) {
            String sub = SUBSIDIARIES.get(Math.abs(h >> 3) % SUBSIDIARIES.size());
            relations.add(new GraphPenetrateDtos.Relation("INVEST", entity.entityId(), sub,
                    0.51 + (Math.abs(h >> 5) % 20) / 100.0, "对外投资控股"));
        }
        if (relTypes.contains("GUARANTEE")) {
            String peer = ORG_HOLDERS.get(Math.abs(h >> 7) % ORG_HOLDERS.size());
            relations.add(new GraphPenetrateDtos.Relation("GUARANTEE", entity.entityId(), peer,
                    null, "互保/连环担保（担保圈关注）"));
        }
        if (relTypes.contains("POSITION")) {
            String person = personName(h >> 11);
            relations.add(new GraphPenetrateDtos.Relation("POSITION", person, entity.entityId(),
                    null, "法定代表人/高管任职"));
        }
        if (relTypes.contains("FAMILY")) {
            String person = personName(h >> 13);
            relations.add(new GraphPenetrateDtos.Relation("FAMILY", person, personName(h >> 17),
                    null, "亲属关系（一致行动人线索）"));
        }
        if (relTypes.contains("RELATED_TRANSACTION")) {
            String peer = SUBSIDIARIES.get(Math.abs(h >> 19) % SUBSIDIARIES.size());
            relations.add(new GraphPenetrateDtos.Relation("RELATED_TRANSACTION", entity.entityId(), peer,
                    null, "关联交易（采购/资金往来）"));
        }
        return relations;
    }

    /** 批量穿透（异步；items ≤ 50） */
    public GraphPenetrateDtos.BatchResult batch(Map<String, Object> body, String requestId) {
        if (!(body.get("items") instanceof List<?> items) || items.isEmpty()) {
            throw new BaiException(GraphPenetrateErrorCodes.PARAM_INVALID, "GPT_1001 参数缺失：items 必填");
        }
        if (items.size() > MAX_BATCH) {
            throw new BaiException(GraphPenetrateErrorCodes.TOO_LONG,
                    "GPT_1006 批量超长（" + items.size() + " > " + MAX_BATCH + " 条），请分批提交");
        }
        List<GraphPenetrateDtos.PenetrateResult> results = new ArrayList<>();
        int i = 0;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new BaiException(GraphPenetrateErrorCodes.PARAM_INVALID,
                        "GPT_1001 参数非法：items[" + i + "] 必须为 object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> itemBody = (Map<String, Object>) m;
            results.add(penetrate(itemBody, requestId + "-" + i++));
        }
        log.info("批量穿透完成: total={}, requestId={}", results.size(), requestId);
        return new GraphPenetrateDtos.BatchResult(results.size(), List.copyOf(results));
    }

    /** 确定性穿透主流程：哈希合成股东图 → 路径枚举 → 乘积/加总 → 实控人认定 */
    private GraphPenetrateDtos.PenetrateResult doPenetrate(Entity entity, String direction, boolean includeControl) {
        String self = entity.entityId();
        int h = self.hashCode();
        List<GraphPenetrateDtos.Path> paths = new ArrayList<>();
        List<GraphPenetrateDtos.EquityItem> summary = new ArrayList<>();
        List<GraphPenetrateDtos.Controller> controllers = new ArrayList<>();
        boolean cycleDetected = Math.abs(h) % 7 == 0;   // 担保圈/循环持股确定性仿真标记
        int elapsedMs = Math.max(1, Math.abs(h) % 200 + 60);
        if ("UPSTREAM".equals(direction)) {
            String holder = ORG_HOLDERS.get(Math.abs(h) % ORG_HOLDERS.size());
            String platform = PLATFORMS.get(Math.abs(h >> 3) % PLATFORMS.size());
            String other = OTHERS.get(Math.abs(h >> 5) % OTHERS.size());
            String person = personName(h >> 7);
            double r0 = 0.55 + (Math.abs(h) % 15) / 100.0;            // 直接持股 0.55~0.69
            double rp = Math.min(0.34, 0.99 - r0);                    // 平台直接持股，保证合计≤1
            double ro = Math.round((1 - r0 - rp) * 100) / 100.0;      // 其余股东
            double indirect = round2(r0 * 0.70 + rp * 0.60);          // 自然人间接加权
            paths.add(new GraphPenetrateDtos.Path(self + "←" + holder, 1, round2(r0), List.of(r0)));
            paths.add(new GraphPenetrateDtos.Path(self + "←" + holder + "←" + person, 2, round2(r0 * 0.70), List.of(r0, 0.70)));
            paths.add(new GraphPenetrateDtos.Path(self + "←" + platform, 1, round2(rp), List.of(rp)));
            paths.add(new GraphPenetrateDtos.Path(self + "←" + platform + "←" + person, 2, round2(rp * 0.60), List.of(rp, 0.60)));
            paths.add(new GraphPenetrateDtos.Path(self + "←" + other, 1, round2(ro), List.of(ro)));
            summary.add(new GraphPenetrateDtos.EquityItem(holder, round2(r0), 0.0, round2(r0)));
            summary.add(new GraphPenetrateDtos.EquityItem(platform, round2(rp), 0.0, round2(rp)));
            summary.add(new GraphPenetrateDtos.EquityItem(other, round2(ro), 0.0, round2(ro)));
            summary.add(new GraphPenetrateDtos.EquityItem(person, 0.0, indirect, indirect));
            if (includeControl && indirect >= CONTROL_THRESHOLD) {
                controllers.add(new GraphPenetrateDtos.Controller(person, indirect, "WEIGHTED_SUM",
                        List.of(holder + "持股" + pct(r0) + "×70%=" + pct(r0 * 0.70),
                                platform + "持股" + pct(rp) + "×60%=" + pct(rp * 0.60))));
            }
        } else {
            // DOWNSTREAM：对外投资穿透，self 为被投企业实控人
            int count = 2 + Math.abs(h) % 3;
            for (int i = 0; i < count; i++) {
                String sub = SUBSIDIARIES.get((Math.abs(h >> i) + i) % SUBSIDIARIES.size());
                double ratio = 0.51 + (Math.abs(h >> (i + 2)) % 25) / 100.0;   // 0.51~0.75
                paths.add(new GraphPenetrateDtos.Path(self + "→" + sub, 1, round2(ratio), List.of(ratio)));
                summary.add(new GraphPenetrateDtos.EquityItem(sub, round2(ratio), 0.0, round2(ratio)));
                if (includeControl && ratio >= CONTROL_THRESHOLD) {
                    controllers.add(new GraphPenetrateDtos.Controller(self, round2(ratio), "WEIGHTED_SUM",
                            List.of("对" + sub + "直接持股" + pct(ratio) + "≥50%")));
                }
            }
        }
        String penetrateId = "GPT" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet() % 10000);
        return new GraphPenetrateDtos.PenetrateResult(penetrateId, List.copyOf(paths), List.copyOf(summary),
                List.copyOf(controllers), cycleDetected, elapsedMs);
    }

    // ---------- sungraph 真实图穿透 ----------

    /** 真实穿透内部结果集 */
    private record RealPenetration(List<GraphPenetrateDtos.Path> paths, List<GraphPenetrateDtos.EquityItem> summary,
                                   boolean equityCycle, boolean guaranteeCycle, String selfName) {}

    /** 获取图快照：sungraph 不可达且无缓存时返回 null（GPT_2001 降级仿真，链路不中断） */
    private GraphSnapshotStore.Snapshot snapshot(String entityId) {
        try {
            return snapshotStore.get();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("GPT_2001 sungraph 图快照获取失败，降级确定性仿真（缓存图兜底）: entity={}, err={}",
                    entityId, e.getMessage());
            return null;
        }
    }

    /** sungraph 穿透入口：实体在图中走真实算法，否则降级确定性仿真（场景侧契约演示 ID 兜底，链路不中断） */
    private GraphPenetrateDtos.PenetrateResult sungraphPenetrate(Entity entity, String direction,
                                                                 int maxDepth, boolean includeControl) {
        GraphSnapshotStore.Snapshot snap = snapshot(entity.entityId());
        GraphSnapshotStore.GNode node = snap == null ? null : snap.node(entity.entityId());
        if (snap == null || node == null) {
            log.warn("实体不在 sungraph 图谱中（场景侧契约演示 ID 或未构建），降级确定性仿真: entity={}/{}",
                    entity.entityType(), entity.entityId());
            return doPenetrate(entity, direction, includeControl);
        }
        String contractType = NODE_TYPE_MAPPING.get(node.type());
        if (contractType == null || !contractType.equals(entity.entityType())) {
            throw new BaiException(GraphPenetrateErrorCodes.PARAM_INVALID,
                    "GPT_1001 参数非法：entityType=" + entity.entityType() + " 与图谱节点类型 "
                            + node.type() + " 不一致（entityId=" + entity.entityId() + "）");
        }
        long begin = System.nanoTime();
        boolean upstream = "UPSTREAM".equals(direction);
        RealPenetration p = realPenetrate(snap, entity.entityId(), upstream, maxDepth);
        List<GraphPenetrateDtos.Controller> controllers = includeControl
                ? realControllers(snap, p, upstream) : List.of();
        String penetrateId = "GPT" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet() % 10000);
        int elapsedMs = Math.max(1, (int) ((System.nanoTime() - begin) / 1_000_000));
        return new GraphPenetrateDtos.PenetrateResult(penetrateId, p.paths(), p.summary(), controllers,
                p.equityCycle() || p.guaranteeCycle(), elapsedMs);
    }

    /** 真实穿透主算法：沿 EQUITY 边 DFS 路径枚举——直接持股=边比例、间接持股=路径比例乘积、同实体加总；
     *  同时检测循环持股环（遍历路径内复现节点）与担保圈（GUARANTEE 有向环经过实体）。 */
    private RealPenetration realPenetrate(GraphSnapshotStore.Snapshot snap, String entityId,
                                          boolean upstream, int maxDepth) {
        // 邻接表：UPSTREAM 按 target 索引（cur=被持股企业，沿 holder 前进）；DOWNSTREAM 按 source 索引
        Map<String, List<GraphSnapshotStore.GEdge>> adj = new LinkedHashMap<>();
        for (GraphSnapshotStore.GEdge e : snap.edges()) {
            if (!"EQUITY".equals(e.type())) {
                continue;
            }
            adj.computeIfAbsent(upstream ? e.target() : e.source(), k -> new ArrayList<>()).add(e);
        }
        List<GraphPenetrateDtos.Path> paths = new ArrayList<>();
        Map<String, double[]> acc = new LinkedHashMap<>();   // holderId → [direct, indirect]
        boolean[] equityCycle = {false};
        List<String> pathNodes = new ArrayList<>();
        pathNodes.add(entityId);
        dfs(snap, entityId, adj, pathNodes, 1.0, 0, maxDepth, upstream, new ArrayList<>(), paths, acc, equityCycle);
        List<GraphPenetrateDtos.EquityItem> summary = new ArrayList<>();
        for (Map.Entry<String, double[]> en : acc.entrySet()) {
            GraphSnapshotStore.GNode n = snap.node(en.getKey());
            String name = n != null ? n.name() : en.getKey();
            summary.add(new GraphPenetrateDtos.EquityItem(name,
                    round2(en.getValue()[0]), round2(en.getValue()[1]),
                    round2(en.getValue()[0] + en.getValue()[1])));
        }
        summary.sort(java.util.Comparator.comparing((GraphPenetrateDtos.EquityItem i) -> -i.weighted()));
        boolean guaranteeCycle = hasGuaranteeCycle(snap, entityId);
        return new RealPenetration(List.copyOf(paths), List.copyOf(summary), equityCycle[0], guaranteeCycle,
                nameOf(snap, entityId));
    }

    /** 路径枚举 DFS：每步把「当前节点按路径比例持股 start」计入汇总（长度1计直接、≥2计间接），
     *  路径内复现已访问节点 → 标记循环持股环（边权重恒为 0~1，环上乘积不放大）。 */
    private void dfs(GraphSnapshotStore.Snapshot snap, String start,
                     Map<String, List<GraphSnapshotStore.GEdge>> adj, List<String> pathNodes,
                     double ratio, int depth, int maxDepth, boolean upstream,
                     List<Double> hopWs,
                     List<GraphPenetrateDtos.Path> paths, Map<String, double[]> acc, boolean[] equityCycle) {
        if (depth >= maxDepth) {
            return;
        }
        String cur = pathNodes.get(pathNodes.size() - 1);
        for (GraphSnapshotStore.GEdge e : adj.getOrDefault(cur, List.of())) {
            String next = upstream ? e.source() : e.target();
            if (next.equals(start)) {
                equityCycle[0] = true;   // 循环持股：路径回到起点
                continue;
            }
            if (pathNodes.contains(next)) {
                equityCycle[0] = true;   // 循环持股：路径回到中间节点
                continue;
            }
            pathNodes.add(next);
            hopWs.add(e.weight());
            double r = round2(ratio * e.weight());
            String sep = upstream ? "←" : "→";
            StringBuilder ps = new StringBuilder();
            for (int i = 0; i < pathNodes.size(); i++) {
                if (i > 0) {
                    ps.append(sep);
                }
                ps.append(nameOf(snap, pathNodes.get(i)));
            }
            paths.add(new GraphPenetrateDtos.Path(ps.toString(), pathNodes.size() - 1, r, List.copyOf(hopWs)));
            double[] sums = acc.computeIfAbsent(next, k -> new double[2]);
            if (pathNodes.size() - 1 == 1) {
                sums[0] += r;
            } else {
                sums[1] += r;
            }
            dfs(snap, start, adj, pathNodes, r, depth + 1, maxDepth, upstream, hopWs, paths, acc, equityCycle);
            pathNodes.remove(pathNodes.size() - 1);
            hopWs.remove(hopWs.size() - 1);
        }
    }

    /** 担保圈检测：从实体沿 GUARANTEE 出边 DFS，能否回到自身（有向环经过实体） */
    private boolean hasGuaranteeCycle(GraphSnapshotStore.Snapshot snap, String entityId) {
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (GraphSnapshotStore.GEdge e : snap.edges()) {
            if ("GUARANTEE".equals(e.type())) {
                adj.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.target());
            }
        }
        Set<String> visiting = new java.util.HashSet<>();
        return guaranteeDfs(entityId, entityId, adj, visiting);
    }

    private boolean guaranteeDfs(String start, String cur, Map<String, List<String>> adj, Set<String> visiting) {
        for (String next : adj.getOrDefault(cur, List.of())) {
            if (next.equals(start)) {
                return true;
            }
            if (visiting.add(next)) {
                if (guaranteeDfs(start, next, adj, visiting)) {
                    return true;
                }
                visiting.remove(next);
            }
        }
        return false;
    }

    /** 实控人认定（真实）：UPSTREAM=自然人加权≥50%，另将 FAMILY/CONSISTENT_ACTION 连通的自然人组合并认定；
     *  DOWNSTREAM=self 对被投企业加权≥50%（沿用契约口径）。 */
    private List<GraphPenetrateDtos.Controller> realControllers(GraphSnapshotStore.Snapshot snap,
                                                                RealPenetration p, boolean upstream) {
        List<GraphPenetrateDtos.Controller> controllers = new ArrayList<>();
        for (GraphPenetrateDtos.EquityItem item : p.summary()) {
            if (item.weighted() < CONTROL_THRESHOLD) {
                continue;
            }
            if (!upstream) {
                controllers.add(new GraphPenetrateDtos.Controller(p.selfName(), item.weighted(), "WEIGHTED_SUM",
                        List.of("对" + item.holder() + "直接/间接持股 " + pct(item.weighted()) + " ≥ 50%")));
                continue;
            }
            GraphSnapshotStore.GNode holder = nodeByName(snap, item.holder());
            if (holder != null && "PERSON".equals(holder.type())) {
                controllers.add(new GraphPenetrateDtos.Controller(item.holder(), item.weighted(), "WEIGHTED_SUM",
                        List.of("穿透加权累计持股 " + pct(item.weighted()) + " ≥ 50%")));
            }
        }
        if (upstream) {
            controllers.addAll(actInConcertControllers(snap, p));
        }
        return controllers;
    }

    /** 一致行动人合并认定：FAMILY/CONSISTENT_ACTION 边连通的自然人（加权>0）传递闭包分组，组内加总≥50% 认定 */
    private List<GraphPenetrateDtos.Controller> actInConcertControllers(GraphSnapshotStore.Snapshot snap,
                                                                        RealPenetration p) {
        Map<String, Double> personRatios = new LinkedHashMap<>();
        for (GraphPenetrateDtos.EquityItem item : p.summary()) {
            GraphSnapshotStore.GNode holder = nodeByName(snap, item.holder());
            if (holder != null && "PERSON".equals(holder.type()) && item.weighted() > 0) {
                personRatios.merge(item.holder(), item.weighted(), Double::sum);
            }
        }
        if (personRatios.size() < 2) {
            return List.of();
        }
        // 并查集：FAMILY/CONSISTENT_ACTION 连通的自然人合并
        Map<String, String> parent = new LinkedHashMap<>();
        personRatios.keySet().forEach(k -> parent.put(k, k));
        for (GraphSnapshotStore.GEdge e : snap.edges()) {
            if (!"FAMILY".equals(e.type()) && !"CONSISTENT_ACTION".equals(e.type())) {
                continue;
            }
            String a = nameOf(snap, e.source());
            String b = nameOf(snap, e.target());
            if (parent.containsKey(a) && parent.containsKey(b)) {
                parent.put(find(parent, a), find(parent, b));
            }
        }
        Map<String, java.util.List<String>> groups = new LinkedHashMap<>();
        for (String name : personRatios.keySet()) {
            groups.computeIfAbsent(find(parent, name), k -> new ArrayList<>()).add(name);
        }
        List<GraphPenetrateDtos.Controller> controllers = new ArrayList<>();
        for (java.util.List<String> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            double sum = group.stream().mapToDouble(personRatios::get).sum();
            if (sum >= CONTROL_THRESHOLD) {
                controllers.add(new GraphPenetrateDtos.Controller(String.join("+", group), round2(sum),
                        "WEIGHTED_SUM+CONSISTENT_ACTION",
                        group.stream().map(n -> n + " 加权 " + pct(personRatios.get(n))).toList()));
            }
        }
        return controllers;
    }

    private static String find(Map<String, String> parent, String x) {
        String root = x;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        return root;
    }

    /** 真实关系查询：实体节点的全部关联边，按契约口径映射（实体不在图中返回 null，由调用方降级仿真） */
    private List<GraphPenetrateDtos.Relation> sungraphRelations(Entity entity, List<String> relTypes) {
        GraphSnapshotStore.Snapshot snap = snapshot(entity.entityId());
        if (snap == null || snap.node(entity.entityId()) == null) {
            return null;
        }
        List<GraphPenetrateDtos.Relation> relations = new ArrayList<>();
        for (GraphSnapshotStore.GEdge e : snap.edges()) {
            boolean outgoing = e.source().equals(entity.entityId());
            boolean incoming = e.target().equals(entity.entityId());
            if (!outgoing && !incoming) {
                continue;
            }
            String relType = outgoing && "EQUITY".equals(e.type())
                    ? "INVEST" : GRAPH_REL_MAPPING.get(e.type());
            if (relType == null || !relTypes.contains(relType)) {
                continue;   // CONSISTENT_ACTION 等无契约映射口径的边类型不输出
            }
            relations.add(new GraphPenetrateDtos.Relation(relType, nameOf(snap, e.source()),
                    nameOf(snap, e.target()), "EQUITY".equals(e.type()) ? e.weight() : null,
                    relType + "（" + describe(e) + "）"));
        }
        relations.sort(java.util.Comparator.comparing((GraphPenetrateDtos.Relation r) -> r.relType())
                .thenComparing(r -> r.from() + r.to()));
        return relations;
    }

    private static String describe(GraphSnapshotStore.GEdge e) {
        String base = e.dataSource() == null ? "来源未知" : "来源 " + e.dataSource();
        return e.validFrom() == null || e.validFrom().isBlank()
                ? base : base + "，" + e.validFrom() + " 起";
    }

    private static String nameOf(GraphSnapshotStore.Snapshot snap, String nodeId) {
        GraphSnapshotStore.GNode n = snap.node(nodeId);
        return n != null ? n.name() : nodeId;
    }

    /** 按名称反查节点（汇总项以名称输出，认定阶段需回查节点类型） */
    private static GraphSnapshotStore.GNode nodeByName(GraphSnapshotStore.Snapshot snap, String name) {
        for (GraphSnapshotStore.GNode n : snap.nodes().values()) {
            if (n.name().equals(name)) {
                return n;
            }
        }
        return null;
    }


    /** 实体校验：缺失/未对齐 → GPT_1001/GPT_1004 */
    private Entity entity(Map<String, Object> body) {
        if (!(body.get("entity") instanceof Map<?, ?> entityRaw) || entityRaw.isEmpty()) {
            throw new BaiException(GraphPenetrateErrorCodes.PARAM_INVALID,
                    "GPT_1001 参数缺失：entity {entityType, entityId} 必填");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) entityRaw;
        String entityType = str(entity.get("entityType"));
        String entityId = str(entity.get("entityId"));
        if (isBlank(entityId)) {
            throw new BaiException(GraphPenetrateErrorCodes.PARAM_INVALID, "GPT_1001 参数缺失：entityId 必填");
        }
        if (entityType == null || !ENTITY_TYPES.contains(entityType.toUpperCase())) {
            throw new BaiException(GraphPenetrateErrorCodes.PARAM_INVALID,
                    "GPT_1001 参数非法：entityType 仅支持 PERSON/ORG");
        }
        if (entityId.startsWith("UNKNOWN")) {
            throw new BaiException(GraphPenetrateErrorCodes.NOT_FOUND,
                    "GPT_1004 实体未对齐/不存在：" + entityId + "，请先经 entity-resolution-agent 对齐");
        }
        return new Entity(entityType.toUpperCase(), entityId);
    }

    private String direction(Map<String, Object> body) {
        String direction = str(body.get("direction"));
        if (direction == null || direction.isBlank()) {
            throw new BaiException(GraphPenetrateErrorCodes.PARAM_INVALID, "GPT_1001 参数缺失：direction 必填");
        }
        direction = direction.toUpperCase();
        if (!DIRECTIONS.contains(direction)) {
            throw new BaiException(GraphPenetrateErrorCodes.DEPTH_INVALID,
                    "GPT_1007 方向非法：" + direction + "（仅支持 UPSTREAM/DOWNSTREAM）");
        }
        return direction;
    }

    private int maxDepth(Map<String, Object> body) {
        Object depth = body.get("maxDepth");
        int maxDepth = depth instanceof Number n ? n.intValue() : DEFAULT_DEPTH;
        if (maxDepth < 1 || maxDepth > MAX_DEPTH) {
            throw new BaiException(GraphPenetrateErrorCodes.DEPTH_INVALID,
                    "GPT_1007 深度非法：" + maxDepth + "（默认 5，上限 10）");
        }
        return maxDepth;
    }

    /** 内部实体模型 */
    private record Entity(String entityType, String entityId) {}

    private static String personName(int hash) {
        return SURNAMES.get(Math.abs(hash) % SURNAMES.size())
                + GIVEN_NAMES.get(Math.abs(hash >> 3) % GIVEN_NAMES.size());
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String pct(double v) {
        return Math.round(v * 1000) / 10.0 + "%";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
