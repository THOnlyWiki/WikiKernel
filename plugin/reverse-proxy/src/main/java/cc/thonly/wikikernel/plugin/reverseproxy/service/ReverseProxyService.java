package cc.thonly.wikikernel.plugin.reverseproxy.service;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.plugin.reverseproxy.model.ReverseProxyRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实际执行反向代理请求。
 *
 * <h2>安全取舍</h2>
 * <ul>
 *   <li>目标 URL 只来自管理员配置的 route，请求路径不能改变目标 host；</li>
 *   <li>默认阻断回环 / 内网 / 链路本地 / 保留地址，避免 SSRF；</li>
 *   <li>不透传 Cookie、Authorization、Origin、Referer、X-Forwarded-*，也不把上游
 *       Set-Cookie 写回浏览器；</li>
 *   <li>不自动跟随上游 3xx，而是把同源 Location 改写后交还给浏览器，防止 SSRF
 *       跳转和开放重定向；</li>
 *   <li>HTML 可加 CSP sandbox，使上游脚本不能以 Wiki 同源身份访问 Wiki 的登录态。</li>
 * </ul>
 */
@Service
public class ReverseProxyService {

    private static final System.Logger LOG = System.getLogger(ReverseProxyService.class.getName());

    /** 单个请求体上限，避免代理被当作上传 / 内存消耗通道。 */
    private static final long MAX_REQUEST_BYTES = 16L * 1024 * 1024;
    /** 为了改写 URL，最多缓存的 HTML / CSS 响应大小；超过则原样流式转发。 */
    private static final int MAX_REWRITE_BYTES = 8 * 1024 * 1024;

    private static final Set<String> REQUEST_SKIP_HEADERS = Set.of(
            "host", "content-length", "connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade",
            "cookie", "cookie2", "set-cookie", "authorization", "origin", "referer",
            "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-port",
            "x-real-ip", "forwarded", "via", "expect", "user-agent",
            "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest", "sec-fetch-user",
            "x-requested-with");

    private static final Set<String> RESPONSE_SKIP_HEADERS = Set.of(
            "content-length", "connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade",
            "set-cookie", "set-cookie2", "location",
            "content-security-policy", "content-security-policy-report-only",
            "x-frame-options", "cross-origin-opener-policy", "cross-origin-embedder-policy",
            "cross-origin-resource-policy", "permissions-policy", "strict-transport-security",
            "content-location", "link", "refresh", "report-to", "nel", "alt-svc");

    private final ReverseProxyConfigService config;
    private final AccessCheck accessCheck;
    private final CurrentUser currentUser;
    private final ReverseProxyHtmlRewriter rewriter;

    private volatile HttpClient httpClient;
    private volatile String httpClientProxy;

    public ReverseProxyService(ReverseProxyConfigService config,
                               AccessCheck accessCheck,
                               CurrentUser currentUser,
                               ReverseProxyHtmlRewriter rewriter) {
        this.config = config;
        this.accessCheck = accessCheck;
        this.currentUser = currentUser;
        this.rewriter = rewriter;
    }

