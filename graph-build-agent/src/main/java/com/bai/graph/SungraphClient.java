package com.bai.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * sungraph（SunGraph Knowledge Explorer）HTTP 客户端（graph.provider=sungraph 时使用）。
 * <p>对接端点（本地免鉴权，见 semantica explorer）：
 * <ul>
 *   <li>POST /api/import —— multipart 文件导入（合并语义），返回 nodes_added/edges_added</li>
 *   <li>GET /api/graph/nodes?type=&amp;limit=&amp;cursor= —— 节点列表（type 过滤 + 游标分页）</li>
 *   <li>GET /api/graph/edges?limit=&amp;cursor= —— 边列表（游标分页）</li>
 * </ul>
 * 任何 IO/协议异常统一抛 {@link SungraphException}，由上层按 GRB_2001 降级（缓存图兜底，链路不中断）。
 */
@Component
public class SungraphClient {

    private static final Logger log = LoggerFactory.getLogger(SungraphClient.class);
    private static final int PAGE_LIMIT = 500;

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;

    public SungraphClient(@Value("${graph.sungraph.base-url:http://localhost:8000}") String baseUrl,
                          @Value("${graph.sungraph.read-timeout-seconds:30}") long readTimeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // 强制 HTTP/1.1：uvicorn(h11) 不接受 Java HttpClient 默认的 h2c 升级头（Upgrade: h2c → 400 Invalid HTTP request received）
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        this.readTimeout = Duration.ofSeconds(readTimeoutSeconds);
        log.info("SungraphClient 初始化: baseUrl={}, readTimeout={}s（导入超时固定 120s：sungraph 首次导入含本体对齐会阻塞事件循环）",
                this.baseUrl, readTimeoutSeconds);
    }

    private final Duration readTimeout;

    /** 导入异常（sungraph 不可达/非 200/响应非法） */
    public static final class SungraphException extends IOException {
        public SungraphException(String message) { super(message); }
    }

    /** 导入结果（对应 ImportResponse：nodes_added/edges_added） */
    public record ImportResult(long nodesAdded, long edgesAdded) {}

    /**
     * 导入一张图（JSON 载荷，格式同 semantica 导入文件：
     * {graph_id, nodes:[{id,type,content,properties,valid_from}], edges:[{id,source_id,target_id,type,weight,properties}]}）。
     * sungraph 为合并语义（同 id 覆盖、异 id 新增）。
     */
    public ImportResult importGraph(String jsonPayload) throws IOException, InterruptedException {
        String boundary = "grb" + UUID.randomUUID().toString().replace("-", "");
        byte[] fileBody = jsonPayload.getBytes(StandardCharsets.UTF_8);
        var head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"graph-build.json\"\r\n"
                + "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        var tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        var out = new java.io.ByteArrayOutputStream();
        out.write(head);
        out.write(fileBody);
        out.write(tail);
        byte[] multipartBody = out.toByteArray();

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/import"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(multipartBody)))
                .timeout(Duration.ofSeconds(120))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new SungraphException("sungraph /api/import 非 200：" + resp.statusCode() + " " + abbreviate(resp.body()));
        }
        JsonNode body = mapper.readTree(resp.body());
        if (body == null || !"success".equals(body.path("status").asText())) {
            throw new SungraphException("sungraph /api/import 状态非 success：" + abbreviate(resp.body()));
        }
        return new ImportResult(body.path("nodes_added").asLong(0), body.path("edges_added").asLong(0));
    }

    /** 节点列表（type 过滤可空；自动翻页取全量，上限防御 100 页） */
    public JsonNode listNodes(String type) throws IOException, InterruptedException {
        List<JsonNode> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 100; page++) {
            StringBuilder url = new StringBuilder(baseUrl + "/api/graph/nodes?limit=" + PAGE_LIMIT);
            if (type != null && !type.isBlank()) {
                url.append("&type=").append(java.net.URLEncoder.encode(type, StandardCharsets.UTF_8));
            }
            if (cursor != null) {
                url.append("&cursor=").append(java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString())).GET()
                    .timeout(readTimeout).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new SungraphException("sungraph /nodes 非 200：" + resp.statusCode());
            }
            JsonNode body = mapper.readTree(resp.body());
            body.path("nodes").forEach(all::add);
            if (!body.path("has_more").asBoolean(false)) {
                break;
            }
            cursor = body.path("next_cursor").asText(null);
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        return mapper.valueToTree(all);
    }

    /** 边列表（自动翻页取全量） */
    public JsonNode listEdges() throws IOException, InterruptedException {
        List<JsonNode> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 100; page++) {
            StringBuilder url = new StringBuilder(baseUrl + "/api/graph/edges?limit=" + PAGE_LIMIT);
            if (cursor != null) {
                url.append("&cursor=").append(java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString())).GET()
                    .timeout(readTimeout).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new SungraphException("sungraph /edges 非 200：" + resp.statusCode());
            }
            JsonNode body = mapper.readTree(resp.body());
            body.path("edges").forEach(all::add);
            if (!body.path("has_more").asBoolean(false)) {
                break;
            }
            cursor = body.path("next_cursor").asText(null);
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        return mapper.valueToTree(all);
    }

    private String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
