package cc.thonly.wikikernel.plugin.aiagent.service;


import cc.thonly.wikikernel.plugin.aiagent.model.AiException;
import cc.thonly.wikikernel.plugin.aiagent.model.AiMessage;
import cc.thonly.wikikernel.plugin.aiagent.model.AiProviderConfig;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini 协议（{@code type: gemini}）：
 * {@code POST {base-url}/v1beta/models/{model}:generateContent?key=…}。
 * <p>
 * system → {@code systemInstruction}；对话 → {@code contents:[{role:user|model, parts:[{text}]}]}；
 * Gemini 要求角色交替，这里会把连续同角色消息合并后再发送。
 */
public class GeminiProvider implements AiProvider {

    private final String id;
    private final String label;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final Double temperature;
    private final Integer maxTokens;
    private final AiHttp http;

    public GeminiProvider(String id, AiProviderConfig config, AiHttp http) {
        this.id = id;
        this.label = config.label().isBlank() ? id : config.label();
        this.model = config.model();
        this.baseUrl = stripSlash(config.baseUrl().isBlank()
                ? "https://generativelanguage.googleapis.com" : config.baseUrl());
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
        return "gemini";
    }

    @Override
    public boolean available() {
        return !model.isBlank() && !apiKey.isEmpty();
    }

    @Override
    public String complete(List<AiMessage> messages) {
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> contents = new ArrayList<>();
        for (AiMessage message : messages) {
            if (AiMessage.SYSTEM.equals(message.role())) {
                if (!system.isEmpty()) {
                    system.append("\n\n");
                }
                system.append(message.content());
                continue;
            }
            String role = AiMessage.ASSISTANT.equals(message.role()) ? "model" : "user";
            Map<String, Object> last = contents.isEmpty() ? null : contents.get(contents.size() - 1);
            if (last != null && role.equals(last.get("role"))) {
                // 角色交替：把连续的同角色消息合并
                @SuppressWarnings("unchecked")
                List<Map<String, String>> parts = (List<Map<String, String>>) last.get("parts");
                parts.add(Map.of("text", message.content()));
            } else {
                List<Map<String, String>> parts = new ArrayList<>();
                parts.add(Map.of("text", message.content()));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("role", role);
                entry.put("parts", parts);
                contents.add(entry);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (!system.isEmpty()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system.toString()))));
        }
        body.put("contents", contents);
        Map<String, Object> generation = new LinkedHashMap<>();
        if (temperature != null) {
            generation.put("temperature", temperature);
        }
        if (maxTokens != null && maxTokens > 0) {
            generation.put("maxOutputTokens", maxTokens);
        }
        if (!generation.isEmpty()) {
            body.put("generationConfig", generation);
        }
        String url = baseUrl + "/v1beta/models/" + model + ":generateContent?key="
                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        JsonNode root = http.postJson(url, Map.of(), body);
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        StringBuilder text = new StringBuilder();
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                text.append(part.path("text").asText());
            }
        }
        if (text.isEmpty()) {
            String reason = root.path("promptFeedback").path("blockReason").asText("");
            throw new AiException("模型没有返回内容"
                    + (reason.isEmpty() ? "：" + root.path("error").path("message").asText("（无错误详情）")
                    : "（被安全策略拦截：" + reason + "）"));
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
