package cc.thonly.wikikernel.common.extension;

import java.util.List;

/**
 * 插件在「站点设置」页（{@code 特殊:权限配置 → 站点设置}）里的一个设置分区（由
 * {@link SiteSettingsSection} 声明字段，core 负责渲染与保存）。
 * <p>
 * 字段的 {@code key} 就是**站点设置键**（{@code wiki_setting}，与 {@code LocalSettings.yaml} /
 * 安装向导写的是同一批键）；core 只认识「键 → 值」，不认识这些键属于哪个功能——
 * 分区标题 / 字段标签由插件用自己的 i18n 提供。
 *
 * @param key          站点设置键（保存时按它写入；沿用迁移前的键名以保持兼容）
 * @param label        显示标签（插件本地化后的文本）
 * @param type         {@code text} / {@code textarea} / {@code checkbox} / {@code select}
 * @param defaultValue 字段没有值时表单里显示的默认值（checkbox 用 {@code "true"} / {@code "false"}）
 * @param options      {@code select} 的选项（{@code value} 即写入的设置值）
 */
public record SettingField(String key, String label, String type, String defaultValue, List<String> options) {

    public static SettingField text(String key, String label, String defaultValue) {
        return new SettingField(key, label, "text", defaultValue, List.of());
    }

    /** 多行文本（{@code textarea}）：适合换行分隔的列表（图片池 / 白名单 / 问答…）。 */
    public static SettingField textarea(String key, String label, String defaultValue) {
        return new SettingField(key, label, "textarea", defaultValue, List.of());
    }

    public static SettingField checkbox(String key, String label, boolean defaultValue) {
        return new SettingField(key, label, "checkbox", Boolean.toString(defaultValue), List.of());
    }

    public static SettingField select(String key, String label, String defaultValue, List<String> options) {
        return new SettingField(key, label, "select", defaultValue, List.copyOf(options));
    }
}