    /**
     * 按当前站点设置构建 / 复用 HttpClient。出站代理支持 HTTP CONNECT 与 SOCKS5；
     * SOCKS5 通过本机回环上的迷你 CONNECT 桥转接（JDK HttpClient 本身不识别 socks5://）。
     */
    private HttpClient client() {
        String configured = config.outboundProxy();
        HttpClient current = httpClient;
        if (current != null && configured.equals(httpClientProxy)) {
            return current;
        }
        synchronized (this) {
            if (httpClient != null && configured.equals(httpClientProxy)) {
                return httpClient;
            }
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER);
            configureProxy(builder, configured);
            httpClient = builder.build();
            httpClientProxy = configured;
            return httpClient;
        }
    }

    private static void configureProxy(HttpClient.Builder builder, String configured) {
        if (configured == null || configured.isBlank()) {
            return;
        }
        String value = configured.trim();
        String scheme = "http";
        String rest = value;
        int schemeEnd = value.indexOf("://");
        if (schemeEnd > 0) {
            scheme = value.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            rest = value.substring(schemeEnd + 3);
        }
        int colon = rest.lastIndexOf(':');
        String host = colon > 0 ? rest.substring(0, colon) : rest;
        int port;
        try {
            port = colon > 0 ? Integer.parseInt(rest.substring(colon + 1)) : 1080;
        } catch (NumberFormatException exception) {
            LOG.log(System.Logger.Level.WARNING,
                    "反向代理出站代理端口不合法，已回退直连: " + configured);
            return;
        }
        if (host.isBlank() || port <= 0 || port > 65535) {
            LOG.log(System.Logger.Level.WARNING,
                    "反向代理出站代理地址不合法，已回退直连: " + configured);
            return;
        }
        if (scheme.startsWith("socks")) {
            try {
                ReverseProxySocks5Bridge bridge = ReverseProxySocks5Bridge.forProxy(
                        host, port, scheme.endsWith("h"));
                builder.proxy(ProxySelector.of(new InetSocketAddress(
                        InetAddress.getLoopbackAddress(), bridge.port())));
            } catch (IOException exception) {
                LOG.log(System.Logger.Level.WARNING,
                        "反向代理 SOCKS5 出站桥创建失败，已回退直连: " + exception.getMessage());
            }
            return;
        }
        if ("http".equals(scheme) || "https".equals(scheme)) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
            return;
        }
        LOG.log(System.Logger.Level.WARNING,
                "反向代理出站代理协议不支持，已回退直连: " + configured);
    }

    public void proxy(String slug, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!config.enabled()) {
            throw new ReverseProxyException(404, "reverseProxy.notFound");
        }
        ReverseProxyRoute route = config.find(slug)
                .orElseThrow(() -> new ReverseProxyException(404, "reverseProxy.notFound"));
        if (!route.isEnabled()) {
            throw new ReverseProxyException(404, "reverseProxy.notFound");
        }
        Viewer viewer = currentUser == null ? Viewer.ANONYMOUS : currentUser.viewer();
        checkAccess(route, viewer);

        String method = request.getMethod() == null
                ? "GET" : request.getMethod().toUpperCase(Locale.ROOT);
        Set<String> methods = methods(route);
        if (!methods.contains(method)) {
            response.setHeader("Allow", String.join(", ", methods));
            throw new ReverseProxyException(405, "reverseProxy.methodNotAllowed");
        }

        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && requestUri.startsWith(contextPath)) {
            requestUri = requestUri.substring(contextPath.length());
        }
        String suffix;
        try {
            suffix = ReverseProxyUrlMapper.suffix(requestUri, slug);
        } catch (IllegalArgumentException exception) {
            throw new ReverseProxyException(400, "reverseProxy.badRequest");
        }
        if (suffix == null) {
            throw new ReverseProxyException(404, "reverseProxy.notFound");
        }

        URI target;
        try {
            target = ReverseProxyUrlMapper.target(route, suffix, request.getQueryString());
        } catch (IllegalArgumentException exception) {
            throw new ReverseProxyException(400, "reverseProxy.badRequest");
        }
        validateTarget(route, target);

        boolean hasBody = hasRequestBody(method);
        long declaredLength = request.getContentLengthLong();
        if (hasBody && declaredLength > MAX_REQUEST_BYTES) {
            throw new ReverseProxyException(413, "reverseProxy.badRequest");
        }

        AtomicBoolean bodyTooLarge = new AtomicBoolean(false);
        HttpRequest.BodyPublisher publisher;
        if (hasBody) {
            publisher = HttpRequest.BodyPublishers.ofInputStream(() -> {
                try {
                    return new LimitedInputStream(request.getInputStream(), MAX_REQUEST_BYTES, bodyTooLarge);
                } catch (IOException exception) {
                    throw new IllegalStateException("读取请求体失败", exception);
                }
            });
        } else {
            publisher = HttpRequest.BodyPublishers.noBody();
        }

        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(route.getTimeoutSeconds()))
                    .method(method, publisher);
        } catch (IllegalArgumentException exception) {
            throw new ReverseProxyException(502, "reverseProxy.upstreamError");
        }
        copyRequestHeaders(route, request, builder);

        HttpResponse<InputStream> upstream;
        try {
            upstream = client().send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ReverseProxyException(502, "reverseProxy.upstreamError");
        } catch (IOException | RuntimeException exception) {
            if (bodyTooLarge.get()) {
                throw new ReverseProxyException(413, "reverseProxy.badRequest");
            }
            LOG.log(System.Logger.Level.WARNING, "反向代理上游请求失败: " + logTarget(target)
                    + " " + exception.getMessage());
            throw new ReverseProxyException(502, "reverseProxy.upstreamError");
        }

        try (InputStream upstreamBody = upstream.body()) {
            writeResponse(route, target, request, response, upstream, upstreamBody, method);
        } catch (IOException exception) {
            if (!response.isCommitted()) {
                LOG.log(System.Logger.Level.WARNING, "反向代理响应写入失败: " + logTarget(target)
                        + " " + exception.getMessage());
                throw new ReverseProxyException(502, "reverseProxy.upstreamError");
            }
            LOG.log(System.Logger.Level.DEBUG, "客户端在代理响应完成前断开: " + target);
        }
    }

    private void checkAccess(ReverseProxyRoute route, Viewer viewer) {
        if (accessCheck != null && viewer != null && accessCheck.blockNotice(viewer).isPresent()) {
            throw new ReverseProxyException(403, "reverseProxy.routeForbidden");
        }
        switch (route.getAccess()) {
            case ReverseProxyRoute.ACCESS_PUBLIC -> {
                return;
            }
            case ReverseProxyRoute.ACCESS_LOGIN -> {
                if (viewer == null || !viewer.loggedIn()) {
                    throw new ReverseProxyException(403, "reverseProxy.routeForbidden");
                }
                return;
            }
            case ReverseProxyRoute.ACCESS_ADMIN -> {
                if (viewer == null || !viewer.loggedIn() || accessCheck == null
                        || !accessCheck.can(AccessAction.PROTECT,
                        ReverseProxyConfigService.SPECIAL_TITLE, viewer)) {
                    throw new ReverseProxyException(403, "reverseProxy.routeForbidden");
                }
            }
            default -> throw new ReverseProxyException(403, "reverseProxy.routeForbidden");
        }
    }

    private static Set<String> methods(ReverseProxyRoute route) {
        Set<String> methods = new HashSet<>();
        for (String part : route.getMethods().split(",")) {
            if (!part.isBlank()) {
                methods.add(part.trim().toUpperCase(Locale.ROOT));
            }
        }
        return methods;
    }

    private static boolean hasRequestBody(String method) {
        return !"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method);
    }

    private void copyRequestHeaders(ReverseProxyRoute route, HttpServletRequest request,
                                    HttpRequest.Builder builder) {
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (REQUEST_SKIP_HEADERS.contains(lower)
                    || lower.startsWith("x-forwarded-")
                    || lower.startsWith("sec-fetch-")
                    || lower.startsWith("proxy-")) {
                continue;
            }
            if (route.isRewriteHtml() && "accept-encoding".equals(lower)) {
                continue;
            }
            for (String value : Collections.list(request.getHeaders(name))) {
                try {
                    builder.header(name, value);
                } catch (IllegalArgumentException ignored) {
                    // JDK HttpClient 对 Host / Connection 等受限头会拒绝；已在上面过滤，这里兜底
                }
            }
        }
        if (route.isRewriteHtml()) {
            builder.header("Accept-Encoding", "identity");
        }
        builder.header("User-Agent", "WikiKernel-ReverseProxy/1.0");
    }

    private void writeResponse(ReverseProxyRoute route, URI target, HttpServletRequest request,
                               HttpServletResponse response, HttpResponse<InputStream> upstream,
                               InputStream upstreamBody, String method) throws IOException {
        int status = upstream.statusCode();
        HttpHeaders headers = upstream.headers();
        String contentType = headers.firstValue("content-type").orElse("");
        boolean identity = headers.firstValue("content-encoding")
                .map(value -> value.equalsIgnoreCase("identity"))
                .orElse(true);
        long declaredLength = headers.firstValueAsLong("content-length").orElse(-1L);
        boolean hasBody = !"HEAD".equals(method) && status != 204 && status != 304;
        boolean ranged = status == 206 || request.getHeader("Range") != null;
        boolean rewrite = hasBody && !ranged && route.isRewriteHtml() && identity
                && isRewritable(contentType)
                && (declaredLength < 0 || declaredLength <= MAX_REWRITE_BYTES);

        byte[] prefix = null;
        boolean rewritten = false;
        if (rewrite) {
            prefix = upstreamBody.readNBytes(MAX_REWRITE_BYTES + 1);
            if (prefix.length <= MAX_REWRITE_BYTES) {
                byte[] replacement = contentType.toLowerCase(Locale.ROOT).startsWith("text/css")
                        ? rewriter.rewriteCss(route.getSlug(), URI.create(route.getUpstream()), target,
                        prefix, contentType)
                        : rewriter.rewriteHtml(route.getSlug(), URI.create(route.getUpstream()), target,
                        prefix, contentType);
                prefix = replacement;
                rewritten = true;
            } else {
                // 超过改写上限：把已读出的前缀和剩余内容原样转发
                rewrite = false;
            }
        }

        response.setStatus(status);
        for (Map.Entry<String, List<String>> entry : headers.map().entrySet()) {
            String name = entry.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (RESPONSE_SKIP_HEADERS.contains(lower)) {
                continue;
            }
            for (String value : entry.getValue()) {
                response.addHeader(name, value);
            }
        }

        String location = headers.firstValue("location").orElse(null);
        if (location != null) {
            String trimmed = location.trim();
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("javascript:") && !lower.startsWith("vbscript:")
                    && !lower.startsWith("data:")) {
                String rewrittenLocation = ReverseProxyUrlMapper.rewriteUrl(route.getSlug(),
                        URI.create(route.getUpstream()), target, location);
                response.setHeader("Location", rewrittenLocation == null ? location : rewrittenLocation);
            }
        }

        if (rewritten) {
            response.setHeader("Content-Length", Integer.toString(prefix.length));
        } else if (declaredLength >= 0 && status != 204 && status != 304) {
            response.setHeader("Content-Length", Long.toString(declaredLength));
        }

        String lowerType = contentType.toLowerCase(Locale.ROOT);
        boolean html = lowerType.startsWith("text/html") || lowerType.startsWith("application/xhtml+xml");
        boolean xmlDocument = lowerType.startsWith("image/svg+xml")
                || lowerType.startsWith("text/xml") || lowerType.startsWith("application/xml");
        if (html || xmlDocument) {
            response.setHeader("X-Frame-Options", "SAMEORIGIN");
            response.setHeader("Content-Security-Policy", route.isSandbox()
                    // 不授予 allow-forms / allow-same-origin：避免上游页面借 Wiki 的登录 Cookie 提交表单。
                    ? "sandbox allow-scripts allow-popups allow-modals allow-downloads; frame-ancestors 'self'"
                    : "frame-ancestors 'self'");
        }
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");

        if (!hasBody) {
            return;
        }
        OutputStream output = response.getOutputStream();
        if (prefix != null) {
            output.write(prefix);
            if (!rewritten) {
                upstreamBody.transferTo(output);
            }
        } else {
            upstreamBody.transferTo(output);
        }
        output.flush();
    }

    private static String logTarget(URI target) {
        return target.getScheme() + "://" + target.getRawAuthority() + target.getRawPath();
    }

    private static boolean isRewritable(String contentType) {
        String lower = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return lower.startsWith("text/html") || lower.startsWith("application/xhtml+xml")
                || lower.startsWith("text/css");
    }

    private void validateTarget(ReverseProxyRoute route, URI target) {
        if (route.isAllowPrivateTargets()) {
            return;
        }
        String host = target.getHost();
        if (host == null || host.isBlank()) {
            throw new ReverseProxyException(502, "reverseProxy.upstreamError");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new ReverseProxyException(502, "reverseProxy.upstreamError");
            }
            for (InetAddress address : addresses) {
                if (!isPublicAddress(address)) {
                    throw new ReverseProxyException(403, "reverseProxy.routeForbidden");
                }
            }
        } catch (UnknownHostException exception) {
            throw new ReverseProxyException(502, "reverseProxy.upstreamError");
        }
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;
            int c = bytes[2] & 0xff;
            if (a == 0 || a == 10 || a == 127
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 192 && b == 0 && c == 0)
                    || (a == 192 && b == 0 && c == 2)
                    || (a == 198 && (b == 18 || b == 19))
                    || (a == 198 && b == 51 && c == 100)
                    || (a == 203 && b == 0 && c == 113)
                    || (a >= 240)) {
                return false;
            }
        } else if (bytes.length == 16) {
            // IPv6 unique-local fc00::/7
            int first = bytes[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {
                return false;
            }
            // IPv4-mapped ::ffff:0:0/96：交给 InetAddress 的 IPv4 分支不适用，保守阻断
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
                return false;
            }
        }
        return true;
    }

    /** 限制请求体大小；超过时设置标记，让上层返回 413。 */
    private static final class LimitedInputStream extends FilterInputStream {

        private final long limit;
        private final AtomicBoolean exceeded;
        private long count;

        private LimitedInputStream(InputStream input, long limit, AtomicBoolean exceeded) {
            super(input);
            this.limit = limit;
            this.exceeded = exceeded;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
                check();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count += read;
                check();
            }
            return read;
        }

        private void check() throws IOException {
            if (count > limit) {
                exceeded.set(true);
                throw new IOException("请求体超过上限");
            }
        }
    }
}
