package com.bai.graph;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 图谱构建服务（BIA-DTA-026）。构建为异步任务仿真（PENDING→RUNNING→DONE，sleep 200ms 模拟图数据库写入，
 * 标注：仿真，用于联调）；stats 为确定性伪值，goldenLinked=0.99×edgesAdded 取整。
 * <p>幂等：同 requestId+dataVersion 不重复构建——幂等缓存 7 天（FULL 构建以 mode+dataVersion 参与键；
 * INCREMENTAL 无 dataVersion 时不可达），实现仍用 IdempotencyStore(7 天 TTL)；
 * 限流 10 QPS（build/increment/jobs/nodes/edges 统一口径）
 */
@Service
public class GraphBuildService {

    private static final Logger log = LoggerFactory.getLogger(GraphBuildService.class);

    private static final int MAX_EVENTS = 10000;
    private static final Pattern DATA_VERSION = Pattern.compile("\\d{8}-\\d{4}");
    /** 关系类型字典（未知类型 → GRB_1006） */
    private static final Set<String> RELATION_TYPES = Set.of(
            "EQUITY", "GUARANTEE", "EMPLOYMENT", "FAMILY", "TRANSACTION", "CONSISTENT_ACTION");
    /** 幂等缓存 7 天（同 requestId+dataVersion 不重复构建） */
    private static final long IDEMPOTENCY_TTL_7D = Duration.ofDays(7).toMillis();

    /** 任务状态表（内存）：jobId → 状态 */
    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();
    private final IdempotencyStore idempotency = new IdempotencyStore(IDEMPOTENCY_TTL_7D);
    private final AtomicLong jobSeq = new AtomicLong();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 图存储提供方：mock=确定性仿真（默认）；sungraph=写真实图库 SunGraph Knowledge Explorer */
    @Value("${graph.provider:mock}")
    private String provider;
    private final SungraphClient sungraph;

    public GraphBuildService(SungraphClient sungraph) {
        this.sungraph = sungraph;
    }

    private boolean useSungraph() {
        return "sungraph".equalsIgnoreCase(provider);
    }

    /** 全量/增量构建（异步任务：先受理 PENDING，后台线程 RUNNING→DONE） */
    public GraphBuildDtos.BuildResult build(GraphBuildDtos.BuildRequest req, String requestId) {
        if (req == null || req.mode() == null || req.mode().isBlank()) {
            throw new BaiException(GraphBuildErrorCodes.PARAM_INVALID, "GRB_1001 参数缺失：mode 必填（FULL/INCREMENTAL）");
        }
        String mode = req.mode().toUpperCase();
        if (!"FULL".equals(mode) && !"INCREMENTAL".equals(mode)) {
            throw new BaiException(GraphBuildErrorCodes.PARAM_INVALID, "GRB_1001 参数非法：mode 须为 FULL/INCREMENTAL");
        }
        validateScope(req.scope());
        String dataVersion = validateDataVersion(req.dataVersion(), "INCREMENTAL".equals(mode));
        String key = "build:" + requestId + ":" + mode + ":" + dataVersion;
        GraphBuildDtos.BuildResult cached = idempotency.get("graph", key);
        if (cached != null) {
            log.info("图谱构建幂等命中（同 requestId+dataVersion 不重复构建，缓存 7 天）: requestId={}, jobId={}",
                    requestId, cached.jobId());
            return cached;
        }
        GraphBuildDtos.BuildResult result = submit(mode, dataVersion, 0, requestId);
        idempotency.put("graph", key, result);
        return result;
    }

    /** 增量事件入库构建（同样走异步任务） */
    public GraphBuildDtos.BuildResult increment(GraphBuildDtos.IncrementRequest req, String requestId) {
        if (req == null || req.events() == null || req.events().isEmpty()) {
            throw new BaiException(GraphBuildErrorCodes.PARAM_INVALID, "GRB_1001 参数缺失：events 必填");
        }
        if (req.events().size() > MAX_EVENTS) {
            throw new BaiException(GraphBuildErrorCodes.OVER_LIMIT,
                    "GRB_1005 events 超 " + MAX_EVENTS + " 条（实际 " + req.events().size() + "），请分批");
        }
        String dataVersion = validateDataVersion(req.dataVersion(), true);
        String key = "increment:" + requestId + ":" + dataVersion;
        GraphBuildDtos.BuildResult cached = idempotency.get("graph", key);
        if (cached != null) {
            log.info("增量构建幂等命中（同 requestId+dataVersion 不重复构建，缓存 7 天）: requestId={}, jobId={}",
                    requestId, cached.jobId());
            return cached;
        }
        GraphBuildDtos.BuildResult result = submit("INCREMENTAL", dataVersion, req.events().size(), requestId, req.events());
        idempotency.put("graph", key, result);
        return result;
    }

