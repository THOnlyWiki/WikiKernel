package cc.thonly.wikikernel.plugin.inputbox;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.common.service.SiteConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.util.HtmlUtils;

/**
 * {@code <inputbox>key=value…</inputbox>}：内部按行解析成创建 / 搜索表单（移植自 core 的实现，行为一致）。
 * <ul>
 *     <li>{@code type=create}（默认）→ {@code 特殊:创建页面?pagename=…}；
 *     带 {@code prefix / preload / editintro} 时由 {@code /plugins/input-box/inputbox.js} 组装目标标题；</li>
 *     <li>{@code type=search} / {@code fulltext} → {@code 特殊:搜索?q=…}；</li>
 *     <li>其它类型按创建处理；未知键忽略。</li>
 * </ul>
 * 功能开关沿用 {@code features.input-box}：关闭时把整个标签按原文转义显示（与迁移前一致）。
 */
public class InputBoxTag implements ParserTag {

    private final SiteConfig siteConfig;
    private final MessageSource messageSource;

    public InputBoxTag(SiteConfig siteConfig, MessageSource messageSource) {
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "inputbox";
    }

    /** 标签内容是 {@code key=value} 行（不参与模板展开），与迁移前的 core 行为一致。 */
    @Override
    public boolean expandBody() {
        return false;
    }

    @Override
    public String render(ParserTagCall call) {
        if (!siteConfig.getBoolean("features.input-box", true)) {
            // 关闭：按原文转义显示（等价于迁移前“未识别的标签”）
            String body = call.body() == null ? "" : call.body();
            return "<p>" + escape("<inputbox>" + body + "</inputbox>") + "</p>";
        }
        return buildForm(parseKeyValueLines(call.body()));
    }

    /** 解析 {@code key=value} 行（键转小写；没有 {@code =} 的行忽略）。 */
    static Map<String, String> parseKeyValueLines(String body) {
        Map<String, String> params = new LinkedHashMap<>();
        if (body == null) {
            return params;
        }
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            params.put(trimmed.substring(0, equals).trim().toLowerCase(Locale.ROOT),
                    trimmed.substring(equals + 1).trim());
        }
        return params;
    }

    private String buildForm(Map<String, String> params) {
        String type = params.getOrDefault("type", "create").trim().toLowerCase(Locale.ROOT);
        boolean search = type.startsWith("search") || type.startsWith("fulltext");
        String defaultValue = params.getOrDefault("default", "");
        String placeholder = params.getOrDefault("placeholder", "");
        String id = params.getOrDefault("id", "");
        String button = params.getOrDefault("buttonlabel", "");
        if (button.isBlank()) {
            button = message(search ? "common.search" : "special.createPage");
        }
        StringBuilder html = new StringBuilder("<form class=\"inputbox\" method=\"get\" action=\"")
                .append(search ? "/wiki/特殊:搜索" : "/wiki/特殊:创建页面").append('"');
        if (!id.isBlank()) {
            html.append(" id=\"").append(escape(id)).append('"');
        }
        if (!search) {
            for (String key : List.of("prefix", "preload", "editintro")) {
                String value = params.get(key);
                if (value != null && !value.isBlank()) {
                    html.append(" data-").append(key).append("=\"")
                            .append(escape(value)).append('"');
                }
            }
        }
        html.append("><input type=\"text\" name=\"").append(search ? "q" : "pagename").append('"');
        if (!defaultValue.isBlank()) {
            html.append(" value=\"").append(escape(defaultValue)).append('"');
        }
        if (!placeholder.isBlank()) {
            html.append(" placeholder=\"").append(escape(placeholder)).append('"');
        }
        String width = params.getOrDefault("width", "").replaceAll("[^0-9]", "");
        if (!width.isEmpty()) {
            html.append(" size=\"").append(width).append('"');
        }
        html.append("/><button type=\"submit\">").append(escape(button))
                .append("</button></form>");
        return html.toString();
    }

    /**
     * 取本地化文案：按钮文字用 core 的通用键 {@code common.search} / {@code special.createPage}
     * （core 的消息优先，插件不再自带中文兜底文案）；
     * 没有消息源或漏翻时显示翻译键本身（与 core 的消息源一致，不回落中文）。
     */
    private String message(String key) {
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

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
