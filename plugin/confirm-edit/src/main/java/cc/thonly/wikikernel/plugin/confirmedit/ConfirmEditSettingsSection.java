package cc.thonly.wikikernel.plugin.confirmedit;

import cc.thonly.wikikernel.common.extension.SettingField;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;

/**
 * 编辑验证码在「站点设置」页里的分区：字段键 = {@link ConfirmEditSettings} 使用的站点设置键
 * （迁移前 core 的表单是同一批键），core 只负责渲染 / 保存；插件没加载时这个分区不存在。
 */
public class ConfirmEditSettingsSection implements SiteSettingsSection {

    private final MessageSource messages;

    public ConfirmEditSettingsSection(MessageSource messages) {
        this.messages = messages;
    }

    @Override
    public String id() {
        return "confirm-edit";
    }

    @Override
    public String title() {
        return text("settings.confirmEdit");
    }

    @Override
    public String description() {
        return text("settings.confirmEditHint");
    }

    @Override
    public int order() {
        return 210;
    }

    @Override
    public List<SettingField> fields() {
        return List.of(
                SettingField.checkbox(ConfirmEditSettings.ENABLED_KEY, text("settings.confirmEditEnabled"), false),
                SettingField.text(ConfirmEditSettings.BYPASS_GROUPS_KEY, text("settings.confirmEditBypass"),
                        ConfirmEditSettings.DEFAULT_BYPASS_GROUPS));
    }

    private String text(String key) {
        return messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }
}
