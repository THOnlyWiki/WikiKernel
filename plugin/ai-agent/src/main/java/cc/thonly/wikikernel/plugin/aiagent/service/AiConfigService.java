package cc.thonly.wikikernel.plugin.aiagent.service;

import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.aiagent.model.AiProperties;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@link AiConfig} 的插件实现（迁移前是 core 的 {@code AiConfigImpl} + {@code wiki_user_ai_key} 表 + Mapper）：
 * <ul>
 *     <li>用户级密钥：插件自己的表 {@code wiki_user_ai_key}（实体 {@code plugin.ai-agent/.../data/WikiUserAiKey}，
 *     由 {@code AiAgentPlugin.schemaEntities()} 声明、core 在 onStart 前建表），数据访问走
 *     {@link PluginDatabase}（JDBC）；</li>
 *     <li>站点级密钥 / 运行时设置：站点设置 {@code ai.provider.<id>.*} / {@code ai.*} / {@code ai-agent.allow-normal-users}，
 *     经 {@link SiteConfig} 读**写**（与 core 的 /init、站点设置页同一个存储，键名不变）；</li>
 *     <li>{@code wikikernel.ai.*}（LocalSettings.yaml / 命令行 / 环境变量）：插件自己的
 *     {@link AiProperties}（{@code @ConfigurationProperties}，插件子容器绑定）。</li>
 * </ul>
 * 优先级与迁移前一致：用户密钥 &gt; 站点设置密钥 &gt; LocalSettings.yaml。
 */
@Service
public class AiConfigService implements AiConfig {

    /** 站点级密钥的设置键前缀（与迁移前一致）。 */
    public static final String SITE_PREFIX = "ai.provider.";

    private final SiteConfig siteConfig;
    private final PluginDatabase database;
    private final AiProperties properties;

    public AiConfigService(SiteConfig siteConfig, PluginDatabase database, AiProperties properties) {
        this.siteConfig = siteConfig;
        this.database = database;
        this.properties = properties;
    }

    @Override
    public Settings settings() {
        String defaultProvider = siteConfig.get("ai.default-provider",
                properties.getDefaultProvider() == null ? "" : properties.getDefaultProvider());
        boolean allowNormal = siteConfig.getBoolean(ALLOW_NORMAL_KEY,
                Boolean.TRUE.equals(properties.getAllowNormalUsers()));
        int maxSteps = siteConfig.getInt("ai.max-steps", properties.getMaxSteps());
        int timeout = siteConfig.getInt("ai.timeout-seconds", properties.getTimeoutSeconds());
        String proxy = siteConfig.get("ai.proxy", properties.getProxy() == null ? "" : properties.getProxy());
        return new Settings(defaultProvider, allowNormal, maxSteps, timeout, proxy,
                properties.getMaxReadChars(), properties.getSessionTtlMinutes(),
                properties.getMaxSessions(), properties.getMaxMessages());
    }

    @Override
    public Entry user(long userId, String providerId) {
        String id = providerId == null ? "" : providerId.trim();
        if (userId <= 0) {
            return new Entry(id, "", "", "", "");
        }
        Optional<DbRow> row = findUserRow(userId, id);
        if (row.isEmpty()) {
            return new Entry(id, "", "", "", "");
        }
        DbRow value = row.get();
        return new Entry(id, value.string("api_key"), value.string("base_url"),
                value.string("model"), value.string("proxy"));
    }

    @Override
    public Entry site(String providerId) {
        String id = providerId == null ? "" : providerId.trim();
        return new Entry(id,
                siteConfig.get(SITE_PREFIX + id + ".key", ""),
                siteConfig.get(SITE_PREFIX + id + ".base-url", ""),
                siteConfig.get(SITE_PREFIX + id + ".model", ""),
                siteConfig.get(SITE_PREFIX + id + ".proxy", ""));
    }

    @Override
    public Entry yaml(String providerId) {
        String id = providerId == null ? "" : providerId.trim();
        AiProperties.Provider provider = properties.getProviders().get(id);
        if (provider == null) {
            return new Entry(id, "", "", "", "");
        }
        // yaml 里还带协议类型 / 显示名 / 采样参数（插件构造适配器要用）
        return new Entry(id, provider.getApiKey(), provider.getBaseUrl(), provider.getModel(),
                provider.getProxy(), provider.getType(), provider.getLabel(),
                provider.getTemperature(), provider.getMaxTokens());
    }

    @Override
    public boolean yamlHasKey(String providerId) {
        return !yaml(providerId).apiKey().isBlank();
    }

