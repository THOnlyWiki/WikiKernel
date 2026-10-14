package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

import java.util.List;

/**
 * 扩展点：页面 {@code <head>} 扩展（meta / link 标签、{@code <title>} 覆盖等，WikiSEO 等用）。
 * <p>
 * core 在渲染词条阅读页时按 {@link #order()} 升序调用，把返回的 HTML 片段插入 {@code <head>}；
 * 站点级脚本 / 样式（Gadgets、多媒体预览、评论区）把 {@link #siteWide()} 设为 {@code true}，
 * 这样特殊页 / 编辑页 / 历史页等**所有页面**都会注入。
 */
public interface HtmlHeadProvider extends Extension {

    /** 执行顺序，值越小越先执行。 */
    default int order() {
        return 0;
    }

    /**
     * 是否在**所有页面**注入（含特殊页 / 编辑页 / 历史页等；默认 {@code false} = 只在词条阅读视图注入）。
     * <p>
     * 依赖页面正文 / 页面属性的扩展（WikiSEO、TemplateData）保持默认即可。
     */
    default boolean siteWide() {
        return false;
    }

    /** 返回要插入 {@code <head>} 的 HTML 片段（如 {@code <meta …>}）；null / 空列表表示不插入。 */
    default List<String> headItems(HeadContext context) {
        return List.of();
    }

    /** 覆盖 {@code <title>}（null / 空表示不覆盖，仍用「页面名 - 站点名」）。 */
    default String htmlTitle(HeadContext context) {
        return null;
    }
}
