package cc.thonly.wikikernel.plugin.aiagent.service;

import cc.thonly.wikikernel.plugin.aiagent.service.AiConfig;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.aiagent.AiAgentSupport;
import cc.thonly.wikikernel.plugin.aiagent.model.AiProviderConfig;
import cc.thonly.wikikernel.plugin.aiagent.model.AiVendorCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 厂商注册表（**按用户**解析）：内置目录（{@link AiVendorCatalog}）里的适配器全部就绪，
 * 只需要“有密钥/有地址”，来源优先级：**用户自己的密钥 &gt; 站点设置密钥 &gt; LocalSettings.yaml**。
 * <p>
 * 迁移前这是 core 的 {@code AiProviderRegistry}：密钥与优先级判断改走
 * {@code common.service.AiConfig} 桥（用户表 / 站点设置 / yaml），插件里不再直接读写数据库与站点设置。
 * 其中 {@code mock} 是内置演示厂商：不需要任何配置，永远可选（新装的站点也能直接用）。
 * 密钥只在服务端；对前端只暴露 id / 显示名 / 模型 / 来源。
 * <p>
 * 显示名与提示**不是 Java 文案**：厂商目录给的是消息键（{@link AiVendorCatalog.Vendor#labelKey()} /
 * {@link AiVendorCatalog.Vendor#hintKey()}），这里用 {@link AiAgentSupport#message} 按当前界面语言解析
 * （缺键回退成**厂商 id**，不回退中文）。
 */
@Component
public class AiProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiProviderRegistry.class);

    /**
     * 一个“可用厂商”选择。
     *
     * @param vendorId 厂商 id（内置目录 id，或 yaml 里自定义的 id）
     * @param type     协议：openai / gemini / anthropic / mock
     * @param label    显示名（已按界面语言解析；缺键 = 厂商 id）
     * @param model    生效模型
     * @param source   来源：user（我的密钥）/ site（站点设置）/ yaml（LocalSettings.yaml）/ mock（内置演示）
     * @param provider 实际调用用的适配器
     */
    public record Selection(String vendorId, String type, String label, String model,
                            String source, AiProvider provider) {
    }

    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper;
    private final AiAgentSupport support;
    private final Map<String, AiHttp> httpByProxy = new ConcurrentHashMap<>();

    public AiProviderRegistry(AiConfig aiConfig, ObjectMapper objectMapper, AiAgentSupport support) {
        this.aiConfig = aiConfig;
        this.objectMapper = objectMapper;
        this.support = support;
        log.info("AI 厂商目录已加载：{}（内置 {} 家；演示厂商恒可用）",
                String.join("、", AiVendorCatalog.ids()), AiVendorCatalog.ids().size());
    }

    // ==================== 厂商显示名 / 提示（插件 i18n） ====================

    /**
     * 厂商显示名：解析 {@code aiAgent.vendor.<id>.label}（当前界面语言）。
     * 缺键时回退成**厂商 id**——id 是界面与配置里的数据标识，不是中文兜底。
     */
    public String vendorLabel(String vendorId) {
        String id = Strings.trimToEmpty(vendorId);
        return AiVendorCatalog.byId(id).map(this::vendorLabel).orElse(id);
    }

    /** 厂商显示名（目录行；同样缺键回退成 id）。 */
    private String vendorLabel(AiVendorCatalog.Vendor vendor) {
        return support.message(vendor.labelKey(), vendor.id());
    }

    /**
     * 厂商提示：解析 {@code aiAgent.vendor.<id>.hint}（当前界面语言）。
     * 漏翻时按项目约定回显**键名**（消息源行为），不会回退中文。
     */
    private String vendorHint(AiVendorCatalog.Vendor vendor) {
        return support.message(vendor.hintKey());
    }

    /** 演示厂商（{@code type=mock}）的 id 列表：特殊页据此让前端**锁住提示词输入**。 */
    public List<String> demoVendorIds() {
        return AiVendorCatalog.demoVendorIds();
    }

    // ==================== 查询 ====================

    /** 该用户可选的厂商（按目录顺序；Mock 始终在最前，其余按默认/来源排序）。 */
    public List<Selection> selections(long userId) {
        List<Selection> result = new ArrayList<>();
        for (AiVendorCatalog.Vendor vendor : AiVendorCatalog.all()) {
            selection(userId, vendor).ifPresent(result::add);
        }
        // LocalSettings.yaml 里额外定义的自定义厂商（不在一览表里的 id）
        aiConfig.yamlProviders().forEach((id, entry) -> {
            if (AiVendorCatalog.byId(id).isPresent()) {
                return;
            }
            String type = entry.type().isBlank() ? "openai" : entry.type().trim().toLowerCase(Locale.ROOT);
            String label = entry.label().isBlank() ? id : entry.label();
            String model = Strings.trimToEmpty(entry.model());
            if ("mock".equals(type) || !Strings.isBlank(entry.apiKey()) || !Strings.isBlank(entry.baseUrl())) {
                AiProvider instance = build(id, type, label, entry.baseUrl(), model, entry.apiKey(),
                        entry.temperature(), entry.maxTokens(), entry.proxy());
                result.add(new Selection(id, type, label, model, "yaml", instance));
            }
        });
        return result;
    }

    /** 单个厂商对该用户的解析；不可用（没密钥等）返回空。 */
    public Optional<Selection> selection(long userId, String vendorId) {
        Optional<AiVendorCatalog.Vendor> maybe = AiVendorCatalog.byId(vendorId);
        return maybe.isEmpty() ? Optional.empty() : selection(userId, maybe.get());
    }

    private Optional<Selection> selection(long userId, AiVendorCatalog.Vendor vendor) {
        AiConfig.Entry userEntry = userId <= 0 ? empty(vendor.id()) : aiConfig.user(userId, vendor.id());
        AiConfig.Entry siteEntry = aiConfig.site(vendor.id());
        AiConfig.Entry yaml = aiConfig.yaml(vendor.id());

        String apiKey = firstNonBlank(userEntry.apiKey(), siteEntry.apiKey(), yaml.apiKey());
        String baseUrl = firstNonBlank(userEntry.baseUrl(), siteEntry.baseUrl(), yaml.baseUrl(), vendor.baseUrl());
        String model = firstNonBlank(userEntry.model(), siteEntry.model(), yaml.model(), vendor.model());
        String proxy = firstNonBlank(userEntry.proxy(), siteEntry.proxy(), yaml.proxy(), settings().proxy());
        // 显示名：yaml 里写死的名字优先（自定义厂商），否则解析插件文案（缺键回退成厂商 id）
        String label = yaml.label().isBlank() ? vendorLabel(vendor) : yaml.label();

        String source;
        if (AiVendorCatalog.MOCK_ID.equals(vendor.id())) {
            source = "mock";
        } else if (!Strings.isBlank(userEntry.apiKey()) || (!vendor.needsKey() && userEntry.configured())) {
            source = "user";
        } else if (!Strings.isBlank(siteEntry.apiKey()) || (!vendor.needsKey() && siteEntry.configured())) {
            source = "site";
        } else {
            source = "yaml";
        }

        boolean available;
        if (AiVendorCatalog.MOCK_ID.equals(vendor.id())) {
            available = true;
        } else if (vendor.needsKey()) {
            available = !Strings.isBlank(apiKey);
        } else {
            // 不需要密钥的（Ollama / 自定义本地端点）：填过地址或模型才出现在列表里
            available = yaml.configured() || userEntry.configured() || siteEntry.configured();
        }
        if (!available || (AiVendorCatalog.DEMO_TYPE.equals(vendor.type())
                && !AiVendorCatalog.MOCK_ID.equals(vendor.id()))) {
            return Optional.empty();
        }
        if (model.isBlank()) {
            return Optional.empty();
        }
        AiProvider provider = build(vendor.id(), vendor.type(), label, baseUrl, model, apiKey,
                yaml.temperature(), yaml.maxTokens(), proxy);
        return Optional.of(new Selection(vendor.id(), vendor.type(), label, model, source, provider));
    }

    /** 默认厂商：yaml 的 default-provider → 用户自己的密钥 → 站点密钥 → 其余可用 → Mock。 */
    public Optional<Selection> defaultSelection(long userId) {
        List<Selection> all = selections(userId);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        String configured = Strings.trimToEmpty(settings().defaultProvider());
        if (!configured.isEmpty()) {
            Optional<Selection> preferred = all.stream()
                    .filter(selection -> selection.vendorId().equals(configured)).findFirst();
            if (preferred.isPresent()) {
                return preferred;
            }
        }
        for (String source : List.of("user", "site", "yaml")) {
            Optional<Selection> match = all.stream().filter(selection -> source.equals(selection.source())).findFirst();
            if (match.isPresent()) {
                return match;
            }
        }
        return all.stream().filter(selection -> !AiVendorCatalog.MOCK_ID.equals(selection.vendorId())).findFirst()
                .or(() -> all.stream().findFirst());
    }

    public String defaultVendorId(long userId) {
        return defaultSelection(userId).map(Selection::vendorId).orElse("");
    }

    /** 全部内置厂商 id（界面配置页用）。 */
    public List<AiVendorCatalog.Vendor> vendors() {
        return AiVendorCatalog.all();
    }

    /** 该用户对每个内置厂商的可用来源（诊断 / 界面）。 */
    public Map<String, String> sources(long userId) {
        Map<String, String> result = new LinkedHashMap<>();
        for (AiVendorCatalog.Vendor vendor : AiVendorCatalog.all()) {
            result.put(vendor.id(), aiConfig.sourceOf(userId, vendor.id()));
        }
        return result;
    }

    /** 供诊断：该用户可用的厂商 id 集合。 */
    public Set<String> availableVendorIds(long userId) {
        Set<String> ids = new LinkedHashSet<>();
        selections(userId).forEach(selection -> ids.add(selection.vendorId()));
        return ids;
    }

    /**
     * 配置页（参数设置 / 站点密钥页）用的厂商行：含尾号掩码、默认 base-url/模型、来源与可用性，
     * **不含任何密钥明文**（用户级 / 站点级两套字段都在里面，界面各取所需）。
     */
    public List<Map<String, Object>> settingsRows(long userId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AiVendorCatalog.Vendor vendor : AiVendorCatalog.all()) {
            AiConfig.Entry userEntry = userId <= 0 ? empty(vendor.id()) : aiConfig.user(userId, vendor.id());
            AiConfig.Entry siteEntry = aiConfig.site(vendor.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", vendor.id());
            // 界面文案（站点密钥面板 / 参数设置 → AI 代理）在这里就解析成当前语言
            row.put("label", vendorLabel(vendor));
            row.put("type", vendor.type());
            row.put("hint", vendorHint(vendor));
            row.put("needsKey", vendor.needsKey());
            row.put("defaultBaseUrl", vendor.baseUrl());
            row.put("defaultModel", vendor.model());
            row.put("userConfigured", userEntry.configured());
            row.put("userMask", AiConfig.mask(userEntry.apiKey()));
            row.put("userBaseUrl", userEntry.baseUrl());
            row.put("userModel", userEntry.model());
            row.put("userProxy", userEntry.proxy());
            row.put("siteConfigured", siteEntry.configured());
            row.put("siteMask", AiConfig.mask(siteEntry.apiKey()));
            row.put("siteBaseUrl", siteEntry.baseUrl());
            row.put("siteModel", siteEntry.model());
            row.put("siteProxy", siteEntry.proxy());
            row.put("yamlConfigured", aiConfig.yaml(vendor.id()).configured());
            row.put("yamlHasKey", aiConfig.yamlHasKey(vendor.id()));
            row.put("available", selection(userId, vendor).isPresent());
            row.put("source", aiConfig.sourceOf(userId, vendor.id()));
            rows.add(row);
        }
        return rows;
    }

    // ==================== 构造适配器 ====================

    private AiProvider build(String id, String type, String label, String baseUrl, String model,
                             String apiKey, Double temperature, Integer maxTokens, String proxy) {
        AiProviderConfig config = new AiProviderConfig(type, baseUrl, apiKey, model, label, temperature, maxTokens,
                proxy);
        AiHttp http = httpFor(config);
        return switch (config.type()) {
            case "anthropic" -> new AnthropicProvider(id, config, http);
            case "gemini" -> new GeminiProvider(id, config, http);
            case "mock" -> new MockProvider(id, config);
            default -> new OpenAiProvider(id, config, http);
        };
    }

    /** 每个代理一个 HTTP 客户端（按 proxy 字符串缓存；超时取 {@code wikikernel.ai.timeout-seconds}）。 */
    private AiHttp httpFor(AiProviderConfig config) {
        String global = settings().proxy();
        String proxy = config.proxy().isBlank() ? global : config.proxy();
        return httpByProxy.computeIfAbsent(proxy == null ? "" : proxy,
                key -> new AiHttp(objectMapper, settings().timeoutSeconds(), key));
    }

    private AiConfig.Settings settings() {
        return aiConfig.settings();
    }

    private static AiConfig.Entry empty(String providerId) {
        return new AiConfig.Entry(providerId, "", "", "", "");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!Strings.isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }
}
