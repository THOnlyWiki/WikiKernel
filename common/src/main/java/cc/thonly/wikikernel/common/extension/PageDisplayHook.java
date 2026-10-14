package cc.thonly.wikikernel.common.extension;

import cc.thonly.wikikernel.common.plugin.Extension;

import java.util.List;

/**
 * 扩展点：页面显示钩子（仿 MediaWiki 的 {@code BeforePageDisplay} / {@code OutputPageParserOutput} 一类钩子）。
 * <p>
 * core 在**词条阅读视图**渲染时按 {@link #order()} 升序调用：
 * <ol>
 *     <li>{@link #renderBody}：认领整页正文（{@code 图表:} 整页图、{@code 微件:} 代码页这类非 wikitext 页面）；</li>
 *     <li>{@link #beforeTitle} / {@link #afterContent}：在标题上方 / 正文下方插入 HTML 片段
 *     （页面横幅、评论区、浏览计数页脚等）。</li>
 * </ol>
 * 约定：返回 {@code null} / 空串表示不参与；抛异常只影响该插件，core 会跳过并继续其它插件。
 */
public interface PageDisplayHook extends Extension {

    /** 执行顺序，值越小越先执行。 */
    default int order() {
        return 0;
    }

    /**
     * 认领整页正文：返回非 {@code null} 时 core 不再按 wikitext / 代码页渲染，
     * 直接把返回值当作正文 HTML（{@code <div class="wiki-content">} 里）。
     * <p>
     * 只在**没有** {@code ?action=edit} / {@code ?action=history} 的阅读视图里调用；
     * 返回 {@code null}（默认）表示不认领。
     */
    default String renderBody(PageDisplayContext context) {
        return null;
    }

    /**
     * 页面**不存在**时认领该标题的显示（如审核的「待审创建」页：作者 / 审核员在页面通过前
     * 看到待审内容预览，其他人照常 404）。返回非 {@code null}（空串也算认领为一个空页面）时
     * core 不再 404，而是渲染一个通用页面外壳（标题 + 返回的 HTML）；
     * 返回 {@code null}（默认）表示不认领，core 继续走原来的 404 / 用户页等分支。
     * <p>
     * 此时上下文里 {@link PageDisplayContext#pageId()} 为 0、{@link PageDisplayContext#content()}
     * 为空串。钩子按 {@link #order()} 升序询问，第一个认领的生效。
     */
    default String renderMissingPage(PageDisplayContext context) {
        return null;
    }

    /** 页面标题上方（页面横幅等）的 HTML 片段；{@code null} / 空表示不插入。 */
    default String beforeTitle(PageDisplayContext context) {
        return null;
    }

    /**
     * **正文之前**的 HTML 片段（提示条一类，如「此页面有 N 条待审更改」）；
     * {@code null} / 空表示不插入。位置在页面标题与正文之间（正文上方、目录 / 分类链接之前）。
     */
    default String beforeContent(PageDisplayContext context) {
        return null;
    }

    /** 正文下方的 HTML 片段（评论区、页脚等）；{@code null} / 空表示不插入。 */
    default String afterContent(PageDisplayContext context) {
        return null;
    }

    /** 该页面额外需要的 {@code <head>} 片段（脚本 / 样式）；默认不注入。 */
    default List<String> headItems(PageDisplayContext context) {
        return List.of();
    }
}
