package cc.thonly.wikikernel.plugin.registrationquiz;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizFakes.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册问答的题库逻辑（从 core 迁来）：
 * 随机抽题与下标对应、按下标校验（大小写与首尾空白宽容、缺下标 / 越界 = 答错）、
 * 数量不等时按较短配对、旧格式 {@code registration.question} 兼容、总开关与空题库。
 */
class RegistrationQuizSettingsTest {

    private static final String QUESTIONS = "1 + 1 = ?\n2 + 2 = ?\n本 wiki 叫什么？";
    private static final String ANSWERS = "2\n4\nWikiKernel";

    private static RegistrationQuizSettings settings(boolean enabled, String questions, String answers) {
        return new RegistrationQuizSettings(config(enabled, questions, answers, ""));
    }

    @Test
    void enabledOnlyWhenToggleOnAndQuestionBankNotEmpty() {
        assertFalse(settings(false, QUESTIONS, ANSWERS).enabled(), "开关关闭时不启用");
        assertFalse(settings(true, "", "").enabled(), "题库为空时不启用");
        assertTrue(settings(true, QUESTIONS, ANSWERS).enabled());
    }

    @Test
    void pickReturnsQuestionAlignedWithItsIndex() {
        RegistrationQuizSettings settings = settings(true, QUESTIONS, ANSWERS);
        List<String> questions = List.of(QUESTIONS.split("\n"));
        Set<Integer> seen = new HashSet<>();
        for (int attempt = 0; attempt < 50; attempt++) {
            RegistrationQuizSettings.Challenge challenge = settings.pick();
            assertTrue(challenge.index() >= 0 && challenge.index() < questions.size(), challenge.toString());
            assertEquals(questions.get(challenge.index()), challenge.question(), "显示的问题应与下标对应");
            seen.add(challenge.index());
        }
        assertFalse(seen.isEmpty(), "应该抽到题目");
    }

    @Test
    void pickReturnsNullWhenDisabled() {
        assertNull(settings(false, QUESTIONS, ANSWERS).pick());
        assertNull(settings(true, "", "").pick());
    }

    @Test
    void acceptsOnlyTheAnswerOfThatIndex() {
        RegistrationQuizSettings settings = settings(true, QUESTIONS, ANSWERS);
        assertTrue(settings.accepts(0, "2"));
        assertTrue(settings.accepts(1, "4"));
        // 大小写与首尾空白宽容
        assertTrue(settings.accepts(2, "  wikikernel "));
        assertTrue(settings.accepts(2, "WIKIKERNEL"));
        // 用别的题的答案 = 答错
        assertFalse(settings.accepts(1, "2"));
        // 没带下标 / 越界 / 非法 = 答错
        assertFalse(settings.accepts(null, "4"));
        assertFalse(settings.accepts(-1, "4"));
        assertFalse(settings.accepts(9, "4"));
        // 空答案 = 答错（空答案不算匹配）
        assertFalse(settings.accepts(0, ""));
        assertFalse(settings.accepts(0, "   "));
    }

    @Test
    void disabledQuizAcceptsAnything() {
        RegistrationQuizSettings settings = settings(false, QUESTIONS, ANSWERS);
        assertTrue(settings.accepts(null, "随便"));
        assertTrue(settings.accepts(9, ""));
    }

    @Test
    void unequalArraysPairByTheShorterLength() {
        RegistrationQuizSettings settings = new RegistrationQuizSettings(
                config(true, "问题一\n问题二\n问题三", "答案一", ""));
        List<RegistrationQuizSettings.QuizRow> rows = settings.rows();
        assertEquals(1, rows.size(), rows.toString());
        assertEquals("问题一", rows.get(0).question());
        assertEquals("答案一", rows.get(0).answer());
        // 配对后只认第 0 题；越界（原问题数组更长）按答错
        assertTrue(settings.accepts(0, "答案一"));
        assertFalse(settings.accepts(1, "答案二"));
    }

    @Test
    void legacySingleQuestionStillWorks() {
        // 旧格式：registration.questions 为空 + registration.question 单问题 + "|" 分隔的多个可接受答案
        RegistrationQuizSettings settings = new RegistrationQuizSettings(
                config(true, "", "2|二", "1 + 1 = ?"));
        List<RegistrationQuizSettings.QuizRow> rows = settings.rows();
        assertEquals(2, rows.size(), rows.toString());
        assertTrue(rows.stream().allMatch(row -> "1 + 1 = ?".equals(row.question())));
        // 多个可接受答案按出现顺序展开成多行同题
        assertEquals("2", rows.get(0).answer());
        assertEquals("二", rows.get(1).answer());
        assertTrue(settings.accepts(0, "2"));
        assertTrue(settings.accepts(1, "二"));
        assertFalse(settings.accepts(0, "二"));
    }

    @Test
    void combinedQuizSupportsMultipleAcceptedAnswersPerQuestion() {
        RegistrationQuizSettings settings = new RegistrationQuizSettings(
                config(true, "1 + 1 = ? | 2 | 二\n本 wiki 叫什么？ | WikiKernel", "", "", ""));
        List<RegistrationQuizSettings.QuizRow> rows = settings.rows();
        assertEquals(2, rows.size(), rows.toString());
        assertEquals(List.of("2", "二"), rows.get(0).answers());
        assertTrue(settings.accepts(0, "2"));
        assertTrue(settings.accepts(0, " 二 "));
        assertFalse(settings.accepts(0, "三"));
        assertTrue(settings.accepts(1, "wikikernel"));
        assertEquals("1 + 1 = ? | 2 | 二\n本 wiki 叫什么？ | WikiKernel", settings.editorText());
    }

    @Test
    void fullWidthSeparatorIsAccepted() {
        RegistrationQuizSettings settings = new RegistrationQuizSettings(
                config(true, "问题｜答案一｜答案二", "", "", ""));
        List<RegistrationQuizSettings.QuizRow> rows = settings.rows();
        assertEquals(1, rows.size(), rows.toString());
        assertEquals(List.of("答案一", "答案二"), rows.get(0).answers());
    }

    @Test
    void splitLinesAndMatchesHelpers() {
        assertEquals(List.of("甲", "乙"), RegistrationQuizSettings.splitLines(" 甲 \n\n 乙\r\n"));
        assertEquals(List.of(), RegistrationQuizSettings.splitLines("   "));
        assertTrue(RegistrationQuizSettings.matches("WikiKernel", " wikikernel "));
        assertFalse(RegistrationQuizSettings.matches("", ""));
        assertFalse(RegistrationQuizSettings.matches(null, "x"));
    }
}
