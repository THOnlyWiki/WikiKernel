package cc.thonly.wikikernel.plugin.popups;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;

/**
 * Popups 的样式与脚本注入：功能开启时注入
 * {@code /plugins/popups/popups.css}（浮层样式）与 {@code /plugins/popups/popups.js}（悬停预览），
 * 并把浮层里的本地化文案放进 {@code window.WIKI_MESSAGES}
 * （键见 {@link PopupsMessages} 的 {@code i18n/messages*.properties}）。
 * <p>
 * 站点级注入（{@code siteWide()=true}）：只有站点级片段才会插到 core 的 {@code window.WIKI_MESSAGES}
 * **之后**，脚本才能拿到下面注入的文案；{@code window.WIKI_CONFIG.popups} 开关本来就由 core 在所有页面注入。
 */
public class PopupsHeadProvider implements HtmlHeadProvider {

    private final SiteConfig siteConfig;
    private final MessageSource messageSource;

    public PopupsHeadProvider(SiteConfig siteConfig, MessageSource messageSource) {
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
    }

    @Override
    public int order() {
        return 500;
    }

    @Override
    public boolean siteWide() {
        return true;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (!siteConfig.getBoolean("features.popups", true)) {
            return List.of();
        }
        return List.of(
                // 自己声明前端开关（core 不再知道这个功能）：脚本读 window.WIKI_CONFIG.popups
                "<script>window.WIKI_CONFIG = Object.assign(window.WIKI_CONFIG || {}, {popups: true});</script>",
                "<link rel=\"stylesheet\" href=\"/plugins/popups/popups.css\">",
                "<script>window.WIKI_MESSAGES = Object.assign(window.WIKI_MESSAGES || {}, {"
                        + "popupsEmptyExtract: " + json(message("popups.emptyExtract")) + ","
                        + "popupsGoToPage: " + json(message("popups.goToPage"))
                        + "});</script>",
                "<script defer src=\"/plugins/popups/popups.js\"></script>");
    }

    /** 取当前语言的文案；没有消息源或漏翻时返回翻译键本身（与 core 的消息源一致，不回落中文）。 */
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

    /** 文案 → JSON 字符串字面量（注入 {@code window.WIKI_MESSAGES} 用）。 */
    private static String json(String value) {
        return "\"" + (value == null ? "" : value
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("<", "\\u003c").replace(">", "\\u003e")
                .replace("&", "\\u0026")) + "\"";
    }
}
