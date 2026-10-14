package cc.thonly.wikikernel.plugin.flashplayer;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.common.service.FileStore;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.StoredMediaFile;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.util.HtmlUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code <flash-player file="[[文件:X.swf]] 或 URL" 其他参数 />}（移植自 core，输出逐字节一致）：
 * 生成播放器占位（前端 {@code /plugins/flash-player/flash-player.js} 懒加载 Ruffle 后播放）。
 * <ul>
 *     <li>{@code file} 支持外部 URL、{@code [[文件:X.swf]]} 与裸文件名；</li>
 *     <li>参数：{@code width/height/autoplay/loop/fullscreen/letterbox/menu/bgcolor/quality/scale/wmode/
 *     allowscriptaccess/unmute/flashvars/base} 与 {@code caption}（也可用标签内容）；</li>
 *     <li>功能关闭（{@code features.flash-player=false}）时退化成文件 / 链接。</li>
 * </ul>
 */
public class FlashPlayerTag implements ParserTag {

    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([A-Za-z][A-Za-z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");

    private final SiteConfig siteConfig;
    private final FileStore fileStore;
    private final MessageSource messageSource;

    public FlashPlayerTag(SiteConfig siteConfig, FileStore fileStore, MessageSource messageSource) {
        this.siteConfig = siteConfig;
        this.fileStore = fileStore;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "flash-player";
    }

    /** 内容（说明文字）不参与 wikitext 展开，与迁移前一致。 */
    @Override
    public boolean expandBody() {
        return false;
    }

    @Override
    public String render(ParserTagCall call) {
        Map<String, String> params = parseAttributes(attributesText(call));
        String raw = params.getOrDefault("file", "").trim();
        String body = call.body() == null ? "" : call.body();
        String caption = !body.isBlank() ? body.trim() : params.getOrDefault("caption", "").trim();
        if (raw.isEmpty()) {
            return error(message("flashPlayer.missingFile"));
        }
        String src;
        String pageUrl;
        String name = raw;
        if (raw.matches("(?i)^https?://.*")) {
            src = raw; // 外部地址：直接播放，也直接链接
            pageUrl = raw;
        } else {
            name = flashFileName(raw);
            if (fileStore == null) {
                return error(message("flashPlayer.unavailable"));
            }
            Optional<StoredMediaFile> file = fileStore.find(name);
            if (file.isEmpty()) {
                return error(message("flashPlayer.missing")) + redFileLink(name);
            }
            src = file.get().url();
            pageUrl = file.get().pageUrl();
            name = file.get().name();
        }
        boolean enabled = siteConfig.getBoolean("features.flash-player", true);
        if (!enabled) {
            // 功能关闭：退化成文件链接（带一句提示）
            return "<span class=\"flash-player-disabled\">"
                    + label("flashPlayer.disabled") + "</span> "
                    + "<a href=\"" + HtmlUtils.htmlEscape(pageUrl) + "\">"
                    + HtmlUtils.htmlEscape(name) + "</a>";
        }

        int width = numberAttribute(params, "width", 550, 40, 4096);
        int height = numberAttribute(params, "height", 400, 40, 4096);
        StringBuilder html = new StringBuilder("<div class=\"flash-player\" data-flash-player=\"1\"");
        html.append(" data-flash-src=\"").append(escapeAttribute(src)).append('"');
        html.append(" data-flash-width=\"").append(width).append('"');
        html.append(" data-flash-height=\"").append(height).append('"');
        html.append(" data-flash-autoplay=\"").append(booleanAttribute(params, "autoplay", true)).append('"');
        html.append(" data-flash-loop=\"").append(booleanAttribute(params, "loop", false)).append('"');
        html.append(" data-flash-fullscreen=\"").append(booleanAttribute(params, "fullscreen", true)).append('"');
        html.append(" data-flash-letterbox=\"").append(booleanAttribute(params, "letterbox", true)).append('"');
        html.append(" data-flash-menu=\"").append(booleanAttribute(params, "menu", true)).append('"');
        html.append(" data-flash-bgcolor=\"").append(escapeAttribute(
                colorAttribute(params, "bgcolor", "#000000"))).append('"');
        html.append(" data-flash-quality=\"").append(choiceAttribute(params, "quality", "high",
                "low", "medium", "high", "best", "autolow", "autohigh", "autobest")).append('"');
        html.append(" data-flash-scale=\"").append(choiceAttribute(params, "scale", "showall",
                "showall", "noborder", "exactfit", "noscale")).append('"');
        html.append(" data-flash-wmode=\"").append(choiceAttribute(params, "wmode", "window",
                "window", "opaque", "transparent", "direct", "gpu")).append('"');
        html.append(" data-flash-allowscriptaccess=\"").append(choiceAttribute(params, "allowscriptaccess",
                "samedomain", "samedomain", "always", "never")).append('"');
        html.append(" data-flash-unmute=\"").append(choiceAttribute(params, "unmute", "overlay",
                "overlay", "on", "off")).append('"');
        String flashvars = params.getOrDefault("flashvars", "").trim();
        if (!flashvars.isEmpty()) {
            html.append(" data-flash-flashvars=\"")
                    .append(escapeAttribute(controlCharsStripped(flashvars))).append('"');
        }
        String base = params.getOrDefault("base", "").trim();
        if (!base.isEmpty()) {
            html.append(" data-flash-base=\"").append(escapeAttribute(base)).append('"');
        }
        html.append(" style=\"width:").append(width).append("px;max-width:100%\">");
        // 回退（脚本未加载时可见）：文件页链接 / 外部地址
        html.append("<a class=\"flash-player-fallback\" href=\"")
                .append(escapeAttribute(pageUrl)).append("\">")
                .append(HtmlUtils.htmlEscape(name)).append("</a>");
        if (!caption.isEmpty()) {
            html.append("<div class=\"flash-player-caption\">")
                    .append(HtmlUtils.htmlEscape(controlCharsStripped(caption))).append("</div>");
        }
        html.append("</div>");
        return html.toString();
    }

    // ==================== 移植自 core 的工具方法 ====================

    /** 标签属性（迁移前 core 用 {@code <flash-player …>} 的属性；不支持时用标签内容当说明）。 */
    private static String attributesText(ParserTagCall call) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : call.attributes().entrySet()) {
            text.append(' ').append(entry.getKey()).append("=\"").append(entry.getValue()).append('"');
        }
        return text.toString();
    }

