package cc.thonly.wikikernel.plugin.confirmedit.service;

import cc.thonly.wikikernel.common.extension.SessionAttributes;
import cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes.sequence;
import static cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 随机加法题：题面与答案一致、题面与会话绑定（一次一题）、答对 / 答错都消耗题目（答错换新题）、
 * 答案宽容（忽略空白与非数字字符）、会话里没有题目时返回 {@code MISSING}。
 */
class CaptchaChallengeTest {

    private static final Pattern QUESTION = Pattern.compile("^(\\d+) \\+ (\\d+) =$");

    @Test
    void generatesAdditionQuestionAndStoresAnswerInSession() {
        SessionAttributes session = session();
        CaptchaChallenge challenge = new CaptchaChallenge(sequence(5, 6)); // 6 + 7
        String question = challenge.question(session);

        Matcher matcher = QUESTION.matcher(question);
        assertTrue(matcher.matches(), "题面应是「a + b =」形式：" + question);
        int expected = Integer.parseInt(matcher.group(1)) + Integer.parseInt(matcher.group(2));
        assertEquals("6 + 7 =", question, "固定随机源下题面可复现");
        assertEquals(String.valueOf(expected), session.getString(CaptchaChallenge.SESSION_ANSWER));
        assertEquals(question, session.getString(CaptchaChallenge.SESSION_QUESTION));
        assertEquals(CaptchaChallenge.Result.OK, challenge.validate(session, String.valueOf(expected)));
    }

    @Test
    void reusesQuestionUntilItIsConsumed() {
        SessionAttributes session = session();
        CaptchaChallenge challenge = new CaptchaChallenge(sequence(0, 0, 7, 7));
        String first = challenge.question(session);
        assertEquals("1 + 1 =", first);
        // 同一会话里再次渲染编辑页：沿用同一道题（不会“刷新就换题”）
        assertEquals(first, challenge.question(session));

        assertEquals(CaptchaChallenge.Result.OK, challenge.validate(session, "2"));
        // 校验后题目被消耗
        assertNull(session.getAttribute(CaptchaChallenge.SESSION_ANSWER));
        assertNull(session.getAttribute(CaptchaChallenge.SESSION_QUESTION));
        // 下一次渲染换一道新题
        assertEquals("8 + 8 =", challenge.question(session));
    }

    @Test
    void wrongAnswerIsConsumedAndQuestionChanges() {
        SessionAttributes session = session();
        CaptchaChallenge challenge = new CaptchaChallenge(sequence(0, 0, 2, 3)); // 1 + 1 → 3 + 4
        assertEquals("1 + 1 =", challenge.question(session));

        assertEquals(CaptchaChallenge.Result.WRONG, challenge.validate(session, "99"));
        assertNull(session.getAttribute(CaptchaChallenge.SESSION_QUESTION), "答错也要消耗题目");
        assertEquals("3 + 4 =", challenge.question(session), "答错后换一道新题");
        assertEquals(CaptchaChallenge.Result.OK, challenge.validate(session, "7"));
    }

    @Test
    void answerIsTolerantOfBlanksAndNonDigits() {
        SessionAttributes session = session();
        CaptchaChallenge challenge = new CaptchaChallenge(sequence(0, 0)); // 1 + 1 = 2
        challenge.question(session);
        assertEquals(CaptchaChallenge.Result.OK, challenge.validate(session, " 2 分 "));

        String again = challenge.question(session);
        assertEquals("1 + 1 =", again);
        assertEquals(CaptchaChallenge.Result.WRONG, challenge.validate(session, "三"));
    }

    @Test
    void missingQuestionIsReportedAsMissing() {
        SessionAttributes session = session();
        CaptchaChallenge challenge = new CaptchaChallenge();
        assertEquals(CaptchaChallenge.Result.MISSING, challenge.validate(session, "2"));
        assertEquals(CaptchaChallenge.Result.MISSING, challenge.validate(null, "2"));
        assertNotNull(challenge.question(session), "没有题目时应生成一道");
    }
}
