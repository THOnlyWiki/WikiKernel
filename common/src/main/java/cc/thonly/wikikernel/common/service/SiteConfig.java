package cc.thonly.wikikernel.common.service;

/**
 * 站点设置读取桥（由 core 提供实现，插件可 {@code @Autowired} 注入）。
 * <p>
 * 键与「特殊:权限配置 → 站点设置」/ LocalSettings.yaml 一致（如功能开关 {@code features.math}、
 * 站点名 {@code site.name}）。只读：插件自己的配置请用 {@code PluginContext.config()}
 * 或安装向导（{@code SetupStep.putPluginSetting}）。
 */
public interface SiteConfig {

    /** 读取字符串设置（不存在返回 defaultValue）。 */
    String get(String key, String defaultValue);

    /** 读取布尔设置（true/1/on/yes 视为真，与 core 归一化一致）。 */
    boolean getBoolean(String key, boolean defaultValue);

    /** 读取整数设置（非法 / 不存在返回 defaultValue）。 */
    default int getInt(String key, int defaultValue) {
        String value = get(key, "");
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    /**
     * 写站点设置（与 core 的 {@code /init}、站点设置页、LocalSettings 导入**同一个存储**，
     * 读取端（{@link #get}）立即能看到）。
     * <p>
     * 用途：插件把自己的设置项（如 {@code ai.provider.<id>.key}）写进平台设置里——值语义与 core 一致
     *（空串 = 清空，读取端回退默认值）。<b>只写自己的键</b>：改别人的键会影响其它插件 / core。
     * <p>
     * 默认实现抛 {@link UnsupportedOperationException}（只读实现 / 测试替身可以不支持写）；
     * core 的实现是可读写的。
     *
     * @param key   设置键（沿用迁移前的键名，保持兼容）
     * @param value 值（不允许 {@code null}，空串表示清空）
     */
    default void set(String key, String value) {
        throw new UnsupportedOperationException("该 SiteConfig 实现是只读的，不支持写站点设置: " + key);
    }
}
