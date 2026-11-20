package cc.thonly.wikikernel.config;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.i18n.InterfaceLanguage;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

@ControllerAdvice
public class InterfaceLanguageAdvice {
    @ModelAttribute("interfaceLanguages")
    public InterfaceLanguage[] interfaceLanguages() {
        return InterfaceLanguage.values();
    }

    @ModelAttribute("interfaceLanguage")
    public InterfaceLanguage interfaceLanguage() {
        return InterfaceLanguage.fromLocale(LocaleContextHolder.getLocale());
    }

    @ModelAttribute("preferredInterfaceLanguage")
    public InterfaceLanguage preferredInterfaceLanguage(HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        return user == null ? null : InterfaceLanguage.parse(user.getLanguage());
    }

    @ModelAttribute("interfaceLanguageQuery")
    public String interfaceLanguageQuery(HttpServletRequest request) {
        String query = request.getQueryString();
        if (query == null || query.isEmpty()) return "?";
        String remaining = Arrays.stream(query.split("&"))
                .filter(part -> !part.isEmpty())
                .filter(part -> !isLanguageParameter(part))
                .collect(Collectors.joining("&"));
        return remaining.isEmpty() ? "?" : "?" + remaining + "&";
    }

    private static boolean isLanguageParameter(String part) {
        int equals = part.indexOf('=');
        String name = equals < 0 ? part : part.substring(0, equals);
        try {
            return WikiLocaleResolver.LANGUAGE_PARAM.equals(URLDecoder.decode(name, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
