package cc.thonly.wikikernel.plugin.echo.service;

import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.plugin.echo.EchoKeys;
import cc.thonly.wikikernel.plugin.echo.EchoSupport;
import cc.thonly.wikikernel.plugin.echo.data.WikiNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知 → 展示数据（特殊:通知 页与顶栏下拉面板共用，保证两处文案 / 链接一致）：
 * 每条通知生成 {@code id / type / typeLabel / read / timeText / html / pageUrl / actorUrl} 等字段，
 * 其中 {@code html} 是**服务端拼好的描述**（用户名 / 页面标题都已转义，链接指向站内页面），
 * 前端（Thymeleaf 或 echo.js）直接插入即可。
 */
@Service
public class EchoView {

    private final EchoNotificationStoreJdbc store;
    private final UserStore userStore;
    private final EchoSupport support;

    @Autowired
    public EchoView(EchoNotificationStoreJdbc store, UserStore userStore, EchoSupport support) {
        this.store = store;
        this.userStore = userStore;
        this.support = support;
    }

    /** 一条通知的展示数据。 */
    public Map<String, Object> view(WikiNotification notification) {
        Map<String, Object> row = new LinkedHashMap<>();
        String type = notification.getType() == null ? "" : notification.getType();
        String pageTitle = notification.getPageTitle();
        String actorName = actorName(notification.getActorUserId());
        row.put("id", notification.getId());
        row.put("type", type);
        row.put("typeLabel", support.message("echo.type." + type, type));
        row.put("icon", iconOf(type));
        row.put("read", Boolean.TRUE.equals(notification.getRead()));
        row.put("timeText", support.formatTime(notification.getCreateAt()));
        row.put("pageTitle", pageTitle);
        row.put("pageUrl", pageTitle == null ? null : EchoSupport.pagePath(pageTitle));
        row.put("actorName", actorName);
        row.put("actorUrl", actorName == null ? null : EchoSupport.pagePath("用户:" + actorName));
        row.put("comment", notification.getComment());
        row.put("html", describe(notification, type, actorName, pageTitle));
        return row;
    }

    /** 一批通知的展示数据（跳过 null）。 */
    public List<Map<String, Object>> views(List<WikiNotification> notifications) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiNotification notification : notifications) {
            if (notification != null) {
                rows.add(view(notification));
            }
        }
        return rows;
    }

    /** 按类型过滤 + 翻页的通知展示数据（特殊:通知 页用）。 */
    public List<Map<String, Object>> list(long userId, String type, int offset, int limit) {
        return views(store.list(userId, type, offset, limit));
    }

    /** 未读数（顶栏 / 特殊页用）。 */
    public long unreadCount(long userId) {
        return store.unreadCount(userId);
    }

    /** 未读数（按分组：alert / notice；空 = 全部）。 */
    public long unreadCount(long userId, String group) {
        return store.unreadCount(userId, EchoKeys.typesOf(group));
    }

    /** 按分组过滤 + 翻页的通知展示数据（顶栏面板用）。 */
    public List<Map<String, Object>> listByGroup(long userId, String group, int offset, int limit) {
        return views(store.list(userId, EchoKeys.typesOf(group), offset, limit));
    }

    // ==================== 描述文案 ====================

    private String describe(WikiNotification notification, String type, String actorName, String pageTitle) {
        String actor = actorName == null
                ? escape(support.message("echo.actor.system", "系统"))
                : "<a href=\"" + EchoSupport.pagePath("用户:" + actorName) + "\">" + escape(actorName) + "</a>";
        String page = pageTitle == null
                ? null
                : "<a href=\"" + EchoSupport.pagePath(pageTitle) + "\">" + escape(pageTitle) + "</a>";
        String summary = notification.getComment();
        StringBuilder html = new StringBuilder();
        switch (type) {
            case EchoKeys.TYPE_EDIT_USER_TALK -> html.append(actor)
                    .append(" ").append(escape(support.message("echo.desc.editUserTalk", null)));
            case EchoKeys.TYPE_MENTION -> html.append(actor)
                    .append(" ").append(escape(support.message("echo.desc.mention.before", null)))
                    .append(" ").append(page == null ? "" : page)
                    .append(" ").append(escape(support.message("echo.desc.mention.after", null)));
            case EchoKeys.TYPE_REVERT -> html.append(actor)
                    .append(" ").append(escape(support.message("echo.desc.revert.before", null)))
                    .append(" ").append(page == null ? "" : page)
                    .append(" ").append(escape(support.message("echo.desc.revert.after", null)));
            case EchoKeys.TYPE_WELCOME -> html.append(escape(
                    support.message("echo.desc.welcome", "欢迎来到本 Wiki！祝您编辑愉快。")));
            default -> html.append(actor).append(" ").append(escape(support.message("echo.desc.generic", null)));
        }
        if (summary != null && !summary.isBlank()) {
            html.append("<span class=\"echo-summary\">（").append(escape(summary)).append("）</span>");
        }
        return html.toString();
    }

    private String iconOf(String type) {
        // 用不依赖图标的字符（所有皮肤都能显示；kawaii 皮肤自带的 Material 图标只覆盖铃铛本身）
        return switch (type) {
            case EchoKeys.TYPE_EDIT_USER_TALK -> "💬";
            case EchoKeys.TYPE_MENTION -> "@";
            case EchoKeys.TYPE_REVERT -> "↩";
            case EchoKeys.TYPE_WELCOME -> "🎉";
            default -> "🔔";
        };
    }

    private String actorName(Long actorUserId) {
        if (actorUserId == null) {
            return null;
        }
        return userStore.byId(actorUserId).map(UserStore.UserRef::username).orElse(null);
    }

    private static String escape(String value) {
        return EchoSupport.escape(value);
    }
}