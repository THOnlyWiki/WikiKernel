package cc.thonly.wikikernel.common.extension;

/**
 * 一次「顶栏个人工具区」渲染的上下文（供 {@link PersonalToolbarHook} 使用，
 * 对应 MediaWiki 的 {@code SkinTemplateNavigation::PersonalUrls} 钩子）。
 */
public interface PersonalToolbarContext {

    /** 当前用户（匿名时为 {@link Viewer#ANONYMOUS}）。 */
    Viewer viewer();

    /** 站点名称。 */
    String siteName();

    /** 站点根地址（如 {@code http://localhost:18080}，不带尾斜杠）。 */
    String baseUrl();
}
