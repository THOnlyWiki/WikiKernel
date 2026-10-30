package cc.thonly.wikikernel.plugin.externalauth.qq.service;

import cc.thonly.wikikernel.common.service.DbRow;
import cc.thonly.wikikernel.common.service.Mailer;
import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * QQ 邮箱验证绑定：用户填写**数字 QQ 号**，插件向 {@code <QQ号>@qq.com} 发送确认链接，
 * 点击后才写入绑定；即使 QQ 邮箱设置了英文别名，也始终使用数字 QQ 地址。
 * <p>
 * 只保存 token 的 SHA-256，不保存明文 token；确认链接 30 分钟内有效，确认成功后立即失效。
 * 表 {@code wiki_qq_email_binding} 属于插件自己，由 {@code WikiPlugin.schemaEntities()} 建表。
 */
@Service
public class QqEmailBindingService {

    public static final String EMAIL_TYPE = "qq-email-bind";
    public static final int TOKEN_TTL_MINUTES = 30;
    public static final int TOKEN_BYTES = 32;

    private static final Pattern QQ_NUMBER = Pattern.compile("[1-9][0-9]{4,11}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final PluginDatabase database;
    private final Mailer mailer;
    private final SiteConfig siteConfig;

    @Autowired
    public QqEmailBindingService(PluginDatabase database, Mailer mailer, SiteConfig siteConfig) {
        this.database = database;
        this.mailer = mailer;
        this.siteConfig = siteConfig;
    }

    /** 已确认的 QQ 绑定；未绑定返回空。 */
    public Optional<Status> status(long userId) {
        if (userId <= 0) {
            return Optional.empty();
        }
        cleanupExpired();
        return database.queryOne("SELECT * FROM wiki_qq_email_binding"
                        + " WHERE user_id = ? AND verified_at IS NOT NULL ORDER BY id DESC LIMIT 1", userId)
                .map(row -> new Status(row.string("qq"), row.dateTime("verified_at")));
    }

    /**
     * 发起绑定：校验数字 QQ、发送确认邮件、写入待确认 token。
     * 失败抛 {@link IllegalArgumentException}，消息值是本地化键。
     */
    public void request(long userId, String username, String qqInput, String baseUrl) {
        String qq = normalizeQq(qqInput);
        String token;
        synchronized (this) {
            if (status(userId).isPresent()) {
                throw new IllegalArgumentException("qqEmail.alreadyBound");
            }
            if (verifiedByOther(userId, qq)) {
                throw new IllegalArgumentException("qqEmail.qqTaken");
            }
            if (pendingByOther(userId, qq)) {
                throw new IllegalArgumentException("qqEmail.qqPending");
            }
            deletePending(userId);
            token = randomToken();
            LocalDateTime now = LocalDateTime.now();
            database.update("INSERT INTO wiki_qq_email_binding"
                            + " (user_id, qq, token_hash, expires_at, verified_at, created_at)"
                            + " VALUES (?, ?, ?, ?, NULL, ?)",
                    userId, qq, hash(token), now.plusMinutes(TOKEN_TTL_MINUTES), now);
        }
        String email = qq + "@qq.com";
        String siteName = Strings.trimToEmpty(siteConfig.get("site.name", "WikiKernel"));
        if (siteName.isEmpty()) {
            siteName = "WikiKernel";
        }
        String confirm = trimTrailingSlash(baseUrl)
                + "/api/qq-email/bind/confirm?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        String subject = siteName + " QQ 邮箱绑定确认";
        String text = "您正在将 QQ " + qq + " 绑定到「" + siteName + "」账号「" + username + "」。\n\n"
                + "请在 " + TOKEN_TTL_MINUTES + " 分钟内打开以下链接完成绑定：\n"
                + confirm + "\n\n"
                + "如果这不是您本人操作，请忽略此邮件；链接过期后绑定不会生效。\n";
        if (!mailer.trySend(EMAIL_TYPE, email, subject, text)) {
            deleteToken(hash(token));
            throw new IllegalArgumentException("qqEmail.sendFailed");
        }
    }

    /** 确认邮件链接。 */
    public ConfirmResult confirm(long userId, String token) {
        String value = Strings.trimToEmpty(token);
        if (value.isEmpty()) {
            return ConfirmResult.INVALID;
        }
        synchronized (this) {
            // 先按 token 查行，再判断过期：如果先 cleanupExpired()，过期 token 会被直接删掉，
            // 用户只能看到「无效」而不是更准确的「已过期」。
            DbRow row = database.queryOne("SELECT * FROM wiki_qq_email_binding"
                    + " WHERE token_hash = ? AND verified_at IS NULL LIMIT 1", hash(value)).orElse(null);
            if (row == null) {
                return ConfirmResult.INVALID;
            }
            if (row.longValue("user_id", 0) != userId) {
                return ConfirmResult.WRONG_USER;
            }
            LocalDateTime expiresAt = row.dateTime("expires_at");
            if (expiresAt == null || !expiresAt.isAfter(LocalDateTime.now())) {
                database.update("DELETE FROM wiki_qq_email_binding WHERE id = ?", row.longValue("id", 0));
                return ConfirmResult.EXPIRED;
            }
            String qq = row.string("qq");
            if (verifiedByOther(userId, qq)) {
                return ConfirmResult.TAKEN;
            }
            int changed = database.update("UPDATE wiki_qq_email_binding SET verified_at = ?"
                    + " WHERE id = ? AND verified_at IS NULL", LocalDateTime.now(), row.longValue("id", 0));
            if (changed == 0) {
                return ConfirmResult.INVALID;
            }
            deletePending(userId);
            return ConfirmResult.OK;
        }
    }

    /** 解除当前账号的 QQ 绑定（包括未确认的待确认记录）；返回是否真的删除。 */
    public boolean unbind(long userId) {
        if (userId <= 0) {
            return false;
        }
        synchronized (this) {
            return database.update("DELETE FROM wiki_qq_email_binding WHERE user_id = ?", userId) > 0;
        }
    }

    /** 数字 QQ 号：5–12 位、首位非 0；不接受英文邮箱别名 / 邮箱地址。 */
    public static String normalizeQq(String value) {
        String qq = Strings.trimToEmpty(value);
        if (!QQ_NUMBER.matcher(qq).matches()) {
            throw new IllegalArgumentException("qqEmail.invalid");
        }
        return qq;
    }

    private void cleanupExpired() {
        database.update("DELETE FROM wiki_qq_email_binding WHERE verified_at IS NULL AND expires_at < ?",
                LocalDateTime.now());
    }

    private void deletePending(long userId) {
        database.update("DELETE FROM wiki_qq_email_binding WHERE user_id = ? AND verified_at IS NULL", userId);
    }

    private void deleteToken(String tokenHash) {
        database.update("DELETE FROM wiki_qq_email_binding WHERE token_hash = ? AND verified_at IS NULL", tokenHash);
    }

    private boolean verifiedByOther(long userId, String qq) {
        return database.queryOne("SELECT id FROM wiki_qq_email_binding"
                        + " WHERE qq = ? AND verified_at IS NOT NULL AND user_id <> ? LIMIT 1", qq, userId)
                .isPresent();
    }

    private boolean pendingByOther(long userId, String qq) {
        return database.queryOne("SELECT id FROM wiki_qq_email_binding"
                        + " WHERE qq = ? AND verified_at IS NULL AND expires_at >= ? AND user_id <> ? LIMIT 1",
                qq, LocalDateTime.now(), userId).isPresent();
    }

    private static String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String trimTrailingSlash(String value) {
        String text = Strings.trimToEmpty(value);
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /** 已确认绑定。 */
    public record Status(String qq, LocalDateTime verifiedAt) {
    }

    /** 确认链接结果。 */
    public enum ConfirmResult {
        OK,
        INVALID,
        EXPIRED,
        WRONG_USER,
        TAKEN
    }
}
