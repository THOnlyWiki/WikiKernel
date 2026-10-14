package cc.thonly.wikikernel.plugin.registrationquiz.hook;

import cc.thonly.wikikernel.common.extension.RegistrationGuard;
import cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizFakes;
import cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizSettings;
import cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizFakes.config;
import static cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizFakes.messages;
import static cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizFakes.submit;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 注册前钩子：按下标校验答案（正确 → 放行；错 / 缺下标 / 越界 → {@code BLOCK} + 说明）；
 * 开关关闭或题库为空 → 直接放行（与迁移前「开关关闭 = 不门禁」一致）。
 */
class RegistrationQuizGuardTest {

    private static final String QUESTIONS = "1 + 1 = ?\n2 + 2 = ?\n本 wiki 叫什么？";
    private static final String ANSWERS = "2\n4\nWikiKernel";

    private static RegistrationQuizGuard guard(boolean enabled, String questions, String answers) {
        RegistrationQuizSettings settings = new RegistrationQuizSettings(config(enabled, questions, answers, ""));
        return new RegistrationQuizGuard(settings, new RegistrationQuizSupport(messages(Map.of(
                "auth.registerAnswerWrong", "注册问答答案不正确，请重试。"))));
    }

    @Test
    void correctAnswerIsAllowed() {
        RegistrationQuizGuard guard = guard(true, QUESTIONS, ANSWERS);
        assertEquals(RegistrationGuard.Outcome.Result.ALLOW, guard.beforeRegister(
                submit("新用户", "", Map.of("registerQuizIndex", "2", "registerAnswer", " wikikernel "))).result(),
                "答案忽略大小写与首尾空白");
        assertEquals(RegistrationGuard.Outcome.Result.ALLOW, guard.beforeRegister(
                submit("新用户", "", Map.of("registerQuizIndex", "0", "registerAnswer", "2"))).result());
    }

    @Test
    void wrongAnswerIsBlockedWithLocalizedMessage() {
        RegistrationQuizGuard guard = guard(true, QUESTIONS, ANSWERS);
        RegistrationGuard.Outcome outcome = guard.beforeRegister(
                submit("新用户", "", Map.of("registerQuizIndex", "1", "registerAnswer", "2")));
        assertEquals(RegistrationGuard.Outcome.Result.BLOCK, outcome.result(), "用别的题的答案回答第 1 题 = 答错");
        assertEquals("注册问答答案不正确，请重试。", outcome.message());
    }

    @Test
    void missingOrOutOfRangeIndexIsBlocked() {
        RegistrationQuizGuard guard = guard(true, QUESTIONS, ANSWERS);
        assertEquals(RegistrationGuard.Outcome.Result.BLOCK,
                guard.beforeRegister(submit("新用户", "", Map.of("registerAnswer", "4"))).result(), "没带下标 = 答错");
        assertEquals(RegistrationGuard.Outcome.Result.BLOCK,
                guard.beforeRegister(submit("新用户", "", Map.of("registerQuizIndex", "9", "registerAnswer", "4"))).result(),
                "下标越界 = 答错");
        assertEquals(RegistrationGuard.Outcome.Result.BLOCK,
                guard.beforeRegister(submit("新用户", "", Map.of("registerQuizIndex", "abc", "registerAnswer", "4"))).result(),
                "非法下标 = 答错");
    }

    @Test
    void disabledOrEmptyBankAllowsRegistration() {
        assertEquals(RegistrationGuard.Outcome.Result.ALLOW,
                guard(false, QUESTIONS, ANSWERS).beforeRegister(submit("新用户", "", Map.of())).result(),
                "开关关闭时不门禁");
        assertEquals(RegistrationGuard.Outcome.Result.ALLOW,
                guard(true, "", "").beforeRegister(submit("新用户", "", Map.of())).result(),
                "题库为空时不门禁");
    }

    @Test
    void legacySingleQuestionIsCheckedToo() {
        RegistrationQuizSettings settings = new RegistrationQuizSettings(
                config(true, "", "2|二", "1 + 1 = ?"));
        RegistrationQuizGuard guard = new RegistrationQuizGuard(settings,
                new RegistrationQuizSupport(messages(Map.of())));
        assertEquals(RegistrationGuard.Outcome.Result.ALLOW, guard.beforeRegister(
                submit("新用户", "", Map.of("registerQuizIndex", "1", "registerAnswer", "二"))).result());
        assertEquals(RegistrationGuard.Outcome.Result.BLOCK, guard.beforeRegister(
                submit("新用户", "", Map.of("registerQuizIndex", "0", "registerAnswer", "二"))).result(),
                "第 0 题只接受「2」（旧格式多个可接受答案按下标展开）");
    }
}
