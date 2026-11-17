package com.bai.scene.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容大模型客户端（火山方舟 coding plan 端点 /chat/completions）。
 * <p>统一超时/重试/异常封装：任何失败抛 {@link LlmException}，由调用方决定降级策略；
 * deepseek-v4 默认返回 reasoning_content 思维链，对话层通过 thinking=disabled 关闭以降低时延，
 * 解析时仅取 message.content。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final LlmProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public LlmClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /** 对话消息（role: system/user/assistant） */
    public record Msg(String role, String content) {}

    public static class LlmException extends RuntimeException {
        public LlmException(String message) { super(message); }
        public LlmException(String message, Throwable cause) { super(message, cause); }
    }

    /** 同步对话补全（默认关闭思维链，适合路由/槽位/短文案场景） */
    public String chat(List<Msg> messages) {
        return chat(messages, true);
    }

    /** 同步对话补全；noThinking=true 时关闭思维链 */
    public String chat(List<Msg> messages, boolean noThinking) {
        return chat(messages, noThinking, props.getTimeoutSeconds());
    }

    /** 同步对话补全；可覆盖单次超时秒数（轻量路由类调用用短超时快速降级） */
    public String chat(List<Msg> messages, boolean noThinking, int timeoutSeconds) {
        if (!props.isEnabled() || props.getBaseUrl() == null || props.getApiKey() == null) {
            throw new LlmException("LLM 未启用或配置缺失");
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return callOnce(messages, noThinking, timeoutSeconds);
            } catch (RuntimeException e) {
                last = e;
                log.warn("LLM 调用失败（第 {} 次）：{}", attempt, e.getMessage());
            }
        }
        throw last;
    }

    private String callOnce(List<Msg> messages, boolean noThinking, int timeoutSeconds) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", props.getModel());
            body.put("messages", messages);
            if (noThinking) {
                body.put("thinking", Map.of("type", "disabled"));
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getBaseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new LlmException("HTTP " + resp.statusCode() + "：" + shortOf(resp.body()));
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText("").isBlank()) {
                throw new LlmException("响应缺少 message.content：" + shortOf(resp.body()));
            }
            return content.asText();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("LLM 调用异常：" + e.getMessage(), e);
        }
    }

    private String shortOf(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    /**
     * 批量向量化（路由语义召回用；OpenAI 兼容 /embeddings 协议）。
     * <p>任何失败抛 {@link LlmException}，由调用方决定降级策略（词面召回兜底）；
     * 返回向量顺序与入参顺序一一对应。
     */
    public List<double[]> embed(List<String> inputs) {
        if (props.getEmbedApiKey() == null || props.getEmbedBaseUrl() == null || props.getEmbedModel() == null) {
            throw new LlmException("embedding 未配置");
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", props.getEmbedModel());
            body.put("input", inputs);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getEmbedBaseUrl() + "/embeddings"))
                    .timeout(Duration.ofSeconds(props.getEmbedTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getEmbedApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new LlmException("embedding HTTP " + resp.statusCode() + "：" + shortOf(resp.body()));
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != inputs.size()) {
                throw new LlmException("embedding 响应数量不符：" + shortOf(resp.body()));
            }
            double[][] out = new double[inputs.size()][];
            for (JsonNode d : data) {
                int idx = d.path("index").asInt(-1);
                JsonNode arr = d.path("embedding");
                if (idx < 0 || idx >= out.length || !arr.isArray() || arr.isEmpty()) {
                    throw new LlmException("embedding 响应格式异常");
                }
                double[] v = new double[arr.size()];
                for (int i = 0; i < arr.size(); i++) v[i] = arr.get(i).asDouble();
                out[idx] = v;
            }
            return List.of(out);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("embedding 调用异常：" + e.getMessage(), e);
        }
    }
}
