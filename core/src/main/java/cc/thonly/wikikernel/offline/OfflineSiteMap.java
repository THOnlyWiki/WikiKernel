package cc.thonly.wikikernel.offline;

import cc.thonly.wikikernel.common.web.WikiUrls;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 导出包的页面路由表：HTML 里的站内链接通过它映射到 pages/&lt;slug&gt;.html。
 */
final class OfflineSiteMap {

    private final Map<String, OfflinePage> byTitle = new LinkedHashMap<>();
    private final Map<String, OfflinePage> byPath = new LinkedHashMap<>();
    private final String contextPath;
    private OfflinePage home;

    OfflineSiteMap(String contextPath) {
        this.contextPath = contextPath == null ? "" : contextPath;
    }

    void add(OfflinePage page) {
        byTitle.put(page.title(), page);
        addPath(page.routePath(), page);
        addPath(stripContext(page.routePath()), page);
        // 兼容旧链接 /wiki/... 和标题解码形式（当前站点可能已改成裸根路径）。
        String legacyPath = WikiUrls.DEFAULT_PAGE_PREFIX + WikiUrls.encodeTitle(page.title());
        addPath(legacyPath, page);
        addPath(stripContext(legacyPath), page);
        try {
            String decoded = URLDecoder.decode(stripContext(page.routePath()), StandardCharsets.UTF_8);
            addPath(decoded, page);
            addPath(URLDecoder.decode(stripContext(legacyPath), StandardCharsets.UTF_8), page);
        } catch (IllegalArgumentException ignored) {
            // 非法百分号编码时只保留原始路径
        }
        if (page.title().equals("首页")) {
            home = page;
        }
    }

    void home(OfflinePage home) {
        this.home = home;
    }

    OfflinePage home() {
        return home;
    }


    Optional<OfflinePage> pageForUri(URI uri) {
        if (uri == null) {
            return Optional.empty();
        }
        String path = uri.getPath();
        OfflinePage page = byPath.get(normalize(path));
        if (page != null) {
            return Optional.of(page);
        }
        try {
            String decoded = URLDecoder.decode(path == null ? "" : path, StandardCharsets.UTF_8);
            page = byPath.get(normalize(decoded));
            if (page != null) {
                return Optional.of(page);
            }
        } catch (IllegalArgumentException ignored) {
            // 继续尝试 WikiUrls
        }
        String clean = stripContext(path == null ? "" : path);
        if (clean.startsWith(WikiUrls.DEFAULT_PAGE_PREFIX)) {
            String legacyTitle = clean.substring(WikiUrls.DEFAULT_PAGE_PREFIX.length());
            try {
                legacyTitle = URLDecoder.decode(legacyTitle, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // 保留原始值
            }
            page = byTitle.get(legacyTitle);
            if (page != null) {
                return Optional.of(page);
            }
        }
        String titlePart = WikiUrls.pagePathRest(clean);
        if (titlePart != null) {
            try {
                titlePart = URLDecoder.decode(titlePart, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // 保留原值
            }
            return Optional.ofNullable(byTitle.get(titlePart));
        }
        return Optional.empty();
    }

    private void addPath(String path, OfflinePage page) {
        if (path == null || path.isEmpty()) {
            return;
        }
        byPath.putIfAbsent(normalize(path), page);
        if (path.endsWith("/")) {
            byPath.putIfAbsent(normalize(path.substring(0, path.length() - 1)), page);
        }
    }

    private String stripContext(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (!contextPath.isEmpty() && path.startsWith(contextPath)) {
            String stripped = path.substring(contextPath.length());
            return stripped.isEmpty() ? "/" : stripped;
        }
        return path;
    }

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        String result = path;
        if (!result.startsWith("/")) {
            result = "/" + result;
        }
        return result;
    }
}
