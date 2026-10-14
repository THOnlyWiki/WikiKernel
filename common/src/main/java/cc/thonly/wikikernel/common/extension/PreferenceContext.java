package cc.thonly.wikikernel.common.extension;

/**
 * 一次参数设置（{@code 特殊:参数设置}）渲染的上下文，供 {@link PreferenceSection} 使用。
 */
public interface PreferenceContext {

    /** 当前用户（参数设置页需要登录，因此这里不会是匿名）。 */
    Viewer viewer();

    /** 站点名称。 */
    String siteName();

    /** 站点根地址（如 {@code http://localhost:8080}，不带尾斜杠）。 */
    String baseUrl();

    /** 取本地化文案（core 的 MessageSource，已合并插件消息；缺失时返回 fallback）。 */
    String message(String key, String fallback);
}
