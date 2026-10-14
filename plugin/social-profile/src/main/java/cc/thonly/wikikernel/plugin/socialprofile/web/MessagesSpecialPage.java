package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.service.WikiText;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.socialprofile.SocialProfileSupport;
import cc.thonly.wikikernel.plugin.socialprofile.data.WikiMessage;
import cc.thonly.wikikernel.plugin.socialprofile.hook.MessagesToolbarHook;
import cc.thonly.wikikernel.plugin.socialprofile.service.MessageCenter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code 特殊:私信} / {@code Special:Messages}（插件特殊页）：收件箱 / 发件箱 / 会话 / 写私信 / 阅读。
 * <p>
 * 查询参数与迁移前 core 的 {@code MessagesSpecialPage} 完全一致：
 * {@code ?box=sent}（发件箱）、{@code ?box=conversations}（会话列表）、
 * {@code ?with=用户名}（与某人的往来，收发按时间拼在一起）、{@code ?id=123}（阅读单条，自动标已读）、
 * {@code ?write=1&to=用户名}（写私信）。模型键与迁出的模板一一对应。
 */
public class MessagesSpecialPage implements SpecialPage {

    private static final String PARAM_BOX = "box";
    private static final String PARAM_ID = "id";
    private static final String PARAM_WRITE = "write";
    private static final String PARAM_TO = "to";
    private static final String PARAM_WITH = "with";

    /** 列表摘要长度（与迁移前一致）。 */
    private static final int EXCERPT_LIMIT = 80;

    /** core 的 {@code PersonalToolbarAdvice} 收集个人工具条目的模型键（见 README「插件扩展点」）。 */
    private static final String TOOLBAR_MODEL_KEY = "pluginPersonalTools";

    private final MessageCenter messages;
    private final UserStore users;
    private final WikiText wikiText;
    private final SocialProfileSupport support;
    private final MessagesToolbarHook toolbar;

    public MessagesSpecialPage(MessageCenter messages, UserStore users, WikiText wikiText,
                               SocialProfileSupport support, MessagesToolbarHook toolbar) {
        this.messages = messages;
        this.users = users;
        this.wikiText = wikiText;
        this.support = support;
        this.toolbar = toolbar;
    }

    @Override
    public String name() {
        return "私信";
    }

    @Override
    public String displayName() {
        return support.message("special.messages");
    }

    @Override
    public List<String> aliases() {
        return List.of("Messages", "MyMessages", "PrivateMessages");
    }

    @Override
    public String category() {
        return SpecialPageCategories.LOGIN;
    }

    @Override
    public String template() {
        return "special/messages";
    }

