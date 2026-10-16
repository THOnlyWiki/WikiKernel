package cc.thonly.wikikernel.plugin.echo.service;

import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.service.UserSettings;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.plugin.echo.EchoKeys;
import cc.thonly.wikikernel.plugin.echo.data.WikiNotification;
import org.springframework.beans.factory.annotation.Autowired;import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Echo 通知的规则层（仿 MediaWiki Echo 的 {@code EchoNotifier}）：
 * <ul>
 *     <li>{@link #talkPageEdited}：有人编辑了你的用户讨论页（{@code edit-user-talk}）；</li>
 *     <li>{@link #mention}：有人在页面里 {@code [[用户:你]]} / {@code @你} 提及了你（{@code mention}）；</li>
 *     <li>{@link #reverted}：你的编辑被回退（{@code revert}）；</li>
 *     <li>{@link #welcome}：新用户欢迎（{@code welcome}）。</li>
 * </ul>
 * <p>
 * 统一规则：
 * <ul>
 *     <li>收件人关闭了该类通知（偏好 {@code echo.pref.enable.<type>}=false）则不创建；</li>
 *     <li>不给自己发通知（触发者 = 收件人时不建）；</li>
 *     <li>去重窗口（60 秒）内同一收件人 + 同一类型 + 同一页面的重复通知不重复建
 *     （例如连续编辑同一讨论页、一次保存里多次提及同一人）。</li>
 * </ul>
 * <p>
 * 触发者 / 收件人都用用户 ID（避免把 core 的 {@code WikiUser} 实体带进插件层）。
 */
@Service
public class EchoNotifier {

    /** 去重窗口（毫秒）：窗口内的重复通知不重复建。 */
    private static final long DEDUPE_WINDOW_MS = 60_000;

    /** 提及匹配：[[用户:名]] / [[User:名]] / @名（中英文、数字、下划线，2~32 位）。 */
    private static final Pattern MENTION_LINK = Pattern.compile(
            "\\[\\[\\s*(?:用户|User)\\s*:\\s*([^\\]|#]+)");
    private static final Pattern MENTION_AT = Pattern.compile(
            "@\\s*([\\p{L}0-9_]{2,32})");

    private final EchoNotificationStoreJdbc store;
    private final UserSettings userSettings;
    private final UserStore userStore;
    private final PluginDatabase database;
    private final EchoMailer mailer;

    @Autowired
    public EchoNotifier(EchoNotificationStoreJdbc store, UserSettings userSettings, UserStore userStore,
                        PluginDatabase database, EchoMailer mailer) {
        this.store = store;
        this.userSettings = userSettings;
        this.userStore = userStore;
        this.database = database;
        this.mailer = mailer;
    }

    /** 讨论页被编辑通知。 */
    public void talkPageEdited(Long actorUserId, String talkPageTitle, Long revisionId, String comment) {
        Long recipient = recipientOfTalkPage(talkPageTitle);
        if (recipient == null) {
            return;
        }
        if (actorUserId != null && Objects.equals(actorUserId, recipient)) {
            return; // 自己编辑自己的讨论页不通知
        }
        if (!enabled(recipient, EchoKeys.TYPE_EDIT_USER_TALK)) {
            return;
        }
        if (recentDuplicate(recipient, EchoKeys.TYPE_EDIT_USER_TALK, talkPageTitle)) {
            return;
        }
        store.insert(newNotification(recipient, EchoKeys.TYPE_EDIT_USER_TALK,
                actorUserId, talkPageTitle, revisionId, comment));
        maybeSendEmail(recipient, EchoKeys.TYPE_EDIT_USER_TALK, actorUserId, talkPageTitle);
    }

    /** @提及 通知（content 为整段正文，用于提取被提及的用户与上下文）。 */
    public void mention(Long actorUserId, String pageTitle, Long revisionId, String content) {
        if (actorUserId == null || content == null || content.isBlank()) {
            return;
        }
        Set<String> names = extractMentionedNames(content);
        if (names.isEmpty()) {
            return;
        }
        for (String name : names) {
            Long recipient = userStore.byName(name).map(UserStore.UserRef::id).orElse(null);
            if (recipient == null || Objects.equals(recipient, actorUserId)) {
                continue; // 不是用户 / 自己提及自己不通知
            }
            if (!enabled(recipient, EchoKeys.TYPE_MENTION)) {
                continue;
            }
            if (recentDuplicate(recipient, EchoKeys.TYPE_MENTION, pageTitle)) {
                continue;
            }
            WikiNotification notification = newNotification(recipient, EchoKeys.TYPE_MENTION,
                    actorUserId, pageTitle, revisionId, null);
            notification.setNeedRender(true);
            notification.setContextText(content);
            store.insert(notification);
            maybeSendEmail(recipient, EchoKeys.TYPE_MENTION, actorUserId, pageTitle);
        }
    }

    /** 回退通知（victimUserIds 是被回退的编辑者用户 ID 列表）。 */
    public void reverted(Long actorUserId, String pageTitle, Long revisionId, String summary,
                         List<Long> victimUserIds) {
        if (victimUserIds == null || victimUserIds.isEmpty()) {
            return;
        }
        for (Long victimId : victimUserIds) {
            if (actorUserId != null && Objects.equals(actorUserId, victimId)) {
                continue;
            }
            if (!enabled(victimId, EchoKeys.TYPE_REVERT)) {
                continue;
            }
            if (recentDuplicate(victimId, EchoKeys.TYPE_REVERT, pageTitle)) {
                continue;
            }
            store.insert(newNotification(victimId, EchoKeys.TYPE_REVERT,
                    actorUserId, pageTitle, revisionId, summary));
            maybeSendEmail(victimId, EchoKeys.TYPE_REVERT, actorUserId, pageTitle);
        }
    }

    /** 欢迎通知（新用户注册成功后写一次）。 */
    public void welcome(Long userId) {
        if (userId == null) {
            return;
        }
        if (!enabled(userId, EchoKeys.TYPE_WELCOME)) {
            return;
        }
        if (store.count(userId, EchoKeys.TYPE_WELCOME) > 0) {
            return; // 只欢迎一次
        }
        store.insert(newNotification(userId, EchoKeys.TYPE_WELCOME, null, null, null, null));
        maybeSendEmail(userId, EchoKeys.TYPE_WELCOME, null, null);
    }

    /**
     * 首次访问时补发欢迎通知（core 没有「注册成功」事件，欢迎走惰性发放）：
     * 只给**注册 2 天内**、且还没收到过欢迎通知的用户发（老账号不会被补一发）。
     *
     * @return 是否本次新发了欢迎
     */
    public boolean ensureWelcome(Long userId) {
        if (userId == null || !enabled(userId, EchoKeys.TYPE_WELCOME)) {
            return false;
        }
        if (store.count(userId, EchoKeys.TYPE_WELCOME) > 0) {
            return false;
        }
        if (registeredBefore(userId, WELCOME_WINDOW)) {
            return false; // 老账号：不补发
        }
        store.insert(newNotification(userId, EchoKeys.TYPE_WELCOME, null, null, null, null));
        return true;
    }

    /** 欢迎窗口：注册多久内算新用户。 */
    private static final java.time.Duration WELCOME_WINDOW = java.time.Duration.ofDays(2);

    /** 该用户注册时间早于 {@code window} 之前？（查不到注册时间时按「老账号」处理） */
    private boolean registeredBefore(long userId, java.time.Duration window) {
        return database.queryOne("SELECT create_at FROM wiki_user WHERE id = ?", userId)
                .map(row -> {
                    java.time.LocalDateTime createAt = row.dateTime("create_at");
                    return createAt == null || createAt.isBefore(java.time.LocalDateTime.now().minus(window));
                })
                .orElse(true);
    }

    // ==================== 内部 ====================

    /** 通知写库后尝试发一封邮件（异步、节流、opt-in；任何失败都不影响通知本身）。 */
    private void maybeSendEmail(long recipient, String type, Long actorUserId, String pageTitle) {
        if (mailer == null) {
            return;
        }
        try {
            mailer.maybeSend(recipient, type, describePlain(type, actorUserId, pageTitle), pageTitle);
        } catch (RuntimeException exception) {
            // 邮件是附加能力：失败只记日志
            org.slf4j.LoggerFactory.getLogger(EchoNotifier.class)
                    .debug("[echo] 邮件通知跳过：{}", exception.toString());
        }
    }

    /** 纯文本描述（邮件正文用；与页面上 HTML 版的文案一致）。 */
    private String describePlain(String type, Long actorUserId, String pageTitle) {
        String actor = actorUserId == null ? null
                : userStore.byId(actorUserId).map(UserStore.UserRef::username).orElse(null);
        if (actor == null) {
            actor = "系统";
        }
        String page = pageTitle == null ? "" : pageTitle;
        return switch (type) {
            case EchoKeys.TYPE_EDIT_USER_TALK -> actor + " 编辑了你的用户讨论页。";
            case EchoKeys.TYPE_MENTION -> actor + " 在《" + page + "》中提及了你。";
            case EchoKeys.TYPE_REVERT -> actor + " 回退了你在《" + page + "》的编辑。";
            case EchoKeys.TYPE_WELCOME -> "欢迎来到本 Wiki！祝您编辑愉快。";
            default -> actor + " 发来了一条通知。";
        };
    }

    private WikiNotification newNotification(long recipient, String type, Long actorUserId,
                                             String pageTitle, Long revisionId, String comment) {
        WikiNotification notification = new WikiNotification();
        notification.setRecipientUserId(recipient);
        notification.setType(type);
        notification.setActorUserId(actorUserId);
        notification.setPageTitle(pageTitle);
        notification.setRevisionId(revisionId);
        notification.setComment(comment);
        notification.setRead(false);
        notification.setCreateAt(LocalDateTime.now());
        notification.setNeedRender(false);
        notification.setContextText("");
        return notification;
    }

    /** 从正文提取被提及的用户名（去重，保序）。 */
    static Set<String> extractMentionedNames(String content) {
        Set<String> names = new LinkedHashSet<>();
        Matcher link = MENTION_LINK.matcher(content);
        while (link.find()) {
            names.add(link.group(1).trim());
        }
        Matcher at = MENTION_AT.matcher(content);
        while (at.find()) {
            names.add(at.group(1).trim());
        }
        return names;
    }

    /** 是否某用户启用了某类通知（缺省 true）。 */
    private boolean enabled(long userId, String type) {
        String value = userSettings == null ? null
                : userSettings.get(userId, EchoKeys.PREF_TYPE_PREFIX + type, null);
        return value == null || !"false".equalsIgnoreCase(value);
    }

    /** 去重：窗口内同一收件人 + 类型 + 页面是否已有通知。 */
    private boolean recentDuplicate(long recipientUserId, String type, String pageTitle) {
        if (pageTitle == null) {
            return false;
        }
        List<WikiNotification> recent = store.list(recipientUserId, type, 0, 1);
        if (recent.isEmpty()) {
            return false;
        }
        WikiNotification latest = recent.get(0);
        if (!pageTitle.equals(latest.getPageTitle())) {
            return false;
        }
        if (latest.getCreateAt() == null) {
            return false;
        }
        return latest.getCreateAt()
                .isAfter(LocalDateTime.now().minusNanos(DEDUPE_WINDOW_MS * 1_000_000));
    }

    /** 讨论页标题 → 收件人用户 id（找不到用户返回 null）。 */
    private Long recipientOfTalkPage(String talkPageTitle) {
        String name = cc.thonly.wikikernel.plugin.echo.EchoSupport.userNameFromTalk(talkPageTitle);
        if (name == null) {
            return null;
        }
        return userStore.byName(name).map(UserStore.UserRef::id).orElse(null);
    }
}