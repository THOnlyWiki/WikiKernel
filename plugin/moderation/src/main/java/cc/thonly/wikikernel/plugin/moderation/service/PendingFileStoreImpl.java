package cc.thonly.wikikernel.plugin.moderation.service;

import cc.thonly.wikikernel.common.plugin.PluginContext;
import cc.thonly.wikikernel.common.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 待审文件暂存的插件实现：{@code PluginContext.dataDir()/pending/<随机名><扩展名>}。
 * <p>
 * 逻辑与迁移前 core 的 {@code PendingFileStoreImpl} 相同（随机名 + 扩展名白名单、路径穿越防护、
 * 失败时统一的 {@code files.uploadFailed} 错误码）；<b>目录从 core 的 {@code {run-path}/pending/}
 * 改成了插件自己的数据目录</b>（core 不再认识「审核暂存」）：升级后旧的待审文件不会自动搬过来，
 * 队列里的历史待审上传需要重新提交一次（见插件 README）。
 */
@Service
public class PendingFileStoreImpl implements PendingFileStore {

    private static final Logger log = LoggerFactory.getLogger(PendingFileStoreImpl.class);

    /** 插件数据目录下的暂存子目录名。 */
    private static final String DIRECTORY_NAME = "pending";

    private final PluginContext context;

    public PendingFileStoreImpl(PluginContext context) {
        this.context = context;
    }

    @Override
    public String stage(String fileName, byte[] content) {
        String storedName = UUID.randomUUID().toString().replace("-", "") + extensionOf(fileName);
        Path directory = directory();
        Path target = directory.resolve(storedName).normalize();
        if (!target.startsWith(directory)) {
            throw new IllegalArgumentException("files.uploadFailed");
        }
        try {
            Files.createDirectories(directory);
            Files.write(target, content == null ? new byte[0] : content);
        } catch (IOException exception) {
            log.error("待审文件暂存失败: {}", exception.getMessage());
            throw new IllegalArgumentException("files.uploadFailed");
        }
        return storedName;
    }

    @Override
    public Optional<byte[]> read(String storedName) {
        Path path = resolve(storedName);
        if (path == null) {
            return Optional.empty();
        }
        try {
            return Files.exists(path) ? Optional.of(Files.readAllBytes(path)) : Optional.empty();
        } catch (IOException exception) {
            log.warn("读取待审文件 {} 失败：{}", path, exception.toString());
            return Optional.empty();
        }
    }

    @Override
    public boolean delete(String storedName) {
        Path path = resolve(storedName);
        if (path == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("删除待审文件 {} 失败：{}", path, exception.toString());
            return false;
        }
    }

    /** 暂存名 → 磁盘路径（带路径穿越防护；非法 / 空返回 null）。 */
    private Path resolve(String storedName) {
        String name = Strings.trimToEmpty(storedName);
        if (name.isEmpty() || name.contains("..") || name.contains("/") || name.contains("\\")) {
            return null;
        }
        Path directory = directory();
        Path path = directory.resolve(name).normalize();
        return path.startsWith(directory) ? path : null;
    }

    /** 暂存目录：插件数据目录下的 {@code pending/}。 */
    private Path directory() {
        return context.dataDir().resolve(DIRECTORY_NAME).toAbsolutePath().normalize();
    }

    private static String extensionOf(String fileName) {
        String name = Strings.trimToEmpty(fileName);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String extension = name.substring(dot);
        return extension.matches("\\.[A-Za-z0-9]{1,8}") ? extension.toLowerCase(Locale.ROOT) : "";
    }
}
