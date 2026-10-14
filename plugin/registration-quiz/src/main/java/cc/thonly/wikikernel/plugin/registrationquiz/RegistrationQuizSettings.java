package cc.thonly.wikikernel.plugin.registrationquiz;

import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.util.Strings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 注册问答的站点设置读取与抽题 / 校验逻辑（**从 core 的 {@code RegistrationQuizService} 整体迁来**）：
 * 两个**等长数组** —— 问题数组 {@code registration.questions} 与答案数组 {@code registration.answers}，
 * {@code a[i]} 与 {@code b[i]} 按下标一一对应。注册页每次随机抽一道（并带回下标），必须答对**那道题**。
 * <p>
 * 键名与 core 的站点设置（{@code RegistrationQuizKeys}）/ {@code LocalSettings.yaml} 完全一致，
 * 所以 {@code /init}、「特殊:权限配置 → 站点设置」的行编辑器与开关语义在迁移前后不变；
 * 插件只读，不写设置（写设置仍由 core 的表单做）。
 */
public class RegistrationQuizSettings {

    /** 站点设置键：功能开关（与 core 的 {@code FeatureService.REGISTRATION_QUIZ_KEY} 相同）。 */
    public static final String ENABLED_KEY = "features.registration-quiz";

    /** 站点设置键：问题数组（换行分隔）。 */
    public static final String QUESTIONS_KEY = "registration.questions";

    /** 站点设置键：答案数组（换行分隔，与问题数组等长）。 */
    public static final String ANSWERS_KEY = "registration.answers";

    /** 旧键：单个问题（仅兼容旧配置；新数组非空时不用它）。 */
    public static final String LEGACY_QUESTION_KEY = "registration.question";

    /** 抽到的题目：下标 + 问题文本。 */
    public record Challenge(int index, String question) {
    }

    /** 一行问答对。 */
    public record QuizRow(String question, String answer) {
    }

    private final SiteConfig siteConfig;

    public RegistrationQuizSettings(SiteConfig siteConfig) {
        this.siteConfig = siteConfig;
    }

    /** 是否启用（总开关开 + 至少有一对问答）。 */
    public boolean enabled() {
        return siteConfig != null
                && siteConfig.getBoolean(ENABLED_KEY, false)
                && !rows().isEmpty();
    }

    /** 全部问答对（新数组按下标配对，数量不等时按较短的长度配对；否则回退旧配置）。 */
    public List<QuizRow> rows() {
        String questionsRaw = siteConfig == null ? "" : siteConfig.get(QUESTIONS_KEY, "");
        List<String> questions = splitLines(questionsRaw);
        if (!questions.isEmpty()) {
            List<String> answers = splitLines(siteConfig.get(ANSWERS_KEY, ""));
            int size = Math.min(questions.size(), answers.size());
            List<QuizRow> rows = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                rows.add(new QuizRow(questions.get(index), answers.get(index)));
            }
            return List.copyOf(rows);
        }
        // 兼容旧配置：单问题 registration.question + 同题的多个可接受答案（| 或换行分隔）
        String legacyQuestion = Strings.trimToEmpty(siteConfig == null ? "" : siteConfig.get(LEGACY_QUESTION_KEY, ""));
        if (legacyQuestion.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(siteConfig.get(ANSWERS_KEY, "").split("[|\\r\\n]+"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(answer -> new QuizRow(legacyQuestion, answer))
                .toList();
    }

    /** 随机抽一道题（未启用返回 {@code null}）。 */
    public Challenge pick() {
        if (!enabled()) {
            return null;
        }
        List<QuizRow> rows = rows();
        int index = ThreadLocalRandom.current().nextInt(rows.size());
        return new Challenge(index, rows.get(index).question());
    }

    /** 校验第 {@code index} 道题的答案（未启用恒为 true；下标缺失 / 越界视为答错）。 */
    public boolean accepts(Integer index, String answer) {
        if (!enabled()) {
            return true;
        }
        List<QuizRow> rows = rows();
        if (index == null || index < 0 || index >= rows.size()) {
            // 没带下标 / 下标越界：按答错处理（注册页总会带上题目下标）
            return false;
        }
        return matches(rows.get(index).answer(), answer);
    }

    /** 把设置里的多行文本切成列表（按行、去首尾空白、忽略空行）。 */
    public static List<String> splitLines(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("\\R"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /** 答案是否匹配（去首尾空白 + 忽略大小写）。 */
    public static boolean matches(String expected, String given) {
        String left = expected == null ? "" : expected.trim();
        String right = given == null ? "" : given.trim();
        return !left.isEmpty() && left.equalsIgnoreCase(right);
    }
}
