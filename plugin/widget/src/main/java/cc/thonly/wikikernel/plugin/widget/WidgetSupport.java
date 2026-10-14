package cc.thonly.wikikernel.plugin.widget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微件（Widgets）的公共小工具：命名空间前缀解析、调用参数构造
 * （{@code a.b.c} 点号嵌套、重复键成列表、{@code false} 转布尔，与迁移前的 core 行为一致）。
 */
public final class WidgetSupport {

    private WidgetSupport() {
    }

    /** 逗号 / 换行 / 竖线分隔的前缀列表；空 = 用默认值。 */
    public static List<String> parsePrefixes(String configured) {
        List<String> prefixes = new ArrayList<>();
        if (configured != null) {
            for (String part : configured.split("[,|\\r\\n]")) {
                String value = part.trim();
                if (!value.isEmpty()) {
                    prefixes.add(value.endsWith(":") ? value : value + ":");
                }
            }
        }
        return prefixes.isEmpty() ? List.of("微件:", "Widget:") : List.copyOf(prefixes);
    }

    /** 参数值：{@code false} 转布尔，其余保持字符串（仿 MediaWiki Widgets）。 */
    public static Object value(String text) {
        return "false".equals(text == null ? "" : text.trim()) ? Boolean.FALSE : text;
    }

    /** 参数写入嵌套 Map：{@code a.b.c} 分层，重复键转列表。 */
    @SuppressWarnings("unchecked")
    public static void put(Map<String, Object> root, String key, Object value) {
        if (key == null || key.isEmpty()) {
            return;
        }
        String[] keys = key.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < keys.length - 1; i++) {
            Object child = current.get(keys[i]);
            if (!(child instanceof Map)) {
                child = new LinkedHashMap<String, Object>();
                current.put(keys[i], child);
            }
            current = (Map<String, Object>) child;
        }
        String last = keys[keys.length - 1];
        Object existing = current.get(last);
        if (existing == null) {
            current.put(last, value);
        } else if (existing instanceof List) {
            ((List<Object>) existing).add(value);
        } else {
            List<Object> list = new ArrayList<>();
            list.add(existing);
            list.add(value);
            current.put(last, list);
        }
    }
}
