package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.extension.PageDisplayContext;
import cc.thonly.wikikernel.common.extension.Viewer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link PageDisplayContext} 的 core 实现：一次词条阅读视图的页面信息（供 {@code PageDisplayHook} 使用）。
 * <p>
 * 页面属性在 wikitext 渲染结束后才齐备，所以这里允许渲染后回填（见 {@link #properties(Map)}）。
 */
public final class WikiPageDisplayContext implements PageDisplayContext {

    private final long pageId;
    private final String title;
    private final String content;
    private final boolean oldRevision;
    private final Map<String, String> query;
    private final Viewer viewer;
    private final String siteName;
    private final String baseUrl;
    private Map<String, String> properties = Map.of();

    public WikiPageDisplayContext(long pageId, String title, String content, boolean oldRevision,
                                  Map<String, String> query, Viewer viewer, String siteName, String baseUrl) {
        this.pageId = pageId;
        this.title = title == null ? "" : title;
        this.content = content == null ? "" : content;
        this.oldRevision = oldRevision;
        this.query = query == null ? Map.of() : Map.copyOf(query);
        this.viewer = viewer == null ? Viewer.ANONYMOUS : viewer;
        this.siteName = siteName == null ? "" : siteName;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
    }

    /** 渲染结束后回填页面属性（模板里写入的属性也在内）。 */
    public void properties(Map<String, String> properties) {
        this.properties = properties == null ? Map.of() : new LinkedHashMap<>(properties);
    }

    @Override
    public long pageId() {
        return pageId;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String content() {
        return content;
    }

    @Override
    public boolean oldRevision() {
        return oldRevision;
    }

    @Override
    public String pageUrl() {
        return WikiUrls.pagePath(title);
    }

    @Override
    public Map<String, String> properties() {
        return Map.copyOf(properties);
    }

    @Override
    public Map<String, String> query() {
        return query;
    }

    @Override
    public Viewer viewer() {
        return viewer;
    }

    @Override
    public String siteName() {
        return siteName;
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }
}
