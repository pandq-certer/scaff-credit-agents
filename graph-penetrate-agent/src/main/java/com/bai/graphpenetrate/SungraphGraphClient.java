package com.bai.graphpenetrate;

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

/**
 * sungraph（SunGraph Knowledge Explorer）图数据读取客户端（graph.provider=sungraph 时使用）。
 * <p>对接端点（本地免鉴权，与 graph-build-agent SungraphClient 同源约定）：
 * <ul>
 *   <li>GET /api/graph/nodes?type=&amp;limit=&amp;cursor= —— 节点列表（type 过滤 + 游标分页）</li>
 *   <li>GET /api/graph/edges?limit=&amp;cursor= —— 边列表（游标分页）</li>
 * </ul>
 * sungraph 无图算法接口（native_graph_algorithm=false）：穿透/环检测在原子层 Java 实现，
 * 本客户端仅负责拉取全量节点+边。任何 IO/协议异常统一抛 {@link SungraphGraphException}。
 */
@Component
public class SungraphGraphClient {

    private static final Logger log = LoggerFactory.getLogger(SungraphGraphClient.class);
    private static final int PAGE_LIMIT = 500;

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final Duration readTimeout;

    public SungraphGraphClient(@Value("${graph.sungraph.base-url:http://localhost:8000}") String baseUrl,
                               @Value("${graph.sungraph.read-timeout-seconds:30}") long readTimeoutSeconds) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // 强制 HTTP/1.1：uvicorn(h11) 不接受 Java HttpClient 默认的 h2c 升级头（Upgrade: h2c → 400）
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        this.readTimeout = Duration.ofSeconds(readTimeoutSeconds);
        log.info("SungraphGraphClient 初始化: baseUrl={}, readTimeout={}s", this.baseUrl, readTimeoutSeconds);
    }

    /** sungraph 读取异常（不可达/非 200/响应非法） */
    public static final class SungraphGraphException extends IOException {
        public SungraphGraphException(String message) { super(message); }
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
            HttpResponse<String> resp = http.send(request(url.toString()), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new SungraphGraphException("sungraph /nodes 非 200：" + resp.statusCode());
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
            HttpResponse<String> resp = http.send(request(url.toString()), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new SungraphGraphException("sungraph /edges 非 200：" + resp.statusCode());
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

    private HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).GET().timeout(readTimeout).build();
    }
}
