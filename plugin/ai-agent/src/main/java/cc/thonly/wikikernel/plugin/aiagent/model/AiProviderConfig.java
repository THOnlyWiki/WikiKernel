package cc.thonly.wikikernel.plugin.aiagent.model;

import cc.thonly.wikikernel.plugin.aiagent.service.AiProviderRegistry;

import java.util.Locale;

/**
 * 一个 AI 厂商的**生效配置**（由 {@link AiProviderRegistry} 合并「内置目录 + 用户密钥 + 站点密钥 +
 * LocalSettings.yaml」后构造，迁移前是 core 的 {@code AiProperties.Provider}）。
 *
 * @param type        协议类型：{@code openai} / {@code anthropic} / {@code gemini} / {@code mock}
 * @param baseUrl     API 基地址（空 = 用适配器内置的官方地址）
 * @param apiKey      API 密钥（本地服务 / mock 可空）
 * @param model       模型名
 * @param label       界面显示名（空 = 用厂商 id）
 * @param temperature 采样温度（可空 = 用厂商默认）
 * @param maxTokens   单次回复最大 token（可空 = 用厂商默认）
 * @param proxy       该厂商的代理（空 = 用全局代理；再空 = 直连）
 */
public record AiProviderConfig(String type, String baseUrl, String apiKey, String model, String label,
                               Double temperature, Integer maxTokens, String proxy) {

    public AiProviderConfig {
        type = type == null || type.isBlank() ? "openai" : type.trim().toLowerCase(Locale.ROOT);
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null ? "" : model.trim();
        label = label == null ? "" : label.trim();
        proxy = proxy == null ? "" : proxy.trim();
    }

    /** 只有基本字段的简版。 */
    public AiProviderConfig(String type, String baseUrl, String apiKey, String model) {
        this(type, baseUrl, apiKey, model, "", null, null, "");
    }
}