    /** 任务状态查询（GET /jobs/{jobId}；不存在 → GRB_1004） */
    public GraphBuildDtos.JobView job(String jobId) {
        JobState state = jobs.get(jobId);
        if (state == null) {
            throw new BaiException(GraphBuildErrorCodes.JOB_NOT_FOUND, "GRB_1004 jobId 不存在：" + jobId);
        }
        return new GraphBuildDtos.JobView(state.jobId, state.mode, state.status, state.dataVersion, state.stats,
                state.createdAt, System.currentTimeMillis() - state.createdMillis);
    }

    /** 节点列表：sungraph 模式读真实图库（仅信贷三类节点），异常降级仿真 */
    public GraphBuildDtos.NodesResult nodes() {
        if (useSungraph()) {
            try {
                List<GraphBuildDtos.NodeView> nodes = new java.util.ArrayList<>();
                for (String type : List.of("COMPANY", "PERSON", "COLLATERAL")) {
                    sungraph.listNodes(type).forEach(n -> nodes.add(toNodeView(n)));
                }
                nodes.sort(java.util.Comparator.comparing(GraphBuildDtos.NodeView::nodeId));
                return new GraphBuildDtos.NodesResult(nodes.size(), List.copyOf(nodes));
            } catch (Exception e) {
                log.warn("GRB_2001 sungraph 节点读取失败，降级仿真列表: err={}", e.getMessage());
            }
        }
        List<GraphBuildDtos.NodeView> nodes = List.of(
                new GraphBuildDtos.NodeView("N00001", "COMPANY", "北京华信科技有限公司", "2024-01-01", "2099-12-31", "core"),
                new GraphBuildDtos.NodeView("N00002", "COMPANY", "上海润泽实业有限公司", "2024-01-01", "2099-12-31", "core"),
                new GraphBuildDtos.NodeView("N00003", "COMPANY", "深圳市恒益贸易发展有限公司", "2024-01-01", "2099-12-31", "core"),
                new GraphBuildDtos.NodeView("N00004", "PERSON", "张伟", "2024-01-01", "2099-12-31", "core"),
                new GraphBuildDtos.NodeView("N00005", "PERSON", "李娜", "2024-01-01", "2099-12-31", "core"),
                new GraphBuildDtos.NodeView("N00006", "PERSON", "王强", "2024-01-01", "2099-12-31", "channel"),
                new GraphBuildDtos.NodeView("N00007", "PERSON", "赵敏", "2024-01-01", "2099-12-31", "channel"),
                new GraphBuildDtos.NodeView("N00008", "COMPANY", "杭州云启信息技术有限公司", "2024-06-01", "2099-12-31", "channel"),
                new GraphBuildDtos.NodeView("N00009", "PERSON", "孙丽", "2025-01-01", "2099-12-31", "credit-report"),
                new GraphBuildDtos.NodeView("N00010", "COLLATERAL", "房产-京A-0007号", "2025-03-01", "2099-12-31", "core"),
                new GraphBuildDtos.NodeView("N00011", "COMPANY", "华信投资合伙企业（有限合伙）", "2024-06-01", "2099-12-31", "core"));
        return new GraphBuildDtos.NodesResult(nodes.size(), nodes);
    }

