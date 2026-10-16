package cc.thonly.wikikernel.plugin.echo.web;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.echo.EchoKeys;
import cc.thonly.wikikernel.plugin.echo.EchoSupport;
import cc.thonly.wikikernel.plugin.echo.hook.EchoToolbarHook;
import cc.thonly.wikikernel.plugin.echo.service.EchoNotificationStoreJdbc;
import cc.thonly.wikikernel.plugin.echo.service.EchoView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:通知} / {@code Special:Notifications}（仿 MediaWiki Echo 的 {@code Special:Notifications}）：
 * <ul>
 *     <li>{@code ?type=}：按通知类型过滤（{@code edit-user-talk} / {@code mention} / {@code revert} / {@code welcome}）；</li>
 *     <li>{@code ?offset=}：翻页（每页 {@link EchoKeys#PAGE_SIZE} 条）；</li>
 *     <li>打开本页**不自动全部已读**（与 MediaWiki 一致；顶部有「全部标为已读」按钮，单条可点「标为已读」）。</li>
 * </ul>
 * 模型键：{@code notificationsLogin} / {@code featureDisabled} / {@code notifications} / {@code notificationTypes}
 * / {@code currentType} / {@code unreadCount} / {@code total} / {@code offset} / {@code pageSize} / {@code hasNext} /
 * {@code hasPrev} / {@code prevOffset} / {@code nextOffset}。
 */
public class NotificationsSpecialPage implements SpecialPage {

    private static final String PARAM_TYPE = "type";
    private static final String PARAM_OFFSET = "offset";

    /** core 的 {@code PersonalToolbarAdvice} 收集个人工具条目的模型键。 */
    private static final String TOOLBAR_MODEL_KEY = "pluginPersonalTools";

    private final EchoNotificationStoreJdbc store;
    private final EchoView view;
    private final EchoSupport support;
    private final EchoToolbarHook toolbar;
    private final cc.thonly.wikikernel.plugin.echo.service.EchoNotifier notifier;

    public NotificationsSpecialPage(EchoNotificationStoreJdbc store, EchoView view, EchoSupport support,
                                    EchoToolbarHook toolbar,
                                    cc.thonly.wikikernel.plugin.echo.service.EchoNotifier notifier) {
        this.store = store;
        this.view = view;
        this.support = support;
        this.toolbar = toolbar;
        this.notifier = notifier;
    }

    @Override
    public String name() {
        return "通知";
    }

    @Override
    public String displayName() {
        return support.message("echo.title");
    }

    @Override
    public List<String> aliases() {
        return List.of("Notifications", "Notification", "Echo");
    }

    @Override
    public String category() {
        return SpecialPageCategories.LOGIN;
    }

    @Override
    public String template() {
        return "special/notifications";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        var user = request.user();
        model.put("notificationsLogin", user == null);
        boolean disabled = !support.enabled();
        model.put("featureDisabled", disabled);
        if (disabled || user == null) {
            return;
        }
        String type = normalizeType(Strings.trimToEmpty(request.parameters().get(PARAM_TYPE)));
        int offset = parseOffset(Strings.trimToEmpty(request.parameters().get(PARAM_OFFSET)));
        notifier.ensureWelcome(user.getId()); // 新用户在首次打开通知页时补发欢迎
        model.put("notificationUserId", user.getId());
        long total = store.count(user.getId(), type);
        List<Map<String, Object>> notifications = view.list(user.getId(), type, offset, EchoKeys.PAGE_SIZE);
        model.put("notifications", notifications);
        model.put("notificationTypes", typeOptions(type));
        model.put("currentType", type == null ? "" : type);
        model.put("unreadCount", view.unreadCount(user.getId()));
        model.put("total", total);
        model.put("offset", offset);
        model.put("pageSize", EchoKeys.PAGE_SIZE);
        model.put("hasPrev", offset > 0);
        model.put("hasNext", offset + EchoKeys.PAGE_SIZE < total);
        model.put("prevOffset", Math.max(0, offset - EchoKeys.PAGE_SIZE));
        model.put("nextOffset", offset + EchoKeys.PAGE_SIZE);
        // 本页不自动标已读：顶部铃铛的未读数与库里一致，无需替换
        model.put("notificationEmpty", notifications.isEmpty());
        refreshToolbarBadge(model);
    }

    /** 用库里的未读数覆盖个人工具区的两个铃铛条目（保证同一次响应里徽标一致）。 */
    private void refreshToolbarBadge(Map<String, Object> model) {
        if (toolbar == null) {
            return;
        }
        Long userId = model.get("notificationUserId") instanceof Long value ? value : null;
        if (userId == null) {
            return;
        }
        String fresh = toolbar.refreshItemHtml(
                view.unreadCount(userId, EchoKeys.GROUP_ALERT),
                view.unreadCount(userId, EchoKeys.GROUP_NOTICE));
        Object existing = model.get(TOOLBAR_MODEL_KEY);
        List<String> items = new ArrayList<>();
        boolean replaced = false;
        if (existing instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof String html)) {
                    continue;
                }
                if (html.contains(EchoToolbarHook.ITEM_CLASS)) {
                    if (!replaced) {
                        items.add(fresh);
                        replaced = true;
                    }
                    continue;
                }
                items.add(html);
            }
        }
        if (!replaced) {
            items.add(fresh);
        }
        model.put(TOOLBAR_MODEL_KEY, items);
    }

    /** 过滤标签（全部 + 每类）。 */
    private List<Map<String, Object>> typeOptions(String current) {
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(option("", support.message("echo.filter.all"), current == null));
        for (String type : EchoKeys.TYPES) {
            options.add(option(type, support.message("echo.type." + type, type), type.equals(current)));
        }
        return options;
    }

    private static Map<String, Object> option(String value, String label, boolean selected) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("value", value);
        option.put("label", label);
        option.put("selected", selected);
        return option;
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        return EchoKeys.TYPES.contains(type) ? type : null;
    }

    private static int parseOffset(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}