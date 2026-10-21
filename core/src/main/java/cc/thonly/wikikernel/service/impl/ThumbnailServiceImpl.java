package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.config.FileStorageProperties;
import cc.thonly.wikikernel.config.ThumbnailProperties;
import cc.thonly.wikikernel.service.ThumbnailService;
import cc.thonly.wikikernel.web.MediaTypes;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * 缩略图实现：调用 ImageMagick 生成 WebP（1/2、1/4），文件放在 {@code <run-path>/thumbnail/<storedName>/}。
 * 生成失败只写日志，不影响原图；配额清理只动 thumbnail 目录。
 */
@Service
public class ThumbnailServiceImpl implements ThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailServiceImpl.class);

    private static final int PROCESS_TIMEOUT_SECONDS = 120;

    private final ThumbnailProperties properties;
    private final FileStorageProperties storageProperties;
    private final Object quotaLock = new Object();
    /** thumb.php 的按需缩放队列：最多 2 个 worker；满了由 CallerRunsPolicy 阻塞当前请求。 */
    private final ThreadPoolExecutor renderPool;

    private volatile String executable;
    private volatile boolean executableSearched;

    public ThumbnailServiceImpl(ThumbnailProperties properties, FileStorageProperties storageProperties) {
        this.properties = properties;
        this.storageProperties = storageProperties;
        int queue = Math.max(1, properties.getQueueLimit());
        this.renderPool = new ThreadPoolExecutor(2, 2, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue), new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** 启动时执行一次配额清理（缩略图目录可能被手工拷贝 / 上次运行遗留撑大）。 */
    @PostConstruct
    public void initialize() {
        try {
            Files.createDirectories(thumbnailRoot());
        } catch (Exception exception) {
            log.warn("创建缩略图目录失败", exception);
        }
        enforceQuota();
    }

    @Override
    public Dimensions measure(Path source) {
        if (source == null || !Files.isRegularFile(source)) {
            return null;
        }
        // 光栅图优先用 JDK；WebP / SVG 等再交给 ImageMagick
        try (InputStream input = Files.newInputStream(source)) {
            BufferedImage image = ImageIO.read(input);
            if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
                return new Dimensions(image.getWidth(), image.getHeight());
            }
        } catch (Exception ignored) {
            // 继续尝试 ImageMagick
        }
        String tool = executable();
        if (tool == null) {
            return null;
        }
        try {
            ProcessResult result = run(List.of(tool, "identify", "-quiet", "-format", "%w %h", source.toString()));
            if (result.exitCode() == 0) {
                // ImageMagick 可能把字体等 warning 一起输出；取末尾两个整数（宽 高）
                String[] parts = result.output().trim().split("\\s+");
                if (parts.length >= 2) {
                    int width = Integer.parseInt(parts[parts.length - 2]);
                    int height = Integer.parseInt(parts[parts.length - 1]);
                    if (width > 0 && height > 0) {
                        return new Dimensions(width, height);
                    }
                }
            }
        } catch (Exception exception) {
            log.debug("读取图片尺寸失败: {}", source, exception);
        }
        return null;
    }

    @Override
    public void generate(WikiFile file) {
        if (!properties.isEnabled() || file == null || file.getStoredName() == null
                || MediaTypes.kindOf(file.getName()) != MediaTypes.Kind.IMAGE) {
            return;
        }
        String storedName = file.getStoredName();
        Path source = baseDirectory().resolve(storedName).normalize();
        if (!Files.isRegularFile(source)) {
            return;
        }
        String tool = executable();
        if (tool == null) {
            return;
        }
        Dimensions sourceSize = measure(source);
        for (String variant : List.of(HALF, QUARTER)) {
            Path output = thumbnailPath(storedName, variant);
            try {
                if (Files.isRegularFile(output) && Files.size(output) > 0) {
                    continue;
                }
                Files.createDirectories(output.getParent());
                String resize;
                if (sourceSize != null) {
                    double ratio = HALF.equals(variant) ? 0.5 : 0.25;
                    int width = Math.max(1, (int) Math.round(sourceSize.width() * ratio));
                    int height = Math.max(1, (int) Math.round(sourceSize.height() * ratio));
                    // 小图（如 1×1）按百分比会得到 0×0，必须钳到至少 1 像素
                    resize = width + "x" + height + "!";
                } else {
                    resize = HALF.equals(variant) ? "50%" : "25%";
                }
                ProcessResult result = run(List.of(
                        tool, source.toString(),
                        "-auto-orient",
                        "-background", "none",
                        "-resize", resize,
                        "-quality", String.valueOf(Math.max(1, Math.min(100, properties.getQuality()))),
                        "-define", "webp:method=6",
                        "-strip",
                        output.toString()));
                if (result.exitCode() != 0) {
                    Files.deleteIfExists(output);
                    log.warn("生成缩略图失败 {} {}: {}", storedName, variant, result.output().trim());
                }
            } catch (Exception exception) {
                log.warn("生成缩略图异常 {} {}", storedName, variant, exception);
            }
        }
        enforceQuota();
    }

    @Override
    public Optional<Path> ensureWidth(WikiFile file, int requestedWidth) {
        if (!properties.isEnabled() || file == null || file.getStoredName() == null
                || MediaTypes.kindOf(file.getName()) != MediaTypes.Kind.IMAGE) {
            return Optional.empty();
        }
        Path source = baseDirectory().resolve(file.getStoredName()).normalize();
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        int sourceWidth = file.getWidth() == null || file.getWidth() <= 0
                ? dimensionWidth(measure(source)) : file.getWidth();
        if (sourceWidth <= 0) {
            return Optional.empty();
        }
        int targetWidth = requestedWidth <= 0 ? sourceWidth : Math.min(requestedWidth, sourceWidth);
        if (targetWidth <= 0) {
            return Optional.of(source);
        }
        Path output = thumbnailRoot().resolve(file.getStoredName())
                .resolve("w" + targetWidth + ".webp").normalize();
        if (!output.startsWith(thumbnailRoot())) {
            return Optional.empty();
        }
        try {
            if (Files.isRegularFile(output) && Files.size(output) > 0) {
                return Optional.of(output);
            }
            Files.createDirectories(output.getParent());
            Future<?> future = renderPool.submit(() -> generateWidth(source, output, targetWidth));
            try {
                future.get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                future.cancel(true);
                log.warn("按需缩略图超时: {} w{}", file.getStoredName(), targetWidth);
                return Optional.empty();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (ExecutionException exception) {
                log.warn("按需缩略图失败: {} w{}", file.getStoredName(), targetWidth, exception.getCause());
                return Optional.empty();
            }
            return Files.isRegularFile(output) && Files.size(output) > 0
                    ? Optional.of(output) : Optional.empty();
        } catch (Exception exception) {
            log.debug("按需缩略图异常: {} w{}", file.getStoredName(), targetWidth, exception);
            return Optional.empty();
        }
    }

    @Override
    public Optional<Path> ensureWidth(Path source, String cacheKey, int requestedWidth) {
        if (!properties.isEnabled() || source == null || !Files.isRegularFile(source)) {
            return Optional.empty();
        }
        Dimensions dimensions = measure(source);
        if (dimensions == null || dimensions.width() <= 0 || dimensions.height() <= 0) {
            return Optional.empty();
        }
        int targetWidth = requestedWidth <= 0
                ? dimensions.width()
                : Math.min(requestedWidth, dimensions.width());
        if (targetWidth <= 0) {
            return Optional.empty();
        }
        String key = Integer.toHexString((cacheKey == null ? source.toString() : cacheKey).hashCode());
        Path output = thumbnailRoot().resolve("offline").resolve(key)
                .resolve("w" + targetWidth + ".webp").normalize();
        if (!output.startsWith(thumbnailRoot())) {
            return Optional.empty();
        }
        try {
            if (Files.isRegularFile(output) && Files.size(output) > 0) {
                return Optional.of(output);
            }
            Files.createDirectories(output.getParent());
            Future<?> future = renderPool.submit(() -> generateWidth(source, output, targetWidth));
            try {
                future.get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                future.cancel(true);
                log.warn("离线导出缩略图超时: {}", cacheKey);
                return Optional.empty();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (ExecutionException exception) {
                log.warn("离线导出缩略图失败: {}", cacheKey, exception.getCause());
                return Optional.empty();
            }
            return Files.isRegularFile(output) && Files.size(output) > 0
                    ? Optional.of(output) : Optional.empty();
        } catch (Exception exception) {
            log.debug("离线导出缩略图异常: {}", cacheKey, exception);
            return Optional.empty();
        }
    }

    private void generateWidth(Path source, Path output, int targetWidth) {
        String tool = executable();
        if (tool == null) {
            return;
        }
        try {
            Files.createDirectories(output.getParent());
            ProcessResult result = run(List.of(
                    tool, source.toString(),
                    "-auto-orient",
                    "-background", "none",
                    // 只给宽度，不写 !：保持比例，且 targetWidth 已限制为不超过原图
                    "-resize", targetWidth + "x",
                    "-quality", String.valueOf(Math.max(1, Math.min(100, properties.getQuality()))),
                    "-define", "webp:method=6",
                    "-strip",
                    output.toString()));
            if (result.exitCode() != 0) {
                Files.deleteIfExists(output);
                log.warn("生成按需缩略图失败 {} w{}: {}", source.getFileName(), targetWidth,
                        result.output().trim());
            }
        } catch (Exception exception) {
            try {
                Files.deleteIfExists(output);
            } catch (Exception ignored) {
                // 忽略清理失败
            }
            log.warn("生成按需缩略图异常 {} w{}", source.getFileName(), targetWidth, exception);
        }
    }

    private static int dimensionWidth(Dimensions dimensions) {
        return dimensions == null ? 0 : Math.max(0, dimensions.width());
    }

    @PreDestroy
    public void shutdown() {
        renderPool.shutdownNow();
    }

    @Override
    public Optional<byte[]> read(String storedName, String variant) {
        Path path = thumbnailPath(storedName, variant);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(path));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    @Override
    public boolean exists(String storedName, String variant) {
        Path path = thumbnailPath(storedName, variant);
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (Exception exception) {
            return false;
        }
    }

    @Override
    public String url(String storedName, String variant) {
        return "/files/thumbnail/" + storedName + "/" + variant + ".webp";
    }

    @Override
    public void delete(String storedName) {
        if (storedName == null || storedName.isBlank()) {
            return;
        }
        Path directory = thumbnailRoot().resolve(storedName).normalize();
        if (!directory.startsWith(thumbnailRoot())) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception exception) {
                    log.debug("删除缩略图失败: {}", path, exception);
                }
            });
        } catch (Exception exception) {
            log.debug("清理缩略图目录失败: {}", directory, exception);
        }
    }

    @Override
    public int enforceQuota() {
        synchronized (quotaLock) {
            long maxBytes = maxSizeBytes();
            Path root = thumbnailRoot();
            if (!Files.isDirectory(root)) {
                return 0;
            }
            List<Path> files = new ArrayList<>();
            long total = 0;
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path path : walk.filter(Files::isRegularFile).toList()) {
                    files.add(path);
                    total += Files.size(path);
                }
            } catch (Exception exception) {
                log.warn("统计缩略图容量失败", exception);
                return 0;
            }
            if (total <= maxBytes) {
                return 0;
            }
            files.sort(Comparator.comparing(path -> {
                try {
                    return Files.getLastModifiedTime(path);
                } catch (Exception exception) {
                    return FileTime.fromMillis(0);
                }
            }));
            int deleted = 0;
            for (Path path : files) {
                if (total <= maxBytes) {
                    break;
                }
                try {
                    long size = Files.size(path);
                    if (Files.deleteIfExists(path)) {
                        total -= size;
                        deleted++;
                    }
                } catch (Exception exception) {
                    log.debug("配额清理删除失败: {}", path, exception);
                }
            }
            removeEmptyDirectories(root);
            if (deleted > 0) {
                log.info("缩略图超过 {} MB，已删除最旧的 {} 个文件（当前 {} MB）",
                        properties.getMaxSizeMb(), deleted, Math.max(0, total) / (1024 * 1024));
            }
            return deleted;
        }
    }

    private long maxSizeBytes() {
        long mb = properties.getMaxSizeMb();
        return mb <= 0 ? Long.MAX_VALUE : mb * 1024L * 1024L;
    }

    private void removeEmptyDirectories(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try (Stream<Path> children = Files.list(path)) {
                            if (children.findAny().isEmpty()) {
                                Files.deleteIfExists(path);
                            }
                        } catch (Exception ignored) {
                            // 目录非空 / 已被并发删除
                        }
                    });
        } catch (Exception ignored) {
            // 清理空目录失败不影响主流程
        }
    }

    private Path thumbnailPath(String storedName, String variant) {
        String value = variant == null ? "" : variant.toLowerCase(Locale.ROOT);
        if (!HALF.equals(value) && !QUARTER.equals(value)) {
            throw new IllegalArgumentException("未知缩略图尺寸");
        }
        Path root = thumbnailRoot();
        Path path = root.resolve(storedName).resolve(value + ".webp").normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("非法缩略图路径");
        }
        return path;
    }

    private Path thumbnailRoot() {
        return baseDirectory().resolve("thumbnail");
    }

    private Path baseDirectory() {
        return Path.of(storageProperties.getRunPath()).toAbsolutePath().normalize();
    }

    private String executable() {
        if (executableSearched) {
            return executable;
        }
        synchronized (this) {
            if (executableSearched) {
                return executable;
            }
            executableSearched = true;
            String configured = properties.getExecutable();
            if (configured != null && !configured.isBlank() && usable(configured)) {
                executable = configured;
                return executable;
            }
            List<String> candidates = new ArrayList<>();
            candidates.add("magick");
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            if (windows) {
                candidates.add("C:\\Program Files\\ImageMagick-7.1.2-Q16-HDRI\\magick.exe");
                candidates.add("C:\\Program Files\\ImageMagick-7.1.0-Q16-HDRI\\magick.exe");
            } else {
                candidates.add("convert");
            }
            for (String candidate : candidates) {
                if (usable(candidate)) {
                    executable = candidate;
                    return executable;
                }
            }
            log.warn("未找到 ImageMagick 的 magick 命令，缩略图生成已停用");
            return null;
        }
    }

    private boolean usable(String command) {
        try {
            ProcessResult result = run(List.of(command, "-version"));
            return result.exitCode() == 0
                    && result.output().toLowerCase(Locale.ROOT).contains("imagemagick");
        } catch (Exception exception) {
            return false;
        }
    }

    private ProcessResult run(List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new ProcessResult(-1, "timeout");
        }
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new ProcessResult(process.exitValue(), output);
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
