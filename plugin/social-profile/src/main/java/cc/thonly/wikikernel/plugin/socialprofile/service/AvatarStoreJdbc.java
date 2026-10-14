package cc.thonly.wikikernel.plugin.socialprofile.service;

import cc.thonly.wikikernel.common.service.PluginDatabase;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@link AvatarStore} 的插件实现：头像文件存 {@code {run-path}/avatars/u{userId}-{时间戳}.{ext}}，
 * 文件名记在 {@code wiki_user.avatar}；上传时替换旧文件。
 * <p>
 * 与迁移前 core 的 {@code AvatarStoreImpl} 行为一致（类型白名单、2MB 上限、旧文件清理），
 * 入口是字节数组，插件可以从任何来源取图片内容。文件名与路径规则和迁移前完全一致，
 * 已有的头像文件不需要搬目录。
 * <p>
 * 运行目录 / 上传上限读的是 core 的 Spring 配置（插件子容器继承父容器的属性源）；
 * {@code wiki_user.avatar} 没有平台写入桥，用 core 提供的 {@link PluginDatabase}（JDBC）
 * 定位到这一列更新。
 */
@Service
public class AvatarStoreJdbc implements AvatarStore {

    private static final Logger log = LoggerFactory.getLogger(AvatarStoreJdbc.class);

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    /** 头像大小上限（MB，且不超过总上传限制）。 */
    private static final int AVATAR_MAX_MB = 2;

    private static final String DEFAULT_RUN_PATH = "./run/files";

    /** 内容类型 → 扩展名兜底。 */
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/gif", "gif",
            "image/webp", "webp");

    private final PluginDatabase database;
    private final UserStore users;
    private final String runPath;
    private final int maxFileSizeMb;

    public AvatarStoreJdbc(PluginDatabase database, UserStore users,
                           @Value("${wikikernel.run-path:./run/files}") String runPath,
                           @Value("${wikikernel.max-file-size-mb:20}") int maxFileSizeMb) {
        this.database = database;
        this.users = users;
        this.runPath = runPath;
        this.maxFileSizeMb = maxFileSizeMb;
    }

    @Override
    public String store(long userId, String originalFileName, String contentType, byte[] content) {
        if (userId <= 0 || content == null || content.length == 0) {
            throw new IllegalArgumentException("avatar.empty");
        }
        String type = Strings.trimToEmpty(contentType).toLowerCase(Locale.ROOT);
        String extension = extensionOf(originalFileName, type);
        if (!type.startsWith("image/") || extension == null) {
            throw new IllegalArgumentException("avatar.notImage");
        }
        int maxMb = Math.max(1, Math.min(AVATAR_MAX_MB,
                maxFileSizeMb <= 0 ? AVATAR_MAX_MB : maxFileSizeMb));
        if (content.length > (long) maxMb * 1024 * 1024) {
            throw new IllegalArgumentException("avatar.tooLarge");
        }
        String fileName = "u" + userId + "-" + System.currentTimeMillis() + "." + extension;
        Path directory = directory();
        Path target = directory.resolve(fileName).normalize();
        if (!target.startsWith(directory)) {
            throw new IllegalArgumentException("avatar.uploadFailed");
        }
        try {
            Files.createDirectories(directory);
            Files.write(target, content);
        } catch (IOException exception) {
            log.error("头像保存失败: {}", exception.getMessage());
            throw new IllegalArgumentException("avatar.uploadFailed");
        }
        String previous = users.byId(userId).map(ref -> Strings.trimToEmpty(ref.avatar())).orElse("");
        database.update("UPDATE wiki_user SET avatar = ? WHERE id = ?", fileName, userId);
        if (!previous.isEmpty() && !previous.equals(fileName)) {
            resolve(previous).ifPresent(old -> {
                try {
                    Files.deleteIfExists(old);
                } catch (IOException exception) {
                    log.warn("旧头像删除失败 {}: {}", previous, exception.getMessage());
                }
            });
        }
        return url(fileName);
    }

    @Override
    public Optional<Path> resolve(String fileName) {
        String name = Strings.trimToEmpty(fileName);
        if (name.isEmpty() || !SAFE_NAME.matcher(name).matches() || name.contains("..")) {
            return Optional.empty();
        }
        Path directory = directory();
        Path path = directory.resolve(name).normalize();
        if (!path.startsWith(directory) || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(path);
    }

    @Override
    public String url(String fileName) {
        String name = Strings.trimToEmpty(fileName);
        return name.isEmpty() ? UserStore.DEFAULT_AVATAR_URL : "/avatars/" + name;
    }

    private Path directory() {
        String configured = Strings.trimToEmpty(runPath);
        return Path.of(configured.isEmpty() ? DEFAULT_RUN_PATH : configured.trim(), "avatars")
                .toAbsolutePath().normalize();
    }

    private static String extensionOf(String originalName, String contentType) {
        String name = Strings.trimToEmpty(originalName).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String extension = name.substring(dot + 1);
            if (ALLOWED_EXTENSIONS.contains(extension)) {
                return extension;
            }
        }
        return CONTENT_TYPES.get(contentType);
    }
}