    /** 边列表：sungraph 模式读真实图库（仅 6 类信贷关系），异常降级仿真 */
    public GraphBuildDtos.EdgesResult edges() {
        if (useSungraph()) {
            try {
                List<GraphBuildDtos.EdgeView> edges = new java.util.ArrayList<>();
                sungraph.listEdges().forEach(e -> {
                    GraphBuildDtos.EdgeView v = toEdgeView(e);
                    if (v != null) {
                        edges.add(v);
                    }
                });
                edges.sort(java.util.Comparator.comparing(GraphBuildDtos.EdgeView::edgeId));
                return new GraphBuildDtos.EdgesResult(edges.size(), List.copyOf(edges));
            } catch (Exception e) {
                log.warn("GRB_2001 sungraph 边读取失败，降级仿真列表: err={}", e.getMessage());
            }
        }
        List<GraphBuildDtos.EdgeView> edges = List.of(
                new GraphBuildDtos.EdgeView("E00001", "N00004", "N00001", "EQUITY", "2024-01-01", "2099-12-31", "core"),
                new GraphBuildDtos.EdgeView("E00002", "N00005", "N00002", "EQUITY", "2024-01-01", "2099-12-31", "core"),
                new GraphBuildDtos.EdgeView("E00003", "N00001", "N00002", "GUARANTEE", "2024-03-01", "2099-12-31", "core"),
                new GraphBuildDtos.EdgeView("E00004", "N00002", "N00003", "GUARANTEE", "2024-05-01", "2099-12-31", "core"),
                new GraphBuildDtos.EdgeView("E00005", "N00004", "N00001", "EMPLOYMENT", "2024-01-01", "2099-12-31", "channel"),
                new GraphBuildDtos.EdgeView("E00006", "N00005", "N00002", "EMPLOYMENT", "2024-02-01", "2099-12-31", "channel"),
                new GraphBuildDtos.EdgeView("E00007", "N00004", "N00006", "FAMILY", "2024-01-01", "2099-12-31", "credit-report"),
                new GraphBuildDtos.EdgeView("E00008", "N00005", "N00007", "FAMILY", "2024-01-01", "2099-12-31", "credit-report"),
                new GraphBuildDtos.EdgeView("E00009", "N00001", "N00008", "TRANSACTION", "2024-07-01", "2099-12-31", "core"),
                new GraphBuildDtos.EdgeView("E00010", "N00002", "N00008", "TRANSACTION", "2024-08-01", "2099-12-31", "core"),
                new GraphBuildDtos.EdgeView("E00011", "N00003", "N00001", "TRANSACTION", "2024-09-01", "2099-12-31", "core"),
                new GraphBuildDtos.EdgeView("E00012", "N00006", "N00007", "CONSISTENT_ACTION", "2025-01-01", "2099-12-31", "channel"),
                new GraphBuildDtos.EdgeView("E00013", "N00007", "N00008", "CONSISTENT_ACTION", "2025-02-01", "2099-12-31", "channel"),
                new GraphBuildDtos.EdgeView("E00014", "N00008", "N00010", "GUARANTEE", "2025-03-01", "2099-12-31", "core"),
                new GraphBuildDtos.EdgeView("E00015", "N00011", "N00001", "EQUITY", "2024-06-01", "2099-12-31", "core"),
                new GraphBuildDtos.EdgeView("E00016", "N00006", "N00011", "EQUITY", "2024-06-01", "2099-12-31", "core"),
                new GraphBuildDtos.EdgeView("E00017", "N00003", "N00001", "GUARANTEE", "2024-07-01", "2099-12-31", "core"),
                new GraphBuildDtos.EdgeView("E00018", "N00009", "N00008", "EQUITY", "2025-01-01", "2099-12-31", "core"));
        return new GraphBuildDtos.EdgesResult(edges.size(), edges);
    }

    // ---------- sungraph 映射 ----------

    /** sungraph 节点 → NodeView（name 取 content，时态取顶层字段，source 取嵌套 properties） */
    private GraphBuildDtos.NodeView toNodeView(JsonNode n) {
        JsonNode pp = n.path("properties").path("properties");
        String source = pp.path("source").asText(pp.path("来源").asText("core"));
        return new GraphBuildDtos.NodeView(
                n.path("id").asText(),
                n.path("type").asText(),
                n.path("content").asText(pp.path("名称").asText("")),
                trimDate(n.path("valid_from").asText(null)),
                trimDate(n.path("valid_until").asText(null)),
                source);
    }

    /** sungraph 边 → EdgeView（仅 6 类信贷关系，其余返回 null 过滤） */
    private GraphBuildDtos.EdgeView toEdgeView(JsonNode e) {
        String rel = e.path("type").asText("").toUpperCase();
        if (!RELATION_TYPES.contains(rel)) {
            return null;
        }
        JsonNode props = e.path("properties");
        String source = props.path("source").asText("core");
        String vf = props.hasNonNull("valid_from") ? props.path("valid_from").asText()
                : e.path("valid_from").asText(null);
        String vt = props.hasNonNull("valid_until") ? props.path("valid_until").asText()
                : e.path("valid_until").asText(null);
        return new GraphBuildDtos.EdgeView(
                e.path("id").asText(),
                e.path("source_id").asText(),
                e.path("target_id").asText(),
                rel,
                trimDate(vf),
                trimDate(vt),
                source);
    }

