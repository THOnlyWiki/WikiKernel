package cc.thonly.wikikernel.plugin.registrationquiz.hook;

import cc.thonly.wikikernel.common.extension.RegistrationContext;
import cc.thonly.wikikernel.common.extension.RegistrationFormField;
import cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizSettings;
import cc.thonly.wikikernel.plugin.registrationquiz.RegistrationQuizSupport;
import org.springframework.web.util.HtmlUtils;

/**
 * 注册页表单字段（{@link RegistrationFormField}）：随机抽一道题，渲染题目 + **隐藏的题目下标** +
 * 答案输入框（字段名 {@code registerQuizIndex} / {@code registerAnswer} 与迁移前 core 模板一致，
 * 注册接口与注册前钩子据此按下标校验）。
 * <p>
 * 只在功能开启且题库非空时渲染；没有插件时注册页没有任何问答字段。
 */
public class RegistrationQuizFormField implements RegistrationFormField {

    private final RegistrationQuizSettings settings;
    private final RegistrationQuizSupport support;

    public RegistrationQuizFormField(RegistrationQuizSettings settings, RegistrationQuizSupport support) {
        this.settings = settings;
        this.support = support;
    }

    @Override
    public String html(RegistrationContext context) {
        RegistrationQuizSettings.Challenge challenge = settings.pick();
        if (challenge == null) {
            return null;
        }
        String question = HtmlUtils.htmlEscape(challenge.question() == null ? "" : challenge.question());
        return "<div class=\"form-field quiz-field\">"
                + "<label for=\"wpRegisterAnswer\">" + question + "</label>"
                + "<input type=\"hidden\" name=\"registerQuizIndex\" value=\"" + challenge.index() + "\"/>"
                + "<input type=\"text\" id=\"wpRegisterAnswer\" name=\"registerAnswer\" autocomplete=\"off\" required/>"
                + "<div class=\"wiki-hint\">"
                + HtmlUtils.htmlEscape(support.text("auth.registerAnswerHint"))
                + "</div>"
                + "</div>";
    }
}
