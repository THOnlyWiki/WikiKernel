package cc.thonly.wikikernel.mail;

import cc.thonly.wikikernel.common.data.WikiUser;

/**
 * 邮箱确认流程（仿 MediaWiki 的 Special:Confirmemail）：
 * 发送确认邮件 → 用户点击一次性链接 → 标记邮箱已确认并自动授予「邮箱确认用户」权限。
 */
public interface EmailConfirmationService {

    /** 确认结果。 */
    enum Result {
        /** 确认成功（已授予「邮箱确认用户」）。 */
        OK,
        /** 之前已经确认过。 */
        ALREADY,
        /** 链接无效 / 已过期 / 邮箱已变更。 */
        INVALID,
        /** 链接属于其他用户（需要用它对应的账号登录后再打开）。 */
        MISMATCH
    }

    /** 给用户发送确认邮件；失败抛 {@link MailException}（消息键可本地化）。 */
    void sendConfirmation(WikiUser user, String baseUrl);

    /** 处理确认链接；{@code currentUserId} 为当前登录用户（必须与令牌归属一致）。 */
    Result confirm(String rawToken, Long currentUserId);
}
