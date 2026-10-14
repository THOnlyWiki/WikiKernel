package cc.thonly.wikikernel.plugin.registrationquiz;

import cc.thonly.wikikernel.common.extension.SettingField;
import cc.thonly.wikikernel.common.extension.SiteSettingsSection;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;

/**
 * 注册问答在「站点设置」页里的分区：字段键 = {@link RegistrationQuizSettings} 使用的站点设置键
 * （迁移前 core 的表单是同一批键），core 只负责渲染 / 保存；插件没加载时这个分区不存在。
 */
public class RegistrationQuizSettingsSection implements SiteSettingsSection {

    private final MessageSource messages;

    public RegistrationQuizSettingsSection(MessageSource messages) {
        this.messages = messages;
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
        return List.of(
                SettingField.checkbox(RegistrationQuizSettings.ENABLED_KEY, text("settings.registerQuizEnabled"), false),
                SettingField.textarea(RegistrationQuizSettings.QUESTIONS_KEY, text("settings.registerQuizQuestions"),
                        ""),
                SettingField.textarea(RegistrationQuizSettings.ANSWERS_KEY, text("settings.registerQuizAnswers"),
                        ""));
    }

    private String text(String key) {
        return messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }
}
