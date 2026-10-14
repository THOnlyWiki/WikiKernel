package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:登录} / {@code Special:UserLogin}：MediaWiki 风格的登录页。
 * 表单由 {@code /js/auth.js} 提交到 {@code POST /api/user/login}。
 */
@Component
public class LoginSpecialPage implements SpecialPage {

    private final MessageSource messageSource;

    public LoginSpecialPage(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "登录";
    }

    @Override
    public String displayName() {
        return messageSource.getMessage("auth.login", null, LocaleContextHolder.getLocale());
    }

    @Override
    public List<String> aliases() {
        return List.of("Login", "UserLogin", "用户登录");
    }

    @Override
    public String template() {
        return "special/login";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        // 登录成功后跳回 ?returnto=...（只允许站内路径，防开放重定向）
        model.put("returnto", safeReturnTo(request.parameters().get("returnto")));
    }

    static String safeReturnTo(String value) {
        if (value == null || !value.startsWith("/") || value.startsWith("//")) {
            return "/";
        }
        return value;
    }
}
