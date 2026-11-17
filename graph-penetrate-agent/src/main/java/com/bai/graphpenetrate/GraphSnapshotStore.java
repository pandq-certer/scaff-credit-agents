package com.bai.graphpenetrate;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * sungraph 图快照缓存（GPT_2001 降级语义的「缓存图兜底」载体）。
 * <p>TTL 内复用内存快照；过期后后台刷新失败时继续返回最近一次成功快照（stale），
 * 仅当从未成功拉取过快照时才抛异常，由上层降级确定性仿真，链路不中断。
 */
@Component
public class GraphSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(GraphSnapshotStore.class);

    /** 图节点（name 取 content；COMPANY↔ORG 与契约口径映射） */
    public record GNode(String id, String type, String name) {}

    /** 图边（weight 为 EQUITY 持股比例口径，0<w≤1；source/validFrom 用于关系明细展示） */
    public record GEdge(String id, String source, String target, String type,
                        double weight, String dataSource, String validFrom) {}

    /** 图快照（节点按 id 索引，边全量；不可变） */
    public record Snapshot(Map<String, GNode> nodes, List<GEdge> edges) {
        public GNode node(String id) { return nodes.get(id); }
    }

    private final SungraphGraphClient client;
    private final long ttlMillis;

    private volatile Snapshot current;
    private volatile long fetchedAtMillis;

    public GraphSnapshotStore(SungraphGraphClient client,
                              @Value("${graph.snapshot-ttl-seconds:60}") long ttlSeconds) {
        this.client = client;
        this.ttlMillis = ttlSeconds * 1000L;
    }

    /**
     * 获取图快照：TTL 内直接返回缓存；过期则刷新——刷新失败时若存在 stale 快照则返回 stale（WARN 一次），
     * 从未成功过才抛 IOException（上层按 GPT_2001 降级仿真）。
     */
    public Snapshot get() throws IOException, InterruptedException {
        Snapshot snap = current;
        if (snap != null && System.currentTimeMillis() - fetchedAtMillis < ttlMillis) {
            return snap;
        }
        try {
            Snapshot fresh = fetch();
            current = fresh;
            fetchedAtMillis = System.currentTimeMillis();
            return fresh;
        } catch (IOException | InterruptedException e) {
            Snapshot stale = current;
            if (stale != null) {
                log.warn("GPT_2001 sungraph 刷新失败，使用缓存图快照兜底（TTL 过期 stale）: err={}", e.getMessage());
                return stale;
            }
            throw e;
        }
    }

    /** 拉取全量节点+边并构建快照（仅信贷节点类型与 6 类关系全保留，映射由算法层按需过滤） */
    private Snapshot fetch() throws IOException, InterruptedException {
        JsonNode rawNodes = client.listNodes(null);
        JsonNode rawEdges = client.listEdges();
        Map<String, GNode> nodes = new LinkedHashMap<>();
        for (JsonNode n : rawNodes) {
            String type = n.path("type").asText("");
            // 契约实体口径仅 PERSON/ORG：COLLATERAL 等非实体节点不入索引
            if (!"PERSON".equals(type) && !"COMPANY".equals(type)) {
                continue;
            }
            JsonNode pp = n.path("properties").path("properties");
            String name = n.path("content").asText(pp.path("名称").asText(n.path("id").asText()));
            nodes.put(n.path("id").asText(), new GNode(n.path("id").asText(), type, name));
        }
        List<GEdge> edges = new ArrayList<>();
        for (JsonNode e : rawEdges) {
            String type = e.path("type").asText("").toUpperCase();
            // 读接口字段为 source/target（导入端点为 source_id/target_id），做双兼容
            String src = firstNonBlank(e.path("source").asText(null), e.path("source_id").asText(null));
            String tgt = firstNonBlank(e.path("target").asText(null), e.path("target_id").asText(null));
            if (src == null || tgt == null || src.isBlank() || tgt.isBlank()) {
                continue;
            }
            double w = e.path("weight").asDouble(1.0);
            JsonNode props = e.path("properties");
            edges.add(new GEdge(e.path("id").asText(), src, tgt, type,
                    w > 0 && w <= 1 ? w : 1.0,
                    props.path("source").asText("core"),
                    firstNonBlank(props.path("valid_from").asText(null), e.path("valid_from").asText(null))));
        }
        Snapshot snap = new Snapshot(java.util.Collections.unmodifiableMap(nodes), List.copyOf(edges));
        log.info("sungraph 图快照刷新完成: nodes={}, edges={}", snap.nodes().size(), snap.edges().size());
        return snap;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
