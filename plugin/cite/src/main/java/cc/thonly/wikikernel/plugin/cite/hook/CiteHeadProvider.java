package cc.thonly.wikikernel.plugin.cite.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.plugin.cite.service.CiteState;

import java.util.List;

/**
 * 页面级资源注入（{@code siteWide=false}）：页面用到引用（渲染时写了
 * {@link CiteState#USED_PROPERTY} 页面属性）才注入 {@code cite.css}。
 */
public class CiteHeadProvider implements HtmlHeadProvider {

    @Override
    public int order() {
        return 610;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (context == null || !"1".equals(context.properties().get(CiteState.USED_PROPERTY))) {
            return List.of();
        }
        return List.of("<link rel=\"stylesheet\" href=\"/plugins/cite/cite.css\">");
    }
}
