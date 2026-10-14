package cc.thonly.wikikernel.plugin.aiagent.service;

import java.util.Map;

/**
 * AI 厂商配置桥（由 core 提供实现，插件可 {@code @Autowired} 注入）：
 * 站点级密钥 / 用户级密钥 / LocalSettings.yaml 三处来源的读取与写入，以及 {@code wikikernel.ai.*} 的运行时设置。
 * <p>
 * 优先级（同一厂商）：**用户密钥 &gt; 站点设置密钥 &gt; LocalSettings.yaml**；
 * 密钥只留在服务端，界面用 {@link #mask(String)} 显示尾号。
 */
public interface AiConfig {

    /** 站点设置键：是否允许普通登录用户使用 AI 代理（{@code settings().allowNormalUsers()} 读它）。 */
    String ALLOW_NORMAL_KEY = "ai-agent.allow-normal-users";

    /**
     * 一个厂商的配置（各字段都可能为空串）。
     *
     * @param type        协议类型（{@code openai} / {@code anthropic} / {@code gemini} / {@code mock}；
     *                    只有 {@code LocalSettings.yaml} 里的厂商会给出，用户 / 站点密钥没有类型）
     * @param label       界面显示名（同样只有 yaml 里的自定义厂商会给出）
     * @param temperature 采样温度（可空 = 用厂商默认）
     * @param maxTokens   单次回复最大 token（可空 = 用厂商默认）
     */
    record Entry(String providerId, String apiKey, String baseUrl, String model, String proxy,
                 String type, String label, Double temperature, Integer maxTokens) {

        public Entry {
            providerId = providerId == null ? "" : providerId;
            apiKey = apiKey == null ? "" : apiKey;
            baseUrl = baseUrl == null ? "" : baseUrl;
            model = model == null ? "" : model;
            proxy = proxy == null ? "" : proxy;
            type = type == null ? "" : type;
            label = label == null ? "" : label;
        }

        /** 只有密钥 / 地址 / 模型 / 代理的简版（用户级、站点级配置用）。 */
        public Entry(String providerId, String apiKey, String baseUrl, String model, String proxy) {
            this(providerId, apiKey, baseUrl, model, proxy, "", "", null, null);
        }

        /** 有密钥。 */
        public boolean hasKey() {
            return !apiKey.isBlank();
        }

        /** 有任何一项配置（密钥 / 地址 / 模型 / 代理）。 */
        public boolean configured() {
            return hasKey() || !baseUrl.isBlank() || !model.isBlank() || !proxy.isBlank();
        }
    }

    /**
     * {@code wikikernel.ai.*} 的运行时设置。
     *
     * @param defaultProvider   下拉里默认选中的厂商 id
     * @param allowNormalUsers  是否允许普通登录用户使用 AI 代理
     * @param maxSteps          一次对话最多几轮工具调用（0 = 不限制）
     * @param timeoutSeconds    单次模型调用超时（秒）
     * @param proxy             全局代理（可空；{@code socks5://} / {@code http://}）
     * @param maxReadChars      一次读取页面正文的字符上限（超出截断）
     * @param sessionTtlMinutes 会话保留时长（分钟）
     * @param maxSessions       内存里保留的会话数上限
     * @param maxMessages       会话里保留的对话条数上限
     */
    record Settings(String defaultProvider, boolean allowNormalUsers, int maxSteps, int timeoutSeconds,
                    String proxy, int maxReadChars, int sessionTtlMinutes, int maxSessions, int maxMessages) {

        /** 兼容旧签名（只关心前五项时的简版）。 */
        public Settings(String defaultProvider, boolean allowNormalUsers, int maxSteps, int timeoutSeconds,
                        String proxy) {
            this(defaultProvider, allowNormalUsers, maxSteps, timeoutSeconds, proxy,
                    24000, 120, 200, 60);
        }
    }

    /** 运行时设置（站点设置里的值优先，其次 LocalSettings.yaml，最后内置默认）。 */
    Settings settings();

    /** 用户级配置（{@code wiki_user_ai_key} 表）。 */
    Entry user(long userId, String providerId);

    /** 站点级配置（站点设置 {@code ai.provider.<id>.*}）。 */
    Entry site(String providerId);

    /** LocalSettings.yaml 里的厂商配置（没写返回空 Entry）。 */
    Entry yaml(String providerId);

    /** 该厂商在 yaml 里是否写了密钥。 */
    boolean yamlHasKey(String providerId);

    /** yaml 里配置过的全部厂商（厂商 id → 配置）。 */
    Map<String, Entry> yamlProviders();

    /**
     * 保存用户级配置；{@code clear} 为 true 时清空该厂商的全部配置（删行）。
     * {@code null} 字段表示不修改（{@code clear=false} 时）。
     */
    void saveUser(long userId, String providerId, String apiKey, String baseUrl, String model,
                  String proxy, boolean clear);

    /** 保存站点级配置（语义同上）。 */
    void saveSite(String providerId, String apiKey, String baseUrl, String model, String proxy, boolean clear);

    /**
     * 站点是否放开普通用户使用 AI 代理（写站点设置 {@link #ALLOW_NORMAL_KEY}，
     * 与 {@code /init} 里的同一项是同一个键，保存即生效）。
     */
    void saveAllowNormalUsers(boolean allow);

    /** 该用户对某厂商的有效来源：{@code user} / {@code site} / {@code yaml} / {@code none}。 */
    String sourceOf(long userId, String providerId);

    /** 界面展示用：密钥尾号掩码（绝不回传完整密钥）。 */
    static String mask(String apiKey) {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty()) {
            return "";
        }
        return key.length() <= 4 ? "••••" : "••••" + key.substring(key.length() - 4);
    }
}
