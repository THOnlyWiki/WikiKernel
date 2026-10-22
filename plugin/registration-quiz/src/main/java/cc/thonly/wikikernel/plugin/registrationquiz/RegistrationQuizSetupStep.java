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
 * 注册问答的安装向导步骤（{@code /init} 里本插件的 tab）：一行一题，题目和该题的多个答案
 * 用 {@code |} 分隔；{@link #apply} 写 {@code wikikernel.registration.quiz} 与
 * {@code wikikernel.features.registration-quiz}（YAML），重启后由插件导入站点设置。
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
                SetupField.textArea("quiz", text("settings.registerQuizItems"), ""));
    }

    @Override
    public SetupResult apply(Map<String, String> values, SetupContext context) {
        String quiz = values.get("quiz") == null ? "" : values.get("quiz").trim();
        List<String> lines = RegistrationQuizSettings.splitLines(quiz);
        List<RegistrationQuizSettings.QuizRow> rows = RegistrationQuizSettings.parseRows(quiz);
        context.putGlobal("wikikernel.features.registration-quiz", truthy(values.get("enabled")));
        context.putGlobal("wikikernel.registration.quiz", quiz);
        // 清掉旧格式，避免新向导保存后旧题目继续生效。
        context.putGlobal("wikikernel.registration.questions", List.of());
        context.putGlobal("wikikernel.registration.answers", List.of());
        context.putGlobal("wikikernel.registration.question", "");
        if (!lines.isEmpty() && rows.size() != lines.size()) {
            return SetupResult.error(text("auth.registerQuizInvalid"));
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
