package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.web.WikiUrls;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * 页面路径前缀可配的接收侧：把「配置的页面路径」请求转发到内部固定的 {@code /wiki/} 路由。
 * <ul>
 *     <li>自定义前缀（{@code page-prefix: p}）：{@code /p/首页} → {@code /wiki/首页}；</li>
 *     <li>裸根部署（{@code page-prefix: ""}）：{@code /首页} → {@code /wiki/首页}，
 *     {@code /api}、{@code /css}、{@code /files} 等保留路径不受影响（见 {@link #RESERVED_SEGMENTS}）。</li>
 * </ul>
 * 链路里不重复标记（{@link OncePerRequestFilter}）；改写只动 URI，查询串 / 方法 / 请求体不变。
 * 生成链接一侧由 {@link WikiUrls#pagePath} 与 {@code WikiLinkSupport} 负责，两边一致。
 */
@Component
public class WikiUrlRewriteFilter extends OncePerRequestFilter {

    /**
     * 顶层保留路径：裸根部署时这些路径不当页面处理（与控制器 / 静态资源顶层目录保持一致）。
     * <p>
     * {@code email}（{@code /email/confirm?token=…}）与 {@code password}（{@code /password/reset…}）
     * 是 core 的顶层路由（邮箱确认 / 重置密码），漏掉会让裸根部署下的确认链接被当成页面标题（404）。
     * {@code init} / {@code version} / {@code render} 同理（安装向导 / 版本端点 / 渲染端点）。
     */
    private static final Set<String> RESERVED_SEGMENTS = Set.of(
            "api", "api.php", "wiki", "css", "js", "img", "vendor", "schemas",
            "skins", "plugins", "files", "avatars", "error",
            "email", "password", "init", "version", "render",
            "favicon.ico", "robots.txt", "index.html",
            // thonly-wiki-extensions 插件的独立页（活动表格编辑器 / 活动日历 / 日历下载）由插件 Controller 处理，
            // 不能被裸根改写当成 wiki 页面标题
            "excel", "calendar");

    /** 这些扩展名的请求不当页面（裸根时不把不存在的静态文件渲染成「页面不存在」）。 */
    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            "ico", "png", "jpg", "jpeg", "gif", "webp", "svg", "css", "js", "map", "txt", "xml", "json",
            "woff", "woff2", "ttf", "otf", "eot");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = contextPath.isEmpty() ? uri : uri.substring(contextPath.length());
        String rewritten = rewrite(path);
        if (rewritten == null || rewritten.equals(path)) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(new RewrittenUriRequest(request, contextPath + rewritten), response);
    }

    /** 需要改写时返回新的站内路径（含前导 /；不含 context path），否则返回 null。 */
    static String rewrite(String path) {
        if (path == null || !path.startsWith("/") || "/".equals(path)) {
            return null;
        }
        String prefix = WikiUrls.pagePrefix();
        if (WikiUrls.DEFAULT_PAGE_PREFIX.equals(prefix)) {
            return null; // 默认 /wiki/：路由原生支持，不改写
        }
        if (WikiUrls.bareRoot()) {
            String first = firstSegment(path);
            String decoded = percentDecode(first);
            if (first.isEmpty()
                    || RESERVED_SEGMENTS.contains(decoded.toLowerCase(Locale.ROOT))
                    // 带命名空间冒号的一定是页面标题（静态文件不会有冒号）：
                    // 例如 Gadget:ReadingProgress.css / 文件:X.png —— 不能按「静态文件」跳过，
                    // 否则 ?action=raw 之类的请求 404（小工具资源、文件原图在裸根下都会失效）。
                    // 浏览器把冒号发成 %3A（ListFiles 等页面生成的链接就是），所以要先解码再判断。
                    || (decoded.indexOf(':') < 0 && hasStaticExtension(decoded))) {
                return null;
            }
            return "/wiki" + path;
        }
        // 自定义前缀（prefix 形如 /p/）
        String withoutSlash = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        if (path.equals(withoutSlash) || path.equals(prefix)) {
            // 前缀根（如 /p 或 /p/）→ 站点根，由首页重定向处理
            return "/";
        }
        if (path.startsWith(prefix)) {
            return "/wiki/" + path.substring(prefix.length());
        }
        return null;
    }

    private static String firstSegment(String path) {
        int end = path.indexOf('/', 1);
        return end < 0 ? path.substring(1) : path.substring(1, end);
    }

    private static boolean hasStaticExtension(String segment) {
        int dot = segment.lastIndexOf('.');
        return dot >= 0 && STATIC_EXTENSIONS.contains(segment.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * 只解码 {@code %XX}（路径里的 {@code +} 是字面加号，不能像表单那样变空格，所以不用 URLDecoder#decode）。
     * 解码失败（残缺的 %）时原样返回。
     */
    static String percentDecode(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '%' && i + 2 < value.length()
                    && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                bytes.reset();
                int j = i;
                while (j + 2 < value.length() && value.charAt(j) == '%'
                        && isHex(value.charAt(j + 1)) && isHex(value.charAt(j + 2))) {
                    bytes.write(Integer.parseInt(value.substring(j + 1, j + 3), 16));
                    j += 3;
                }
                out.append(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                i = j - 1;
                continue;
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static boolean isHex(char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
    }

    /** 只替换 URI / servletPath 的请求包装（默认 servlet 映射下 servletPath = 完整路径）。 */
    static final class RewrittenUriRequest extends HttpServletRequestWrapper {

        private final String uri;

        RewrittenUriRequest(HttpServletRequest request, String uri) {
            super(request);
            this.uri = uri;
        }

        @Override
        public String getRequestURI() {
            return uri;
        }

        @Override
        public String getServletPath() {
            return uri;
        }

        @Override
        public String getPathInfo() {
            return null;
        }
    }
}