    @Override
    public void populate(SpecialPageRequest request, Map<String, Object> model) {
        WikiUser user = request.user();
        model.put("messagesLogin", user == null);
        model.put("messagesMode", "inbox");
        boolean featureDisabled = !support.enabled();
        model.put("featureDisabled", featureDisabled);
        if (featureDisabled || user == null) {
            return;
        }
        model.put("unreadCount", messages.unreadCount(user.getId()));

        String write = Strings.trimToEmpty(request.parameters().get(PARAM_WRITE));
        if (!write.isEmpty()) {
            model.put("messagesMode", "compose");
            model.put("compose", true);
            model.put("composeTo", Strings.trimToEmpty(request.parameters().get(PARAM_TO)));
            return;
        }

        // 会话：与某人的收发记录按时间拼在一起（打开会话即把对方发来的未读标为已读）
        String with = Strings.trimToEmpty(request.parameters().get(PARAM_WITH));
        if (!with.isEmpty()) {
            populateThread(user, with, model);
            return;
        }

        String idValue = Strings.trimToEmpty(request.parameters().get(PARAM_ID));
        if (!idValue.isEmpty()) {
            long id;
            try {
                id = Long.parseLong(idValue);
            } catch (NumberFormatException exception) {
                model.put("messageNotFound", true);
                return;
            }
            WikiMessage message = messages.view(user.getId(), id).orElse(null);
            if (message == null) {
                model.put("messageNotFound", true);
                return;
            }
            Set<Long> ids = new LinkedHashSet<>(List.of(message.getFromUserId(), message.getToUserId()));
            Map<Long, String> names = messages.names(ids);
            boolean recipient = user.getId().equals(message.getToUserId());
            Long otherId = recipient ? message.getFromUserId() : message.getToUserId();
            String otherName = names.getOrDefault(otherId, "#" + otherId);
            model.put("messagesMode", "view");
            model.put("messageView", true);
            model.put("messageId", message.getId());
            model.put("fromName", names.getOrDefault(message.getFromUserId(), "#" + message.getFromUserId()));
            model.put("toName", names.getOrDefault(message.getToUserId(), "#" + message.getToUserId()));
            model.put("messageInbox", recipient);
            model.put("messageOther", otherName);
            model.put("messageOtherUrl", SocialProfileSupport.pagePath("用户:" + otherName));
            model.put("messageTime", formatTime(message.getCreateAt()));
            model.put("messageHtml", render(message.getContent()));
            // view() 可能刚把这条标成已读：刷新顶部「私信 (N)」徽标
            refreshToolbarBadge(model, messages.unreadCount(user.getId()));
        }

        boolean sentBox = "sent".equalsIgnoreCase(Strings.trimToEmpty(request.parameters().get(PARAM_BOX)));
        boolean conversations = "conversations".equalsIgnoreCase(
                Strings.trimToEmpty(request.parameters().get(PARAM_BOX)));
        if (conversations) {
            populateConversations(user, model);
            return;
        }
        List<WikiMessage> rows = sentBox ? messages.sent(user.getId()) : messages.inbox(user.getId());
        Set<Long> ids = new LinkedHashSet<>();
        for (WikiMessage message : rows) {
            ids.add(sentBox ? message.getToUserId() : message.getFromUserId());
        }
        Map<Long, String> names = messages.names(ids);
        List<Map<String, Object>> messageRows = new ArrayList<>();
        for (WikiMessage message : rows) {
            Long otherId = sentBox ? message.getToUserId() : message.getFromUserId();
            String otherName = names.getOrDefault(otherId, "#" + otherId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", message.getId());
            row.put("other", otherName);
            row.put("otherUrl", SocialProfileSupport.pagePath("用户:" + otherName));
            row.put("timeText", formatTime(message.getCreateAt()));
            row.put("unread", !sentBox && !Boolean.TRUE.equals(message.getRead()));
            row.put("excerpt", excerpt(message.getContent()));
            messageRows.add(row);
        }
        model.put("messageBoxSent", sentBox);
        model.put("messagesMode", sentBox ? "sent" : "inbox");
        model.put("messageRows", messageRows);
    }

    /** 与某人的往来：两个方向的私信按时间拼在一起（打开会话即已读）。 */
    private void populateThread(WikiUser user, String otherName, Map<String, Object> model) {
        model.put("messagesMode", "thread");
        UserStore.UserRef other = users.byName(otherName).orElse(null);
        if (other == null) {
            model.put("messageNotFound", true);
            model.put("threadRows", List.of());
            return;
        }
        messages.markConversationRead(user.getId(), other.id());
        model.put("threadPeer", other.username());
        model.put("threadPeerUrl", SocialProfileSupport.pagePath("用户:" + other.username()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WikiMessage message : messages.conversation(user.getId(), other.id())) {
            boolean mine = user.getId().equals(message.getFromUserId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", message.getId());
            row.put("mine", mine);
            row.put("timeText", formatTime(message.getCreateAt()));
            row.put("contentHtml", render(message.getContent()));
            rows.add(row);
        }
        model.put("threadRows", rows);
        // 打开会话刚把未读清零：刷新顶部「私信 (N)」徽标
        refreshToolbarBadge(model, messages.unreadCount(user.getId()));
    }

    /**
     * 用最新未读数刷新顶部「私信 (N)」条目：core 的个人工具条目在进入控制器**之前**就已收集，
     * 而本页会立刻把未读标为已读，所以这里替换掉同一次响应里的那一条（其它插件的条目原样保留）。
     */
    private void refreshToolbarBadge(Map<String, Object> model, long unread) {
        model.put("unreadCount", unread);
        if (toolbar == null) {
            return;
        }
        String fresh = toolbar.itemHtml(unread);
        Object existing = model.get(TOOLBAR_MODEL_KEY);
        List<String> items = new ArrayList<>();
        boolean replaced = false;
        if (existing instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof String html)) {
                    continue;
                }
                if (html.contains(MessagesToolbarHook.ITEM_CLASS)) {
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

    /** 「会话」标签：按对方聚合的往来列表（最后一条 / 条数 / 未读），按最后一条消息倒序。 */
    private void populateConversations(WikiUser user, Map<String, Object> model) {
        model.put("messagesMode", "conversations");
        Map<Long, Map<String, Object>> byPeer = new LinkedHashMap<>();
        for (WikiMessage message : messages.allVisible(user.getId())) {
            boolean mine = user.getId().equals(message.getFromUserId());
            Long peerId = mine ? message.getToUserId() : message.getFromUserId();
            Map<String, Object> row = byPeer.get(peerId);
            if (row == null) {
                row = new LinkedHashMap<>();
                row.put("peerId", peerId);
                row.put("count", 0);
                row.put("unread", 0);
                byPeer.put(peerId, row);
            }
            row.put("count", (Integer) row.get("count") + 1);
            if (!mine && !Boolean.TRUE.equals(message.getRead())) {
                row.put("unread", (Integer) row.get("unread") + 1);
            }
            // allVisible 按时间升序：后面的覆盖「最后一条」
            row.put("lastId", message.getId());
            row.put("excerpt", excerpt(message.getContent()));
            row.put("timeText", formatTime(message.getCreateAt()));
        }
        Map<Long, String> names = messages.names(byPeer.keySet());
        List<Map<String, Object>> rows = new ArrayList<>(byPeer.values());
        for (Map<String, Object> row : rows) {
            Long peerId = (Long) row.get("peerId");
            String peerName = names.getOrDefault(peerId, "#" + peerId);
            row.put("peer", peerName);
            row.put("peerUrl", SocialProfileSupport.pagePath("用户:" + peerName));
        }
        rows.sort((left, right) -> Long.compare((Long) right.get("lastId"), (Long) left.get("lastId")));
        model.put("conversationRows", rows);
    }

    /** 私信正文按 wikitext 渲染（与页面正文同一套渲染器）。 */
    private String render(String content) {
        return wikiText == null ? Strings.trimToEmpty(content) : wikiText.toHtml(content, "特殊:私信");
    }

    /** 时间显示：走消息键 {@code format.dateTime}（core 的本地化格式）。 */
    private String formatTime(LocalDateTime time) {
        String pattern = support.message("format.dateTime", "yyyy-MM-dd HH:mm");
        if (time == null) {
            return "";
        }
        try {
            return time.format(DateTimeFormatter.ofPattern(pattern));
        } catch (IllegalArgumentException exception) {
            return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
    }

    private static String excerpt(String content) {
        String text = Strings.trimToEmpty(content).replaceAll("\\s+", " ");
        return text.length() <= EXCERPT_LIMIT ? text : text.substring(0, EXCERPT_LIMIT) + "…";
    }
}
