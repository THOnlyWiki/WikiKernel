package cc.thonly.wikikernel.plugin.echo.service;

import cc.thonly.wikikernel.common.service.Mailer;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.service.UserSettings;
import cc.thonly.wikikernel.plugin.echo.EchoKeys;
import cc.thonly.wikikernel.plugin.echo.EchoSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Echo 的邮件通知（「有机会就发一封」）：通知写库后，给开了邮件偏好的收件人异步发一封纯文本邮件。
 *
 * <h3>发送条件（全部满足才发）</h3>
 * <ol>
 *     <li>站点邮件可用：{@code wikikernel.email.enabled=true} 且 SMTP 配置完整，
 *     且类型 {@code echo-notification} 没被 {@code wikikernel.email.types.*} 关掉；</li>
 *     <li>用户偏好 {@code echo.pref.email=true}（默认关，opt-in）；</li>
 *     <li>收件人**邮箱已确认**（与 MediaWiki 一致：未确认邮箱不发通知邮件）；</li>
 *     <li>节流：同一收件人 + 同一通知类型，{@code EMAIL_THROTTLE} 内最多一封
 *     （水位存偏好 {@code echo.pref.email.last.<type>}，避免连续编辑刷屏）。</li>
 * </ol>
 *
 * <h3>为什么异步</h3>
 * SMTP 发送超时上限 15 秒，同步发会拖慢用户的保存请求、且可能与数据库事务/锁纠缠——
 * 用单线程 daemon 执行器发送（插件停止时由 {@code EchoPlugin.onStop} 关闭）。
 */
@Service
public class EchoMailer {

    private static final Logger log = LoggerFactory.getLogger(EchoMailer.class);

    /** 同类通知的邮件节流（毫秒）：同一收件人 + 同一类型 30 分钟内最多一封。 */
    private static final long EMAIL_THROTTLE_MS = 30 * 60 * 1000L;

    private final Mailer mailer;
    private final UserSettings userSettings;
    private final PluginDatabase database;
    private final EchoSupport support;

    /** 发送线程（单线程：邮件少、串行发送避免同时开多个 SMTP 连接）。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "echo-mailer");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public EchoMailer(Mailer mailer, UserSettings userSettings, PluginDatabase database, EchoSupport support) {
        this.mailer = mailer;
        this.userSettings = userSettings;
        this.database = database;
        this.support = support;
    }

    /** 站点邮件通道是否可用于 Echo（配置 + 总开关 + 类型开关）。 */
    public boolean available() {
        return mailer != null && mailer.isConfigured() && mailer.isEnabled(EchoKeys.EMAIL_TYPE);
    }

    /** 某用户是否开了邮件通知偏好（默认关）。 */
    public boolean emailEnabled(long userId) {
        return "true".equalsIgnoreCase(userSettings.get(userId, EchoKeys.PREF_EMAIL, "false"));
    }

    /**
     * 通知写库后调用：满足条件时**异步**发一封通知邮件（不阻塞调用方，异常只记日志）。
     *
     * @param recipientUserId 收件人
     * @param type            通知类型（邮件正文与节流水位按它区分）
     * @param description     通知描述（纯文本，如「稀神灵梦 编辑了你的用户讨论页」）
     * @param pageTitle       涉及页面（可空：欢迎通知没有页面）
     */
    public void maybeSend(long recipientUserId, String type, String description, String pageTitle) {
        if (!available() || !emailEnabled(recipientUserId) || type == null || type.isBlank()) {
            return;
        }
        Recipient recipient = recipient(recipientUserId);
        if (recipient == null || recipient.email() == null || recipient.email().isBlank()
                || !recipient.emailConfirmed()) {
            return; // 未确认邮箱 / 没有邮箱：不发（与 MediaWiki 一致）
        }
        if (throttled(recipientUserId, type)) {
            return;
        }
        String subject = support.message("echo.email.subject", "你有新的通知");
        String body = buildBody(description, pageTitle);
        submit(() -> {
            if (mailer.trySend(EchoKeys.EMAIL_TYPE, recipient.email(), subject, body)) {
                userSettings.set(recipientUserId, EchoKeys.PREF_EMAIL_LAST_PREFIX + type,
                        Long.toString(Instant.now().toEpochMilli()));
            }
        });
    }

    /** 关闭发送线程（插件停止时调用；最多等 5 秒，未发完的直接丢弃）。 */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 内部 ====================

    /** 邮件正文：描述 + 站内链接 + 通知页链接 + 偏好提示。 */
    private String buildBody(String description, String pageTitle) {
        StringBuilder text = new StringBuilder();
        text.append(description).append("\n\n");
        String baseUrl = support.baseUrl();
        if (pageTitle != null && !pageTitle.isBlank()) {
            text.append(EchoSupport.pagePath(pageTitle).isBlank() ? "" : baseUrl
                    + EchoSupport.pagePath(pageTitle)).append("\n");
        }
        text.append(baseUrl).append(EchoSupport.pagePath("特殊:通知")).append("\n\n");
        text.append(support.message("echo.email.footer",
                "你收到这封邮件是因为你在参数设置里开启了通知邮件；可在「特殊:参数设置 → 通知」里关闭。"));
        return text.toString();
    }

    private void submit(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException exception) {
                    log.warn("[echo] 通知邮件发送失败：{}", exception.toString());
                }
            });
        } catch (RuntimeException exception) {
            // 执行器已关闭（插件正在停止）：直接丢弃，不影响任何请求
            log.debug("[echo] 邮件任务未提交（执行器已关闭）：{}", exception.toString());
        }
    }

    /** 节流：同类邮件 30 分钟内只发一封。 */
    private boolean throttled(long userId, String type) {
        String last = userSettings.get(userId, EchoKeys.PREF_EMAIL_LAST_PREFIX + type, "");
        if (last == null || last.isBlank()) {
            return false;
        }
        try {
            long lastMillis = Long.parseLong(last.trim());
            return Instant.now().toEpochMilli() - lastMillis < EMAIL_THROTTLE_MS;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    /** 收件人邮箱与确认状态（wiki_user 的 email / email_confirmed）。 */
    private Recipient recipient(long userId) {
        return database.queryOne("SELECT email, email_confirmed FROM wiki_user WHERE id = ?", userId)
                .map(row -> new Recipient(row.string("email"), row.boolValue("email_confirmed", false)))
                .orElse(null);
    }

    private record Recipient(String email, boolean emailConfirmed) {
    }
}