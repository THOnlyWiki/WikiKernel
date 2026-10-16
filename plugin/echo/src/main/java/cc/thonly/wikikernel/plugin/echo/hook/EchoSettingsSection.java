package cc.thonly.wikikernel.plugin.echo.hook;

import cc.thonly.wikikernel.common.extension.SettingField;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.plugin.echo.EchoKeys;
import cc.thonly.wikikernel.plugin.echo.EchoSupport;

import java.util.List;

/**
 * 「特殊:权限配置 → 站点设置」里的 **Echo 通知** 分区（站点级开关）：
 * 除 {@code features.echo}（功能总开关，core 自动渲染）之外，这里放「通知」的行为开关。
 * <p>
 * 键值存 core 的 {@code SiteConfig}（{@code echo.*}），保存由 core 的站点设置表单统一处理；
 * 标签走 {@code echo.settings.*} 消息键（分区标题本身是本地化文本，直接写中文兜底）。
 */
public final class EchoSettingsSection implements SiteSettingsSection {

    private final EchoSupport support;

    public EchoSettingsSection(EchoSupport support) {
        this.support = support;
    }

    @Override
    public String id() {
        return "echo";
    }

    @Override
    public String title() {
        return support.message("echo.settings.title", "Echo 通知");
    }

    @Override
    public String description() {
        return support.message("echo.settings.description",
                "通知插件（MediaWiki Echo 风格）：讨论页被编辑 / 被回退 / @提及 的站点级开关。");
    }

    @Override
    public int order() {
        return 160;
    }

    @Override
    public List<SettingField> fields() {
        return List.of(
                SettingField.checkbox(EchoKeys.ENABLED_KEY,
                        support.message("echo.settings.enabled", "启用 Echo 通知"), true),
                SettingField.checkbox("echo.site.disableMention",
                        support.message("echo.settings.disableMention", "禁用 @提及 通知"), false),
                SettingField.checkbox("echo.site.disableTalk",
                        support.message("echo.settings.disableTalk", "禁用讨论页被编辑通知"), false),
                SettingField.checkbox("echo.site.disableRevert",
                        support.message("echo.settings.disableRevert", "禁用被回退通知"), false),
                SettingField.checkbox("echo.site.disableWelcome",
                        support.message("echo.settings.disableWelcome", "禁用新用户欢迎通知"), false));
    }
}