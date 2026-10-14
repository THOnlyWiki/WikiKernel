package cc.thonly.wikikernel.plugin.aiagent.service;


import cc.thonly.wikikernel.plugin.aiagent.model.AiException;
import cc.thonly.wikikernel.plugin.aiagent.model.AiMessage;
import cc.thonly.wikikernel.plugin.aiagent.model.AiProviderConfig;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages 协议（{@code type: anthropic}）：
 * {@code POST {base-url}/v1/messages}，{@code x-api-key: …} + {@code anthropic-version}。
 * <p>
 * system 单独放在 {@code system} 字段；其余消息映射为 {@code messages:[{role:user|assistant, content}]}。
 */
public class AnthropicProvider implements AiProvider {

    private final String id;
    private final String label;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final Double temperature;
    private final Integer maxTokens;
    private final AiHttp http;

    public AnthropicProvider(String id, AiProviderConfig config, AiHttp http) {
        this.id = id;
        this.label = config.label().isBlank() ? id : config.label();
        this.model = config.model();
        this.baseUrl = stripSlash(config.baseUrl().isBlank()
                ? "https://api.anthropic.com" : config.baseUrl());
        this.apiKey = config.apiKey();
        this.temperature = config.temperature();
        this.maxTokens = config.maxTokens() == null || config.maxTokens() <= 0 ? 4096 : config.maxTokens();
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
        return "anthropic";
    }

    @Override
    public boolean available() {
        return !model.isBlank() && !apiKey.isEmpty();
    }

    @Override
    public String complete(List<AiMessage> messages) {
        StringBuilder system = new StringBuilder();
        List<Map<String, String>> chat = new ArrayList<>();
        for (AiMessage message : messages) {
            if (AiMessage.SYSTEM.equals(message.role())) {
                if (!system.isEmpty()) {
                    system.append("\n\n");
                }
                system.append(message.content());
            } else {
                chat.add(Map.of("role", message.role(), "content", message.content()));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        if (!system.isEmpty()) {
            body.put("system", system.toString());
        }
        body.put("messages", chat);
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        JsonNode root = http.postJson(baseUrl + "/v1/messages",
                Map.of("x-api-key", apiKey, "anthropic-version", "2023-06-01"), body);
        JsonNode blocks = root.path("content");
        StringBuilder text = new StringBuilder();
        if (blocks.isArray()) {
            for (JsonNode block : blocks) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }
        }
        if (text.isEmpty()) {
            throw new AiException("模型没有返回内容：" + root.path("error").path("message").asText("（无错误详情）"));
        }
        return text.toString();
    }

    private static String stripSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
