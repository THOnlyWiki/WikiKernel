package cc.thonly.wikikernel.plugin.easytimeline;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.util.HtmlUtils;

/**
 * {@code <timeline>…</timeline>}（EasyTimeline 子集）：解析定义并渲染成内联 SVG；
 * 失败显示错误提示（消息键 {@code timeline.invalid}，文案在 {@code i18n/messages*.properties}）；
 * 功能关闭（{@code features.timeline=false}）时按原文转义显示（与迁移前一致）。
 */
public class TimelineTag implements ParserTag {

    private final SiteConfig siteConfig;
    private final MessageSource messageSource;

    public TimelineTag(SiteConfig siteConfig, MessageSource messageSource) {
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
    }

    @Override
    public String name() {
        return "timeline";
    }

    /** 内容原样交给渲染器（不做 wikitext 解析，与 MediaWiki / 迁移前一致）。 */
    @Override
    public boolean expandBody() {
        return false;
    }

    @Override
    public String render(ParserTagCall call) {
        String body = call.body() == null ? "" : call.body();
        if (!siteConfig.getBoolean("features.timeline", true)) {
            return "<p>" + HtmlUtils.htmlEscape("<timeline>" + body + "</timeline>") + "</p>";
        }
        String svg = TimelineRenderer.render(body, message("timeline.label"));
        if (svg != null) {
            return svg;
        }
        return "<span class=\"wiki-error\">" + HtmlUtils.htmlEscape(message("timeline.invalid")) + "</span>";
    }

    /**
     * 取本地化文案（文案在 {@code i18n/messages*.properties}，见 {@link EasyTimelineMessages}）；
     * 没有消息源、core 与插件都没有这个键时显示翻译键本身（与 core 的消息源一致，不回落中文）。
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
}
