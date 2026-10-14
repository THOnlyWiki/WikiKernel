package cc.thonly.wikikernel.plugin.pagebanner;

import cc.thonly.wikikernel.common.extension.SettingField;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;

/**
 * 页面横幅在「站点设置」页里的分区（{@code 特殊:权限配置 → 站点设置}）：
 * 字段键 = {@link PageBannerSettings} 使用的站点设置键（迁移前 core 的表单是同一批键），
 * core 只负责渲染 / 保存，本插件负责标签文案与字段集合。
 * <p>
 * 关键点：core 里不再有任何页面横幅的设置代码——插件没加载时这个分区也不存在。
 */
public class PageBannerSettingsSection implements SiteSettingsSection {

    private final MessageSource messages;

    public PageBannerSettingsSection(MessageSource messages) {
        this.messages = messages;
    }

    @Override
    public String id() {
        return "page-banner";
    }

    @Override
    public String title() {
        return text("settings.pageBanner");
    }

    @Override
    public String description() {
        return text("settings.pageBannerHint");
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public List<SettingField> fields() {
        return List.of(
                SettingField.checkbox(PageBannerSettings.ENABLED_KEY, text("settings.pageBannerEnabled"), true),
                SettingField.textarea(PageBannerSettings.FILES_KEY, text("settings.pageBannerFiles"), ""),
                SettingField.checkbox(PageBannerSettings.WHITELIST_ENABLED_KEY,
                        text("settings.pageBannerWhitelistEnabled"), true),
                SettingField.textarea(PageBannerSettings.WHITELIST_KEY, text("settings.pageBannerWhitelist"), ""),
                SettingField.select(PageBannerSettings.ROTATION_KEY, text("settings.pageBannerRotation"),
                        "stable", PageBannerSettings.ROTATION_MODES),
                SettingField.textarea(PageBannerSettings.PER_PAGE_KEY, text("settings.pageBannerPerPage"), ""),
                SettingField.text(PageBannerSettings.INTERVAL_KEY, text("settings.pageBannerInterval"), "5"),
                SettingField.select(PageBannerSettings.TRANSITION_KEY, text("settings.pageBannerTransition"),
                        PageBannerSettings.DEFAULT_TRANSITION, PageBannerSettings.TRANSITION_MODES),
                SettingField.checkbox(PageBannerSettings.CONTROLS_KEY, text("settings.pageBannerControls"), true),
                SettingField.checkbox(PageBannerSettings.PAUSE_ON_HOVER_KEY,
                        text("settings.pageBannerPauseOnHover"), true));
    }

    private String text(String key) {
        return messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }
}
