package cc.thonly.wikikernel.service;

/**
 * 站点设置（键值）：存 {@code wiki_setting}，内存缓存；可在「特殊:权限配置」页修改，
 * LocalSettings.yaml 的 {@code wikikernel.permissions.*} 默认值启动时导入（见实现类）。
 */
public interface SiteSettingsService {

    /** 是否允许编辑其他用户的用户页（管理员始终可以）。 */
    String ALLOW_OTHER_USER_PAGE_EDIT = "permissions.allow-other-user-page-edit";

    /** 读取字符串设置；不存在时返回默认值。 */
    String get(String key, String defaultValue);

    /** 读取布尔设置（值等于 true/1/on/yes，忽略大小写）。 */
    boolean getBoolean(String key, boolean defaultValue);

    /** 写入设置（新增或覆盖）并刷新缓存。 */
    void set(String key, String value);

    /** 清空缓存并重新加载（测试 / 导入用）。 */
    void reload();
}
