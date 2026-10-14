package cc.thonly.wikikernel.plugin.socialprofile.service;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 头像存储（**属于插件自己**）：头像图片存 {@code {run-path}/avatars/}，文件名记在
 * {@code wiki_user.avatar}；本接口负责校验（类型 / 大小）、落盘、更新用户字段与删除旧文件，
 * 插件只管上传入口与界面。
 * <p>
 * 默认头像地址统一用 {@code common.service.UserStore#DEFAULT_AVATAR_URL}；
 * 实现见 {@link AvatarStoreJdbc}，门面见 {@link AvatarSupport}。
 */
public interface AvatarStore {

    /** 只允许这些扩展名（也用于 {@code /avatars/} 的文件名白名单）。 */
    java.util.Set<String> ALLOWED_EXTENSIONS = java.util.Set.of("png", "jpg", "jpeg", "gif", "webp");

    /**
     * 保存头像：校验并落盘、更新 {@code wiki_user.avatar}、删除旧文件。
     *
     * @param userId           用户 ID
     * @param originalFileName 原始文件名（取扩展名）
     * @param contentType      客户端提交的 MIME（不可信，仅用于扩展名兜底）
     * @param content          图片字节
     * @return 可访问的头像 URL
     * @throws IllegalArgumentException 内容为空 / 不是图片 / 超过大小上限 / 写入失败（消息键，可本地化）
     */
    String store(long userId, String originalFileName, String contentType, byte[] content);

    /** 按文件名解析磁盘路径（带路径穿越防护）；不存在返回空。 */
    Optional<Path> resolve(String fileName);

    /** 头像 URL（空 = 默认头像）。 */
    String url(String fileName);
}
