package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.common.extension.PersonalToolbarContext;
import cc.thonly.wikikernel.common.extension.Viewer;

/**
 * {@link PersonalToolbarContext} 的 core 实现：当前用户 + 站点名 + 站点根地址
 * （供 {@code PersonalToolbarHook} 生成「私信（N）」这类个人工具条目）。
 */
public final class RequestPersonalToolbarContext implements PersonalToolbarContext {

    private final Viewer viewer;
    private final String siteName;
    private final String baseUrl;

    public RequestPersonalToolbarContext(Viewer viewer, String siteName, String baseUrl) {
        this.viewer = viewer == null ? Viewer.ANONYMOUS : viewer;
        this.siteName = siteName == null ? "" : siteName;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
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
