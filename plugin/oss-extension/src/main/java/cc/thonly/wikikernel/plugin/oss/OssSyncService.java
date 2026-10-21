package cc.thonly.wikikernel.plugin.oss;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiFileVersion;
import cc.thonly.wikikernel.common.extension.FileChangeEvent;
import cc.thonly.wikikernel.common.extension.FileChangeListener;
import cc.thonly.wikikernel.common.service.FileCatalog;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * 文件变更 → OSS 的本地失败队列。
 * <p>
 * 请求线程只写一个很小的 properties 元数据文件；真正的 HTTP 上传由后台虚拟线程完成。
 * OSS 不可用时任务留在 outbox 里，恢复后自动重试；插件重启也会继续处理。
 */
public final class OssSyncService implements FileChangeListener, AutoCloseable {

    private final OssSettings settings;
    private final OssClient client;
    private final FileCatalog catalog;
    private final Path outboxDir;
    private volatile Thread worker;
    private volatile boolean running = true;

    public OssSyncService(OssSettings settings, OssClient client, FileCatalog catalog, Path dataDir) {
        this.settings = settings;
        this.client = client;
        this.catalog = catalog;
        this.outboxDir = dataDir.resolve("outbox");
    }

    public void start() {
        try {
            Files.createDirectories(outboxDir);
        } catch (Exception exception) {
            return;
        }
        if (settings.migrateExisting()) {
            migrateExisting();
        }
        worker = Thread.ofVirtual().name("oss-outbox").start(this::loop);
    }

    @Override
    public void onFileChanged(FileChangeEvent event) {
        enqueue(event);
    }

    private void enqueue(FileChangeEvent event) {
        if (event == null || !settings.enabled()) {
            return;
        }
        Properties properties = new Properties();
        properties.setProperty("type", event.type().name());
        properties.setProperty("fileId", String.valueOf(event.fileId()));
        properties.setProperty("fileName", event.fileName() == null ? "" : event.fileName());
        properties.setProperty("storedName", event.storedName() == null ? "" : event.storedName());
        properties.setProperty("contentType", event.contentType() == null ? "" : event.contentType());
        properties.setProperty("size", String.valueOf(event.size()));
        properties.setProperty("version", String.valueOf(event.version()));
        properties.setProperty("current", String.valueOf(event.current()));
        properties.setProperty("path", event.path() == null ? "" : event.path().toString());
        properties.setProperty("createdAt", Instant.now().toString());
        properties.setProperty("attempts", "0");
        String name = String.format("%013d-%s.properties", System.currentTimeMillis(),
                UUID.randomUUID().toString().substring(0, 8));
        write(properties, outboxDir.resolve(name));
    }

    /** 首次启用时把现有 484 张（以及历史版本）全部排入 outbox。 */
    private void migrateExisting() {
        Path marker = outboxDir.resolve("migration.done");
        if (Files.isRegularFile(marker)) {
            return;
        }
        try {
            for (WikiFile file : catalog.all()) {
                enqueueUpload(file, true);
                for (WikiFileVersion version : catalog.versions(file)) {
                    FileChangeEvent event = new FileChangeEvent(FileChangeEvent.Type.UPLOAD,
                            file.getId(), file.getName(), version.getStoredName(), version.getContentType(),
                            version.getSize() == null ? 0 : version.getSize(),
                            version.getVersion() == null ? 1 : version.getVersion(),
                            catalog.pathOf(version.getStoredName()), false);
                    enqueue(event);
                }
            }
            Files.writeString(marker, Instant.now().toString());
        } catch (Exception exception) {
            // 迁移标记不写，下次启动重试；已经入队的任务仍会被处理
        }
    }

    private void enqueueUpload(WikiFile file, boolean current) {
        try {
            FileChangeEvent event = new FileChangeEvent(FileChangeEvent.Type.UPLOAD,
                    file.getId() == null ? 0 : file.getId(), file.getName(), file.getStoredName(),
                    file.getContentType(), file.getSize() == null ? 0 : file.getSize(),
                    file.getVersion() == null ? 1 : file.getVersion(),
                    catalog.pathOf(file.getStoredName()), current);
            enqueue(event);
        } catch (Exception ignored) {
            // 单个文件缺失不阻断全量迁移
        }
    }

    private void loop() {
        while (running) {
            try {
                if (!client.available()) {
                    Thread.sleep(1500);
                    continue;
                }
                List<Path> items;
                try (var stream = Files.list(outboxDir)) {
                    items = stream.filter(path -> path.getFileName().toString().endsWith(".properties"))
                            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                            .limit(1)
                            .toList();
                }
                if (items.isEmpty()) {
                    Thread.sleep(700);
                    continue;
                }
                Path item = items.get(0);
                if (process(item)) {
                    Files.deleteIfExists(item);
                } else {
                    Thread.sleep(1500);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private boolean process(Path item) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(item)) {
            properties.load(input);
        } catch (Exception exception) {
            return true; // 坏文件直接丢弃，避免永久堵住队列
        }
        String type = properties.getProperty("type", "");
        String storedName = properties.getProperty("storedName", "");
        long fileId = parseLong(properties.getProperty("fileId"), 0);
        if ("UPLOAD".equals(type)) {
            Path path = Path.of(properties.getProperty("path", ""));
            if (properties.getProperty("path", "").isBlank() || !Files.isRegularFile(path)) {
                return true; // 本地原图已被删除，无法再上传
            }
            FileChangeEvent event = new FileChangeEvent(FileChangeEvent.Type.UPLOAD, fileId,
                    properties.getProperty("fileName", ""), storedName,
                    properties.getProperty("contentType", "application/octet-stream"),
                    parseLong(properties.getProperty("size"), 0),
                    (int) parseLong(properties.getProperty("version"), 1), path,
                    Boolean.parseBoolean(properties.getProperty("current", "true")));
            return client.upload(event);
        }
        if ("DELETE".equals(type)) {
            return client.delete(storedName);
        }
        if ("SOFT_DELETE".equals(type)) {
            return client.markDeleted(fileId, true);
        }
        if ("RESTORE".equals(type)) {
            return client.markDeleted(fileId, false);
        }
        return true;
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception exception) {
            return fallback;
        }
    }

    private void write(Properties properties, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".outbox-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temp)) {
                properties.store(output, "oss sync");
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // 文件监听不能影响 Wiki 保存；失败任务会在下次变更 / 启动时再尝试
        }
    }

    @Override
    public void close() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }
}
