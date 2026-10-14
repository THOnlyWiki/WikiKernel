package cc.thonly.wikikernel.plugin.flashplayer;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.common.service.SiteConfig;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;

/**
 * {@code <flash-player>} 的前端脚本（懒加载 Ruffle WASM）：
 * 功能开启时注入 {@code /plugins/flash-player/flash-player.js}
 * （脚本内部再从 core 的 {@code /vendor/ruffle/…} 懒加载 Ruffle，页面里没有 Flash 时不下载 wasm）。
 * <p>
 * 站点级注入（{@code siteWide()=true}）：只有站点级片段才会插到 core 的
 * {@code window.WIKI_MESSAGES} **之后**，脚本才能拿到下面注入的本地化文案
 * （键见 {@link FlashPlayerMessages} 的 {@code i18n/messages*.properties}）；
 * {@code window.WIKI_CONFIG.flashPlayer} 开关本来就由 core 在所有页面注入。
 */
public class FlashPlayerHeadProvider implements HtmlHeadProvider {

    private final SiteConfig siteConfig;
    private final MessageSource messageSource;

    public FlashPlayerHeadProvider(SiteConfig siteConfig, MessageSource messageSource) {
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
        if (!siteConfig.getBoolean("features.flash-player", true)) {
            return List.of();
        }
        return List.of(
                // 鑷繁澹版槑鍓嶇寮€鍏筹紙core 涓嶅啀鐭ラ亾杩欎釜鍔熻兘锛夛細鑴氭湰璇?window.WIKI_CONFIG.flashPlayer
                "<script>window.WIKI_CONFIG = Object.assign(window.WIKI_CONFIG || {}, {flashPlayer: true});</script>",
                "<script>window.WIKI_MESSAGES = Object.assign(window.WIKI_MESSAGES || {}, {"
                        + "flashPlayerPlayFailed: " + json(message("flashPlayer.playFailed")) + ","
                        + "flashPlayerClickToPlay: " + json(message("flashPlayer.clickToPlay")) + ","
                        + "flashPlayerRuffleNotReady: " + json(message("flashPlayer.ruffleNotReady")) + ","
                        + "flashPlayerLoadFailed: " + json(message("flashPlayer.loadFailed"))
                        + "});</script>",
                "<script defer src=\"/plugins/flash-player/flash-player.js\"></script>");
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