    @Override
    public Map<String, Entry> yamlProviders() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        properties.getProviders().forEach((id, provider) -> entries.put(id,
                new Entry(id, provider.getApiKey(), provider.getBaseUrl(), provider.getModel(),
                        provider.getProxy(), provider.getType(), provider.getLabel(),
                        provider.getTemperature(), provider.getMaxTokens())));
        return Map.copyOf(entries);
    }

    @Override
    public void saveUser(long userId, String providerId, String apiKey, String baseUrl, String model,
                         String proxy, boolean clear) {
        if (userId <= 0) {
            return;
        }
        String id = providerId == null ? "" : providerId.trim();
        Optional<DbRow> existing = findUserRow(userId, id);
        if (clear) {
            existing.ifPresent(row -> database.update("DELETE FROM wiki_user_ai_key WHERE id = ?",
                    row.longValue("id", 0)));
            return;
        }
        // 与迁移前一致：null 字段 = 不改；只填了空串的字段也照写（明确清空某一项）
        String currentKey = existing.map(row -> row.string("api_key")).orElse("");
        String currentBaseUrl = existing.map(row -> row.string("base_url")).orElse("");
        String currentModel = existing.map(row -> row.string("model")).orElse("");
        String currentProxy = existing.map(row -> row.string("proxy")).orElse("");
        String nextKey = apiKey != null ? apiKey.trim() : currentKey;
        String nextBaseUrl = baseUrl != null ? baseUrl.trim() : currentBaseUrl;
        String nextModel = model != null ? model.trim() : currentModel;
        String nextProxy = proxy != null ? proxy.trim() : currentProxy;
        LocalDateTime now = LocalDateTime.now();
        if (existing.isEmpty()) {
            if (nextKey.isBlank() && nextBaseUrl.isBlank() && nextModel.isBlank() && nextProxy.isBlank()) {
                return; // 全空 = 不落一行（与迁移前「插完发现全空就删」等价）
            }
            database.insert("INSERT INTO wiki_user_ai_key"
                            + "(user_id, provider_id, api_key, base_url, model, proxy, update_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    userId, id, nextKey, nextBaseUrl, nextModel, nextProxy, now);
            return;
        }
        long rowId = existing.get().longValue("id", 0);
        if (nextKey.isBlank() && nextBaseUrl.isBlank() && nextModel.isBlank() && nextProxy.isBlank()) {
            database.update("DELETE FROM wiki_user_ai_key WHERE id = ?", rowId);
            return;
        }
        database.update("UPDATE wiki_user_ai_key SET api_key = ?, base_url = ?, model = ?,"
                        + " proxy = ?, update_at = ? WHERE id = ?",
                nextKey, nextBaseUrl, nextModel, nextProxy, now, rowId);
    }

    @Override
    public void saveSite(String providerId, String apiKey, String baseUrl, String model, String proxy, boolean clear) {
        String id = providerId == null ? "" : providerId.trim();
        if (clear) {
            siteConfig.set(SITE_PREFIX + id + ".key", "");
            siteConfig.set(SITE_PREFIX + id + ".base-url", "");
            siteConfig.set(SITE_PREFIX + id + ".model", "");
            siteConfig.set(SITE_PREFIX + id + ".proxy", "");
            return;
        }
        if (apiKey != null) {
            siteConfig.set(SITE_PREFIX + id + ".key", apiKey.trim());
        }
        if (baseUrl != null) {
            siteConfig.set(SITE_PREFIX + id + ".base-url", baseUrl.trim());
        }
        if (model != null) {
            siteConfig.set(SITE_PREFIX + id + ".model", model.trim());
        }
        if (proxy != null) {
            siteConfig.set(SITE_PREFIX + id + ".proxy", proxy.trim());
        }
    }

    @Override
    public void saveAllowNormalUsers(boolean allow) {
        siteConfig.set(ALLOW_NORMAL_KEY, Boolean.toString(allow));
    }

    @Override
    public String sourceOf(long userId, String providerId) {
        if (user(userId, providerId).configured()) {
            return "user";
        }
        if (site(providerId).configured()) {
            return "site";
        }
        if (yaml(providerId).configured()) {
            return "yaml";
        }
        return "none";
    }

    /** 用户密钥行（不存在返回空）。 */
    private Optional<DbRow> findUserRow(long userId, String providerId) {
        return database.queryOne("SELECT * FROM wiki_user_ai_key WHERE user_id = ? AND provider_id = ? LIMIT 1",
                userId, providerId);
    }
}
