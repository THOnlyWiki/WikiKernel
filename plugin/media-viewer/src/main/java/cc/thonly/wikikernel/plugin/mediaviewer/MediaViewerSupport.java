package cc.thonly.wikikernel.plugin.mediaviewer;

import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.util.HtmlUtils;

/**
 * 多媒体预览的公共部分：功能开关与本地化文案（播放器 / 查看器的按钮文案经
 * {@code window.WIKI_MESSAGES} 传给前端脚本）。
 */
public class MediaViewerSupport {

    private final SiteConfig siteConfig;
    private final MessageSource messageSource;

    public MediaViewerSupport(SiteConfig siteConfig, MessageSource messageSource) {
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
    }

    /** 多媒体预览是否开启（站点设置 {@code features.media-viewer}，默认关闭）。 */
    public boolean enabled() {
        return siteConfig != null && siteConfig.getBoolean(MediaViewerPlugin.ENABLED_KEY, false);
    }

    /**
     * 取当前语言的文案（core 的 MessageSource 已合并插件消息）；缺失时**显示翻译键本身**
     * （与 core 的「漏翻显示键名」策略一致，不会回落到中文）。
     */
    public String message(String key, Object... args) {
        if (messageSource == null) {
            return key;
        }
        try {
            String value = messageSource.getMessage(key, args.length == 0 ? null : args, key,
                    LocaleContextHolder.getLocale());
            return value == null || value.isBlank() ? key : value;
        } catch (RuntimeException exception) {
            return key;
        }
    }

    /** 文案 → JSON 字符串字面量（注入 {@code window.WIKI_MESSAGES} 用）。 */
    public String json(String key, Object... args) {
        return "\"" + jsonEscape(message(key, args)) + "\"";
    }

    static String jsonEscape(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** HTML 属性 / 文本转义（播放器占位用）。 */
    static String escapeAttribute(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value).replace("\"", "&quot;");
    }
}
