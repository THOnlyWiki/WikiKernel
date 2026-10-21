package cc.thonly.wikikernel.common.extension;

import java.util.List;

/**
 * 特殊页分组（{@code 特殊:特殊页面} 的排版分类，仿 MediaWiki Special:SpecialPages 的导航分组）。
 * <p>
 * 分组 id 是稳定标识；展示名走消息键 {@code specialPages.group.<id>}。
 * 插件注册的 {@link SpecialPage} 可以覆写 {@link SpecialPage#category()} 返回自己的分组 id
 * （未知 id 会单独成组，展示名回退为 id）；不覆写则归入 {@link #OTHER}。
 */
public final class SpecialPageCategories {

    /** 页面列表。 */
    public static final String PAGES = "pages";
    /** 账号管理。 */
    public static final String LOGIN = "login";
    /** 用户与权限。 */
    public static final String USERS = "users";
    /** 最近更改与日志。 */
    public static final String CHANGES = "changes";
    /** 媒体文件报告与上传。 */
    public static final String MEDIA = "media";
    /** 数据与工具。 */
    public static final String WIKI = "wiki";
    /** 页面工具。 */
    public static final String PAGETOOLS = "pagetools";
    /** 重定向特殊页面。 */
    public static final String REDIRECTS = "redirects";
    /** 高使用页面。 */
    public static final String HIGHUSE = "highuse";
    /** 维护报告。 */
    public static final String MAINTENANCE = "maintenance";
    /** 调查。 */
    public static final String SURVEYS = "surveys";
    /** 其他特殊页面（未分类的默认归入这里）。 */
    public static final String OTHER = "other";

    /** 分组展示顺序（与 thonly.cc / MediaWiki 的 Special:SpecialPages 一致）。 */
    public static final List<String> ORDER = List.of(
            MAINTENANCE, PAGES, LOGIN, USERS, CHANGES, MEDIA, WIKI,
            REDIRECTS, HIGHUSE, PAGETOOLS, SURVEYS, OTHER);

    private SpecialPageCategories() {
    }
}
