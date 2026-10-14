package cc.thonly.wikikernel.user;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.service.LoginTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * 持久登录过滤器：Session 里没有用户时，尝试用 {@code wiki_token} Cookie 自动登录。
 * <p>
 * 令牌在数据库里，所以服务重启（内存 Session 丢失）后仍能恢复登录；
 * 令牌无效时顺手清掉 Cookie，避免反复查询。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class PersistentLoginFilter extends OncePerRequestFilter {

    private final LoginTokenService loginTokenService;
    private final LoginTokenCookie tokenCookie;

    public PersistentLoginFilter(LoginTokenService loginTokenService, LoginTokenCookie tokenCookie) {
        this.loginTokenService = loginTokenService;
        this.tokenCookie = tokenCookie;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (UserSession.current(request) == null) {
            Optional<String> value = tokenCookie.read(request);
            if (value.isPresent()) {
                Optional<WikiUser> user = loginTokenService.resolve(value.get());
                if (user.isPresent()) {
                    UserSession.login(request, user.get().toTransferCopy(), true);
                } else {
                    response.addHeader(HttpHeaders.SET_COOKIE, tokenCookie.clear().toString());
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
