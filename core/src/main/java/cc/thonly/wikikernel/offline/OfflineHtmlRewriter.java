package cc.thonly.wikikernel.offline;

import cc.thonly.wikikernel.common.web.WikiUrls;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 把真实渲染出来的页面 HTML 改写成离线可打开的版本。
 *
 * <p>页面、文件和特殊页链接会映射到导出包的 pages/ 或 search.html；
 * 静态资源通过 {@link OfflineAssetStore} 下载并改写为相对路径。</p>
 */
final class OfflineHtmlRewriter {

    private final OfflineSiteMap site;
    private final OfflineAssetStore assets;
    private final String searchPath;
    private final String specialPrefix;

    OfflineHtmlRewriter(OfflineSiteMap site, OfflineAssetStore assets) {
        this.site = site;
        this.assets = assets;
        this.searchPath = WikiUrls.pagePath("特殊:搜索");
        this.specialPrefix = WikiUrls.specialPrefix();
    }

    void rewrite(Document document, String pageUrl, OfflinePage current, boolean includeSearchIndex) {
        document.select("base").remove();
        URI pageUri = URI.create(pageUrl);

        for (Element link : document.select("a[href]")) {
            String raw = link.attr("href");
            String local = rewriteLink(raw, pageUri, current);
            if (local == null) {
                if (isEditableAction(raw)) {
                    link.attr("href", "#");
                    link.attr("data-offline-disabled", "1");
                }
            } else {
                link.attr("href", local);
                if ("#".equals(local)) {
                    link.attr("data-offline-disabled", "1");
                }
            }
        }

        for (Element element : document.select("link[href]")) {
            String rel = element.attr("rel").toLowerCase(Locale.ROOT);
            // canonical / alternate / search 等描述性链接不是资源，不能当 CSS/JS 下载。
            if (!(rel.contains("stylesheet") || rel.contains("icon") || rel.contains("preload")
                    || rel.contains("manifest") || rel.contains("apple-touch"))) {
                continue;
            }
            String local = rewriteAsset(element.attr("href"), pageUri, current);
            if (local != null) {
                element.attr("href", local);
            }
        }
        for (Element element : document.select("script[src]")) {
            String local = rewriteAsset(element.attr("src"), pageUri, current);
            if (local != null) {
                element.attr("src", local);
            }
        }
        for (Element element : document.select("img[src]")) {
            String local = rewriteAsset(element.attr("src"), pageUri, current);
            if (local != null) {
                element.attr("src", local);
            }
        }
        for (Element element : document.select("img[data-original-src]")) {
            String local = rewriteAsset(element.attr("data-original-src"), pageUri, current);
            if (local != null) {
                element.attr("data-original-src", local);
            }
        }
        for (Element element : document.select("img[srcset],source[srcset]")) {
            element.attr("srcset", rewriteSrcset(element.attr("srcset"), pageUri, current));
        }
        for (Element element : document.select("source[src],audio[src],track[src]")) {
            String local = rewriteAsset(element.attr("src"), pageUri, current);
            if (local != null) {
                element.attr("src", local);
            }
        }
        for (Element element : document.select("video[poster]")) {
            String local = rewriteAsset(element.attr("poster"), pageUri, current);
            if (local != null) {
                element.attr("poster", local);
            }
        }
        for (Element element : document.select("use[href],image[href]")) {
            String local = rewriteAsset(element.attr("href"), pageUri, current);
            if (local != null) {
                element.attr("href", local);
            }
        }
        for (Element element : document.select("[style]")) {
            element.attr("style", assets.rewriteCss(element.attr("style"), pageUri, assetPrefix(current)));
        }
        for (Element style : document.select("style")) {
            style.text(assets.rewriteCss(style.data(), pageUri, assetPrefix(current)));
        }
        for (Element form : document.select("form[action]")) {
            String action = form.attr("action");
            if (isSearchPath(action)) {
                form.attr("action", rootRef(current, "search.html"));
                form.attr("method", "get");
            } else if (!isPureHash(action)) {
                form.attr("action", "#");
                form.attr("data-offline-disabled", "1");
            }
        }
        for (Element form : document.select("form:not([action])")) {
            form.attr("data-offline-disabled", "1");
        }
        if (document.body() != null) {
            document.body().addClass("wiki-offline");
        }

        Element head = document.head();
        String prefix = assetPrefix(current);
        head.appendElement("link")
                .attr("rel", "stylesheet")
                .attr("href", prefix + "offline.css");
        if (includeSearchIndex) {
            head.appendElement("script")
                    .attr("defer", "")
                    .attr("src", prefix + "search-index.js");
        }
        head.appendElement("script")
                .attr("defer", "")
                .attr("src", prefix + "offline.js");
    }

