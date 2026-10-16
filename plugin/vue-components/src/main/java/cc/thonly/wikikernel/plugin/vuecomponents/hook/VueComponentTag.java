package cc.thonly.wikikernel.plugin.vuecomponents.hook;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.plugin.vuecomponents.VueComponentsPlugin;
import cc.thonly.wikikernel.plugin.vuecomponents.service.ComponentRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vue 组件解析标签：一个实例承载**所有** {@code *.vue} 组件的标签
 * （{@link #names()} 动态返回组件名，渲染时用 {@link ParserTagCall#tag()} 判断实际用到的那个）。
 * <ul>
 *     <li>{@code <test-component />} 或 {@code <test-component>…</test-component>}：</li>
 *     <li>属性 → props：普通属性按字符串（值先按上下文展开，支持 {@code {{PAGENAME}}} / 链接等），
 *         {@code :count="3"} 这种带 {@code :} 前缀的按 JSON 解析（数字 / 布尔 / 数组 / 对象）；</li>
 *     <li>标签内容 = 默认插槽：**可以嵌套**其它组件标签（本类递归转换），也支持普通 wikitext；</li>
 *     <li>渲染成一个挂载点 {@code div.wiki-vue[data-vue-component]}，浏览器端（{@code vue-components.js}）
 *         拉取组件源码、编译并挂载；用到组件的页面由 {@link VueHeadProvider} 注入 Vue 与加载器。</li>
 * </ul>
 */
@Component
public class VueComponentTag implements ParserTag {

    /** 渲染上下文属性：页面用到组件时置 1（保存后成为页面属性，head 据此注入资源）。 */
    public static final String USED_PROPERTY = "vue-components-used";

    /** 没有组件时的代表名（不参与匹配）。 */
    private static final String PLACEHOLDER_NAME = "vue-component";

    private final ComponentRegistry registry;

    @Autowired
    public VueComponentTag(ComponentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        // 代表名：有组件时用第一个（版本页等展示用），否则用占位名
        List<String> names = registry.tagNames();
        return names.isEmpty() ? PLACEHOLDER_NAME : names.get(0);
    }

    @Override
    public List<String> names() {
        return registry.tagNames();
    }

    /** 标签内容随正文展开（模板 / 解析器函数先跑），再作为默认插槽处理。 */
    @Override
    public boolean expandBody() {
        return true;
    }

    @Override
    public String render(ParserTagCall call) {
        String tag = call.tag() == null ? "" : call.tag().toLowerCase(Locale.ROOT);
        String title = registry.titleOf(tag);
        if (title == null) {
            return error(message(call, "vue.error.missing", tag));
        }
        call.context().setProperty(USED_PROPERTY, "1");
        String slot = renderNested(call, call.body() == null ? "" : call.body());
        return "<div class=\"wiki-vue\" data-vue-component=\"" + escapeAttribute(tag) + "\""
                + " data-vue-title=\"" + escapeAttribute(title) + "\""
                + " data-vue-props=\"" + escapeAttribute(json(propsOf(call))) + "\">"
                + slot + "</div>";
    }

    /** 把标签内容里嵌套的组件标签递归转成挂载点（外层标签先匹配，内层保持原文，这里自己处理）。 */
    private String renderNested(ParserTagCall call, String body) {
        String result = body;
        List<String> names = registry.tagNames();
        for (String name : names) {
            if (name.isEmpty() || result.indexOf('<' + name) < 0) {
                continue;
            }
            Matcher matcher = nestedPattern(name).matcher(result);
            StringBuilder out = new StringBuilder();
            int last = 0;
            while (matcher.find()) {
                out.append(result, last, matcher.start());
                String childTitle = registry.titleOf(name);
                if (childTitle == null) {
                    out.append(matcher.group());
                } else {
                    // 分组：1 = 属性串，2 = 内容（nestedPattern 只有两个分组）
                    String childBody = matcher.group(2) == null ? "" : matcher.group(2);
                    String slot = renderNested(call, childBody);
                    String childProps = json(nestedProps(call, matcher.group(1)));
                    out.append("<div class=\"wiki-vue\" data-vue-component=\"").append(escapeAttribute(name))
                            .append("\" data-vue-title=\"").append(escapeAttribute(childTitle))
                            .append("\" data-vue-props=\"").append(escapeAttribute(childProps))
                            .append("\">").append(slot).append("</div>");
                }
                last = matcher.end();
            }
            if (out.length() > 0) {
                out.append(result, last, result.length());
                result = out.toString();
            }
        }
        return result;
    }

    /** 嵌套标签的属性串 → props（与外层同一套规则）。 */
    private Map<String, Object> nestedProps(ParserTagCall call, String attributeText) {
        Map<String, Object> props = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(attributeText == null ? "" : attributeText);
        while (matcher.find()) {
            String rawKey = matcher.group(1);
            String value = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3)
                    : matcher.group(4) != null ? matcher.group(4) : "";
            putProp(props, call, rawKey, value);
        }
        return props;
    }

    /** 外层标签属性 → props：{@code :name="json"} 走 JSON，其余是字符串（值先展开）。 */
    private Map<String, Object> propsOf(ParserTagCall call) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : call.attributes().entrySet()) {
            putProp(props, call, entry.getKey(), entry.getValue());
        }
        return props;
    }

    private static void putProp(Map<String, Object> props, ParserTagCall call, String rawKey, String value) {
        if (rawKey == null || rawKey.isBlank()) {
            return;
        }
        String key = rawKey.trim();
        boolean bound = key.startsWith(":");
        if (bound) {
            key = key.substring(1).trim();
        }
        if (key.isEmpty()) {
            return;
        }
        String expanded = call.expand(value == null ? "" : value);
        if (bound) {
            // 绑定属性（:count="3" / :items="[1,2]"）：原文交给浏览器 JSON.parse（服务端不做 JSON 解析）
            props.put(key, Map.of("$json", expanded));
        } else {
            props.put(key, expanded);
        }
    }

    /** 简版 JSON 序列化（props 传给浏览器；只支持字符串 / 数字 / 布尔 / null / Map / List）。 */
    static String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(json(String.valueOf(entry.getKey()))).append(':').append(json(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    out.append(',');
                }
                out.append(json(list.get(index)));
            }
            return out.append(']').toString();
        }
        return json(String.valueOf(value));
    }

    private static String json(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16).append('"');
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String escapeAttribute(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 组件不存在的错误提示（文案在插件 i18n；没有消息源时退回键名）。 */
    private static String error(String text) {
        return "<span class=\"wiki-error\">" + text.replace("&", "&amp;").replace("<", "&lt;") + "</span>";
    }

    private static String message(ParserTagCall call, String key, String argument) {
        VueComponentsPlugin plugin = VueComponentsPlugin.instance();
        String template = plugin == null ? key : plugin.message(key);
        String text = template == null || template.isBlank() ? key : template;
        return text.replace("{0}", argument == null ? "" : argument);
    }

    /** 嵌套组件标签的匹配（与外层同一套属性语法；支持自闭合与成对）。 */
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([:A-Za-z_][:A-Za-z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    private static Pattern nestedPattern(String name) {
        String quoted = Pattern.quote(name);
        return Pattern.compile("(?si)<" + quoted + "\\b([^>]*?)(?:/>|>(.*?)</" + quoted + "\\s*>)");
    }
}
