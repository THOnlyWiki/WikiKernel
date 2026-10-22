package cc.thonly.wikikernel.plugin.reverseproxy.service;

import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.plugin.reverseproxy.model.ReverseProxyConfig;
import cc.thonly.wikikernel.plugin.reverseproxy.model.ReverseProxyRoute;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 反向代理路由的持久化与校验。
 *
 * <p>配置存放在站点设置键 {@value #ROUTES_KEY}（JSON）。这样：
 * 不需要给插件加数据库表，也不会把路由写进 LocalSettings.yaml；
 * 站点设置本身和其它插件 / core 使用同一个存储，备份和恢复行为一致。</p>
 *
 * <p>所有写入都走 {@link #saveRoutes(List)}，它会在落盘前统一规范化并拒绝
 * 重复 slug、非 http(s) 上游、用户信息、URL 片段等危险或无法代理的配置。</p>
 */
@Service
public class ReverseProxyConfigService {

    public static final String ROUTES_KEY = "reverse-proxy.routes";
    public static final String OUTBOUND_PROXY_KEY = "reverse-proxy.outbound-proxy";
    public static final String FEATURE_KEY = "features.reverse-proxy";
    public static final String SPECIAL_TITLE = "特殊:反向代理";

    private static final Pattern SLUG_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Set<String> ACCESS_VALUES = Set.of(
            ReverseProxyRoute.ACCESS_PUBLIC,
            ReverseProxyRoute.ACCESS_LOGIN,
            ReverseProxyRoute.ACCESS_ADMIN);
    private static final Set<String> ALLOWED_METHODS = Set.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final System.Logger LOG = System.getLogger(ReverseProxyConfigService.class.getName());

    private final SiteConfig siteConfig;
    private final ObjectMapper objectMapper;

    /** 原始 JSON 变化 / 超过 TTL 时重新解析，避免每个静态资源请求都读库。 */
    private volatile String cachedRaw;
    private volatile long cachedAt;
    private volatile List<ReverseProxyRoute> cachedRoutes = List.of();

    public ReverseProxyConfigService(SiteConfig siteConfig, ObjectMapper objectMapper) {
        this.siteConfig = siteConfig;
        this.objectMapper = objectMapper;
    }

    /** 插件总开关（站点设置 → 插件设置）。 */
    public boolean enabled() {
        return siteConfig.getBoolean(FEATURE_KEY, true);
    }

    /** 出站代理；空 = 直连。支持 http(s):// 与 socks5(h)://。 */
    public String outboundProxy() {
        String value = siteConfig.get(OUTBOUND_PROXY_KEY, "");
        return value == null ? "" : value.trim();
    }

    /** 当前所有路由（不可变副本）。 */
    public synchronized List<ReverseProxyRoute> routes() {
        String raw = siteConfig.get(ROUTES_KEY, "");
        if (raw == null) {
            raw = "";
        }
        long now = System.currentTimeMillis();
        if (cachedRaw != null && cachedRaw.equals(raw) && now - cachedAt < 5_000) {
            return cachedRoutes;
        }
        cachedRaw = raw;
        cachedAt = now;
        if (raw.isBlank()) {
            cachedRoutes = List.of();
            return cachedRoutes;
        }
        try {
            List<ReverseProxyRoute> parsed;
            try {
                ReverseProxyConfig config = objectMapper.readValue(raw, ReverseProxyConfig.class);
                parsed = config == null || config.getRoutes() == null ? List.of() : config.getRoutes();
            } catch (RuntimeException wrapperFailure) {
                // 兼容手工写入 / 旧数据：也接受直接的 JSON 数组
                ReverseProxyRoute[] values = objectMapper.readValue(raw, ReverseProxyRoute[].class);
                parsed = values == null ? List.of() : List.of(values);
            }
            List<ReverseProxyRoute> normalized = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (ReverseProxyRoute route : parsed) {
                try {
                    ReverseProxyRoute value = normalize(route);
                    if (seen.add(value.getSlug())) {
                        normalized.add(value);
                    }
                } catch (IllegalArgumentException exception) {
                    LOG.log(System.Logger.Level.WARNING,
                            "忽略非法反向代理路由: " + exception.getMessage());
                }
            }
            cachedRoutes = List.copyOf(normalized);
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.ERROR, "反向代理路由 JSON 解析失败，按空配置处理", exception);
            cachedRoutes = List.of();
        }
        return cachedRoutes;
    }

    public Optional<ReverseProxyRoute> find(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return routes().stream()
                .filter(route -> route.getSlug().equals(slug.trim()))
                .findFirst();
    }

    /**
     * 新增或更新路由。
     *
     * @param candidate    新配置
     * @param originalSlug 编辑前的 slug；新增时为空
     */
    public synchronized ReverseProxyRoute upsert(ReverseProxyRoute candidate, String originalSlug) {
        ReverseProxyRoute normalized = normalize(candidate);
        String oldSlug = originalSlug == null ? "" : originalSlug.trim();
        LinkedHashMap<String, ReverseProxyRoute> bySlug = new LinkedHashMap<>();
        for (ReverseProxyRoute route : routes()) {
            bySlug.put(route.getSlug(), route);
        }
        if (!oldSlug.isEmpty() && !oldSlug.equals(normalized.getSlug())) {
            bySlug.remove(oldSlug);
        }
        ReverseProxyRoute existing = bySlug.get(normalized.getSlug());
        if (existing != null && !normalized.getSlug().equals(oldSlug)) {
            throw new IllegalArgumentException("路由名已被占用: " + normalized.getSlug());
        }
        bySlug.put(normalized.getSlug(), normalized);
        saveRoutes(new ArrayList<>(bySlug.values()));
        return normalized;
    }

    public synchronized boolean delete(String slug) {
        if (slug == null || slug.isBlank()) {
            return false;
        }
        LinkedHashMap<String, ReverseProxyRoute> bySlug = new LinkedHashMap<>();
        for (ReverseProxyRoute route : routes()) {
            bySlug.put(route.getSlug(), route);
        }
        if (bySlug.remove(slug.trim()) == null) {
            return false;
        }
        saveRoutes(new ArrayList<>(bySlug.values()));
        return true;
    }

    /** 统一校验后写入站点设置。 */
    public synchronized void saveRoutes(List<ReverseProxyRoute> routes) {
        Map<String, ReverseProxyRoute> bySlug = new LinkedHashMap<>();
        for (ReverseProxyRoute route : routes == null ? List.<ReverseProxyRoute>of() : routes) {
            ReverseProxyRoute normalized = normalize(route);
            if (bySlug.putIfAbsent(normalized.getSlug(), normalized) != null) {
                throw new IllegalArgumentException("路由名重复: " + normalized.getSlug());
            }
        }
        ReverseProxyConfig config = new ReverseProxyConfig(new ArrayList<>(bySlug.values()));
        String json;
        try {
            json = objectMapper.writeValueAsString(config);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("反向代理配置序列化失败", exception);
        }
        siteConfig.set(ROUTES_KEY, json);
        // 立即刷新缓存，管理页保存后下一请求就生效
        cachedRaw = json;
        cachedAt = System.currentTimeMillis();
        cachedRoutes = List.copyOf(bySlug.values());
    }

    /**
     * 规范化并校验单条路由。此方法也供测试与 HTML 重写器使用。
     */
    public static ReverseProxyRoute normalize(ReverseProxyRoute route) {
        if (route == null) {
            throw new IllegalArgumentException("路由不能为空");
        }
        String slug = trim(route.getSlug());
        if (!SLUG_PATTERN.matcher(slug).matches()) {
            throw new IllegalArgumentException("路由名只能包含字母、数字、下划线和连字符，长度 1-40");
        }
        String upstream = normalizeUpstream(route.getUpstream());
        String access = trim(route.getAccess()).toLowerCase(Locale.ROOT);
        if (access.isEmpty()) {
            access = ReverseProxyRoute.ACCESS_ADMIN;
        }
        if (!ACCESS_VALUES.contains(access)) {
            throw new IllegalArgumentException("访问级别必须是 public / login / admin");
        }
        String methods = normalizeMethods(route.getMethods());
        int timeout = route.getTimeoutSeconds();
        if (timeout <= 0) {
            timeout = ReverseProxyRoute.DEFAULT_TIMEOUT_SECONDS;
        }
        timeout = Math.min(timeout, ReverseProxyRoute.MAX_TIMEOUT_SECONDS);
        String label = trim(route.getLabel());
        if (label.isEmpty()) {
            label = slug;
        }
        if (label.length() > 80) {
            label = label.substring(0, 80);
        }
        return new ReverseProxyRoute(slug, upstream, access, route.isEnabled(), route.isRewriteHtml(),
                route.isSandbox(), methods, timeout, label, route.isAllowPrivateTargets());
    }

    /** 把上游地址规范成 {@code scheme://authority[/path]}（去掉尾斜杠）。 */
    public static String normalizeUpstream(String raw) {
        String value = trim(raw);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("上游地址不能为空");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("上游地址格式不正确");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("上游地址只支持 http / https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("上游地址缺少有效主机名");
        }
        if (uri.getRawUserInfo() != null || uri.getRawAuthority() != null
                && uri.getRawAuthority().contains("@")) {
            throw new IllegalArgumentException("上游地址不能包含用户名 / 密码");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("上游地址不能包含 # 片段");
        }
        if (uri.getRawQuery() != null) {
            throw new IllegalArgumentException("上游地址不能包含查询参数，请把参数放到代理路径里");
        }
        if (uri.getPort() == 0) {
            throw new IllegalArgumentException("上游端口不合法");
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("上游地址路径必须以 / 开头");
        }
        if (hasDotSegment(path)) {
            throw new IllegalArgumentException("上游地址不能包含 . / .. 路径段");
        }
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.contains("%2e") || lowerPath.contains("%2f") || lowerPath.contains("%5c")) {
            throw new IllegalArgumentException("上游地址路径不能包含编码过的点 / 斜杠");
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.length() > 1 && path.contains("..")) {
            throw new IllegalArgumentException("上游地址路径不合法");
        }
        String authority = uri.getRawAuthority();
        return scheme + "://" + authority + ("/".equals(path) ? "" : path);
    }

    /** 方法列表：大写、去空白、去重；空值 = GET,HEAD。 */
    public static String normalizeMethods(String raw) {
        String value = trim(raw);
        if (value.isEmpty()) {
            value = ReverseProxyRoute.DEFAULT_METHODS;
        }
        LinkedHashSet<String> methods = new LinkedHashSet<>();
        for (String part : value.split("[,\\s]+")) {
            String method = part.trim().toUpperCase(Locale.ROOT);
            if (method.isEmpty()) {
                continue;
            }
            if (!ALLOWED_METHODS.contains(method)) {
                throw new IllegalArgumentException("不支持的 HTTP 方法: " + method);
            }
            methods.add(method);
        }
        if (methods.isEmpty()) {
            throw new IllegalArgumentException("至少允许一个 HTTP 方法");
        }
        return String.join(",", methods);
    }

    private static boolean hasDotSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
