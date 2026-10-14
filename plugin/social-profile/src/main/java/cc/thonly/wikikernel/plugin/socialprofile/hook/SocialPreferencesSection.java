package cc.thonly.wikikernel.plugin.socialprofile.hook;

import cc.thonly.wikikernel.common.extension.PreferenceContext;
import cc.thonly.wikikernel.common.extension.PreferenceSection;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.service.ProfileService;
import org.springframework.web.util.HtmlUtils;

/**
 * 「特殊:参数设置」里的**社交资料**分区（仿 MediaWiki SocialProfile 在参数设置里加的社交偏好）：
 * <ul>
 *     <li>{@code showBirthday}：是否在资料卡显示生日；</li>
 *     <li>{@code showLocation}：是否在资料卡显示现居地；</li>
 *     <li>{@code allowBoard}：是否允许其他人在你的留言板留言；</li>
 *     <li>{@code showGifts}：是否在资料卡显示礼物清单；</li>
 *     <li>{@code showAwards}：是否在资料卡显示勋章。</li>
 * </ul>
 * 表单提交到插件自己的接口 {@code POST /api/social-profile/preferences}
 * （脚本 {@code /plugins/social-profile/social-profile.js}，由 head provider 站点级注入），
 * 偏好键都带 {@code socialProfile.pref.} 前缀，存在 {@code common.service.UserSettings} 里。
 * <p>
 * core 会把这个分区当成「参数设置」的一个标签页（模型键 {@code pluginPreferenceSections}），
 * 所以插件**不需要**改 core 的参数设置模板。
 */
public final class SocialPreferencesSection implements PreferenceSection {

    private final ProfileService profiles;
    private final SocialProfileSupport support;

    public SocialPreferencesSection(ProfileService profiles, SocialProfileSupport support) {
        this.profiles = profiles;
        this.support = support;
    }

    @Override
    public String id() {
        return "social-profile";
    }

    @Override
    public String labelKey() {
        return "socialProfile.preferences.title";
    }

    @Override
    public String fallbackLabel() {
        return "社交资料";
    }

    @Override
    public int order() {
        return 150;
    }

    @Override
    public String html(PreferenceContext context) {
        if (!support.enabled()) {
            return "<p class=\"wiki-hint\">" + escape(text(context, "feature.userInteractionDisabledHint"))
                    + "</p>";
        }
        long userId = context.viewer() == null ? 0 : context.viewer().id();
        ProfileService.SocialPreferences preferences = profiles.preferences(userId);
        StringBuilder html = new StringBuilder();
        html.append("<p class=\"wiki-hint\">")
                .append(escape(text(context, "socialProfile.preferences.hint")))
                .append("</p>")
                .append("<form id=\"social-profile-preferences-form\" class=\"wiki-form\"")
                .append(" data-api=\"/api/social-profile/preferences\"><div class=\"sp-preferences\">");
        checkbox(html, context, "showBirthday", preferences.showBirthday());
        checkbox(html, context, "showLocation", preferences.showLocation());
        checkbox(html, context, "allowBoard", preferences.allowBoard());
        checkbox(html, context, "showGifts", preferences.showGifts());
        checkbox(html, context, "showAwards", preferences.showAwards());
        html.append("</div><div class=\"form-actions\"><button type=\"submit\" class=\"wiki-button\">")
                .append(escape(text(context, "socialProfile.preferences.save")))
                .append("</button></div><p class=\"form-message\" role=\"alert\" hidden></p>")
                .append("<p class=\"wiki-hint\"><a href=\"/wiki/特殊:编辑资料\">")
                .append(escape(text(context, "socialProfile.editProfile")))
                .append("</a></p></form>");
        return html.toString();
    }

    /** 一个复选框（文案走 {@code socialProfile.preferences.<name>} 消息键）。 */
    private void checkbox(StringBuilder html, PreferenceContext context, String name, boolean checked) {
        html.append("<label class=\"pref-checkbox\"><input type=\"checkbox\" name=\"")
                .append(escape(name)).append("\" value=\"1\"")
                .append(checked ? " checked" : "")
                .append("/><span>").append(escape(text(context, "socialProfile.preferences." + name)))
                .append("</span></label>");
    }

    /** 分区文案：{@code fallback} 传 {@code null} = 漏翻时显示翻译键本身。 */
    private static String text(PreferenceContext context, String key) {
        return context.message(key, null);
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
