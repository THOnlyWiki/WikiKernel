package cc.thonly.wikikernel.plugin.echo.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.plugin.echo.EchoSupport;

import java.util.List;

/**
 * Echo 的前端资源（站点级注入：铃铛样式 / 下拉面板脚本 / 通知页脚本，所有皮肤都要）：
 * <ul>
 *     <li>{@code /plugins/echo/echo.css}：铃铛图标（自带 Material Icons 字体，皮肤无关）、
 *     未读红点、下拉面板；</li>
 *     <li>{@code /plugins/echo/echo.js}：铃铛下拉开合、轮询未读数、标记已读、全部标为已读、通知页交互；</li>
 *     <li>{@code window.WIKI_MESSAGES} 的 Echo 文案。</li>
 * </ul>
 * 不依赖任何皮肤的私有 CSS / JS：kawaii-timeless 与 plain / vector / fallback 都只靠
 * {@code PersonalToolbarHook} 输出的类名（{@code .echo-entry} / {@code .echo-bell} / {@code .echo-badge} /
 * {@code .echo-flyout}）即可工作。
 */
public final class EchoHeadProvider implements HtmlHeadProvider {

    private final EchoSupport support;

    public EchoHeadProvider(EchoSupport support) {
        this.support = support;
    }

    @Override
    public int order() {
        return 630; // 在 social-profile(640) 之前
    }

    @Override
    public boolean siteWide() {
        return true;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (!support.enabled()) {
            return List.of();
        }
        return List.of(
                "<link rel=\"stylesheet\" href=\"/plugins/echo/echo.css\">",
                "<script>window.WIKI_MESSAGES = Object.assign(window.WIKI_MESSAGES || {}, {"
                        + "echoMarkAllRead: " + EchoSupport.json(support.message("echo.markAllRead")) + ","
                        + "echoViewAll: " + EchoSupport.json(support.message("echo.viewAll")) + ","
                        + "echoEmpty: " + EchoSupport.json(support.message("echo.empty")) + ","
                        + "echoNetworkError: " + EchoSupport.json(support.message("echo.networkError")) + ","
                        + "echoOperationFailed: " + EchoSupport.json(support.message("echo.operationFailed")) + ","
                        + "echoMarkRead: " + EchoSupport.json(support.message("echo.markRead")) + ","
                        + "echoJumpTo: " + EchoSupport.json(support.message("echo.jumpTo")) + ","
                        + "echoPreferencesSaved: " + EchoSupport.json(support.message("echo.preferences.saved"))
                        + "});</script>",
                "<script defer src=\"/plugins/echo/echo.js\"></script>");
    }
}