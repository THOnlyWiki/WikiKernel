package cc.thonly.wikikernel.plugin.confirmedit.hook;

import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.common.extension.SessionAttributes;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes;
import cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditSettings;
import cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditSupport;
import cc.thonly.wikikernel.plugin.confirmedit.service.CaptchaChallenge;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes.api;
import static cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes.config;
import static cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes.messages;
import static cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes.sequence;
import static cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes.session;
import static cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes.web;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 保存前钩子：未豁免用户答错 → {@code BLOCK}（说明给编辑页显示、题目被消耗 → 下次渲染换题）；
 * 答对 → 放行；功能关闭 / 免验证组 / {@code api} 来源 → 直接放行（迁移前 api.php 不受 ConfirmEdit 影响）。
 */
class ConfirmEditGuardTest {

    private static final Viewer EDITOR = Viewer.of(8, "编者", Set.of("用户"));
    private static final Viewer ADMIN = Viewer.of(9, "管理员甲", Set.of("管理员"));

    private static ConfirmEditGuard guard(boolean enabled, String bypass) {
        return new ConfirmEditGuard(new ConfirmEditSupport(
                new ConfirmEditSettings(config(enabled, bypass)), new CaptchaChallenge(sequence(0, 0)),
                messages(Map.of("confirmEdit.wrong", "验证答案不正确，请重试。"))));
    }

    @Test
    void wrongAnswerBlocksAndConsumesTheQuestion() {
        SessionAttributes session = session();
        ConfirmEditGuard guard = guard(true, "管理员");
        // 先渲染编辑页（生成题目：1 + 1 =）
        session.setAttribute(CaptchaChallenge.SESSION_QUESTION, "1 + 1 =");
        session.setAttribute(CaptchaChallenge.SESSION_ANSWER, "2");

        EditGuard.Outcome outcome = guard.beforeSave(web(EDITOR, session, Map.of("captchaAnswer", "3")));
        assertEquals(EditGuard.Outcome.Result.BLOCK, outcome.result());
        assertEquals("验证答案不正确，请重试。", outcome.message());
        assertNull(session.getAttribute(CaptchaChallenge.SESSION_ANSWER), "答错要消耗题目（下次渲染换新题）");
    }

    @Test
    void missingAnswerBlocksAsWrong() {
        SessionAttributes session = session();
        ConfirmEditGuard guard = guard(true, "管理员");
        session.setAttribute(CaptchaChallenge.SESSION_QUESTION, "1 + 1 =");
        session.setAttribute(CaptchaChallenge.SESSION_ANSWER, "2");

        assertEquals(EditGuard.Outcome.Result.BLOCK,
                guard.beforeSave(web(EDITOR, session, Map.of())).result(), "没带答案 = 答错");
    }

    @Test
    void correctAnswerAllowsSaving() {
        SessionAttributes session = session();
        session.setAttribute(CaptchaChallenge.SESSION_QUESTION, "1 + 1 =");
        session.setAttribute(CaptchaChallenge.SESSION_ANSWER, "2");
        EditGuard.Outcome outcome = guard(true, "管理员")
                .beforeSave(web(EDITOR, session, Map.of("captchaAnswer", " 2 ")));
        assertEquals(EditGuard.Outcome.Result.ALLOW, outcome.result());
        assertNull(session.getAttribute(CaptchaChallenge.SESSION_ANSWER), "答对后题目也要消耗（一次一题）");
    }

    @Test
    void disabledFeatureAndBypassGroupsSkipValidation() {
        SessionAttributes session = session();
        session.setAttribute(CaptchaChallenge.SESSION_ANSWER, "2");
        assertEquals(EditGuard.Outcome.Result.ALLOW,
                guard(false, "管理员").beforeSave(web(EDITOR, session, Map.of("captchaAnswer", "0"))).result(),
                "功能关闭时不校验");
        assertEquals(EditGuard.Outcome.Result.ALLOW,
                guard(true, "管理员").beforeSave(web(ADMIN, session, Map.of("captchaAnswer", "0"))).result(),
                "免验证组不校验");
    }

    @Test
    void apiSourceIsNeverBlocked() {
        SessionAttributes session = session();
        session.setAttribute(CaptchaChallenge.SESSION_QUESTION, "1 + 1 =");
        session.setAttribute(CaptchaChallenge.SESSION_ANSWER, "2");
        // api.php?action=edit：来源 api，即使答错（或没带答案）也放行
        assertEquals(EditGuard.Outcome.Result.ALLOW,
                guard(true, "管理员").beforeSave(api(EDITOR, session, Map.of())).result());
        assertEquals("2", session.getString(CaptchaChallenge.SESSION_ANSWER), "api 来源不应消耗题目");
    }
}
