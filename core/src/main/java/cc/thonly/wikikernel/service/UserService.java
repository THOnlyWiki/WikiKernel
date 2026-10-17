package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.user.MediaWikiPassword;
import jakarta.annotation.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface UserService {

    /** 用户名里禁止出现的字符（仿 MediaWiki Renameuser：不能含这些字符）。 */
    String FORBIDDEN_USERNAME_CHARS = "#<>[]|{}/";

    /** 新用户名是否合法：去空白后非空，且不含 {@value #FORBIDDEN_USERNAME_CHARS} 等字符。 */
    static boolean isValidUsername(String username) {
        String value = Strings.trimToEmpty(username);
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (FORBIDDEN_USERNAME_CHARS.indexOf(value.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    WikiUser login(String username, String rawPassword);

    WikiUser createUser(String username, String rawPassword, String email);

    /**
     * 注册新账号并记录时区偏好（{@code timeZone} 为浏览器时区，非法 / 空则跟随服务器时区）。
     */
    WikiUser createUser(String username, String rawPassword, @Nullable String email, @Nullable String timeZone);

    boolean isExisted(String username);

    /** 按用户名查用户。 */
    Optional<WikiUser> findByUsername(String username);

    /**
     * 重命名用户（改 {@code wiki_user.username}；用户名没有唯一约束，重名前用 {@link #isExisted} 查重）。
     * 新名为空 / 含非法字符 / 与旧名相同 / 已被占用 / 用户不存在时返回空（服务层防御，调用方仍应先校验并提示）。
     */
    Optional<WikiUser> rename(Long userId, String newUsername);

    /** 按电子邮箱查用户（忽略大小写）。 */
    Optional<WikiUser> findByEmail(String email);

    /** 更新密码（存 MediaWiki 格式哈希，见 {@link MediaWikiPassword}），返回是否成功。 */
    boolean updatePassword(long userId, String rawPassword);

    /** 按 ID 查用户（参数设置等取最新数据用）。 */
    Optional<WikiUser> findById(long userId);

    /** 更新用户参数设置（不包含皮肤），返回更新后的用户。 */
    WikiUser updatePreferences(long userId, UserPreferences preferences);

    /** 还原全部默认设置（保留用户名 / 密码 / 电子邮件等账号数据），返回更新后的用户。 */
    WikiUser resetPreferences(long userId);

    /** 批量查用户 ID → 用户名（历史页展示编辑者用）。 */
    Map<Long, String> usernames(Collection<Long> ids);

    /** 批量取头像文件名（用户 ID → 头像文件名，空串 = 默认头像；用户不存在则没有该键）。 */
    Map<Long, String> avatars(Collection<Long> ids);

    /** 更新用户皮肤偏好（null / 空 = 站点默认），返回更新后的用户；用户不存在返回空。 */
    Optional<WikiUser> updateSkin(long userId, String skinId);

    /** 更新头像文件名（存 {@code wiki_user.avatar}），返回更新后的用户；用户不存在返回空。 */
    Optional<WikiUser> updateAvatar(long userId, String avatarFile);

    /** 标记电子邮件已确认（记确认时间）。 */
    boolean markEmailConfirmed(long userId);

    /** 清除电子邮件确认状态（如换绑邮箱时调用）。 */
    boolean clearEmailConfirmation(long userId);

    /** 记录一次邮箱确认邮件的发送时间（发送冷却用）。 */
    void markEmailConfirmSent(long userId);

    /** 最近一次邮箱确认邮件的发送时间（发送冷却判断用；从未发送过为空）。 */
    Optional<java.time.LocalDateTime> emailConfirmLastSentAt(long userId);

    /** 全部用户名（新的在前，供日志页“用户”输入框自动补全；最多 500 个）。 */
    List<String> allUsernames();
}
