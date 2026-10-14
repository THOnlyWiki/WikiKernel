package cc.thonly.wikikernel.plugin.categorytree.hook;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeRenderer;

/**
 * 正文里直接书写的 {@code <categorytree>分类名</categorytree>}（对应原版的 {@code ParserFirstCallInit}
 * + {@code Hooks::parserHook}）。
 * <p>
 * {@link #expandBody()} 返回 {@code false}：标签内容（分类名）不参与模板展开，属性才是选项，
 * core 会在正文展开前把整段标签保护起来、渲染结果作为可信 HTML 原样放回。
 */
public final class CategoryTreeTag implements ParserTag {

    /** 标签名（与 MediaWiki 一致，大小写不敏感）。 */
    public static final String TAG_NAME = "categorytree";

    private final CategoryTreeRenderer renderer;

    public CategoryTreeTag(CategoryTreeRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public String name() {
        return TAG_NAME;
    }

    @Override
    public boolean expandBody() {
        return false;
    }

    @Override
    public String render(ParserTagCall call) {
        // 只在真正用到分类树的页面上加载插件的 JS / CSS
        call.context().setProperty(CategoryTreeRenderer.USED_PROPERTY, "1");
        return this.renderer.renderTag(call.attributes(), call.body());
    }
}
