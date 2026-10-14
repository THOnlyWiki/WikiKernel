package cc.thonly.wikikernel.common.plugin.support;

import cc.thonly.wikikernel.common.plugin.PluginConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 基于 Map 的 PluginConfig 实现。 */
public final class MapPluginConfig implements PluginConfig {

    private final Map<String, String> values;

    public MapPluginConfig(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values == null ? Map.of() : values));
    }

    @Override
    public String get(String key, String defaultValue) {
        String value = this.values.get(key);
        return value == null ? defaultValue : value;
    }

    @Override
    public Map<String, String> asMap() {
        return this.values;
    }
}
