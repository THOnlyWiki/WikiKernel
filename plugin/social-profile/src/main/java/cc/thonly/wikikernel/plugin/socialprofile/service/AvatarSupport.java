package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.UserStore;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 头像规则（插件侧门面）：校验与落盘由插件自己的 {@link AvatarStore}（{@link AvatarStoreJdbc}）负责，
 * 这里只做「谁能看到 / 取到哪个头像 URL」这类插件自己的判断，供 API、特殊页与资料卡钩子共用。
 */
@Component
public final class AvatarSupport {

    private final AvatarStore avatars;
    private final UserStore users;

    public AvatarSupport(AvatarStore avatars, UserStore users) {
        this.avatars = avatars;
        this.users = users;
    }

    /**
     * 保存 / 替换某个用户的头像。
     *
     * @throws IllegalArgumentException 校验失败（消息键：{@code avatar.empty} / {@code avatar.notImage} /
     *                                  {@code avatar.tooLarge} / {@code avatar.uploadFailed}），由调用方本地化
     */
    public String store(long userId, String originalFileName, String contentType, byte[] content) {
        return avatars.store(userId, originalFileName, contentType, content);
    }

    /** 某个用户的头像 URL（未上传 / 用户不存在 = 默认头像）。 */
    public String avatarUrl(long userId) {
        return users.byId(userId)
                .map(ref -> ref.avatarUrl(UserStore.DEFAULT_AVATAR_URL))
                .orElse(UserStore.DEFAULT_AVATAR_URL);
    }

    /** 用户是否存在（头像上传前校验账号，账号被删时返回 404）。 */
    public boolean exists(long userId) {
        return users.byId(userId).isPresent();
    }

    /** 头像 URL（空文件名 = 默认头像）。 */
    public String url(String fileName) {
        return avatars.url(fileName);
    }

    /** 按文件名解析头像文件（带路径穿越防护；不存在返回空）——{@code GET /avatars/<文件名>} 用。 */
    public Optional<Path> resolve(String fileName) {
        return avatars.resolve(fileName);
    }
}
