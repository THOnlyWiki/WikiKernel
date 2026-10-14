package cc.thonly.wikikernel.plugin.depdemo.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;

import java.util.List;

/**
 * {@code HtmlHeadProvider} 演示：站点头部片段。
 * <p>
 * 本插件的描述文件声明了 {@code plugin.clientRequires=comments}（前端脚本依赖 comments 插件先加载），
 * 因此本片段在 {@code <head>} 里的位置由 {@code PluginManager.clientLoadOrder()} 决定：
 * 被依赖插件（comments）的片段一定排在前面。
 */
public class DepDemoHeadProvider implements HtmlHeadProvider {

    @Override
    public int order() {
        return 100;
    }

    /** 站点级：特殊页 / 编辑页等所有页面都注入，方便观察顺序。 */
    @Override
    public boolean siteWide() {
        return true;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        return List.of("<!-- dep-demo: 前端资源（plugin.clientRequires=comments，comments 的片段在本条之前） -->",
                "<meta name=\"dep-demo\" content=\"loaded\" />");
    }
}
