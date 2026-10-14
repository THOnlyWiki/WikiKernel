package cc.thonly.wikikernel.plugin.aiagent.hook;

import cc.thonly.wikikernel.common.extension.PreferenceContext;
import cc.thonly.wikikernel.common.extension.PreferenceSection;
import cc.thonly.wikikernel.plugin.aiagent.AiAgentSupport;
import cc.thonly.wikikernel.plugin.aiagent.service.AiProviderRegistry;

import java.util.List;
import java.util.Map;

/**
 * 参数设置里的「AI 代理」分区（原 core 的 {@code 特殊:参数设置 → AI 代理} 标签页，迁入插件）：
 * 每个厂商一行（API 密钥 / 地址 / 模型 / 代理 + 保存 / 清除），保存走插件自己的接口
 * {@code POST /api/ai-keys}（{@code scope=user}），脚本 {@code /plugins/ai-agent/ai-keys.js}。
 * <p>
 * 用户密钥只用于**自己**的对话（互不共享），优先级：用户密钥 &gt; 站点密钥 &gt; LocalSettings.yaml；
 * 界面只显示尾号掩码（密钥只留在服务端）。
 */
public class AiKeysPreferenceSection implements PreferenceSection {

    private final AiProviderRegistry registry;
    private final AiAgentSupport support;

    public AiKeysPreferenceSection(AiProviderRegistry registry, AiAgentSupport support) {
        this.registry = registry;
        this.support = support;
    }

    @Override
    public String id() {
        return "ai";
    }

    @Override
    public String labelKey() {
        return "preferences.tab.ai";
    }

    @Override
    public String fallbackLabel() {
        // 给人看的名字走 labelKey（preferences.tab.ai）；这里只留英文技术名，避免漏翻时漏出中文
        return "AI agent";
    }

    @Override
    public int order() {
        return 110;
    }

    @Override
    public String html(PreferenceContext context) {
        StringBuilder html = new StringBuilder();
        html.append("<form id=\"preferences-ai-form\" class=\"wiki-form\" data-keys-scope=\"user\"")
                .append(" data-keys-confirm-clear=\"").append(escape(message(context, "preferences.ai.clearConfirm"))).append('"')
                .append(" data-keep-label=\"").append(escape(message(context, "preferences.ai.keyKeep"))).append('"')
                .append(" data-default-placeholder=\"").append(escape(message(context, "preferences.ai.keyPlaceholder"))).append("\">");
        html.append("<p class=\"wiki-hint\">")
                .append(escape(message(context, "preferences.ai.intro")))
                .append("</p>");
        if (!support.enabled()) {
            html.append("<p class=\"wiki-warning\">")
                    .append(escape(message(context, "preferences.ai.disabled")))
                    .append("</p>");
        }
        List<Map<String, Object>> vendors = registry.settingsRows(context.viewer() == null ? 0 : context.viewer().id());
        for (Map<String, Object> vendor : vendors) {
            String id = String.valueOf(vendor.get("id"));
            boolean userConfigured = Boolean.TRUE.equals(vendor.get("userConfigured"));
            String configuredLabel = message(context, "preferences.ai.configured", vendor.get("userMask"));
            html.append("<details class=\"ai-key-row\" data-provider=\"").append(escape(id)).append('"')
                    .append(" data-configured-label=\"").append(escape(configuredLabel)).append('"')
                    .append(" data-missing-label=\"").append(escape(message(context, "preferences.ai.notConfigured"))).append("\">")
                    .append("<summary>")
                    .append("<span class=\"ai-key-chevron\" aria-hidden=\"true\"></span>")
                    .append("<strong class=\"ai-key-name\">").append(escape(String.valueOf(vendor.get("label")))).append("</strong>")
                    .append("<span class=\"ai-key-state").append(userConfigured ? " ok" : "").append("\">")
                    .append(escape(userConfigured ? configuredLabel
                            : message(context, "preferences.ai.notConfigured")))
                    .append("</span>")
                    .append("<span class=\"ai-key-hint\">").append(escape(String.valueOf(vendor.get("hint")))).append("</span>");
            if (Boolean.TRUE.equals(vendor.get("yamlHasKey"))) {
                html.append("<span class=\"ai-key-hint\">")
                        .append(escape(message(context, "preferences.ai.yamlKey")))
                        .append("</span>");
            }
            if (Boolean.TRUE.equals(vendor.get("siteConfigured"))) {
                html.append("<span class=\"ai-key-hint\">")
                        .append(escape(message(context, "preferences.ai.siteKey", vendor.get("siteMask"))))
                        .append("</span>");
            }
            html.append("</summary><div class=\"ai-key-body\">")
                    .append(field(context, "preferences.ai.key", "password", "ai-key-input",
                            "ai-key-" + id, "", userConfigured
                                    ? message(context, "preferences.ai.keyKeep")
                                    : message(context, "preferences.ai.keyPlaceholder")))
                    .append(field(context, "preferences.ai.baseUrl", "text", "ai-base-input",
                            "ai-base-" + id, String.valueOf(vendor.get("userBaseUrl")),
                            String.valueOf(vendor.get("defaultBaseUrl"))))
                    .append(field(context, "preferences.ai.model", "text", "ai-model-input",
                            "ai-model-" + id, String.valueOf(vendor.get("userModel")),
                            String.valueOf(vendor.get("defaultModel"))))
                    .append(field(context, "preferences.ai.proxy", "text", "ai-proxy-input",
                            "ai-proxy-" + id, String.valueOf(vendor.get("userProxy")),
                            message(context, "preferences.ai.proxyPlaceholder")))
                    .append("<div class=\"ai-key-actions\">")
                    .append("<button type=\"button\" class=\"wiki-button ai-key-save\">")
                    .append(escape(message(context, "preferences.ai.save"))).append("</button>")
                    .append("<button type=\"button\" class=\"wiki-button ai-key-clear\">")
                    .append(escape(message(context, "preferences.ai.clear"))).append("</button>")
                    .append("</div></div></details>");
        }
        html.append("<p class=\"form-message ai-keys-message\" hidden></p>")
                // 保存逻辑由站点级注入的 /plugins/ai-agent/ai-keys.js 处理（AiAgentHeadProvider），
                // 这里不再重复引一次脚本（同一个文件被两个 <script> 加载会执行两次）
                .append("</form>");
        return html.toString();
    }

    private String field(PreferenceContext context, String labelKey, String type, String cssClass,
                         String id, String value, String placeholder) {
        boolean secret = "password".equals(type);
        return "<div class=\"form-field form-field-inline\">"
                + "<label for=\"" + escape(id) + "\">" + escape(message(context, labelKey)) + "</label>"
                + "<input type=\"" + type + "\" class=\"" + cssClass + "\" id=\"" + escape(id) + "\""
                // 密钥永不回显：password 字段不带 value
                + (secret ? " autocomplete=\"off\"" : " value=\"" + escape(value == null ? "" : value) + "\"")
                + " placeholder=\"" + escape(placeholder == null ? "" : placeholder) + "\"/>"
                + "</div>";
    }

    /** 分区文案：{@code PreferenceContext.message} 的 {@code fallback} 传 {@code null} = 漏翻时显示翻译键本身。 */
    private static String message(PreferenceContext context, String key, Object... args) {
        String value = context.message(key, null);
        if (args == null || args.length == 0 || value == null) {
            return value;
        }
        for (int index = 0; index < args.length; index++) {
            value = value.replace("{" + index + "}", String.valueOf(args[index]));
        }
        return value;
    }

    private static String escape(String value) {
        return AiAgentSupport.escape(value);
    }
}
