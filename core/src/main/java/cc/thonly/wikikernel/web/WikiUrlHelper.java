package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.web.WikiUrls;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 模板里生成站内 URL 的统一入口。
 * <p>
 * 模板不再直接写 {@code @{/wiki/特殊:登录}} 这类裸中文路径，而是调用：
 * <pre>{@code
 * th:href="${wikiUrl.loginTo(loginReturnTo)}"
 * th:href="${wikiUrl.register()}"
 * th:href="${wikiUrl.preferences()}"
 * }</pre>
 * 这样路径段会经过 {@link WikiUrls#pagePath(String)} 百分号编码，查询参数也会正确编码。
 * 尤其重要：登录 / 注册 / 参数设置等特殊页必须用编码路径，避免部分客户端把裸 UTF-8
 * 路径直接发给 Tomcat 后被 400 拒绝。
 */
public final class WikiUrlHelper {

    /** 无状态实例；由 {@link cc.thonly.wikikernel.user.CurrentUserAdvice} 放到所有 MVC 模型里。 */
    public static final WikiUrlHelper INSTANCE = new WikiUrlHelper();

    private WikiUrlHelper() {
    }

    /** 任意页面标题 → 编码后的站内路径（含配置的页面前缀）。 */
    public String page(String title) {
        return WikiUrls.pagePath(title);
    }

    /** 特殊页名（不带「特殊:」前缀）→ 编码后的站内路径。 */
    public String special(String name) {
        return page("特殊:" + (name == null ? "" : name));
    }

    public String login() {
        return special("登录");
    }

    /** 登录页 + {@code returnto} 查询参数（值会编码；空白值不附加）。 */
    public String loginTo(String returnTo) {
        return withQuery("特殊:登录", "returnto", returnTo);
    }

    public String register() {
        return special("注册");
    }

    /** 注册页 + {@code returnto} 查询参数。 */
    public String registerTo(String returnTo) {
        return withQuery("特殊:注册", "returnto", returnTo);
    }

    public String passwordReset() {
        return special("重置密码");
    }

    public String preferences() {
        return special("参数设置");
    }

    public String appearance() {
        return special("外观");
    }

    /** 修改密码页 + {@code returnto}；空白时使用参数设置页。 */
    public String changePassword(String returnTo) {
        String target = isBlank(returnTo) ? preferences() : returnTo;
        return withQuery("特殊:修改密码", "returnto", target);
    }

    /**
     * 页面标题 + 一个查询参数。路径与参数名 / 参数值都会编码；
     * 值为空白时不附加查询串。
     */
    public String withQuery(String title, String name, String value) {
        String path = page(title);
        if (isBlank(value)) {
            return path;
        }
        return path + (path.contains("?") ? "&" : "?")
                + encode(name) + "=" + encode(value);
    }

    private static String encode(String value) {
        // URLEncoder 是 application/x-www-form-urlencoded 规则，会把路径分隔符也编码成 %2F。
        // 查询参数里的 / 是合法字符（RFC 3986 query = pchar / "/" / "?"），保留它既能避免
        // Tomcat 对 %2F 的额外处理，也让 returnto 与旧模板输出保持一致。
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/")
                .replace("%2f", "/");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
