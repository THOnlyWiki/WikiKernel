package cc.thonly.wikikernel.plugin.categorytree.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.plugin.categorytree.CategoryTreeMessages;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeRenderer;
import java.util.List;

/**
 * 只在页面真的用到分类树时才插入插件的前端资源
 * （对应原版 {@code CategoryTree::setHeaders} 的 {@code addModuleStyles} / {@code addModules}）。
 * <p>
 * {@link CategoryTreeTag} 渲染时会把页面属性 {@code categorytree-used=1} 写进渲染上下文，
 * core 渲染完正文后用同一份属性回调这里；属性会随页面属性表持久化，所以命中解析缓存的页面同样能拿到资源。
 */
public final class CategoryTreeHeadProvider implements HtmlHeadProvider {

    /** 样式表地址（插件 Controller 从 jar 里的 {@code static/plugins/category-tree/} 提供）。 */
    public static final String STYLE_URL = "/plugins/category-tree/category-tree.css";

    /** 脚本地址。 */
    public static final String SCRIPT_URL = "/plugins/category-tree/ext.categoryTree.js";

    /** 前端文案（原版走 ResourceLoader 的 messages，这里直接内联一个 JSON 对象）。 */
    public static final String MESSAGES_GLOBAL = "window.CategoryTreeMessages";

    private final CategoryTreeMessages messages;

    public CategoryTreeHeadProvider(CategoryTreeMessages messages) {
        this.messages = messages == null ? CategoryTreeMessages.system() : messages;
    }

    @Override
    public int order() {
        // 放在其它 head 扩展之后即可（越小越先执行）
        return 200;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (context == null || !"1".equals(context.properties().get(CategoryTreeRenderer.USED_PROPERTY))) {
            return List.of();
        }
        return List.of(
                "<script>" + MESSAGES_GLOBAL + " = " + this.messages.json() + ";</script>",
                "<link rel=\"stylesheet\" href=\"" + STYLE_URL + "\">",
                "<script defer src=\"" + SCRIPT_URL + "\"></script>");
    }
}
