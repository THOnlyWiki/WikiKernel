package cc.thonly.wikikernel.plugin.reverseproxy.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 管理 API 的 CSRF 令牌。
 *
 * <p>仅依赖同源检查不够：旧版过滤器的 {@code Origin: null} 分支曾被发现可绕过。
 * 管理 API 要求一个保存在 HttpSession 中、只能由同源脚本读取的随机令牌，
 * 并通过自定义请求头发送。跨站表单无法读取 / 伪造自定义请求头，跨站 fetch
 * 也因同源策略拿不到令牌。</p>
 */
public final class ReverseProxyCsrf {

    public static final String HEADER = "X-Reverse-Proxy-CSRF";
    private static final String SESSION_KEY = "reverse-proxy.csrf";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ReverseProxyCsrf() {
    }

    /** 获取（必要时创建）当前会话的令牌。 */
    public static String token(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        synchronized (session) {
            Object existing = session.getAttribute(SESSION_KEY);
            if (existing instanceof String value && !value.isBlank()) {
                return value;
            }
            byte[] bytes = new byte[32];
            RANDOM.nextBytes(bytes);
            String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute(SESSION_KEY, value);
            return value;
        }
    }

    /** 校验请求头里的令牌；没有会话 / 没有令牌 / 不匹配都失败。 */
    public static boolean verify(HttpServletRequest request, String supplied) {
        if (request == null || supplied == null || supplied.isBlank()) {
            return false;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Object expected = session.getAttribute(SESSION_KEY);
        if (!(expected instanceof String value) || value.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),
                supplied.trim().getBytes(StandardCharsets.UTF_8));
    }
}