    /** ISO 日期截断（2024-01-01T00:00:00+00:00 → 2024-01-01），非日期格式原样返回 */
    private String trimDate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 10 && s.charAt(4) == '-' && s.charAt(10) == 'T' ? s.substring(0, 10) : s;
    }

    // ---------- 任务编排 ----------

    /** 受理任务：幂等键已在上游处理；PENDING → 后台线程 RUNNING →（sungraph 写入或 200ms 仿真）→ DONE */
    private GraphBuildDtos.BuildResult submit(String mode, String dataVersion, int eventCount, String requestId) {
        return submit(mode, dataVersion, eventCount, requestId, null);
    }

    /**
     * 增量事件 schema（graph.provider=sungraph 时的映射约定，doc 口径）：
     * <ul>
     *   <li>NODE_UPSERT：{op, nodeId, nodeType, name, validFrom, validTo, source}</li>
     *   <li>EDGE_UPSERT：{op, edgeId, fromNode, toNode, relationType, validFrom, validTo, source}</li>
     * </ul>
     * 未知 op 事件跳过计数；source ∈ core/channel/credit-report/external（缺省 core）。
     */
    private GraphBuildDtos.BuildResult submit(String mode, String dataVersion, int eventCount,
                                              String requestId, List<Map<String, Object>> events) {
        String jobId = "GRJ" + dayStamp() + String.format("%07d", jobSeq.incrementAndGet());
        JobState state = new JobState(jobId, mode, dataVersion, eventCount);
        boolean real = useSungraph();
        List<Map<String, Object>> evs = events;
        jobs.put(jobId, state);
        Thread worker = new Thread(() -> {
            try {
                state.status = "RUNNING";
                if (real) {
                    GraphBuildDtos.Stats stats = importToSungraph(mode, evs, jobId);
                    state.stats = stats;
                } else {
                    Thread.sleep(200); // 模拟图数据库写入耗时（仿真，用于联调）
                    state.stats = computeStats(mode, dataVersion, eventCount);
                }
                state.status = "DONE";
                log.info("图谱构建任务完成({}): jobId={}, mode={}, dataVersion={}, stats={}",
                        real ? "sungraph 实写" : "仿真，用于联调", jobId, mode, dataVersion, state.stats);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                state.status = "FAILED";
            } catch (Exception e) {
                // GRB_2001 降级语义：sungraph 不可用时缓存图兜底（仿真统计），链路不中断
                state.stats = computeStats(mode, dataVersion, eventCount);
                state.status = "DONE";
                log.warn("GRB_2001 sungraph 写入失败，降级仿真统计（缓存图兜底）: jobId={}, err={}", jobId, e.getMessage());
            }
        }, "graph-build-" + jobId);
        worker.setDaemon(true);
        worker.start();
        log.info("图谱构建受理: requestId={}, jobId={}, mode={}, dataVersion={}, events={}, provider={}",
                requestId, jobId, mode, dataVersion, eventCount, real ? "sungraph" : "mock");
        return new GraphBuildDtos.BuildResult(jobId, mode, state.status, null);
    }

    /** 真实写入 sungraph：FULL=种子图整体导入；INCREMENTAL=事件映射导入。返回实写统计。 */
    private GraphBuildDtos.Stats importToSungraph(String mode, List<Map<String, Object>> events, String jobId)
            throws IOException, InterruptedException {
        SungraphClient.ImportResult r;
        if ("FULL".equals(mode)) {
            String seed = readSeedGraph();
            r = sungraph.importGraph(seed);
            log.info("sungraph 种子图导入完成: jobId={}, nodesAdded={}, edgesAdded={}", jobId, r.nodesAdded(), r.edgesAdded());
        } else {
            String payload = eventsToPayload(events);
            r = sungraph.importGraph(payload);
            log.info("sungraph 增量导入完成: jobId={}, events={}, nodesAdded={}, edgesAdded={}",
                    jobId, events == null ? 0 : events.size(), r.nodesAdded(), r.edgesAdded());
        }
        long goldenLinked = Math.round(r.edgesAdded() * 0.99);
        return new GraphBuildDtos.Stats(r.nodesAdded(), 0, r.edgesAdded(), 0, goldenLinked);
    }

    /** 读取类路径种子图（与 mock 确定性 N/E 数据集逐条对齐） */
    private String readSeedGraph() throws IOException {
        try (var reader = new InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("credit-demo-graph.json"), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        }
    }

    /** 增量事件 → sungraph 导入载荷 */
    private String eventsToPayload(List<Map<String, Object>> events) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("graph_id", "credit-increment-graph");
        ArrayNode nodes = root.putArray("nodes");
        ArrayNode edges = root.putArray("edges");
        if (events != null) {
            for (Map<String, Object> ev : events) {
                String op = String.valueOf(ev.getOrDefault("op", ""));
                if ("NODE_UPSERT".equals(op)) {
                    ObjectNode n = nodes.addObject();
                    n.put("id", String.valueOf(ev.get("nodeId")));
                    n.put("type", String.valueOf(ev.getOrDefault("nodeType", "PERSON")));
                    n.put("content", String.valueOf(ev.getOrDefault("name", ev.get("nodeId"))));
                    String vf = strOrNull(ev.get("validFrom"));
                    if (vf != null) {
                        n.put("valid_from", vf);
                    }
                    n.putObject("properties").putObject("properties").put("source", String.valueOf(ev.getOrDefault("source", "core")));
                } else if ("EDGE_UPSERT".equals(op)) {
                    ObjectNode e = edges.addObject();
                    e.put("id", String.valueOf(ev.get("edgeId")));
                    e.put("source_id", String.valueOf(ev.get("fromNode")));
                    e.put("target_id", String.valueOf(ev.get("toNode")));
                    e.put("type", String.valueOf(ev.getOrDefault("relationType", "TRANSACTION")));
                    e.put("weight", 1.0);
                    ObjectNode props = e.putObject("properties");
                    props.put("source", String.valueOf(ev.getOrDefault("source", "core")));
                    String vf = strOrNull(ev.get("validFrom"));
                    String vt = strOrNull(ev.get("validTo"));
                    if (vf != null) {
                        props.put("valid_from", vf);
                    }
                    if (vt != null) {
                        props.put("valid_until", vt);
                    }
                }
                // 未知 op：跳过
            }
        }
        return mapper.writeValueAsString(root);
    }

    private String strOrNull(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }

    /** 确定性统计（基于 mode+dataVersion+eventCount 哈希；goldenLinked=0.99×edgesAdded 取整） */
    private GraphBuildDtos.Stats computeStats(String mode, String dataVersion, int eventCount) {
        int h = (mode + "|" + dataVersion + "|" + eventCount).hashCode() & 0x7fffffff;
        long nodesAdded;
        long nodesUpdated;
        long edgesAdded;
        long edgesUpdated;
        if ("FULL".equals(mode)) {
            nodesAdded = 1200 + h % 800;
            nodesUpdated = 0;
            edgesAdded = 3500 + h % 1500;
            edgesUpdated = 0;
        } else {
            nodesAdded = 80 + h % 120;
            nodesUpdated = 40 + h % 60;
            edgesAdded = 150 + h % 200;
            edgesUpdated = 60 + h % 80;
        }
        long goldenLinked = Math.round(edgesAdded * 0.99);
        return new GraphBuildDtos.Stats(nodesAdded, nodesUpdated, edgesAdded, edgesUpdated, goldenLinked);
    }

    /** scope.relationTypes 字典校验（未知 → GRB_1006） */
    private void validateScope(GraphBuildDtos.Scope scope) {
        if (scope == null || scope.relationTypes() == null) {
            return;
        }
        for (String t : scope.relationTypes()) {
            if (t == null || !RELATION_TYPES.contains(t)) {
                throw new BaiException(GraphBuildErrorCodes.DATA_INVALID,
                        "GRB_1006 relationTypes 非法：" + t + "（可用 " + RELATION_TYPES + "）");
            }
        }
    }

    /** dataVersion 校验：INCREMENTAL 必填（缺失 → GRB_1006），格式 yyyyMMdd-HHmm（非法 → GRB_1006） */
    private String validateDataVersion(String dataVersion, boolean required) {
        if (dataVersion == null || dataVersion.isBlank()) {
            if (required) {
                throw new BaiException(GraphBuildErrorCodes.DATA_INVALID, "GRB_1006 参数缺失：INCREMENTAL 模式 dataVersion 必填");
            }
            return "-";
        }
        if (!DATA_VERSION.matcher(dataVersion).matches()) {
            throw new BaiException(GraphBuildErrorCodes.DATA_INVALID, "GRB_1006 参数非法：dataVersion 格式须为 yyyyMMdd-HHmm");
        }
        return dataVersion;
    }

    private String dayStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    /** 任务状态（线程安全：status/stats 由后台线程单写、查询线程读，volatile 保证可见性） */
    private static final class JobState {
        final String jobId;
        final String mode;
        final String dataVersion;
        final int eventCount;
        final String createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        final long createdMillis = System.currentTimeMillis();
        volatile String status = "PENDING";
        volatile GraphBuildDtos.Stats stats;

        private JobState(String jobId, String mode, String dataVersion, int eventCount) {
            this.jobId = jobId;
            this.mode = mode;
            this.dataVersion = dataVersion;
            this.eventCount = eventCount;
        }
    }
}
