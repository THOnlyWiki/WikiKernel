package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.service.WikiText;
import cc.thonly.wikikernel.wikitext.WikitextRenderer;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Wikitext 渲染桥：插件用 core 的完整渲染器渲染片段（评论内容、预览、待审正文等）。
 */
@Service
public class WikiTextImpl implements WikiText {

    private final WikitextRenderer renderer;

    public WikiTextImpl(WikitextRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public String toHtml(String wikitext, String pageTitle) {
        return renderer.render(wikitext == null ? "" : wikitext, pageTitle == null ? "" : pageTitle);
    }

    @Override
    public Rendered toHtmlWithCategories(String wikitext, String pageTitle) {
        WikitextRenderer.RenderResult result = renderer.renderWithMeta(
                wikitext == null ? "" : wikitext, pageTitle == null ? "" : pageTitle);
        List<String> categories = result.categories() == null ? List.of()
                : result.categories().stream().map(ref -> ref.title()).toList();
        return new Rendered(result.html(), categories);
    }
}
