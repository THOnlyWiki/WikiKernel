package cc.thonly.wikikernel.plugin.dpl;

import cc.thonly.wikikernel.common.extension.ParserFunctionCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DPL 参数表：把 {@code {{#dpl: |key=value |key2=value2 }}} 的参数按顶层 {@code |} 拆好后，
 * 再按第一个 {@code =} 拆成键值（值允许为空，裸键视为 flag）。
 * <p>
 * 参数值会先经过 {@link ParserFunctionCall#expand(String)}，所以
 * {@code resultsheader=共{{NUMBEROFARTICLES}}个页面} 里的魔术字能正常展开。
 */
final class DplArguments {

    private final Map<String, List<String>> values;

    private DplArguments(Map<String, List<String>> values) {
        this.values = values;
    }

    static DplArguments parse(ParserFunctionCall call) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (String raw : call.args()) {
            String expanded = call.expand(raw == null ? "" : raw);
            String text = expanded == null ? "" : expanded.trim();
            if (text.isEmpty()) {
                continue;
            }
            int equals = text.indexOf('=');
            String key;
            String value;
            if (equals < 0) {
                key = text.toLowerCase(Locale.ROOT);
                value = "1";
            } else {
                key = text.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                value = text.substring(equals + 1).trim();
            }
            if (!key.isEmpty()) {
                values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
            }
        }
        return new DplArguments(values);
    }

    /** 第一个值；没有时返回 {@code null}。 */
    String first(String key) {
        List<String> list = values.get(key.toLowerCase(Locale.ROOT));
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    /** 同名参数的全部值（DPL 的 {@code category=…} 可重复表示 AND）。 */
    List<String> all(String key) {
        List<String> list = values.get(key.toLowerCase(Locale.ROOT));
        return list == null ? List.of() : List.copyOf(list);
    }

    String first(String key, String defaultValue) {
        String value = first(key);
        return value == null ? defaultValue : value;
    }

    int integer(String key, int defaultValue) {
        String value = first(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    boolean bool(String key, boolean defaultValue) {
        String value = first(key);
        if (value == null) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return true; // 裸键
        }
        return "1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized)
                || "on".equals(normalized);
    }
}
