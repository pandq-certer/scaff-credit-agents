package com.bai.kretrieve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * SunBrain V23 知识库检索客户端（只读，调用方 service 侧负责降级）。
 * <p>接口：POST {base-url}/api/v1/knowledge-bases/{bindingRef}/search，
 * 鉴权 X-API-Key（retrieve 能力域 + KB 白名单），需 SunBrain 侧开启 SUNBRAIN_V23_CORE_ENABLED。
 * <p>响应包裹 {"success":true,"data":{hits:[...]}}；hits 字段：ordinal/documentRef/chunkRef/
 * documentVersionKey/text/score/citation{page,offset}。任何失败（网络/超时/非 2xx/success=false）
 * 抛 {@link SunbrainKbException}，由上层降级内置语料快照，不直接外抛给调用方。
 */
@Component
public class SunbrainKbClient {

    private static final Logger log = LoggerFactory.getLogger(SunbrainKbClient.class);

    /** V23 单条命中（已裁剪为检索侧所需字段） */
    public record KbHit(int ordinal, String documentRef, String chunkRef, String text,
                        double score, int page, int offset) {}

    /** V23 检索结果（含溯源追踪标识，便于双端排查） */
    public record KbSearchResult(String searchRef, String traceId, String status, List<KbHit> hits) {}

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String bindingRef;
    private final String purpose;
    private final Duration timeout;

    public SunbrainKbClient(
            @Value("${kb.sunbrain.base-url:http://localhost:8080}") String baseUrl,
            @Value("${kb.sunbrain.api-key:}") String apiKey,
            @Value("${kb.sunbrain.binding-ref:}") String bindingRef,
            @Value("${kb.sunbrain.purpose:knowledge-retrieve}") String purpose,
            @Value("${kb.sunbrain.timeout-ms:5000}") long timeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.bindingRef = bindingRef;
        this.purpose = purpose;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    /** provider=sunbrain 是否具备调用条件（api-key 与 binding-ref 均已配置），缺失时上层降级 */
    public boolean configured() {
        return !apiKey.isBlank() && !bindingRef.isBlank();
    }

    /**
     * V23 混合检索。documentTypes 为可选过滤（null/空表示不过滤）。
     */
    public KbSearchResult search(String query, int topK, List<String> documentTypes) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.put("query", query);
        body.put("topK", topK);
        if (documentTypes != null && !documentTypes.isEmpty()) {
            ArrayNode types = body.putObject("filters").putArray("documentTypes");
            documentTypes.forEach(types::add);
        }
        body.put("purpose", purpose);
        String payload = mapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/knowledge-bases/" + bindingRef + "/search"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-API-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SunbrainKbException("HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        JsonNode root = mapper.readTree(response.body());
        if (!root.path("success").asBoolean(false)) {
            throw new SunbrainKbException("success=false: " + abbreviate(response.body()));
        }
        JsonNode data = root.path("data");
        List<KbHit> hits = new ArrayList<>();
        for (JsonNode h : data.path("hits")) {
            hits.add(new KbHit(
                    h.path("ordinal").asInt(),
                    h.path("documentRef").asText(""),
                    h.path("chunkRef").asText(""),
                    h.path("text").asText(""),
                    h.path("score").asDouble(0),
                    h.path("citation").path("page").asInt(0),
                    h.path("citation").path("offset").asInt(0)));
        }
        KbSearchResult result = new KbSearchResult(
                data.path("searchRef").asText(""), data.path("traceId").asText(""),
                data.path("status").asText(""), hits);
        log.info("sunbrain V23 检索: bindingRef={}, searchRef={}, traceId={}, 命中={}",
                bindingRef, result.searchRef(), result.traceId(), hits.size());
        return result;
    }

    private static String abbreviate(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) + "…" : s);
    }

    /** sunbrain 调用失败（网络/超时/鉴权/协议），上层捕获后降级内置语料 */
    public static class SunbrainKbException extends IOException {
        public SunbrainKbException(String message) {
            super(message);
        }
    }
}
