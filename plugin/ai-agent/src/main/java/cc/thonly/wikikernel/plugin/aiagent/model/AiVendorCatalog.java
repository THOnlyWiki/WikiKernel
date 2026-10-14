package cc.thonly.wikikernel.plugin.aiagent.model;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 内置 AI 厂商目录：**适配器全部内置**，用户/管理员只需要填密钥（可选改 base-url / 模型）。
 * <p>
 * {@code mock} 属于内置演示厂商（离线、不需要密钥，任何环境都可用）；
 * 其余厂商按协议走 {@code openai}（OpenAI 兼容）/ {@code gemini} / {@code anthropic} 适配器。
 * <p>
 * <b>显示名与提示不写在 Java 里</b>：{@link Vendor#labelKey()} / {@link Vendor#hintKey()} 只是消息键
 * （{@code aiAgent.vendor.<id>.label} / {@code aiAgent.vendor.<id>.hint}），文字在插件自己的
 * {@code i18n/messages.properties}（中文）与 {@code i18n/messages_en.properties}（英文）里，
 * 由 {@code AiProviderRegistry} 解析成当前界面语言的文案（缺键回退成厂商 id，不回退中文）。
 */
public final class AiVendorCatalog {

    /**
     * 一个内置厂商的默认配置。
     *
     * @param id       厂商 id（配置 / 界面用的稳定标识；**不要因为改名而改 id**）
     * @param type     协议：openai / gemini / anthropic / mock
     * @param labelKey 显示名的消息键（{@code aiAgent.vendor.<id>.label}）
     * @param baseUrl  默认 API 基地址（空 = 适配器内置官方地址；openai 兼容请带 /v1）
     * @param model    默认模型
     * @param needsKey 是否需要密钥（Ollama / 演示厂商不需要）
     * @param hintKey  申请密钥 / 说明的消息键（{@code aiAgent.vendor.<id>.hint}）
     */
    public record Vendor(String id, String type, String labelKey, String baseUrl, String model,
                         boolean needsKey, String hintKey) {
    }

    /** 演示厂商（不需要密钥；任何环境都可用）。 */
    public static final String MOCK_ID = "mock";

    /** 演示协议（{@code type}）：回复是预先写好的固定示例，不是真实模型。 */
    public static final String DEMO_TYPE = "mock";

    private static final List<Vendor> BUILT_IN = List.of(
            new Vendor(MOCK_ID, DEMO_TYPE, labelKey(MOCK_ID), "", "mock", false, hintKey(MOCK_ID)),
            new Vendor("openai", "openai", labelKey("openai"), "https://api.openai.com/v1", "gpt-4o-mini", true,
                    hintKey("openai")),
            new Vendor("deepseek", "openai", labelKey("deepseek"), "https://api.deepseek.com/v1", "deepseek-chat",
                    true, hintKey("deepseek")),
            new Vendor("groq", "openai", labelKey("groq"), "https://api.groq.com/openai/v1",
                    "llama-3.3-70b-versatile", true, hintKey("groq")),
            new Vendor("gemini", "gemini", labelKey("gemini"), "", "gemini-2.0-flash", true, hintKey("gemini")),
            new Vendor("anthropic", "anthropic", labelKey("anthropic"), "", "claude-3-5-sonnet-latest", true,
                    hintKey("anthropic")),
            new Vendor("openrouter", "openai", labelKey("openrouter"), "https://openrouter.ai/api/v1",
                    "deepseek/deepseek-chat-v3-0324:free", true, hintKey("openrouter")),
            new Vendor("siliconflow", "openai", labelKey("siliconflow"), "https://api.siliconflow.cn/v1",
                    "deepseek-ai/DeepSeek-V3", true, hintKey("siliconflow")),
            new Vendor("ollama", "openai", labelKey("ollama"), "http://127.0.0.1:11434/v1", "llama3.1", false,
                    hintKey("ollama")),
            new Vendor("custom", "openai", labelKey("custom"), "", "", true, hintKey("custom")));

    private AiVendorCatalog() {
    }

    /** 显示名的消息键（插件 i18n；界面文案全在 {@code i18n/messages*.properties} 里）。 */
    public static String labelKey(String vendorId) {
        return "aiAgent.vendor." + vendorId + ".label";
    }

    /** 说明 / 提示的消息键。 */
    public static String hintKey(String vendorId) {
        return "aiAgent.vendor." + vendorId + ".hint";
    }

    /** 演示厂商（{@code type=mock}）的 id 列表：界面据此**锁住提示词输入**（前端不写死 id）。 */
    public static List<String> demoVendorIds() {
        return BUILT_IN.stream().filter(vendor -> DEMO_TYPE.equals(vendor.type()))
                .map(Vendor::id).toList();
    }

    public static List<Vendor> all() {
        return BUILT_IN;
    }

    public static Optional<Vendor> byId(String id) {
        String want = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        return BUILT_IN.stream().filter(vendor -> vendor.id().equals(want)).findFirst();
    }

    /** 内置厂商 id 集合（用于识别“内置厂商 + 自定义配置”）。 */
    public static List<String> ids() {
        return BUILT_IN.stream().map(Vendor::id).toList();
    }
}
