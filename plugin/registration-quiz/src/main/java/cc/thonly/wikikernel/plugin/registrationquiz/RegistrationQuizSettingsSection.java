package cc.thonly.wikikernel.plugin.registrationquiz;

import cc.thonly.wikikernel.common.extension.SettingField;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;

/**
 * 注册问答在「站点设置」页里的分区：所有问答放在一个 textarea 中，一行一题，
 * 格式为「问题 | 答案1 | 答案2」。旧的两个数组 / 单问题配置会转换成这个格式显示。
 * core 只负责渲染 / 保存；插件没加载时这个分区不存在。
 */
public class RegistrationQuizSettingsSection implements SiteSettingsSection {

    private final MessageSource messages;
    private final SiteConfig siteConfig;

    public RegistrationQuizSettingsSection(MessageSource messages) {
        this(messages, null);
    }

    public RegistrationQuizSettingsSection(MessageSource messages, SiteConfig siteConfig) {
        this.messages = messages;
        this.siteConfig = siteConfig;
    }

    @Override
    public String id() {
        return "registration-quiz";
    }

    @Override
    public String title() {
        return text("settings.registerQuiz");
    }

    @Override
    public String description() {
        return text("settings.registerQuizHint");
    }

    @Override
    public int order() {
        return 220;
    }

    @Override
    public List<SettingField> fields() {
        String current = new RegistrationQuizSettings(siteConfig).editorText();
        return List.of(
                SettingField.checkbox(RegistrationQuizSettings.ENABLED_KEY, text("settings.registerQuizEnabled"), false),
                SettingField.textarea(RegistrationQuizSettings.QUIZ_KEY, text("settings.registerQuizItems"),
                        current));
    }

    private String text(String key) {
        return messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }
}
