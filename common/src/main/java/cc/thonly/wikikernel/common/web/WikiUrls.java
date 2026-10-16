package cc.thonly.wikikernel.common.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 页面 URL 工具。
 * <p>
 * 标题里的 {@code /} 表示子页面（如 {@code 首页/样式.css}），必须保留为路径分隔符——
 * 不能对整串 {@code URLEncoder} 编码，否则 {@code /} 会变成 {@code %2F}，被 Tomcat 直接拒绝。
 * 这里按 {@code /} 拆段后逐段编码，得到浏览器与服务器都接受的路径。
 * <p>
 * 页面路径前缀与特殊页前缀可配置（{@code wikikernel.urls.page-prefix} / {@code special-prefix}，
 * 见 {@link cc.thonly.wikikernel.config.UrlProperties}）：默认 {@code /wiki/}，留空 = 裸根路径
 * {@code /首页}（由 {@link WikiUrlRewriteFilter} 把裸根请求转发到内部 {@code /wiki/} 路由）。
 */
public final class WikiUrls {

    /** 默认页面路径前缀（MediaWiki 风格的 {@code /wiki/}）。 */
    public static final String DEFAULT_PAGE_PREFIX = "/wiki/";

    /** 默认特殊页前缀。 */
    public static final String DEFAULT_SPECIAL_PREFIX = "特殊";

    private static volatile String pagePrefix = DEFAULT_PAGE_PREFIX;
    private static volatile String specialPrefix = DEFAULT_SPECIAL_PREFIX;

    private WikiUrls() {
    }

    /**
     * 按配置设置页面路径前缀 / 特殊页前缀（启动时由 {@link cc.thonly.wikikernel.config.UrlConfig} 调用）。
     *
     * @param configuredPagePrefix    页面路径前缀：{@code wiki}（默认）/{@code p}（→ {@code /p/}）/
     *                                空串或 {@code /}（裸根）
     * @param configuredSpecialPrefix 特殊页前缀：{@code 特殊}（默认）/{@code Special} 等
     */
    public static void configure(String configuredPagePrefix, String configuredSpecialPrefix) {
        pagePrefix = normalizePagePrefix(configuredPagePrefix);
        specialPrefix = normalizeSpecialPrefix(configuredSpecialPrefix);
    }

    /** 当前页面路径前缀（{@code /wiki/}、{@code /p/} 或裸根的 {@code /}）。 */
    public static String pagePrefix() {
        return pagePrefix;
    }

    /** 当前特殊页前缀（不带冒号）。 */
    public static String specialPrefix() {
        return specialPrefix;
    }

    /** 是否裸根部署（页面直接挂在 {@code /} 下）。 */
    public static boolean bareRoot() {
        return "/".equals(pagePrefix);
    }

