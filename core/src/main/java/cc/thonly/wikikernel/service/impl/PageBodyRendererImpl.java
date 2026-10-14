package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.extension.PageRenderHook;
import cc.thonly.wikikernel.common.extension.WikiRenderContext;
import cc.thonly.wikikernel.plugin.PluginManager;
import cc.thonly.wikikernel.service.PageBody;
import cc.thonly.wikikernel.service.PageBodyRenderer;
import cc.thonly.wikikernel.wikitext.WikitextRenderer;
import org.springframework.stereotype.Service;

import java.util.Comparator;

/**
 * 页面正文渲染实现：把原先散落在 {@code WikiPageController} 各处的
 * 「createContext → renderWithMeta → PageRenderHook」串成一条管线，
 * 页面查看（缓存未命中）、模板变更预渲染、分类页说明都走这里，行为完全一致。
 * <p>
 * 插件钩子异常照旧向外抛（与迁移前一致）：请求路径由 Spring 统一处理，
 * 预渲染路径由 {@code PageCacheServiceImpl} 捕获后只记日志。
 */
@Service
public class PageBodyRendererImpl implements PageBodyRenderer {

    private final WikitextRenderer wikitextRenderer;
    private final PluginManager pluginManager;

    public PageBodyRendererImpl(WikitextRenderer wikitextRenderer, PluginManager pluginManager) {
        this.wikitextRenderer = wikitextRenderer;
        this.pluginManager = pluginManager;
    }

    @Override
    public PageBody render(String title, String content, long revisionId) {
        WikiRenderContext context = this.wikitextRenderer.createContext(title);
        WikitextRenderer.RenderResult result =
                this.wikitextRenderer.renderWithMeta(content, title, context);
        String html = result.html();
        for (PageRenderHook hook : this.pluginManager.extensions(PageRenderHook.class).stream()
                .sorted(Comparator.comparingInt(PageRenderHook::order)).toList()) {
            html = hook.afterRender(title, html);
        }
        return new PageBody(revisionId, html, result.displayTitle(), result.categories(),
                result.interwikiLinks(), context.properties());
    }
}
