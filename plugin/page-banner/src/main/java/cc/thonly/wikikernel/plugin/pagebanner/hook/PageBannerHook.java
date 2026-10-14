package cc.thonly.wikikernel.plugin.pagebanner.hook;

import cc.thonly.wikikernel.common.extension.PageDisplayContext;
import cc.thonly.wikikernel.common.extension.PageDisplayHook;
import cc.thonly.wikikernel.plugin.pagebanner.PageBannerSettings;
import cc.thonly.wikikernel.plugin.pagebanner.service.PageBannerRenderer;
import java.util.List;

/**
 * 页面横幅的显示钩子（仿 MediaWiki 的 {@code BeforePageDisplay} 钩子）：
 * <ul>
 *     <li>{@link #beforeTitle}：读本次渲染写入的页面属性
 *     （{@code pagebanner-file} / {@code pagebanner-caption} / {@code pagebanner-exclude}）
 *     与站点设置，命中时返回横幅 HTML（多张图片 = 轮播容器；core 把它放进标题上方的
 *     {@code #page-banner} 容器里）；不显示返回 {@code null}；</li>
 *     <li>{@link #headItems}：本页**可能**显示横幅时注入插件样式
 *     {@code /plugins/page-banner/page-banner.css}；本页确有**多张**图片时再注入轮播脚本
 *     {@code /plugins/page-banner/page-banner.js}（{@code defer}，且只在这一页），
 *     并用 {@code window.WIKI_MESSAGES} 补齐本插件的前端文案。</li>
 * </ul>
 * 页面属性是本次渲染的结果：模板 / 解析器函数里写的 {@code {{PAGEBANNER:…}}} 立即生效，无需先保存页面。
 * <p>
 * 控件文案（上一张 / 下一张 / 指示点 / 状态）通过 {@code data-*} 属性交给脚本，脚本里不写死任何文案：
 * 文案取不到时用翻译键本身，脚本再回退到 {@code window.WIKI_MESSAGES}。
 */
public class PageBannerHook implements PageDisplayHook {

    private final PageBannerRenderer renderer;
    private final PageBannerMessages messages;

    public PageBannerHook(PageBannerRenderer renderer) {
        this(renderer, null);
    }

    /**
     * @param renderer 横幅 HTML 生成器（轮播配置由渲染器的 {@link PageBannerSettings} 提供）
     * @param messages 文案（可空：缺键时用翻译键本身）
     */
    public PageBannerHook(PageBannerRenderer renderer, PageBannerMessages messages) {
        this.renderer = renderer;
        this.messages = messages;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public String beforeTitle(PageDisplayContext context) {
        if (context == null) {
            return null;
        }
        return renderer.htmlForProperties(context.title(), context.properties(), texts());
    }

    @Override
    public List<String> headItems(PageDisplayContext context) {
        if (context == null
                || !renderer.mayDisplay(context.title(), PageBannerRenderer.excluded(context.properties()))) {
            return List.of();
        }
        // 本页实际会显示的图片数决定要不要加载轮播脚本（一张 = 静态横幅，脚本不注入）
        boolean carousel = renderer.slidesForProperties(context.title(), context.properties()).size() > 1;
        if (!carousel) {
            return List.of("<link rel=\"stylesheet\" href=\"" + PageBannerRenderer.STYLESHEET + "\">");
        }
        return List.of(
                "<link rel=\"stylesheet\" href=\"" + PageBannerRenderer.STYLESHEET + "\">",
                "<script>" + messagesScript() + "</script>",
                "<script defer src=\"" + PageBannerRenderer.SCRIPT + "\"></script>");
    }

    /** 文案来源（控件文案经 {@code data-*} 属性传给脚本；漏翻时用翻译键本身）。 */
    private PageBannerRenderer.TextProvider texts() {
        return this::message;
    }

    /** 本插件的前端文案（键见 i18n；漏翻时返回键名，脚本再回退到键名本身）。 */
    private String message(String key) {
        return messages == null ? key : messages.text(key);
    }

    /** 注入 {@code window.WIKI_MESSAGES}：轮播脚本用到的文案（键与 {@code i18n/messages*.properties} 一致）。 */
    private String messagesScript() {
        return "window.WIKI_MESSAGES = Object.assign(window.WIKI_MESSAGES || {}, {"
                + "pageBannerPrev: " + json(message(PageBannerRenderer.PREV_LABEL_KEY)) + ","
                + "pageBannerNext: " + json(message(PageBannerRenderer.NEXT_LABEL_KEY)) + ","
                + "pageBannerGoto: " + json(message(PageBannerRenderer.GOTO_LABEL_KEY)) + ","
                + "pageBannerStatus: " + json(message(PageBannerRenderer.STATUS_LABEL_KEY))
                + "});";
    }

    /** 文案 → JSON 字符串字面量（注入 {@code window.WIKI_MESSAGES} 用）。 */
    private static String json(String value) {
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
        return "\"" + out + "\"";
    }
}
