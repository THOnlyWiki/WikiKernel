package cc.thonly.wikikernel.common.plugin;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 插件配置视图，对应 application.yaml 中：
 *
 * <pre>
 * wikikernel:
 *   plugins:
 *     settings:
 *       &lt;plugin-id&gt;:
 *         key: value
 * </pre>
 */
public interface PluginConfig {

    String get(String key, String defaultValue);

    default String get(String key) {
        return get(key, null);
    }

    default boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    default int getInt(String key, int defaultValue) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /** 逗号分隔的列表，空白项会被忽略。 */
    default List<String> getList(String key) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    Map<String, String> asMap();
}
