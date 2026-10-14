package cc.thonly.wikikernel.plugin.categorytree.hook;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeRenderer;

/**
 * 展开后才会出现的 {@code <categorytree>} 标签（模板 / 解析器函数生成的）。
 * <p>
 * core 对 {@code expandBody = true} 的标签会在正文展开之后提取并作为可信 HTML 块放回，
 * 因此 {@code {{#categorytree:…}}} 与写在模板里的 {@code <categorytree>} 都靠这个实现；
 * 正文里直接书写的标签由 {@link CategoryTreeTag} 在展开前处理（分类名不参与展开）。
 */
public final class CategoryTreeNestedTag implements ParserTag {

    private final CategoryTreeRenderer renderer;

    public CategoryTreeNestedTag(CategoryTreeRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return CategoryTreeTag.TAG_NAME;
    }

    @Override
    public boolean expandBody() {
        return true;
    }

    @Override
    public String render(ParserTagCall call) {
        call.context().setProperty(CategoryTreeRenderer.USED_PROPERTY, "1");
        return this.renderer.renderTag(call.attributes(), call.body());
    }
}