    /**
     * 从站内路径里取页面标题部分（去掉页面路径前缀）；不是页面路径时返回 {@code null}。
     * 除当前配置的前缀外，固定路由 {@code /wiki/} 也始终接受（内部路由与旧链接兼容）。
     */
    public static String pagePathRest(String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith(pagePrefix)) {
            return path.substring(pagePrefix.length());
        }
        if (!DEFAULT_PAGE_PREFIX.equals(pagePrefix) && path.startsWith(DEFAULT_PAGE_PREFIX)) {
            return path.substring(DEFAULT_PAGE_PREFIX.length());
        }
        return null;
    }

    /** 页面路径：{@code 首页/样式.css} → {@code /wiki/%E9%A6%96%E9%A1%B5/%E6%A0%B7%E5%BC%8F.css}（前缀可配）。 */
    public static String pagePath(String title) {
        if (title == null || title.isEmpty()) {
            return pagePrefix;
        }
        String normalized = normalizeSpecialTitle(title);
        return pagePrefix + Arrays.stream(normalized.split("/", -1))
                .map(WikiUrls::encodeSegment)
                .collect(Collectors.joining("/"));
    }

    /**
     * 页面名的 URL 编码（空格 → 下划线，保留子页面的 {@code /}）：
     * {@code A B/C D} → {@code A_B/C_D}。用于跨 Wiki 前缀的 {@code $1} 替换等需要「数据库键」形式的地方。
     */
    public static String encodeTitle(String title) {
        if (title == null || title.isEmpty()) {
            return "";
        }
        return Arrays.stream(title.replace(' ', '_').split("/", -1))
                .map(WikiUrls::encodeSegment)
                .collect(Collectors.joining("/"));
    }

    /**
     * 对 Wiki 链接里页面名的编码（同 MediaWiki 的 {@code wfUrlencode}）：
     * 空格 → 下划线，并把 MediaWiki 特意保留的 {@code : ' , $ @} 还原成字面字符，
     * 因此 {@code [[commons:File:Example.svg]]} 得到 {@code https://commons.wikimedia.org/wiki/File:Example.svg}。
     */
    public static String encodeInterwikiTitle(String title) {
        if (title == null || title.isEmpty()) {
            return "";
        }
        return Arrays.stream(title.replace(' ', '_').split("/", -1))
                .map(segment -> encodeSegment(segment)
                        .replace("%3A", ":").replace("%27", "'").replace("%2C", ",")
                        .replace("%24", "$").replace("%40", "@"))
                .collect(Collectors.joining("/"));
    }

    /** 把标题开头的 {@code 特殊:} / {@code Special:} 前缀换成配置的特殊页前缀。 */
    public static String normalizeSpecialTitle(String title) {
        if (title == null) {
            return null;
        }
        if (title.startsWith("特殊:")) {
            return specialPrefix + ":" + title.substring("特殊:".length());
        }
        if (title.startsWith("Special:")) {
            return specialPrefix + ":" + title.substring("Special:".length());
        }
        return title;
    }

    /**
     * 把模板里写死的 {@code /wiki/} 链接（Thymeleaf {@code @{/wiki/...}} 生成的结果）
     * 改成配置的页面路径前缀，并把路径里的特殊页前缀替换成配置值（含 URL 编码形式）。
     * 非站内链接、不含 {@code /wiki/} 的链接原样返回。
     */
    public static String rewriteTemplateLink(String link) {
        if (link == null || link.isEmpty()) {
            return link;
        }
        String result = link;
        if (!DEFAULT_PAGE_PREFIX.equals(pagePrefix)) {
            result = replacePagePrefix(result);
        }
        if (!DEFAULT_SPECIAL_PREFIX.equals(specialPrefix)) {
            result = replaceSpecialPrefix(result);
        }
        return result;
    }

    /** 替换链接里的 {@code /wiki/} 前缀（查询串不动；也处理恰好以 {@code /wiki} 结尾的情况）。 */
    private static String replacePagePrefix(String link) {
        int query = link.indexOf('?');
        String path = query >= 0 ? link.substring(0, query) : link;
        String suffix = query >= 0 ? link.substring(query) : "";
        int index = path.indexOf(DEFAULT_PAGE_PREFIX);
        if (index >= 0) {
            return path.substring(0, index) + pagePrefix + path.substring(index + DEFAULT_PAGE_PREFIX.length()) + suffix;
        }
        if (path.endsWith("/wiki")) {
            String trimmed = pagePrefix.endsWith("/") ? pagePrefix.substring(0, pagePrefix.length() - 1) : pagePrefix;
            return path.substring(0, path.length() - "/wiki".length()) + trimmed + suffix;
        }
        return link;
    }

    /** 替换链接里特殊页名的前缀：{@code /wiki/特殊:%E6%90%9C%E7%B4%A2} → 配置前缀（编码/未编码两种形式都处理）。 */
    private static String replaceSpecialPrefix(String link) {
        String encodedOld = DEFAULT_SPECIAL_PREFIX + "%3A"; // URLEncoder 输出大写
        String encodedOldLower = DEFAULT_SPECIAL_PREFIX + "%3a";
        String encodedNew = urlEncodePrefix(specialPrefix);
        String result = link;
        result = result.replace(DEFAULT_PAGE_PREFIX + DEFAULT_SPECIAL_PREFIX + ":",
                DEFAULT_PAGE_PREFIX + specialPrefix + ":");
        result = result.replace(DEFAULT_PAGE_PREFIX + encodedOld,
                DEFAULT_PAGE_PREFIX + encodedNew + "%3A");
        result = result.replace(DEFAULT_PAGE_PREFIX + encodedOldLower,
                DEFAULT_PAGE_PREFIX + encodedNew + "%3A");
        return result;
    }

    /** 前缀段的百分比编码（用于 URL 里已编码的特殊页前缀替换；非 ASCII 段保持编码一致）。 */
    private static String urlEncodePrefix(String prefix) {
        return URLEncoder.encode(prefix, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 页面路径前缀归一化：null → 默认 wiki；空 / "/" → 裸根 "/"；其它 → "/<值>/"。 */
    private static String normalizePagePrefix(String value) {
        if (value == null) {
            return DEFAULT_PAGE_PREFIX;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "/".equals(trimmed)) {
            return "/";
        }
        String body = trimmed;
        if (body.startsWith("/")) {
            body = body.substring(1);
        }
        if (body.endsWith("/")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isEmpty()) {
            return "/";
        }
        return "/" + body.toLowerCase(Locale.ROOT) + "/";
    }

    /** 特殊页前缀归一化：null / 空白 → 默认 特殊；去掉结尾冒号。 */
    private static String normalizeSpecialPrefix(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_SPECIAL_PREFIX;
        }
        String trimmed = value.trim();
        return trimmed.endsWith(":") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
