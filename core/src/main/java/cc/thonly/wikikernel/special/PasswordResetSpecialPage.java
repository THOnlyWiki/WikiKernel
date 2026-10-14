package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:重置密码} / {@code Special:PasswordReset}（仿 MediaWiki）：忘记密码入口。
 * <p>
 * 表单提交到 {@code POST /password/reset-request}（{@link cc.thonly.wikikernel.controller.PasswordResetController}），
 * 成功后同页提示「重置邮件已发送」（不暴露账号是否存在）。
 */
@Component
public class PasswordResetSpecialPage implements SpecialPage {

    private final MessageSource messageSource;

    public PasswordResetSpecialPage(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "重置密码";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("special.passwordReset", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("PasswordReset", "忘记密码", "密码重置");
    }

    @Override
    public String template() {
        return "special/password-reset";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        // 表单是静态的，结果由 POST /password/reset-request 直接渲染同一模板
    }
}
