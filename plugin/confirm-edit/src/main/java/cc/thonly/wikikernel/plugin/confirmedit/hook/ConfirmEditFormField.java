package cc.thonly.wikikernel.plugin.confirmedit.hook;

import cc.thonly.wikikernel.common.extension.EditFormContext;
import cc.thonly.wikikernel.common.extension.EditFormField;
import cc.thonly.wikikernel.plugin.confirmedit.ConfirmEditSupport;
import cc.thonly.wikikernel.plugin.confirmedit.service.CaptchaChallenge;
import org.springframework.web.util.HtmlUtils;

/**
 * 编辑页表单字段（{@link EditFormField}）：在编辑框下方插入一道随机加法题 + 答案输入框。
 * <p>
 * 只在功能开启且当前用户未命中免验证组时渲染（含匿名）；题面按会话沿用，答错后 core 重新渲染编辑页
 * 时会生成新题（见 {@link CaptchaChallenge}）。字段名与迁移前 core 模板一致（{@code captchaAnswer}），
 * 因此保存前钩子（{@link ConfirmEditGuard}）能直接从表单参数里取到答案。
 */
public class ConfirmEditFormField implements EditFormField {

    private final ConfirmEditSupport support;

    public ConfirmEditFormField(ConfirmEditSupport support) {
        this.support = support;
    }

    @Override
    public String html(EditFormContext context) {
        if (context == null || !support.appliesTo(context.viewer())) {
            return null;
        }
        String question = support.question(context.session());
        if (question.isEmpty()) {
            return null;
        }
        String label = support.text("confirmEdit.question");
        String placeholder = support.text("confirmEdit.answerPlaceholder");
        return "<div class=\"edit-captcha form-field\">"
                + "<label for=\"wpCaptchaAnswer\">" + escape(label) + "</label>"
                + "<span class=\"captcha-question\">" + escape(question) + "</span>"
                + "<input type=\"text\" id=\"wpCaptchaAnswer\" name=\"captchaAnswer\" autocomplete=\"off\""
                + " inputmode=\"numeric\" placeholder=\"" + escapeAttribute(placeholder) + "\"/>"
                + "</div>";
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    private static String escapeAttribute(String value) {
        return escape(value).replace("\"", "&quot;");
    }
}
