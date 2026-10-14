package cc.thonly.wikikernel.plugin.math;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.util.HtmlUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code <math>TeX</math>} / {@code <chem>…</chem>}（与 {@link MathPlugin} 注册两个实例）：
 * 生成 KaTeX 占位（与迁移前 core 的输出逐字节一致）：
 * <pre>{@code <span class="mw-math[ mw-math-block]" data-tex="…" data-display="inline|block"[ data-chem="1"]>
 *   <span class="mw-math-fallback">TeX 源码</span></span>}</pre>
 * 标签内容原样作为 TeX（不做 wikitext 解析，与 MediaWiki 一致）；
 * 功能关闭（{@code features.math=false}）时按原文转义显示。
 */
public class MathTag implements ParserTag {

    /** 属性：{@code key="value"} / {@code key='value'} / {@code key=value}。 */
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([A-Za-z][A-Za-z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    private final SiteConfig siteConfig;
    private final MessageSource messageSource;
    private final boolean chem;

    public MathTag(SiteConfig siteConfig, MessageSource messageSource, boolean chem) {
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
        this.chem = chem;
    }

    @Override
    public String name() {
        return chem ? "chem" : "math";
    }

    /** 内容原样作为 TeX（不参与模板展开），与迁移前一致。 */
    @Override
    public boolean expandBody() {
        return false;
    }

    @Override
    public String render(ParserTagCall call) {
        String body = call.body() == null ? "" : call.body();
        if (!siteConfig.getBoolean("features.math", true)) {
            String tag = chem ? "chem" : "math";
            String attributes = attributeText(call.attributes());
            return "<p>" + HtmlUtils.htmlEscape("<" + tag + attributes + ">" + body + "</" + tag + ">") + "</p>";
        }
        return buildFormula(call.attributes(), body);
    }

    /** 生成公式占位（KaTeX 渲染；脚本未加载时显示 TeX 源码）。 */
    String buildFormula(Map<String, String> params, String content) {
        String tex = content == null ? "" : content.trim().replaceAll("\\s+", " ");
        if (tex.isEmpty()) {
            return "<span class=\"wiki-error\">" + HtmlUtils.htmlEscape(message("math.empty"))
                    + "</span>";
        }
        boolean block = "block".equalsIgnoreCase(params.getOrDefault("display", "inline").trim());
        StringBuilder html = new StringBuilder("<span class=\"mw-math");
        if (block) {
            html.append(" mw-math-block");
        }
        html.append("\" data-tex=\"").append(escapeAttribute(tex)).append('"');
        html.append(" data-display=\"").append(block ? "block" : "inline").append('"');
        if (chem) {
            html.append(" data-chem=\"1\"");
        }
        html.append("><span class=\"mw-math-fallback\">")
                .append(HtmlUtils.htmlEscape(chem ? "\\ce{" + tex + "}" : tex))
                .append("</span></span>");
        return html.toString();
    }

    /**
     * 取本地化文案（文案在 {@code i18n/messages*.properties}，见 {@link MathMessages}）；
     * 没有消息源、core 与插件都没有这个键时显示翻译键本身（不回落中文）。
     */
    private String message(String key) {
        if (messageSource == null) {
            return key;
        }
        try {
            String value = messageSource.getMessage(key, new Object[0], key, LocaleContextHolder.getLocale());
            return value == null || value.isBlank() ? key : value;
        } catch (RuntimeException exception) {
            return key;
        }
    }

    /** 把属性表还原成文本（关闭时按原文显示用；顺序为插入顺序）。 */
    private static String attributeText(Map<String, String> attributes) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            text.append(' ').append(entry.getKey()).append("=\"")
                    .append(entry.getValue().replace("&", "&amp;").replace("\"", "&quot;"))
                    .append('"');
        }
        return text.toString();
    }

    /** 与迁移前 core 的 {@code HtmlSanitizer.escapeAttribute} 一致。 */
    static String escapeAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 解析属性（键转小写；无值属性按空串）。 */
    static Map<String, String> parseAttributes(String attributes) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(attributes == null ? "" : attributes);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2)
                    : matcher.group(3) != null ? matcher.group(3)
                    : matcher.group(4) != null ? matcher.group(4) : "";
            result.put(matcher.group(1).toLowerCase(Locale.ROOT), value.trim());
        }
        return result;
    }
}
