package cc.thonly.wikikernel.common.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户访问桥（由 core 提供实现，插件可 {@code @Autowired} 注入）。
 * <p>
 * 只读：按 ID / 用户名查用户、批量取用户名与头像、判断用户组。
 * 写用户资料请用 {@link UserSettings}；新建账号请用 {@code common.extension.RegistrationGuard} 钩子所在的注册流程。
 */
public interface UserStore {

    /** 未上传头像时的内置默认头像（站点可在设置里覆盖，插件应优先调用 {@link #defaultAvatarUrl()}）。 */
    String DEFAULT_AVATAR_URL = "/img/avatar-default.svg";

    /**
     * 当前站点的默认头像 URL。
     * <p>
     * core 实现会读取站点设置 {@code site.default-avatar}（Timeless 的「默认头像」也写这个共享键）；
     * 未配置 / 配置非法时返回 {@link #DEFAULT_AVATAR_URL}。测试替身可不覆盖，直接得到内置地址。
     */
    default String defaultAvatarUrl() {
        return DEFAULT_AVATAR_URL;
    }

    /** 用户公开信息（不含密码 / 邮箱等敏感字段）。 */
    record UserRef(long id, String username, String avatar, boolean emailConfirmed) {

        /** 头像 URL（空文件名 = 默认头像）。 */
        public String avatarUrl(String defaultUrl) {
            return avatar == null || avatar.isEmpty() ? defaultUrl : "/avatars/" + avatar;
        }
    }

    /** 按 ID 查用户（不存在返回空）。 */
    Optional<UserRef> byId(long userId);

    /** 按用户名查用户（不区分大小写，不存在返回空）。 */
    Optional<UserRef> byName(String username);

    /** 批量取用户名（找不到的 ID 不出现）。 */
    Map<Long, String> names(Collection<Long> userIds);

    /** 批量取头像文件名（找不到 / 未设置为空串）。 */
    Map<Long, String> avatars(Collection<Long> userIds);

    /** 用户组名集合（权限检查用；不存在返回空集合）。 */
    java.util.Set<String> groups(long userId);

    /** 用户名是否已存在。 */
    boolean exists(String username);

    /**
     * 随机抽几个**上传过头像**的用户（仿 MediaWiki SocialProfile 的 {@code Special:RandomUsersWithAvatars}）。
     * <p>
     * 只返回 {@code wiki_user.avatar} 非空的用户（默认头像不算），每次调用尽量给出不同顺序；
     * 有头像的用户不足 {@code limit} 时返回实际数量，一个都没有时返回空列表。
     *
     * @param limit 最多几个（{@code <= 0} = 不返回）
     */
    List<UserRef> randomWithAvatar(int limit);
}
