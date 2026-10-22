package cc.thonly.wikikernel.plugin.reverseproxy.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条反向代理路由。
 *
 * <p>路由 URL 固定为 {@code /proxy_<slug>/}，这样不会和 Wiki 自己的
 * {@code /api/**}、{@code /wiki/**}、静态资源等路径冲突；{@code slug} 只允许
 * 字母、数字、下划线和连字符。上游地址由管理员配置，请求方无法修改目标主机，
 * 因此它不是任意 URL 的开放代理。</p>
 *
 * <p>{@code access}：{@code public} / {@code login} / {@code admin}；
 * {@code methods}：逗号分隔的 HTTP 方法，默认只允许 GET、HEAD。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReverseProxyRoute {

    public static final String ACCESS_PUBLIC = "public";
    public static final String ACCESS_LOGIN = "login";
    public static final String ACCESS_ADMIN = "admin";

    public static final String DEFAULT_METHODS = "GET,HEAD";
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int MAX_TIMEOUT_SECONDS = 120;

    /** URL 中的路由名，例如 {@code google_doc} → {@code /proxy_google_doc/}。 */
    private String slug = "";

    /** 上游基地址，例如 {@code https://docs.google.com/}。 */
    private String upstream = "";

    /** public / login / admin。 */
    private String access = ACCESS_ADMIN;

    /** 是否启用该路由。 */
    private boolean enabled = true;

    /** 是否改写 HTML / CSS 里的上游 URL（默认关闭，避免破坏复杂前端）。 */
    private boolean rewriteHtml = false;

    /** 是否为 HTML 加 CSP sandbox（隔离上游脚本，默认开启）。 */
    private boolean sandbox = true;

    /** 允许的 HTTP 方法，逗号分隔。 */
    private String methods = DEFAULT_METHODS;

    /** 上游请求超时（秒）。 */
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    /** 管理界面显示名；空时使用 slug。 */
    private String label = "";

    /** 是否允许解析到内网 / 回环地址（默认关闭）。 */
    private boolean allowPrivateTargets = false;

    /** 路由对外路径（不含主机），始终以斜杠结尾。 */
    public String publicPath() {
        return "/proxy_" + slug + "/";
    }
}
