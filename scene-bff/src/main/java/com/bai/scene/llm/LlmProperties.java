package com.bai.scene.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 大模型接入配置（OpenAI 兼容协议；火山方舟 coding plan 专用端点）。
 * 环境变量可覆盖：LLM_ENABLED / LLM_BASE_URL / LLM_API_KEY / LLM_MODEL。
 * LLM 不可用或关闭时，对话层自动回退关键词路由+剧本模板，不阻断已验证链路。
 */
@Component
@ConfigurationProperties(prefix = "bai.llm")
public class LlmProperties {

    /** 是否启用大模型对话层 */
    private boolean enabled;

    /** OpenAI 兼容 base-url（如 https://ark.cn-beijing.volces.com/api/coding/v3） */
    private String baseUrl;

    /** API Key（生产环境经环境变量注入，不入库） */
    private String apiKey;

    /** 模型名（如 deepseek-v4-flash） */
    private String model;

    /** 单次调用超时秒数 */
    private int timeoutSeconds = 40;

    /** embedding 接口地址（语义召回用；如 https://api.siliconflow.cn/v1） */
    private String embedBaseUrl;

    /** embedding API Key（环境变量注入，不入库） */
    private String embedApiKey;

    /** embedding 模型名（如 BAAI/bge-m3） */
    private String embedModel;

    /** embedding 单次调用超时秒数（路由轻量调用，超时即降级词面召回） */
    private int embedTimeoutSeconds = 8;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getEmbedBaseUrl() { return embedBaseUrl; }
    public void setEmbedBaseUrl(String embedBaseUrl) { this.embedBaseUrl = embedBaseUrl; }
    public String getEmbedApiKey() { return embedApiKey; }
    public void setEmbedApiKey(String embedApiKey) { this.embedApiKey = embedApiKey; }
    public String getEmbedModel() { return embedModel; }
    public void setEmbedModel(String embedModel) { this.embedModel = embedModel; }
    public int getEmbedTimeoutSeconds() { return embedTimeoutSeconds; }
    public void setEmbedTimeoutSeconds(int embedTimeoutSeconds) { this.embedTimeoutSeconds = embedTimeoutSeconds; }
}