    /** 页面的可搜索文本（用于离线搜索索引）。 */
    String searchableText(Document document, int maxLength) {
        Element content = document.selectFirst(".mw-parser-output");
        if (content == null) {
            content = document.body();
        }
        if (content == null) {
            return "";
        }
        String text = content.text().replaceAll("\\s+", " ").trim();
        if (text.length() > maxLength) {
            text = text.substring(0, maxLength);
        }
        return text;
    }

    private String rewriteLink(String raw, URI pageUri, OfflinePage current) {
        if (raw == null || raw.isBlank() || raw.startsWith("#")
                || raw.startsWith("javascript:") || raw.startsWith("mailto:")) {
            return null;
        }
        URI uri = resolve(raw, pageUri);
        if (uri == null) {
            return null;
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String fragment = uri.getRawFragment();
        String suffix = fragment == null || fragment.isBlank() ? "" : "#" + fragment;

        if (isSearchPath(path)) {
            String query = searchQuery(uri.getRawQuery());
            return rootRef(current, "search.html") + query + suffix;
        }
        if ("/".equals(path) || path.isEmpty()) {
            return rootRef(current, "index.html") + suffix;
        }
        if (isAssetPath(path)) {
            String local = rewriteAsset(raw, pageUri, current);
            return local == null ? null : local + suffix;
        }
        if (path.startsWith("/api") || path.startsWith("/auth/")) {
            return "#";
        }
        Optional<OfflinePage> target = site.pageForUri(uri);
        if (target.isPresent()) {
            return (current == null ? "pages/" + target.get().slug()
                    : target.get().linkFrom(current)) + suffix;
        }
        if (path.startsWith(WikiUrls.DEFAULT_PAGE_PREFIX) || WikiUrls.pagePathRest(path) != null) {
            return rootRef(current, "offline.html") + suffix;
        }
        if (path.startsWith("/" + specialPrefix + ":") || path.startsWith("/Special:")) {
            return rootRef(current, "offline.html") + suffix;
        }
        return null;
    }

    private String rewriteAsset(String raw, URI pageUri, OfflinePage current) {
        if (raw == null || raw.isBlank() || raw.startsWith("#")
                || raw.startsWith("data:") || raw.startsWith("javascript:")) {
            return null;
        }
        String local = assets.asset(raw, pageUri.toString());
        return local == null ? null : assetPrefix(current) + local;
    }

    private String rewriteSrcset(String srcset, URI pageUri, OfflinePage current) {
        if (srcset == null || srcset.isBlank()) {
            return srcset;
        }
        StringBuilder out = new StringBuilder();
        for (String item : srcset.split(",")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 2);
            String local = rewriteAsset(parts[0], pageUri, current);
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(local == null ? parts[0] : local);
            if (parts.length > 1) {
                out.append(' ').append(parts[1]);
            }
        }
        return out.toString();
    }

    private String assetPrefix(OfflinePage current) {
        return current == null ? "assets/" : "../assets/";
    }

    private static String rootRef(OfflinePage current, String name) {
        return current == null ? name : "../" + name;
    }

    private static String searchQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        String search = null;
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq);
            if ("search".equals(key) || "q".equals(key)) {
                try {
                    search = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ignored) {
                    search = part.substring(eq + 1);
                }
            }
        }
        return search == null || search.isBlank()
                ? "" : "?search=" + URLEncoder.encode(search, StandardCharsets.UTF_8);
    }

    private static URI resolve(String raw, URI pageUri) {
        try {
            URI uri = pageUri.resolve(raw.trim());
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            String lower = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(lower) && !"https".equals(lower)) {
                return null;
            }
            String host = uri.getHost();
            String pageHost = pageUri.getHost();
            if (host != null && pageHost != null && !host.equalsIgnoreCase(pageHost)
                    && !("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean isSearchPath(String path) {
        if (path == null) {
            return false;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            decoded = path;
        }
        String searchTitle = "/" + specialPrefix + ":搜索";
        return decoded.equals(searchPath)
                || decoded.equals(searchTitle)
                || decoded.equals("/Special:Search")
                || decoded.endsWith("/" + specialPrefix + ":搜索")
                || decoded.endsWith("/Special:Search")
                || decoded.contains("/wiki/" + specialPrefix + ":搜索")
                || decoded.contains("/wiki/Special:Search");
    }

    private static boolean isAssetPath(String path) {
        return path.startsWith("/files/") || path.startsWith("/skins/")
                || path.startsWith("/plugins/") || path.startsWith("/vendor/")
                || path.startsWith("/css/") || path.startsWith("/js/")
                || path.startsWith("/img/") || path.equals("/favicon.ico")
                || path.startsWith("/load.php") || path.startsWith("/thumb.php");
    }

    private static boolean isEditableAction(String raw) {
        if (raw == null) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("action=edit") || lower.contains("action=history")
                || lower.contains("action=diff") || lower.contains("action=delete")
                || lower.contains("action=protect") || lower.contains("action=move");
    }

    private static boolean isPureHash(String raw) {
        return raw == null || raw.isBlank() || raw.startsWith("#");
    }
}
