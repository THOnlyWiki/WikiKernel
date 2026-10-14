package cc.thonly.wikikernel.plugin.confirmedit.hook;

import cc.thonly.wikikernel.common.extension.EditFormContext;
import cc.thonly.wikikernel.common.extension.SessionAttributes;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes;
import cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditSettings;
import cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditSupport;
import cc.thonly.wikikernel.plugin.confirmedit.service.CaptchaChallenge;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes.config;
import static cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes.form;
import static cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes.messages;
import static cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes.sequence;
import static cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditFakes.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编辑页表单字段：编辑框下方插入题目 + 答案输入框（字段名 {@code captchaAnswer} 与迁移前一致）、
 * 题面按会话沿用、功能关闭或命中免验证组时不渲染。
 */
class ConfirmEditFormFieldTest {

    private static final Viewer EDITOR = Viewer.of(8, "编者", Set.of("用户"));
    private static final Viewer ADMIN = Viewer.of(9, "管理员甲", Set.of("管理员"));

    private static ConfirmEditFormField field(boolean enabled, String bypass) {
        return new ConfirmEditFormField(new ConfirmEditSupport(
                new ConfirmEditSettings(config(enabled, bypass)), new CaptchaChallenge(sequence(0, 0)),
                messages(Map.of("confirmEdit.question", "请回答验证题（防机器人）：",
                        "confirmEdit.answerPlaceholder", "答案（数字）"))));
    }

    @Test
    void rendersQuestionAndAnswerInputAndStoresChallenge() {
        SessionAttributes session = session();
        EditFormContext context = form(EDITOR, session);
        String html = field(true, "管理员").html(context);

        assertNotNull(html);
        assertTrue(html.contains("edit-captcha"), html);
        assertTrue(html.contains("captcha-question"), html);
        assertTrue(html.contains("1 + 1 ="), html);
        assertTrue(html.contains("name=\"captchaAnswer\""), html);
        assertTrue(html.contains("请回答验证题"), html);
        assertTrue(html.contains("答案（数字）"), html);
        // 题面与答案存进会话（保存前钩子据此校验）
        assertEquals("1 + 1 =", session.getString(CaptchaChallenge.SESSION_QUESTION));
        assertEquals("2", session.getString(CaptchaChallenge.SESSION_ANSWER));
    }

    @Test
    void questionIsStableAcrossRendersUntilConsumed() {
        SessionAttributes session = session();
        ConfirmEditFormField field = field(true, "管理员");
        String first = field.html(form(EDITOR, session));
        String second = field.html(form(EDITOR, session));
        assertEquals(first, second, "同一会话里重复渲染编辑页题目不变");
    }

    @Test
    void hiddenWhenFeatureDisabledOrViewerExempt() {
        assertNull(field(false, "管理员").html(form(EDITOR, session())), "功能关闭时不显示");
        assertNull(field(true, "管理员").html(form(ADMIN, session())), "免验证组不显示");
        // 匿名用户需要验证码（站点设置留空 = 默认免验证组，不含匿名）
        assertNotNull(field(true, "").html(form(Viewer.ANONYMOUS, session())));
        // 会话为空（SessionAttributes.NONE）时仍应渲染，页面不报错
        assertNotNull(field(true, "管理员").html(form(EDITOR, SessionAttributes.NONE)));
    }
}
