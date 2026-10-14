package cc.thonly.wikikernel.plugin.graph;

import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.util.HtmlUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图形支持的公共部分：功能开关、Vega 规范校验、占位 HTML、命名空间判断与本地化文案。
 * <p>
 * 与 MediaWiki 的 Graphoid 服务不同，这里不依赖外部渲染服务：规范用
 * {@code <script type="application/json">} 原样带出，{@code /plugins/graph/graph.js} 用
 * core 内置的 {@code /vendor/vega/} 渲染。
 */
public class GraphSupport {

    /** {@code 图表:} / {@code Graph:} 命名空间前缀（与 core 的命名空间表一致）。 */
    public static final List<String> NAMESPACE_PREFIXES = List.of("图表:", "Graph:");

    private static final Pattern ATTR = Pattern.compile(
            "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*(?:=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+)))?");

    private final SiteConfig siteConfig;
    private final MessageSource messageSource;

    public GraphSupport(SiteConfig siteConfig, MessageSource messageSource) {
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
    }

    /** 图形功能是否开启（站点设置 {@code features.graph}，默认开启）。 */
    public boolean enabled() {
        return siteConfig == null || siteConfig.getBoolean(GraphPlugin.ENABLED_KEY, true);
    }

    /** 标题是否属于 {@code 图表:} / {@code Graph:} 命名空间（不含讨论页）。 */
    public static boolean isGraphTitle(String title) {
        String value = title == null ? "" : title.trim();
        for (String prefix : NAMESPACE_PREFIXES) {
            if (value.regionMatches(true, 0, prefix, 0, prefix.length()) && value.length() > prefix.length()) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code <graph …>规范</graph>}：解析属性并生成占位；
     * 内容为空或不像 JSON 时返回 {@code null}（调用方显示错误提示）。
     */
    public String placeholder(String attributes, String body) {
        Map<String, String> params = parseAttributes(attributes);
        return figure(body, params.getOrDefault("title", ""), params.get("width"), params.get("height"));
    }

    /** 整页图（{@code 图表:} 命名空间）：页面正文本身就是 Vega 规范，说明文字用页面标题。 */
    public String pagePlaceholder(String content, String caption) {
        return figure(content, caption, null, null);
    }

    /** 无效规范的本地化错误提示（放在正文里的 {@code <p class="wiki-error">}）。 */
    public String invalidSpec() {
        return "<p class=\"wiki-error\">" + escape(message("graph.invalid")) + "</p>";
    }

    /**
     * 取本地化文案（插件消息包里的 {@code graph.*} 键）；取不到时**显示键名本身**
     * （与 core 的「漏翻显示键名」策略一致，不会回落到中文）。
     */
    public String message(String key) {
        if (messageSource == null) {
            return key;
        }
        try {
            String value = messageSource.getMessage(key, null, key, LocaleContextHolder.getLocale());
            return value == null || value.isBlank() ? key : value;
        } catch (RuntimeException exception) {
            return key;
        }
    }

    private String figure(String body, String caption, String width, String height) {
        String spec = body == null ? "" : body.trim();
        if (spec.isEmpty() || !(spec.startsWith("{") || spec.startsWith("["))) {
            return null;
        }
        StringBuilder style = new StringBuilder();
        if (width != null && width.trim().matches("\\d{1,4}")) {
            style.append("width:").append(Integer.parseInt(width.trim())).append("px;");
        }
        if (height != null && height.trim().matches("\\d{1,4}")) {
            style.append("min-height:").append(Integer.parseInt(height.trim())).append("px;");
        }
        StringBuilder html = new StringBuilder("<figure class=\"mw-graph\"");
        if (style.length() > 0) {
            html.append(" style=\"").append(style).append("\"");
        }
        html.append("><div class=\"mw-graph-plot\"><span class=\"mw-graph-fallback\">")
                .append(escape(message("graph.needJavaScript")))
                .append("</span></div>")
                .append("<script type=\"application/json\" class=\"mw-graph-spec\">")
                .append(escapeJson(spec)).append("</script>");
        if (caption != null && !caption.isBlank()) {
            html.append("<figcaption class=\"mw-graph-caption\">")
                    .append(escape(caption.trim())).append("</figcaption>");
        }
        html.append("</figure>");
        return html.toString();
    }

    /** 把 JSON 文本安全地放进 {@code <script>} 里（JSON 里的 {@code <} 只在字符串中出现，替换为 \\u 转义合法）。 */
    static String escapeJson(String json) {
        return json.replace("&", "\\u0026").replace("<", "\\u003c").replace(">", "\\u003e");
    }

    /** 解析标签属性（{@code key="value"} / {@code key=value} / {@code key}），键转小写。 */
    static Map<String, String> parseAttributes(String attributes) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = ATTR.matcher(attributes == null ? "" : attributes);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3)
                    : matcher.group(4) != null ? matcher.group(4) : "";
            if (key != null && !key.isEmpty()) {
                result.put(key.toLowerCase(Locale.ROOT), value.trim());
            }
        }
        return result;
    }

    static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
