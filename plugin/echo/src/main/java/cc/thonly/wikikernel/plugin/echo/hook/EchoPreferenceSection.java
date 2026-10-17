package cc.thonly.wikikernel.plugin.echo.hook;

import cc.thonly.wikikernel.common.extension.PreferenceContext;
import cc.thonly.wikikernel.common.extension.PreferenceSection;
import cc.thonly.wikikernel.common.service.UserSettings;
import cc.thonly.wikikernel.plugin.echo.EchoKeys;
import cc.thonly.wikikernel.plugin.echo.EchoSupport;
import cc.thonly.wikikernel.plugin.echo.service.EchoMailer;
import org.springframework.web.util.HtmlUtils;

/**
 * 「特殊:参数设置」里的**通知**分区（仿 MediaWiki Echo 的通知偏好）：
 * 每类通知一个复选框（默认全部开启），表单提交到插件自己的接口
 * {@code POST /api/echo/preferences}（脚本 {@code /plugins/echo/echo.js}，站点级注入），
 * 偏好键 {@code echo.pref.enable.<type>}，存在 {@code common.service.UserSettings}。
 */
public final class EchoPreferenceSection implements PreferenceSection {

    private final UserSettings userSettings;
    private final EchoSupport support;
    private final EchoMailer mailer;

    public EchoPreferenceSection(UserSettings userSettings, EchoSupport support,
                                 EchoMailer mailer) {
        this.userSettings = userSettings;
        this.support = support;
        this.mailer = mailer;
    }

    @Override
    public String id() {
        return "echo";
    }

    @Override
    public String labelKey() {
        return "echo.preferences.title";
    }

    @Override
    public String fallbackLabel() {
        return "通知";
    }

    @Override
    public int order() {
        return 140;
    }

    @Override
    public String html(PreferenceContext context) {
        if (!support.enabled()) {
            return "<p class=\"wiki-hint\">" + escape(text(context, "echo.preferences.disabled"))
                    + "</p>";
        }
        long userId = context.viewer() == null ? 0 : context.viewer().id();
        StringBuilder html = new StringBuilder();
        html.append("<p class=\"wiki-hint\">")
                .append(escape(text(context, "echo.preferences.hint")))
                .append("</p>")
                .append("<form id=\"echo-preferences-form\" class=\"wiki-form\"")
                .append(" data-api=\"/api/echo/preferences\"><div class=\"echo-preferences\">");
        for (String type : EchoKeys.TYPES) {
            checkbox(html, context, type, enabled(userId, type));
        }
        // 邮件通知（opt-in；站点未配置邮件 / 类型被关时显示说明并禁用）
        boolean mailAvailable = mailer != null && mailer.available();
        checkbox(html, context, "email", emailEnabled(userId), !mailAvailable);
        if (!mailAvailable) {
            html.append("<p class=\"wiki-hint\">")
                    .append(escape(text(context, "echo.preferences.emailDisabled")))
                    .append("</p>");
        }
        html.append("</div><div class=\"form-actions\"><button type=\"submit\" class=\"wiki-button\">")
                .append(escape(text(context, "echo.preferences.save")))
                .append("</button></div><p class=\"form-message\" role=\"alert\" hidden></p></form>");
        return html.toString();
    }

    /** 一个类型开关复选框（文案走 {@code echo.preferences.<type>} 消息键）。 */
    private void checkbox(StringBuilder html, PreferenceContext context, String type, boolean checked) {
        checkbox(html, context, type, checked, false);
    }

    /** 一个开关复选框；{@code disabled} = 置灰不可改（如站点邮件不可用时的邮件开关）。 */
    private void checkbox(StringBuilder html, PreferenceContext context, String type, boolean checked,
                          boolean disabled) {
        html.append("<label class=\"pref-checkbox\"><input type=\"checkbox\" name=\"")
                .append(escape(type)).append("\" value=\"1\"")
                .append(checked ? " checked" : "")
                .append(disabled ? " disabled" : "")
                .append("/><span>").append(escape(text(context, "echo.preferences." + type)))
                .append("</span></label>");
    }

    /** 邮件偏好（默认关 = opt-in）。 */
    private boolean emailEnabled(long userId) {
        String value = userSettings == null ? null
                : userSettings.get(userId, EchoKeys.PREF_EMAIL, null);
        return "true".equalsIgnoreCase(value);
    }

    private boolean enabled(long userId, String type) {
        String value = userSettings == null ? null
                : userSettings.get(userId, EchoKeys.PREF_TYPE_PREFIX + type, null);
        return value == null || !"false".equalsIgnoreCase(value);
    }

    private static String text(PreferenceContext context, String key) {
        return context.message(key, null);
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}