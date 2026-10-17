package cc.thonly.wikikernel.plugin.dpl;

import cc.thonly.wikikernel.common.extension.ParserFunction;
import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.plugin.PluginDescriptor;
import cc.thonly.wikikernel.common.plugin.WikiPlugin;
import cc.thonly.wikikernel.common.service.PageSearchStore;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * DynamicPageList4 扩展移植：{@code {{#dpl:…}}} 页面列表，以及 DPL 的变量 / 正则替换 / 取数字辅助函数。
 * <p>
 * 查询通过 core 的 {@link PageSearchStore} 完成（分类、命名空间、标题、排序），插件不直接依赖数据库表。
 * 高级转写（{@code #dplchapter}）、矩阵（{@code #dplmatrix}）等函数未包含在本移植里。
 */
public class DynamicPageListPlugin implements WikiPlugin {

    public static final String PLUGIN_ID = "dynamic-page-list";

    @Autowired
    private PageSearchStore pageSearchStore;

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(PLUGIN_ID, "DynamicPageList4", "1.0.0",
                "DynamicPageList4 parser function: {{#dpl:...}} page lists with category, namespace, "
                        + "title and order filters, plus #dplvar / #dplreplace / #dplnum");
    }

    @Override
    public void onStart(PluginContext context) {
        context.register(ParserFunction.class, new DplFunction(this.pageSearchStore));
        context.register(ParserFunction.class, new DplVarFunction());
        context.register(ParserFunction.class, new DplReplaceFunction());
        context.register(ParserFunction.class, new DplNumFunction());
        context.logger().log(System.Logger.Level.INFO,
                "已启用 DynamicPageList4：{{#dpl}} 页面列表（分类 / 命名空间 / 标题 / 排序）");
    }
}
