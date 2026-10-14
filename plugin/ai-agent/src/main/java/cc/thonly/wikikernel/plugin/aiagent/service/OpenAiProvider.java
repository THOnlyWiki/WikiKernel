package cc.thonly.wikikernel.plugin.aiagent.service;


import cc.thonly.wikikernel.plugin.aiagent.model.AiException;
import cc.thonly.wikikernel.plugin.aiagent.model.AiMessage;
import cc.thonly.wikikernel.plugin.aiagent.model.AiProviderConfig;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议（{@code type: openai}）：
 * {@code POST {base-url}/chat/completions}，{@code Authorization: Bearer …}。
 * <p>
 * 覆盖 OpenAI / DeepSeek / Groq / OpenRouter / Together / 硅基流动 / Ollama / LM Studio / vLLM 等
 * （本地服务把 api-key 留空即可）。
 */
public class OpenAiProvider implements AiProvider {

    private final String id;
    private final String label;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final Double temperature;
    private final Integer maxTokens;
    private final AiHttp http;

    public OpenAiProvider(String id, AiProviderConfig config, AiHttp http) {
        this.id = id;
        this.label = config.label().isBlank() ? id : config.label();
        this.model = config.model();
        this.baseUrl = stripSlash(config.baseUrl().isBlank()
                ? "https://api.openai.com/v1" : config.baseUrl());
        this.apiKey = config.apiKey();
        this.temperature = config.temperature();
        this.maxTokens = config.maxTokens();
        this.http = http;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String type() {
        return "openai";
    }

    @Override
    public boolean available() {
        return !model.isBlank();
    }

    @Override
    public String complete(List<AiMessage> messages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream()
                .map(message -> Map.of("role", message.role(), "content", message.content()))
                .toList());
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens != null && maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (!apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        JsonNode root = http.postJson(baseUrl + "/chat/completions", headers, body);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
            throw new AiException("模型没有返回内容：" + root.path("error").path("message").asText("（无错误详情）"));
        }
        return content.asText();
    }

    private static String stripSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