    /** 解析属性（键转小写）。 */
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

    /** 数字属性（带范围限制；非法 / 缺省用 fallback）。 */
    private static int numberAttribute(Map<String, String> params, String key, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(params.getOrDefault(key, "").trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** 布尔属性：{@code true/1/yes/on} → true；{@code false/0/no/off} → false；其它用 fallback。 */
    private static boolean booleanAttribute(Map<String, String> params, String key, boolean fallback) {
        String value = params.getOrDefault(key, "").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return fallback;
        }
        if (value.equals("true") || value.equals("1") || value.equals("yes") || value.equals("on")) {
            return true;
        }
        if (value.equals("false") || value.equals("0") || value.equals("no") || value.equals("off")) {
            return false;
        }
        return fallback;
    }

    /** 白名单值（不在列表里用 fallback）。 */
    private static String choiceAttribute(Map<String, String> params, String key, String fallback, String... choices) {
        String value = params.getOrDefault(key, "").trim().toLowerCase(Locale.ROOT);
        for (String choice : choices) {
            if (choice.equals(value)) {
                return value;
            }
        }
        return fallback;
    }

    /** 颜色属性：只允许 {@code #rgb/#rrggbb} 与字母数字（防注入）。 */
    private static String colorAttribute(Map<String, String> params, String key, String fallback) {
        String value = params.getOrDefault(key, "").trim();
        return value.matches("#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?") ? value : fallback;
    }

    /** {@code [[文件:X.swf]]} / {@code 文件:X.swf} / {@code X.swf} → 文件名。 */
    static String flashFileName(String raw) {
        String value = raw.trim();
        if (value.startsWith("[[") && value.endsWith("]]")) {
            value = value.substring(2, value.length() - 2).trim();
        }
        int pipe = value.indexOf('|');
        if (pipe >= 0) {
            value = value.substring(0, pipe).trim();
        }
        int colon = value.indexOf(':');
        if (colon > 0) {
            String prefix = value.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (List.of("文件", "file", "image", "图像", "圖片").contains(prefix)) {
                value = value.substring(colon + 1).trim();
            }
        }
        return value.replace('_', ' ');
    }

    /** 去掉控制字符（属性值里理论上不会有，防注入 JSON / HTML）。 */
    private static String controlCharsStripped(String value) {
        return value.replaceAll("[\\p{Cntrl}]", " ");
    }

    /** 红链到不存在的文件页。 */
    private static String redFileLink(String name) {
        return "<a class=\"new\" href=\"/wiki/" + escapeAttribute("文件:" + name.replace(' ', '_'))
                + "\">" + HtmlUtils.htmlEscape(name) + "</a>";
    }

    private String error(String message) {
        return "<span class=\"wiki-error\">" + HtmlUtils.htmlEscape(message) + "</span>";
    }

    /**
     * 取本地化文案（文案在 {@code i18n/messages*.properties}，见 {@link FlashPlayerMessages}）；
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

    private String label(String key) {
        return HtmlUtils.htmlEscape(message(key));
    }

    /** 与迁移前 core 的 {@code HtmlSanitizer.escapeAttribute} 一致。 */
    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
