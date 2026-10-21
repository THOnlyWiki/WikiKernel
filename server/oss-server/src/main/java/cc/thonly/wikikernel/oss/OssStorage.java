package cc.thonly.wikikernel.oss;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 原图 / 缩略图磁盘存储（SQLite 只存元数据）。 */
@Component
public class OssStorage {

    private static final String SAFE_NAME = "[A-Za-z0-9._-]+";

    private final OssProperties properties;

    public OssStorage(OssProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() throws IOException {
        Files.createDirectories(originalRoot());
        Files.createDirectories(thumbnailRoot());
    }

    public Path originalPath(String storedName) {
        validate(storedName);
        Path root = originalRoot();
        Path path = root.resolve(storedName).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("非法存储名");
        }
        return path;
    }

    public Path thumbnailPath(String storedName, String variant) {
        validate(storedName);
        if (!"half".equals(variant) && !"quarter".equals(variant)) {
            throw new IllegalArgumentException("未知缩略图尺寸");
        }
        Path root = thumbnailRoot();
        Path path = root.resolve(storedName).resolve(variant + ".webp").normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("非法缩略图路径");
        }
        return path;
    }

    public Path store(String storedName, byte[] content) throws IOException {
        Path target = originalPath(storedName);
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    public byte[] read(String storedName) throws IOException {
        return Files.readAllBytes(originalPath(storedName));
    }

    public void delete(String storedName) {
        try {
            Files.deleteIfExists(originalPath(storedName));
        } catch (Exception ignored) {
            // 幂等删除
        }
        deleteThumbnails(storedName);
    }

    public void deleteThumbnails(String storedName) {
        Path directory = thumbnailRoot().resolve(storedName).normalize();
        if (!directory.startsWith(thumbnailRoot())) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 忽略清理失败
                }
            });
        } catch (Exception ignored) {
            // 目录不存在
        }
    }

    public Path originalRoot() {
        return Path.of(properties.getStorageDir()).toAbsolutePath().normalize();
    }

    public Path thumbnailRoot() {
        return originalRoot().resolve("thumbnails");
    }

    private static void validate(String storedName) {
        if (storedName == null || !storedName.matches(SAFE_NAME)) {
            throw new IllegalArgumentException("非法存储名");
        }
    }
}
