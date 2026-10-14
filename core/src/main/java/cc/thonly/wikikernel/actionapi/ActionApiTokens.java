package cc.thonly.wikikernel.actionapi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * MediaWiki Action API 的会话 token（{@code action=query&meta=tokens}）：
 * <ul>
 *     <li>{@code logintoken}：登录用（仿 MediaWiki 的 login token）；</li>
 *     <li>{@code csrftoken}：编辑等写操作用（仿 MediaWiki 的 csrf token）。</li>
 * </ul>
 * 存在 HTTP Session 里（与网页表单的 Spring CSRF 相互独立，/api.php 由本类自行校验）。
 */
public final class ActionApiTokens {

    private static final String CSRF_KEY = "wikikernel.actionapi.csrf";
    private static final String LOGIN_KEY = "wikikernel.actionapi.login";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ActionApiTokens() {
    }

    /** CSRF token（不存在则生成）。 */
    public static String csrf(HttpServletRequest request) {
        return ensure(request, CSRF_KEY);
    }

    /** 登录 token（不存在则生成）。 */
    public static String login(HttpServletRequest request) {
        return ensure(request, LOGIN_KEY);
    }

    /** 校验 token（类型为 {@link #csrf} 或 {@link #login} 的值）。 */
    public static boolean valid(HttpServletRequest request, String expected, String provided) {
        if (expected == null || provided == null || provided.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    /** 登录成功后轮换 CSRF token（与 MediaWiki 一致）。 */
    public static void rotateCsrf(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(CSRF_KEY);
        }
    }

    private static String ensure(HttpServletRequest request, String key) {
        HttpSession session = request.getSession(true);
        Object existing = session.getAttribute(key);
        if (existing instanceof String value && !value.isEmpty()) {
            return value;
        }
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String value = HexFormat.of().formatHex(bytes);
        session.setAttribute(key, value);
        return value;
    }
}
