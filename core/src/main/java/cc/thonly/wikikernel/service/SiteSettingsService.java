package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.service.SiteConfig;

/**
 * 站点设置（键值）：存 {@code wiki_setting}，内存缓存；可在「特殊:权限配置」页修改，
 * LocalSettings.yaml 的 {@code wikikernel.permissions.*} 默认值启动时导入（见实现类）。
 */
public interface SiteSettingsService {

    /** 是否允许编辑其他用户的用户页（管理员始终可以）。 */
    String ALLOW_OTHER_USER_PAGE_EDIT = "permissions.allow-other-user-page-edit";

    /** 是否允许未登录用户查看「特殊:最近更改」（默认允许）。 */
    String ALLOW_ANONYMOUS_RECENT_CHANGES_KEY = "permissions.allow-anonymous-recent-changes";

    /** 站点图标 URL（favicon.ico / &lt;link rel="icon"&gt;）；空 = 内置 wiki-logo.svg。 */
    String FAVICON_KEY = "site.favicon";

    /**
     * 站点默认头像 URL（未上传头像的用户 / 匿名评论等使用）；空 = 内置
     * {@code /img/avatar-default.svg}。这是 core 与各插件共享的键，Timeless 的「默认头像」
     * 设置也写这里，评论 / SocialProfile 不需要依赖 Timeless 插件即可读取。
     */
    String DEFAULT_AVATAR_KEY = SiteConfig.DEFAULT_AVATAR_KEY;

    /** 是否输出 {@code <meta name="referrer" content="no-referrer">}（默认关闭）。 */
    String NO_REFERRER_KEY = "site.no-referrer";

    /** {@code /robots.txt} 的动态内容；空 = 使用内置默认规则。 */
    String ROBOTS_KEY = "site.robots";

    /** {@code /robots.txt} 的内置默认内容（站点设置留空时使用）。 */
    String DEFAULT_ROBOTS = "User-agent: *\nAllow: /\n";

    /** 读取字符串设置；不存在时返回默认值。 */
    String get(String key, String defaultValue);

    /** 读取布尔设置（值等于 true/1/on/yes，忽略大小写）。 */
    boolean getBoolean(String key, boolean defaultValue);

    /** 写入设置（新增或覆盖）并刷新缓存。 */
    void set(String key, String value);

    /** 清空缓存并重新加载（测试 / 导入用）。 */
    void reload();
}
