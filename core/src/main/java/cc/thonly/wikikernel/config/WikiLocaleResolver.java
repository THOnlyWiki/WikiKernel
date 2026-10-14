package cc.thonly.wikikernel.config;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.LocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * MediaWiki 风格的语言解析：
 * <ol>
 *     <li>{@code ?uselang=zh|en}（参数名与 MediaWiki 一致），并记进会话；</li>
 *     <li>会话里记住的上次选择；</li>
 *     <li>默认简体中文。</li>
 * </ol>
 * 不支持的语言一律回落到默认语言；解析出的 Locale 会随请求交给 MessageSource 与 Thymeleaf。
 */
public class WikiLocaleResolver implements LocaleResolver {

    /** 切换语言的参数名（MediaWiki 也用 uselang）。 */
    public static final String LANGUAGE_PARAM = "uselang";

    private static final String SESSION_ATTRIBUTE = "wikiLocale";
    private static final List<String> SUPPORTED_LANGUAGES = List.of("zh", "en");
    private static final Locale DEFAULT_LOCALE = Locale.SIMPLIFIED_CHINESE;

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        Locale requested = parse(request.getParameter(LANGUAGE_PARAM));
        if (requested != null) {
            request.getSession(true).setAttribute(SESSION_ATTRIBUTE, requested);
            return requested;
        }
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_ATTRIBUTE) instanceof Locale sessionLocale) {
            return sessionLocale;
        }
        // 用户「参数设置 → 国际化」里的语言偏好
        WikiUser user = UserSession.current(request);
        if (user != null) {
            Locale preferred = parse(user.getLanguage());
            if (preferred != null) {
                return preferred;
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

    private static Locale parse(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        Locale locale = Locale.forLanguageTag(language.replace('_', '-'));
        return SUPPORTED_LANGUAGES.contains(locale.getLanguage()) ? locale : null;
    }
}
