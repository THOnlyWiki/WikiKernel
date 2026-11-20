package cc.thonly.wikikernel.config;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.i18n.InterfaceLanguage;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

/**
 * MediaWiki 风格的语言解析：
 * <ol>
 *     <li>{@code ?uselang=} 指定的界面语言，并记进会话；</li>
 *     <li>会话里记住的上次选择；</li>
 *     <li>默认简体中文。</li>
 * </ol>
 * 不支持的语言一律回落到默认语言；解析出的 Locale 会随请求交给 MessageSource 与 Thymeleaf。
 */
public class WikiLocaleResolver implements LocaleResolver {

    /** 切换语言的参数名（MediaWiki 也用 uselang）。 */
    public static final String LANGUAGE_PARAM = "uselang";

    private static final String SESSION_ATTRIBUTE = "wikiLocale";
    private static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        InterfaceLanguage requested = InterfaceLanguage.parse(request.getParameter(LANGUAGE_PARAM));
        if (requested != null) {
            request.getSession(true).setAttribute(SESSION_ATTRIBUTE, requested.getLocale());
            return requested.getLocale();
        }
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_ATTRIBUTE) instanceof Locale sessionLocale) {
            InterfaceLanguage stored = InterfaceLanguage.fromLocale(sessionLocale);
            if (stored != null) return stored.getLocale();
        }
        // 用户「参数设置 → 国际化」里的语言偏好
        WikiUser user = UserSession.current(request);
        if (user != null) {
            InterfaceLanguage preferred = InterfaceLanguage.parse(user.getLanguage());
            if (preferred != null) {
                return preferred.getLocale();
            }
        }
        return DEFAULT_LOCALE;
    }

    /** 参数设置保存后调用：清掉会话里的临时语言（?uselang），让用户语言偏好生效。 */
    public static void clearSessionLocale(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_ATTRIBUTE);
        }
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        request.getSession(true).setAttribute(SESSION_ATTRIBUTE, locale);
    }

}
