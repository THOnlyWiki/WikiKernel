package cc.thonly.wikikernel.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wikimedia Codex 图标服务：加载随包内置的完整 {@code codex-icons.json}，
 * 在 Thymeleaf 里输出内联 SVG（{@code th:utext="${@codexIconService.icon('search')}"}）。
 * <p>
 * 图标路径来自官方 {@code @wikimedia/codex-icons 2.7.0}（MIT / CC BY 4.0），
 * 不依赖 CDN、也不需要前端 Vue。
 */
@Component
public class CodexIconService {

    private static final String PREFIX = "cdxIcon";
    private final Map<String, Object> icons;

    public CodexIconService() {
        Map<String, Object> loaded = Map.of();
        try (InputStream input = new ClassPathResource(
                "static/vendor/codex/2.7.0/codex-icons.json").getInputStream()) {
            Object parsed = new Yaml().load(input);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> values = new LinkedHashMap<>();
                map.forEach((key, value) -> {
                    if (key != null) {
                        values.put(String.valueOf(key), value);
                    }
                });
                loaded = values;
            }
        } catch (IOException | RuntimeException exception) {
            // 资源缺失不应让整站启动失败；调用方会得到空字符串
            loaded = Map.of();
        }
        this.icons = loaded;
    }

    /** 默认尺寸（medium）图标。 */
    public String icon(String name) {
        return icon(name, "medium", null);
    }

    /** 指定尺寸 / 无障碍标签的图标。 */
    public String icon(String name, String size, String label) {
        String key = iconKey(name);
        Object value = icons.get(key);
        if (value instanceof Map<?, ?> map) {
            value = map.get("ltr");
            if (!(value instanceof String)) {
                value = map.get("default");
            }
            if (!(value instanceof String) && map.get("langCodeMap") instanceof Map<?, ?> languages) {
                value = languages.get("en");
                if (!(value instanceof String)) {
                    value = languages.values().stream().filter(String.class::isInstance).findFirst().orElse(null);
                }
            }
        }
        if (!(value instanceof String markup) || markup.isBlank()) {
            return "";
        }
        String sizeClass = size == null || size.isBlank() ? "" : " cdx-icon--" + size;
        StringBuilder html = new StringBuilder("<span class=\"cdx-icon").append(sizeClass).append("\"");
        if (label == null || label.isBlank()) {
            html.append(" aria-hidden=\"true\"");
        } else {
            html.append(" role=\"img\" aria-label=\"")
                    .append(HtmlUtils.htmlEscape(label)).append("\"");
        }
        html.append("><svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 20 20\" focusable=\"false\">")
                .append(markup).append("</svg></span>");
        return html.toString();
    }

    /** 完整图标名单（去掉 {@code cdxIcon} 前缀，按字母排序）。 */
    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (String key : icons.keySet()) {
            if (key.startsWith(PREFIX) && key.length() > PREFIX.length()) {
                names.add(key.substring(PREFIX.length()));
            }
        }
        names.sort(Comparator.naturalOrder());
        return List.copyOf(names);
    }

    /** CodexExample 页面用：名称 → 内联 SVG。 */
    public List<Map<String, String>> examples() {
        List<Map<String, String>> result = new ArrayList<>();
        for (String name : names()) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("html", icon(name));
            result.add(row);
        }
        return List.copyOf(result);
    }

    private static String iconKey(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        if (name.startsWith(PREFIX)) {
            return name;
        }
        StringBuilder key = new StringBuilder(PREFIX);
        for (String part : name.trim().split("[-_\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            key.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return key.toString();
    }
}
