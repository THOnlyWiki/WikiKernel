package cc.thonly.wikikernel.plugin.reverseproxy.service;

import cc.thonly.wikikernel.plugin.reverseproxy.model.ReverseProxyRoute;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * URL 映射规则（纯函数，便于单元测试）。
 *
 * <p>代理路径 {@code /proxy_<slug>/<suffix>} 映射到
 * {@code upstreamBasePath + <suffix>}。例如上游 {@code https://example.com/docs/}
 * 的 {@code /proxy_docs/foo} 会转发到 {@code https://example.com/docs/foo}。
 *
 * <p>只处理与上游同源的 URL。外部 URL 保持原样，避免把代理变成可访问任意主机的
 * 开放代理；这也意味着上游页面里指向其它域名的资源仍然由用户直接访问。</p>
 */
public final class ReverseProxyUrlMapper {

    public static final String PROXY_PREFIX = "/proxy_";

    private ReverseProxyUrlMapper() {
    }

    /**
     * 从请求路径中取出路由后缀（以 {@code /} 开头；根路由为 {@code ""}）。
     * 路径不属于该路由时返回 {@code null}。
     */
    public static String suffix(String requestUri, String slug) {
        if (requestUri == null || slug == null || slug.isBlank()) {
            return null;
        }
        String prefix = PROXY_PREFIX + slug;
        if (requestUri.equals(prefix)) {
            return "";
        }
        if (!requestUri.startsWith(prefix + "/")) {
            return null;
        }
        String suffix = requestUri.substring(prefix.length());
        // Tomcat 在禁用 Cookie 时可能把 ;jsessionid=… 拼进 URL；这不是上游路径。
        int session = suffix.indexOf(";jsessionid=");
        if (session >= 0) {
            int next = suffix.indexOf('/', session);
            suffix = next < 0 ? suffix.substring(0, session) : suffix.substring(0, session) + suffix.substring(next);
        }
        validateSuffix(suffix);
        return suffix;
    }

    /** 校验请求后缀，拒绝目录穿越与编码过的斜杠 / 反斜杠。 */
    public static void validateSuffix(String suffix) {
        if (suffix == null) {
            throw new IllegalArgumentException("代理路径不能为空");
        }
        if (suffix.indexOf('\\') >= 0 || suffix.indexOf('\r') >= 0 || suffix.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("代理路径包含非法字符");
        }
        String lower = suffix.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")) {
            throw new IllegalArgumentException("代理路径包含编码过的点 / 斜杠");
        }
        for (String segment : suffix.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("代理路径不能包含 . / .. 路径段");
            }
        }
    }

    /** 构造实际发往上游的 URI。 */
    public static URI target(ReverseProxyRoute route, String suffix, String rawQuery) {
        if (route == null) {
            throw new IllegalArgumentException("路由不能为空");
        }
        URI upstream = URI.create(route.getUpstream());
        String basePath = basePath(upstream);
        String value = suffix == null ? "" : suffix;
        validateSuffix(value);
        if (!value.isEmpty() && !value.startsWith("/")) {
            throw new IllegalArgumentException("代理后缀必须以 / 开头");
        }
        String joined = basePath + (value.startsWith("/") ? value.substring(1) : value);
        if (joined.isEmpty()) {
            joined = "/";
        }
        if (!joined.startsWith("/")) {
            joined = "/" + joined;
        }
        if (hasDotSegment(joined)) {
            throw new IllegalArgumentException("代理路径不能包含 . / .. 路径段");
        }
        String query = rawQuery == null ? "" : rawQuery;
        if (query.indexOf('\r') >= 0 || query.indexOf('\n') >= 0 || query.indexOf('#') >= 0) {
            throw new IllegalArgumentException("查询字符串包含非法字符");
        }
        String target = upstream.getScheme() + "://" + upstream.getRawAuthority()
                + joined + (query.isEmpty() ? "" : "?" + query);
        try {
            return new URI(target);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("上游 URL 不合法", exception);
        }
    }

    /**
     * 把上游同源 URL 映射成代理 URL（含 query / fragment）；不同源或不在上游
     * basePath 下时返回 {@code null}。
     */
    public static String proxyPath(String slug, URI upstream, URI target) {
        if (slug == null || slug.isBlank() || upstream == null || target == null
                || !sameOrigin(upstream, target)) {
            return null;
        }
        String basePath = basePath(upstream);
        String targetPath = target.getRawPath();
        if (targetPath == null || targetPath.isEmpty()) {
            targetPath = "/";
        }
        String suffix;
        if ("/".equals(basePath)) {
            suffix = targetPath.startsWith("/") ? targetPath.substring(1) : targetPath;
        } else {
            String baseWithoutSlash = basePath.substring(0, basePath.length() - 1);
            if (targetPath.equals(baseWithoutSlash)) {
                suffix = "";
            } else if (targetPath.startsWith(basePath)) {
                suffix = targetPath.substring(basePath.length());
            } else {
                return null;
            }
        }
        String path = PROXY_PREFIX + slug + "/" + suffix;
        if (target.getRawQuery() != null) {
            path += "?" + target.getRawQuery();
        }
        if (target.getRawFragment() != null) {
            path += "#" + target.getRawFragment();
        }
        return path;
    }

    /**
     * 改写一个 HTML / CSS 中出现的 URL：相对 URL 先相对当前上游页面解析，再尝试映射到代理路径。
     * 不能映射（外部域名、data:、#anchor…）时返回原值。
     */
    public static String rewriteUrl(String slug, URI upstream, URI pageUri, String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        String value = rawUrl.trim();
        if (value.isEmpty() || value.startsWith("#")) {
            return rawUrl;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:") || lower.startsWith("javascript:") || lower.startsWith("mailto:")
                || lower.startsWith("tel:") || lower.startsWith("blob:") || lower.startsWith("about:")
                || lower.startsWith("file:") || lower.startsWith("ftp:")) {
            return rawUrl;
        }
        try {
            URI resolved = pageUri.resolve(value);
            String mapped = proxyPath(slug, upstream, resolved);
            return mapped == null ? rawUrl : mapped;
        } catch (IllegalArgumentException exception) {
            return rawUrl;
        }
    }

    public static String basePath(URI upstream) {
        String path = upstream.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (!path.endsWith("/")) {
            path += "/";
        }
        return path;
    }

    public static boolean sameOrigin(URI left, URI right) {
        if (left == null || right == null
                || left.getScheme() == null || right.getScheme() == null
                || !left.getScheme().equalsIgnoreCase(right.getScheme())) {
            return false;
        }
        String leftHost = left.getHost();
        String rightHost = right.getHost();
        if (leftHost == null || rightHost == null || !leftHost.equalsIgnoreCase(rightHost)) {
            return false;
        }
        return port(left) == port(right);
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean hasDotSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }
}
