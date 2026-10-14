package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiFileVersion;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.config.FileStorageProperties;
import cc.thonly.wikikernel.db.mapper.WikiFileMapper;
import cc.thonly.wikikernel.db.mapper.WikiFileVersionMapper;
import cc.thonly.wikikernel.log.LogTypeConfig;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.LogService;
import cc.thonly.wikikernel.web.FileTitles;
import cc.thonly.wikikernel.web.MediaTypes;
import cc.thonly.wikikernel.web.WikiTitles;
import cc.thonly.wikikernel.web.WikiUrls;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 文件服务实现：内容存 {@code wikikernel.run-path}（默认 {@code ./run/files}），
 * 当前版本元信息存 {@code wiki_file}，历史版本存 {@code wiki_file_version}。
 * <p>
 * 类型策略：图片（png/jpg/jpeg/gif/webp）内联预览；音视频（webm/ogv/mp4/ogg/mp3/wav/m4a/flac 等）
 * 在启用多媒体预览时内联播放（否则下载）；常见文档给对应 MIME；其余按二进制下载。
 * 禁止上传可执行 / 会被同源执行的类型（html/js/css/svg 等），防止存储型 XSS。
 */
@Service
public class FileServiceImpl implements FileService {

    /** 禁止上传的扩展名（会被浏览器同源执行或注入脚本）。 */
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "html", "htm", "xhtml", "xml", "svg", "js", "mjs", "cjs", "css",
            "jsp", "php", "asp", "aspx", "exe", "dll", "bat", "cmd", "sh", "ps1", "jar", "war");

    /** 常见文档类型 → MIME（不信任客户端提交的 Content-Type；图片 / 音视频见 {@link MediaTypes}）。 */
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("txt", "text/plain;charset=UTF-8"),
            Map.entry("md", "text/plain;charset=UTF-8"),
            Map.entry("zip", "application/zip"));

    private final WikiFileMapper wikiFileMapper;
    private final WikiFileVersionMapper wikiFileVersionMapper;
    private final FileStorageProperties properties;
    private final LogService logService;
    private final MessageSource messageSource;

    public FileServiceImpl(WikiFileMapper wikiFileMapper,
                           WikiFileVersionMapper wikiFileVersionMapper,
                           FileStorageProperties properties,
                           LogService logService,
                           MessageSource messageSource) {
        this.wikiFileMapper = wikiFileMapper;
        this.wikiFileVersionMapper = wikiFileVersionMapper;
        this.properties = properties;
        this.logService = logService;
        this.messageSource = messageSource;
    }

    @Override
    public WikiFile store(WikiUser uploader, String name, String description, byte[] content) {
        String fileName = normalizeName(name);
        // 文件名也遵循标题规则（非法字符 / 过长等直接拒绝；仿 MediaWiki 的文件名校验）
        WikiTitles.validate(fileName).ifPresent(error -> {
            throw new IllegalArgumentException("files.invalidName");
        });
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("files.empty");
        }
        int maxBytes = Math.max(1, properties.getMaxFileSizeMb()) * 1024 * 1024;
        if (content.length > maxBytes) {
            throw new IllegalArgumentException("files.tooLarge");
        }
        String extension = extensionOf(fileName);
        if (BLOCKED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("files.invalidType");
        }
        WikiFile existing = findAnyByName(fileName).orElse(null);
        if (existing != null && Boolean.TRUE.equals(existing.getDeleted())) {
            throw new IllegalArgumentException("files.deletedExists");
        }

        String storedName = uniqueStoredName(extension);
        Path path = baseDirectory().resolve(storedName).normalize();
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, content);
        } catch (IOException exception) {
            throw new IllegalStateException("files.writeFailed", exception);
        }

        String comment = Strings.trimToEmpty(description);
        Long uploaderId = uploader == null ? null : uploader.getId();
        LocalDateTime now = LocalDateTime.now();

        WikiFile file;
        if (existing == null) {
            file = new WikiFile();
            file.setName(fileName);
            file.setStoredName(storedName);
            file.setContentType(contentTypeOf(extension));
            file.setSize((long) content.length);
            file.setUploaderId(uploaderId);
            file.setDescription(comment);
            file.setCreatedAt(now);
            file.setVersion(1);
            file.setDeleted(false);
            wikiFileMapper.insert(file);
        } else {
            int next = currentVersion(existing) + 1;
            archiveCurrent(existing);
            String contentType = contentTypeOf(extension);
            long size = content.length;
            wikiFileMapper.update(null, Wrappers.<WikiFile>lambdaUpdate()
                    .eq(WikiFile::getId, existing.getId())
                    .set(WikiFile::getStoredName, storedName)
                    .set(WikiFile::getContentType, contentType)
                    .set(WikiFile::getSize, size)
                    .set(WikiFile::getUploaderId, uploaderId)
                    .set(WikiFile::getDescription, comment)
                    .set(WikiFile::getCreatedAt, now)
                    .set(WikiFile::getVersion, next));
            applyVersion(existing, storedName, contentType, size, uploaderId, comment, now, next);
            file = existing;
        }

        logService.record(LogTypeConfig.UPLOAD, uploaderId, fileName, null, file.getDescription());
        return file;
    }

    @Override
    public Optional<WikiFile> findById(long id) {
        return Optional.ofNullable(wikiFileMapper.selectById(id));
    }

    @Override
    public Optional<WikiFile> findByName(String name) {
        return findByNameVariant(name, false);
    }

    @Override
    public Optional<WikiFile> findAnyByName(String name) {
        return findByNameVariant(name, true);
    }

    /**
     * 文件名查询：先精确匹配，再在「下划线 ↔ 空格」之间重试
     * （仿 MediaWiki：文件名里的下划线等价于空格，{@code File:My_File.png} 能打开 {@code My File.png}）。
     */
    private Optional<WikiFile> findByNameVariant(String name, boolean includeDeleted) {
        // 文件名与标题一样规范化（首字母大写等），保证「小写 / 下划线写法」都能命中
        String value = FileTitles.normalizeName(name);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        Optional<WikiFile> found = queryByName(value, includeDeleted);
        if (found.isPresent()) {
            return found;
        }
        String variant = value.indexOf('_') >= 0
                ? value.replace('_', ' ')
                : value.replace(' ', '_');
        if (variant.equals(value)) {
            return Optional.empty();
        }
        return queryByName(variant, includeDeleted);
    }

    private Optional<WikiFile> queryByName(String value, boolean includeDeleted) {
        return Optional.ofNullable(wikiFileMapper.selectOne(Wrappers.<WikiFile>lambdaQuery()
                .eq(WikiFile::getName, value)
                .eq(!includeDeleted, WikiFile::getDeleted, false)
                .last("LIMIT 1")));
    }

    @Override
    public Optional<StoredFile> resolveStored(String storedName) {
        String value = Strings.trimToEmpty(storedName);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        WikiFile file = wikiFileMapper.selectOne(Wrappers.<WikiFile>lambdaQuery()
                .eq(WikiFile::getStoredName, value)
                .last("LIMIT 1"));
        if (file != null) {
            return Optional.of(new StoredFile(file.getName(), file.getStoredName(),
                    file.getContentType(), Boolean.TRUE.equals(file.getDeleted())));
        }
        WikiFileVersion version = wikiFileVersionMapper.selectOne(Wrappers.<WikiFileVersion>lambdaQuery()
                .eq(WikiFileVersion::getStoredName, value)
                .last("LIMIT 1"));
        if (version == null) {
            return Optional.empty();
        }
        WikiFile owner = wikiFileMapper.selectById(version.getFileId());
        if (owner == null) {
            return Optional.empty();
        }
        return Optional.of(new StoredFile(owner.getName(), version.getStoredName(),
                version.getContentType(), Boolean.TRUE.equals(owner.getDeleted())));
    }

    @Override
    public List<WikiFile> recent(int limit) {
        return wikiFileMapper.selectList(Wrappers.<WikiFile>lambdaQuery()
                .eq(WikiFile::getDeleted, false)
                .orderByDesc(WikiFile::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    @Override
    public List<WikiFile> all() {
        return wikiFileMapper.selectList(Wrappers.<WikiFile>lambdaQuery()
                .eq(WikiFile::getDeleted, false)
                .orderByDesc(WikiFile::getId));
    }

    @Override
    public List<WikiFile> deleted() {
        return wikiFileMapper.selectList(Wrappers.<WikiFile>lambdaQuery()
                .eq(WikiFile::getDeleted, true)
                .orderByDesc(WikiFile::getId));
    }

    @Override
    public List<WikiFileVersion> versions(WikiFile file) {
        if (file == null || file.getId() == null) {
            return List.of();
        }
        return wikiFileVersionMapper.selectList(Wrappers.<WikiFileVersion>lambdaQuery()
                .eq(WikiFileVersion::getFileId, file.getId())
                .orderByDesc(WikiFileVersion::getVersion));
    }

    @Override
    public WikiFile revert(WikiUser user, String name, int version) {
        WikiFile file = findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("files.notFound"));
        int current = currentVersion(file);
        if (version == current) {
            throw new IllegalArgumentException("files.revertCurrent");
        }
        WikiFileVersion target = wikiFileVersionMapper.selectOne(Wrappers.<WikiFileVersion>lambdaQuery()
                .eq(WikiFileVersion::getFileId, file.getId())
                .eq(WikiFileVersion::getVersion, version)
                .last("LIMIT 1"));
        if (target == null) {
            throw new IllegalArgumentException("files.versionNotFound");
        }

        // 复制旧版本内容为新文件（每个版本各自一份磁盘文件，彻底删除时一并清理）
        byte[] content;
        try {
            content = read(target.getStoredName());
        } catch (IOException exception) {
            throw new IllegalStateException("files.readFailed", exception);
        }
        String storedName = uniqueStoredName(extensionOf(file.getName()));
        Path path = baseDirectory().resolve(storedName).normalize();
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, content);
        } catch (IOException exception) {
            throw new IllegalStateException("files.writeFailed", exception);
        }

        int next = current + 1;
        archiveCurrent(file);
        Long userId = user == null ? null : user.getId();
        String comment = message("files.revertComment", version);
        LocalDateTime now = LocalDateTime.now();
        wikiFileMapper.update(null, Wrappers.<WikiFile>lambdaUpdate()
                .eq(WikiFile::getId, file.getId())
                .set(WikiFile::getStoredName, storedName)
                .set(WikiFile::getContentType, target.getContentType())
                .set(WikiFile::getSize, target.getSize())
                .set(WikiFile::getUploaderId, userId)
                .set(WikiFile::getDescription, comment)
                .set(WikiFile::getCreatedAt, now)
                .set(WikiFile::getVersion, next));
        applyVersion(file, storedName, target.getContentType(), target.getSize(), userId, comment, now, next);

        logService.record(LogTypeConfig.UPLOAD, userId, file.getName(), null, comment);
        return file;
    }

    @Override
    public boolean delete(WikiUser user, long id, boolean hard) {
        WikiFile file = wikiFileMapper.selectById(id);
        if (file == null) {
            return false;
        }
        Long userId = user == null ? null : user.getId();
        if (hard) {
            Set<String> storedNames = new LinkedHashSet<>();
            storedNames.add(file.getStoredName());
            for (WikiFileVersion version : versions(file)) {
                storedNames.add(version.getStoredName());
            }
            wikiFileVersionMapper.delete(Wrappers.<WikiFileVersion>lambdaQuery()
                    .eq(WikiFileVersion::getFileId, id));
            wikiFileMapper.deleteById(id);
            for (String storedName : storedNames) {
                try {
                    Files.deleteIfExists(pathOf(storedName));
                } catch (IOException exception) {
                    throw new IllegalStateException("files.deleteFailed", exception);
                }
            }
            logService.record(LogTypeConfig.FILEDELETE, userId, file.getName(), null,
                    message("files.hardDeleteComment"));
            return true;
        }
        if (!Boolean.TRUE.equals(file.getDeleted())) {
            wikiFileMapper.update(null, Wrappers.<WikiFile>lambdaUpdate()
                    .eq(WikiFile::getId, id)
                    .set(WikiFile::getDeleted, true)
                    .set(WikiFile::getDeletedBy, userId)
                    .set(WikiFile::getDeletedAt, LocalDateTime.now()));
        }
        logService.record(LogTypeConfig.FILEDELETE, userId, file.getName(), null, null);
        return true;
    }

    @Override
    public boolean restore(WikiUser user, long id) {
        WikiFile file = wikiFileMapper.selectById(id);
        if (file == null) {
            return false;
        }
        if (!Boolean.TRUE.equals(file.getDeleted())) {
            return true;
        }
        wikiFileMapper.update(null, Wrappers.<WikiFile>lambdaUpdate()
                .eq(WikiFile::getId, id)
                .set(WikiFile::getDeleted, false)
                .set(WikiFile::getDeletedBy, null)
                .set(WikiFile::getDeletedAt, null));
        logService.record(LogTypeConfig.FILERESTORE, user == null ? null : user.getId(),
                file.getName(), null, null);
        return true;
    }

    @Override
    public Path pathOf(WikiFile file) {
        return pathOf(file.getStoredName());
    }

    @Override
    public Path pathOf(String storedName) {
        Path base = baseDirectory();
        Path path = base.resolve(Strings.trimToEmpty(storedName)).normalize();
        if (!path.startsWith(base)) {
            throw new IllegalArgumentException("files.invalidName");
        }
        return path;
    }

    @Override
    public byte[] read(WikiFile file) throws IOException {
        return read(file.getStoredName());
    }

    @Override
    public byte[] read(String storedName) throws IOException {
        return Files.readAllBytes(pathOf(storedName));
    }

    @Override
    public boolean isImage(WikiFile file) {
        return isImage(file.getName());
    }

    @Override
    public boolean isImage(String fileName) {
        return MediaTypes.kindOf(fileName) == MediaTypes.Kind.IMAGE;
    }

    @Override
    public String mediaKind(WikiFile file) {
        return mediaKind(file.getName());
    }

    @Override
    public String mediaKind(String fileName) {
        return MediaTypes.kindOf(fileName).name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String urlOf(WikiFile file) {
        return "/files/" + file.getStoredName();
    }

    @Override
    public String urlOf(WikiFileVersion version) {
        return "/files/" + version.getStoredName();
    }

    @Override
    public String pageUrlOf(WikiFile file) {
        return pageUrl(file.getName());
    }

    @Override
    public String pageUrl(String fileName) {
        return WikiUrls.pagePath(FileTitles.titleOf(fileName));
    }

    // ==================== 内部实现 ====================

    /** 当前版本号（历史数据可能为空，按 1 处理）。 */
    private static int currentVersion(WikiFile file) {
        return file.getVersion() == null ? 1 : file.getVersion();
    }

    /** 把当前版本归档进 wiki_file_version（上传新版本 / 恢复旧版本前调用）。 */
    private void archiveCurrent(WikiFile file) {
        WikiFileVersion archived = new WikiFileVersion();
        archived.setFileId(file.getId());
        archived.setVersion(currentVersion(file));
        archived.setStoredName(file.getStoredName());
        archived.setContentType(file.getContentType());
        archived.setSize(file.getSize());
        archived.setUploaderId(file.getUploaderId());
        archived.setComment(file.getDescription());
        archived.setCreatedAt(file.getCreatedAt());
        wikiFileVersionMapper.insert(archived);
    }

    /** 更新内存里的当前版本信息（与上面的 UPDATE 同步，返回值直接用）。 */
    private static void applyVersion(WikiFile file, String storedName, String contentType,
                                     Long size, Long uploaderId, String comment,
                                     LocalDateTime createdAt, int version) {
        file.setStoredName(storedName);
        file.setContentType(contentType);
        file.setSize(size);
        file.setUploaderId(uploaderId);
        file.setDescription(comment);
        file.setCreatedAt(createdAt);
        file.setVersion(version);
    }

    private Path baseDirectory() {
        return Path.of(Strings.trimToEmpty(properties.getRunPath())).toAbsolutePath().normalize();
    }

    private String normalizeName(String name) {
        String value = FileTitles.normalizeName(name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("files.nameRequired");
        }
        if (value.length() > 150) {
            throw new IllegalArgumentException("files.nameTooLong");
        }
        return value;
    }

    private String uniqueStoredName(String extension) {
        String suffix = extension.isEmpty() ? "" : "." + extension;
        for (int i = 0; i < 5; i++) {
            String candidate = System.currentTimeMillis() + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + suffix;
            boolean used = wikiFileMapper.selectCount(Wrappers.<WikiFile>lambdaQuery()
                    .eq(WikiFile::getStoredName, candidate)) > 0
                    || wikiFileVersionMapper.selectCount(Wrappers.<WikiFileVersion>lambdaQuery()
                    .eq(WikiFileVersion::getStoredName, candidate)) > 0;
            if (!used && !Files.exists(baseDirectory().resolve(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("files.writeFailed");
    }

    private static String extensionOf(String name) {
        return MediaTypes.extensionOf(name);
    }

    private static String contentTypeOf(String extension) {
        String media = MediaTypes.contentTypeOf(extension);
        if (media != null) {
            return media;
        }
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
