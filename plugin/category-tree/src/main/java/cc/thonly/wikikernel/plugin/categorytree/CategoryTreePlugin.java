package cc.thonly.wikikernel.plugin.categorytree;

import cc.thonly.wikikernel.common.extension.ActionApiModule;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.extension.MessageBundle;
import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.SetupStep;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.CategoryStore;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.categorytree.hook.CategoryTreeApiModule;
import cc.thonly.wikikernel.plugin.categorytree.hook.CategoryTreeFunction;
import cc.thonly.wikikernel.plugin.categorytree.hook.CategoryTreeHeadProvider;
import cc.thonly.wikikernel.plugin.categorytree.hook.CategoryTreeNestedTag;
import cc.thonly.wikikernel.plugin.categorytree.hook.CategoryTreeTag;
import cc.thonly.wikikernel.plugin.categorytree.service.CategoryTreeRenderer;
import cc.thonly.wikikernel.plugin.categorytree.web.CategoryTreeSpecialPage;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * CategoryTree 扩展移植（对应 MediaWiki 的 {@code CategoryTree} 扩展）：
 * <ul>
 *     <li>{@code <categorytree>分类</categorytree>} 标签与 {@code {{#categorytree:分类|…}}} 解析器函数；</li>
 *     <li>「特殊:分类树」页面（对应 {@code Special:CategoryTree}）；</li>
 *     <li>{@code action=categorytree} API（对应 {@code ApiCategoryTree}）；</li>
 *     <li>按需加载的前端 JS / CSS（对应 {@code CategoryTree::setHeaders} + {@code ext.categoryTree} 模块）；</li>
 *     <li>「分类树」全部选项：{@code mode} / {@code hideprefix} / {@code showcount} / {@code namespaces} /
 *     {@code hideroot} / {@code depth} / {@code class} / {@code style} / {@code id} / {@code notranslations}。</li>
 * </ul>
 * 未移植的部分（侧栏 portlet、分类页树按钮、页面底部分类链接劫持、SpecialTrackingCategories、
 * 解析缓存与 Translate 的 notranslations）见插件 {@code README.md}。
 */
public class CategoryTreePlugin implements WikiPlugin {

    @Autowired
    private CategoryStore categoryStore;

    @Autowired
    private WikiPageStore wikiPageStore;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("category-tree", "CategoryTree", "1.0.0",
                "Category tree tag and parser function, AJAX expansion, the action=categorytree API "
                        + "and Special:CategoryTree");
    }

    @Override
    public void onStart(PluginContext context) {
        CategoryTreeConfig config = CategoryTreeConfig.from(context.config());
        CategoryTreeMessages messages = CategoryTreeMessages.system();
        CategoryTreeRenderer renderer = new CategoryTreeRenderer(categoryStore, wikiPageStore, config, messages);

        // 消息包（i18n/messages*.properties）注册给 core：plugin.category-tree.* / categorytree* / setup.* 都能查
        context.register(MessageBundle.class, messages);
        if (config.allowTag()) {
            // 正文里直接书写的标签（分类名不参与模板展开）
            context.register(ParserTag.class, new CategoryTreeTag(renderer));
            // 模板 / 解析器函数生成的标签（展开后提取为可信 HTML）
            context.register(ParserTag.class, new CategoryTreeNestedTag(renderer));
            context.register(ParserFunction.class, new CategoryTreeFunction(renderer));
        }
        context.register(SpecialPage.class, new CategoryTreeSpecialPage(renderer, config, messages));
        context.register(ActionApiModule.class, new CategoryTreeApiModule(renderer, config));
        context.register(HtmlHeadProvider.class, new CategoryTreeHeadProvider(messages));
        context.register(SetupStep.class, new CategoryTreeSetup(config, messages));

        context.logger().log(System.Logger.Level.INFO, config.allowTag()
                ? "已启用分类树：<categorytree> / {{#categorytree}}、action=categorytree 与「特殊:分类树」"
                : "已关闭解析入口（allow-tag=false）：只保留 action=categorytree 与「特殊:分类树」");
    }
}
