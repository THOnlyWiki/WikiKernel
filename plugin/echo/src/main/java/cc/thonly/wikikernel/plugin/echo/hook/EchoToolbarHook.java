package cc.thonly.wikikernel.plugin.echo.hook;

import cc.thonly.wikikernel.common.extension.PersonalToolbarContext;
import cc.thonly.wikikernel.common.extension.PersonalToolbarHook;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.plugin.echo.EchoKeys;
import cc.thonly.wikikernel.plugin.echo.EchoSupport;
import cc.thonly.wikikernel.plugin.echo.service.EchoNotificationStoreJdbc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 顶栏个人工具区的 Echo 入口（仿 MediaWiki Echo 的 {@code pt-notifications-alert} +
 * {@code pt-notifications-notice}，与线上站一致的两个条目）：未登录不显示。
 * <ul>
 *     <li><b>提醒</b>（{@code .echo-entry.echo-alerts}，铃铛图标 {@code notifications}）：
 *     提及 / 用户讨论页被编辑 / 被回退；</li>
 *     <li><b>一般通知</b>（{@code .echo-entry.echo-notices}，托盘图标 {@code inbox}）：欢迎等站点消息。</li>
 * </ul>
 * 通过 {@link PersonalToolbarHook} 贡献，core 会把返回的 {@code <li>} 追加到所有皮肤的个人工具区
 * （kawaii-timeless 的 {@code #personal-extra}、plain 的 {@code .plain-personal}、
 * vector / fallback 的 {@code #p-personal}）。
 * <p>
 * 每个条目带未读红点（{@code <span class="echo-badge">} 仅在有未读时输出）与自己的下拉面板容器
 * （{@code data-group} 标明分组；面板内容由前端 {@code /plugins/echo/echo.js} 拉取
 * {@code GET /api/echo/list?group=…} 填充，样式见 {@code /plugins/echo/echo.css}）。
 */
@Component
public final class EchoToolbarHook implements PersonalToolbarHook {

    /** 条目的稳定类名（前端 / 特殊:通知 页刷新徽标时按它替换旧条目）。 */
    public static final String ITEM_CLASS = "echo-entry";

    /** 未读徽标类名（皮肤 CSS 的红点规则按它显示）。 */
    public static final String BADGE_CLASS = "echo-badge";

    /** 提醒条目类名。 */
    public static final String ALERTS_CLASS = "echo-alerts";

    /** 一般通知条目类名。 */
    public static final String NOTICES_CLASS = "echo-notices";

    private final EchoNotificationStoreJdbc store;
    private final EchoSupport support;

    @Autowired
    public EchoToolbarHook(EchoNotificationStoreJdbc store, EchoSupport support) {
        this.store = store;
        this.support = support;
    }

    @Override
    public int order() {
        return 90; // 在 私信(100) 之前：两个 Echo 条目放最左（搜索框旁）
    }

    @Override
    public String itemsHtml(PersonalToolbarContext context) {
        if (!support.enabled() || context == null) {
            return null;
        }
        Viewer viewer = context.viewer();
        if (viewer == null || !viewer.loggedIn()) {
            return null;
        }
        long alerts = store.unreadCount(viewer.id(), EchoKeys.ALERT_TYPES);
        long notices = store.unreadCount(viewer.id(), EchoKeys.NOTICE_TYPES);
        return itemHtml(EchoKeys.GROUP_ALERT, alerts) + itemHtml(EchoKeys.GROUP_NOTICE, notices);
    }

    /** 一个 Echo 条目（提醒 / 一般通知）的 HTML；{@code group} = {@code alert} / {@code notice}。 */
    public String itemHtml(String group, long unread) {
        boolean alert = !EchoKeys.GROUP_NOTICE.equals(group);
        String entryClass = alert ? ALERTS_CLASS : NOTICES_CLASS;
        String title = support.message(alert ? "echo.alerts.title" : "echo.notices.title",
                alert ? "提醒" : "一般通知");
        StringBuilder html = new StringBuilder();
        html.append("<li class=\"").append(ITEM_CLASS).append(' ').append(entryClass)
                .append("\" data-group=\"").append(group).append("\"><a class=\"echo-bell\" href=\"")
                .append(EchoSupport.pagePath("特殊:通知"))
                .append("\" title=\"").append(EchoSupport.escape(title))
                .append("\"><span>").append(EchoSupport.escape(title))
                .append("</span>");
        if (unread > 0) {
            html.append("<span class=\"").append(BADGE_CLASS).append("\">").append(unread).append("</span>");
        }
        html.append("</a><div class=\"echo-flyout\" hidden data-group=\"").append(group)
                .append("\"><div class=\"echo-flyout-head\"><span>")
                .append(EchoSupport.escape(title))
                .append("</span><a class=\"echo-mark-all\" href=\"javascript:void(0)\">")
                .append(EchoSupport.escape(support.message("echo.markAllRead")))
                .append("</a></div>")
                .append("<div class=\"echo-flyout-list\"></div>")
                .append("<div class=\"echo-flyout-foot\"><a href=\"")
                .append(EchoSupport.pagePath("特殊:通知"))
                .append("\">").append(EchoSupport.escape(support.message("echo.viewAll")))
                .append("</a></div></div></li>");
        return html.toString();
    }

    /** 刷新两个条目（特殊:通知 页把未读清零时替换同一次响应里的旧条目）。 */
    public String refreshItemHtml(long alerts, long notices) {
        return itemHtml(EchoKeys.GROUP_ALERT, alerts) + itemHtml(EchoKeys.GROUP_NOTICE, notices);
    }

    /** 前端未读徽标的选择器（JS / 样式复用）。 */
    public static String badgeSelector() {
        return "." + BADGE_CLASS;
    }
}