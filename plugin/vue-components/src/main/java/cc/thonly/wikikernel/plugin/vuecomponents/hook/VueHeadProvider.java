package cc.thonly.wikikernel.plugin.vuecomponents.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;

import java.util.List;

/**
 * 页面级资源注入（{@code siteWide=false}）：页面用到 Vue 组件（渲染时写了
 * {@link VueComponentTag#USED_PROPERTY} 页面属性）才注入加载器；
 * 加载器是 ES 模块，自己 {@code import} 本地打包的 Vue 3（{@code vue.esm-browser.prod.js}）。
 */
public class VueHeadProvider implements HtmlHeadProvider {

    @Override
    public int order() {
        return 640;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (context == null || !"1".equals(context.properties().get(VueComponentTag.USED_PROPERTY))) {
            return List.of();
        }
        return List.of(
                "<link rel=\"stylesheet\" href=\"/plugins/vue-components/vue-components.css\">",
                "<script type=\"module\" src=\"/plugins/vue-components/vue-components.js\"></script>");
    }
}
