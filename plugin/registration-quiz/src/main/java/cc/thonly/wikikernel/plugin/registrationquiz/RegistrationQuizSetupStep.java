package cc.thonly.wikikernel.plugin.registrationquiz;

import cc.thonly.wikikernel.common.extension.SetupContext;
import cc.thonly.wikikernel.common.extension.SetupField;
import cc.thonly.wikikernel.common.extension.SetupResult;
import cc.thonly.wikikernel.common.extension.SetupStep;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 注册问答的安装向导步骤（{@code /init} 里本插件的 tab）：题目 / 答案按行填写，
 * {@link #apply} 写 {@code wikikernel.registration.*} 与 {@code wikikernel.features.registration-quiz}（YAML），
 * 重启后由插件导入站点设置——与迁移前 core 向导写的是同一批键。
 */
public class RegistrationQuizSetupStep implements SetupStep {

    private final MessageSource messages;

    public RegistrationQuizSetupStep(MessageSource messages) {
        this.messages = messages;
    }

    @Override
    public String id() {
        return "registration-quiz";
    }

    @Override
    public String displayName() {
        return text("settings.registerQuiz");
    }

    @Override
    public String description() {
        return text("setup.registrationQuizHint");
    }

    @Override
    public int order() {
        return 220;
    }

    @Override
    public List<SetupField> fields() {
        return List.of(
                SetupField.checkbox("enabled", text("setup.registrationQuiz"), false),
                SetupField.textArea("questions", text("settings.registerQuizQuestions"), ""),
                SetupField.textArea("answers", text("settings.registerQuizAnswers"), ""));
    }

    @Override
    public SetupResult apply(Map<String, String> values, SetupContext context) {
        List<String> questions = RegistrationQuizSettings.splitLines(values.get("questions"));
        List<String> answers = RegistrationQuizSettings.splitLines(values.get("answers"));
        context.putGlobal("wikikernel.features.registration-quiz", truthy(values.get("enabled")));
        context.putGlobal("wikikernel.registration.questions", questions);
        context.putGlobal("wikikernel.registration.answers", answers);
        context.putGlobal("wikikernel.registration.question", "");
        if (!questions.isEmpty() && questions.size() != answers.size()) {
            return SetupResult.error(text("auth.registerQuizMismatch")
                    .replace("{0}", String.valueOf(questions.size()))
                    .replace("{1}", String.valueOf(answers.size())));
        }
        return SetupResult.ok(text("setup.registerQuizSaved"));
    }

    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized)
                || "on".equals(normalized) || "yes".equals(normalized);
    }

    private String text(String key) {
        return messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }
}
