package com.bai.scene.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 原子服务调用器：POST JSON + X-Request-Id / Authorization 头，解析 {requestId, code, message, data} 统一响应。
 * 单机联调直连 localhost:port；调用失败不抛出，返回失败结果由编排层按场景 E6xxx 降级策略处理。
 */
@Component
public class AtomicInvoker {

    private static final Logger log = LoggerFactory.getLogger(AtomicInvoker.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper om = new ObjectMapper();

    @Value("${scene.atomic-base-url:http://localhost}")
    private String baseUrl;

    public record InvokeResult(boolean ok, JsonNode data, String error) {
        public static InvokeResult fail(String error) { return new InvokeResult(false, null, error); }
    }

    public InvokeResult post(String urlAndPath, Object payload) {
        String url = urlAndPath.startsWith("http") ? urlAndPath : baseUrl + urlAndPath;
        String requestId = UUID.randomUUID().toString().replace("-", "");
        try {
            String body = om.writeValueAsString(payload == null ? Map.of() : payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-Request-Id", requestId)
                    .header("Authorization", "Bearer scene-bff-internal")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = om.readTree(resp.body());
            if (resp.statusCode() != 200 || !"0".equals(root.path("code").asText(""))) {
                String msg = root.path("message").asText("HTTP " + resp.statusCode());
                log.warn("原子调用失败: url={}, http={}, code={}, message={}", url, resp.statusCode(),
                        root.path("code").asText(""), msg);
                return InvokeResult.fail(msg);
            }
            return new InvokeResult(true, root.path("data"), null);
        } catch (Exception e) {
            log.warn("原子调用异常: url={}, error={}", url, e.getMessage());
            return InvokeResult.fail(e.getMessage());
        }
    }
}
