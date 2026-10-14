package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

/**
 * 扩展点：页面渲染完成后的处理钩子（如注入目录、代码高亮、页脚等）。
 * <p>
 * 定义在 common 中，core 聚合执行，插件可按需提供实现。
 */
public interface PageRenderHook extends Extension {

    /** 执行顺序，值越小越先执行。 */
    default int order() {
        return 0;
    }

    /**
     * @param slug 页面标识
     * @param html 已渲染的 html
     * @return 处理后的 html
     */
    String afterRender(String slug, String html);
}
