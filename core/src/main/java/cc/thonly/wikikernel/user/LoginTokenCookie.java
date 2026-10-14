package cc.thonly.wikikernel.user;

import cc.thonly.wikikernel.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;

/**
 * 持久登录 Cookie 的读写（名 / 有效期来自 {@code wikikernel.auth}）。
 * HttpOnly + SameSite=Lax；HTTPS 下自动带 Secure。
 */
@Component
public class LoginTokenCookie {

    /** 「永不过期」时 Cookie 的 Max-Age（10 年，浏览器实际等同于长期有效）。 */
    private static final long TEN_YEARS_SECONDS = 10L * 365 * 24 * 60 * 60;

    private final AuthProperties properties;

    public LoginTokenCookie(AuthProperties properties) {
        this.properties = properties;
    }

    public String name() {
        return properties.getTokenCookieName();
    }

    /** 读取 Cookie 值；不存在返回空。 */
    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> name().equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    /** 签发 / 刷新持久登录 Cookie。 */
    public ResponseCookie create(String value, boolean secure) {
        return base(value)
                .maxAge(properties.getTokenExpireDays() > 0
                        ? (long) properties.getTokenExpireDays() * 24 * 60 * 60
                        : TEN_YEARS_SECONDS)
                .secure(secure)
                .build();
    }

    /** 删除 Cookie（退出登录 / 令牌失效）。 */
    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(name(), value)
                .httpOnly(true)
                .path("/")
                .sameSite("Lax");
    }
}
