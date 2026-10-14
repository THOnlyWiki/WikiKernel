package cc.thonly.wikikernel.plugin.gadgets.hook;

import cc.thonly.wikikernel.common.extension.PreferenceContext;
import cc.thonly.wikikernel.common.extension.PreferenceSection;
import cc.thonly.wikikernel.plugin.gadgets.model.GadgetDefinition;
import cc.thonly.wikikernel.plugin.gadgets.service.GadgetService;
import java.util.List;
import org.springframework.web.util.HtmlUtils;

/**
 * 参数设置里的「Gadgets」分区（仿 MediaWiki 参数设置 → 小工具）：
 * 每个可见 Gadget 一个复选框 + 保存按钮；保存走插件自己的接口
 * {@code POST /api/gadgets/preferences}（脚本 {@code /plugins/gadgets/gadgets.js}）。
 */
public class GadgetsPreferenceSection implements PreferenceSection {

    private final GadgetService gadgets;

    public GadgetsPreferenceSection(GadgetService gadgets) {
        this.gadgets = gadgets;
    }

    @Override
    public String id() {
        return "gadgets";
    }

    @Override
    public String labelKey() {
        return "gadgets.preferences.title";
    }

    @Override
    public String fallbackLabel() {
        return "小工具";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String html(PreferenceContext context) {
        if (!gadgets.enabled()) {
            return "<p class=\"wiki-hint\">" + escape(text(context, "gadgets.disabled")) + "</p>";
        }
        List<GadgetDefinition> definitions = gadgets.visibleDefinitions();
        StringBuilder html = new StringBuilder();
        html.append("<p class=\"wiki-hint\">")
                .append(escape(text(context, "gadgets.preferences.hint")))
                .append("</p>");
        if (definitions.isEmpty()) {
            html.append("<p class=\"wiki-hint\">")
                    .append(escape(text(context, "gadgets.preferences.empty")))
                    .append("</p>")
                    .append("<p class=\"wiki-hint\"><a href=\"")
                    .append(escape(gadgets.definitionPageUrl()))
                    .append("\">")
                    .append(escape(text(context, "gadgets.preferences.create")))
                    .append("</a></p>");
            return html.toString();
        }
        html.append("<form id=\"gadgets-preferences-form\" class=\"wiki-form\" data-api=\"/api/gadgets/preferences\">")
                .append("<div class=\"gadget-preferences\">");
        for (GadgetDefinition definition : definitions) {
            html.append("<label class=\"pref-checkbox\">")
                    .append("<input type=\"checkbox\" name=\"gadgets\" value=\"")
                    .append(escape(definition.name())).append('"')
                    .append(gadgets.isEnabledForCurrentUser(definition.name()) ? " checked" : "")
                    .append("/><span>").append(escape(definition.name())).append("</span>");
            if (!definition.description().isBlank()) {
                html.append("<small>").append(escape(definition.description())).append("</small>");
            }
            html.append("</label>");
        }
        html.append("</div><div class=\"form-actions\"><button type=\"submit\" class=\"wiki-button\">")
                .append(escape(text(context, "gadgets.preferences.save")))
                .append("</button></div><p class=\"form-message\" role=\"alert\" hidden></p></form>")
                // 保存逻辑随分区一起注入（只在参数设置页出现）；提示文案交给脚本用的 WIKI_MESSAGES
                .append("<script>window.WIKI_MESSAGES = Object.assign(window.WIKI_MESSAGES || {}, {")
                .append("gadgetsSaved: ").append(json(text(context, "gadgets.preferences.saved"))).append(',')
                .append("gadgetsDisabledNoChange: ")
                .append(json(text(context, "gadgets.preferences.disabledNoChange")))
                .append("});</script>")
                .append("<script defer src=\"/plugins/gadgets/gadgets.js\"></script>");
        return html.toString();
    }

    /** 分区文案：{@code PreferenceContext.message} 的 {@code fallback} 传 {@code null} = 漏翻时显示翻译键本身。 */
    private static String text(PreferenceContext context, String key) {
        return context.message(key, null);
    }

    /** 文案 → JSON 字符串字面量（注入 {@code window.WIKI_MESSAGES} 用）。 */
    private static String json(String value) {
        return "\"" + (value == null ? "" : value
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("<", "\\u003c").replace(">", "\\u003e")) + "\"";
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
