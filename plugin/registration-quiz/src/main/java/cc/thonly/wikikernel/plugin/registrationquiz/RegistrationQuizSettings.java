package cc.thonly.wikikernel.plugin.registrationquiz;

import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.util.Strings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 注册问答的站点设置读取与抽题 / 校验逻辑（**从 core 的 {@code RegistrationQuizService} 整体迁来**）：
 * 新格式是**一行一道题**，题目和该题的所有可接受答案放在同一行，用 {@code |} 分隔：
 * <pre>
 * 1 + 1 = ? | 2 | 二
 * 本 wiki 叫什么？ | WikiKernel
 * </pre>
 * 同一道题可以有多个答案；注册页每次随机抽一道（并带回下标），必须答对该题**任意一个**答案。
 * <p>
 * 兼容旧配置：{@code registration.questions} / {@code registration.answers} 两个等长数组，
 * 以及更早的 {@code registration.question} + {@code registration.answers}（{@code |} 分隔多个答案）。
 * 新格式非空时优先使用新格式，旧键不需要立即删除。
 * <p>
 * 键名与 core 的站点设置（{@code RegistrationQuizKeys}）/ {@code LocalSettings.yaml} 保持一致；
 * 插件只读，不写设置（写设置仍由 core 的表单做）。
 */
public class RegistrationQuizSettings {

    /** 站点设置键：功能开关（与 core 的 {@code FeatureService.REGISTRATION_QUIZ_KEY} 相同）。 */
    public static final String ENABLED_KEY = "features.registration-quiz";

    /** 推荐的新键：每行一题，格式为「问题 | 答案1 | 答案2」。 */
    public static final String QUIZ_KEY = "registration.quiz";

    /** 旧键：问题数组（每行一题）。 */
    public static final String QUESTIONS_KEY = "registration.questions";

    /** 旧键：答案数组（每行一题，与问题数组等长）。 */
    public static final String ANSWERS_KEY = "registration.answers";

    /** 更早的旧键：单个问题（仅兼容旧配置；新数组非空时不用它）。 */
    public static final String LEGACY_QUESTION_KEY = "registration.question";

    /** 抽到的题目：下标 + 问题文本。 */
    public record Challenge(int index, String question) {
    }

    /**
     * 一行问答对：一个问题可以带多个可接受答案。
     */
    public record QuizRow(String question, List<String> answers) {

        public QuizRow {
            answers = answers == null ? List.of() : List.copyOf(answers);
        }

        /** 兼容旧调用：返回第一个可接受答案。 */
        public String answer() {
            return answers.isEmpty() ? "" : answers.get(0);
        }
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

    /**
     * 全部问答对：优先新格式 {@link #QUIZ_KEY}；否则兼容旧的两个数组 / 单问题格式。
     * 旧数组数量不等时仍按较短长度配对，避免升级前配置直接失效。
     */
    public List<QuizRow> rows() {
        String quiz = Strings.trimToEmpty(siteConfig == null ? "" : siteConfig.get(QUIZ_KEY, ""));
        if (!quiz.isEmpty()) {
            return parseRows(quiz);
        }
        List<String> questions = splitLines(siteConfig == null ? "" : siteConfig.get(QUESTIONS_KEY, ""));
        if (!questions.isEmpty()) {
            List<String> answers = splitLines(siteConfig.get(ANSWERS_KEY, ""));
            int size = Math.min(questions.size(), answers.size());
            List<QuizRow> rows = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                rows.add(new QuizRow(questions.get(index), List.of(answers.get(index))));
            }
            return List.copyOf(rows);
        }
        // 兼容更早的配置：单问题 registration.question + 多个可接受答案（| 或换行分隔）
        String legacyQuestion = Strings.trimToEmpty(siteConfig == null ? "" : siteConfig.get(LEGACY_QUESTION_KEY, ""));
        if (legacyQuestion.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(siteConfig.get(ANSWERS_KEY, "").split("[|｜\\r\\n]+"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(answer -> new QuizRow(legacyQuestion, List.of(answer)))
                .toList();
    }

    /**
     * 解析新格式。每行至少要有「问题 | 答案」；题目和答案去首尾空白，空答案忽略，
     * 同一题的重复答案只保留一次。格式不完整 / 没有答案的行会被忽略。
     */
    public static List<QuizRow> parseRows(String raw) {
        List<QuizRow> rows = new ArrayList<>();
        for (String line : splitLines(raw)) {
            String[] parts = line.split("[|｜]", -1);
            if (parts.length < 2) {
                continue;
            }
            String question = parts[0].trim();
            List<String> answers = new ArrayList<>();
            for (int index = 1; index < parts.length; index++) {
                String answer = parts[index].trim();
                if (!answer.isEmpty() && !answers.contains(answer)) {
                    answers.add(answer);
                }
            }
            if (!question.isEmpty() && !answers.isEmpty()) {
                rows.add(new QuizRow(question, answers));
            }
        }
        return List.copyOf(rows);
    }

    /** 站点设置 textarea 的当前内容：新格式原样显示；只有旧格式时转换成新格式，方便直接编辑。 */
    public String editorText() {
        String quiz = Strings.trimToEmpty(siteConfig == null ? "" : siteConfig.get(QUIZ_KEY, ""));
        if (!quiz.isEmpty()) {
            return quiz;
        }
        StringBuilder text = new StringBuilder();
        for (QuizRow row : rows()) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(row.question()).append(" | ").append(String.join(" | ", row.answers()));
        }
        return text.toString();
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
        return rows.get(index).answers().stream().anyMatch(expected -> matches(expected, answer));
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
