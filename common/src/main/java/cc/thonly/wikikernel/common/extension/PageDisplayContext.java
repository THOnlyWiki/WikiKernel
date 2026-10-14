package cc.thonly.wikikernel.common.extension;

import java.util.Map;

/**
 * 一次页面显示（词条阅读视图）的上下文，供 {@link PageDisplayHook} 使用。
 * <p>
 * 与 {@link HeadContext}（{@code <head>} 注入）的区别：这里拿到的是**正文**及其渲染结果，
 * 插件可以在标题上方 / 正文下方插入 HTML，或者直接认领整页正文（非 wikitext 的命名空间页）。
 */
public interface PageDisplayContext {

    /** 页面 ID（页面不存在时为 0）。 */
    long pageId();

    /** 页面标题（含命名空间，如 {@code 图表:演示}）。 */
    String title();

    /** 当前展示版本的正文（查看旧版本时是那一版的内容）。 */
    String content();

    /** 是否在查看旧版本（{@code ?oldid=N} 且不是当前版本）。 */
    boolean oldRevision();

    /** 页面标题对应的站内路径（如 {@code /wiki/首页}）。 */
    String pageUrl();

    /**
     * 页面属性：wikitext 页面是**本次渲染**写入的属性（模板里的改动立即生效），
     * 其它页面（代码页 / 命名空间页）为空 Map。
     */
    Map<String, String> properties();

    /** 查询参数（{@code action} / {@code oldid} / …；不可修改）。 */
    Map<String, String> query();

    /** 当前用户（匿名时为 {@link Viewer#ANONYMOUS}）。 */
    Viewer viewer();

    /** 站点名称。 */
    String siteName();

    /** 站点根地址（如 {@code http://localhost:8080}，不带尾斜杠）。 */
    String baseUrl();
}
