package cc.thonly.wikikernel.oss;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** OSS 端 WebP 缩略图：首次请求时生成并缓存，后续直接读缓存。 */
@Service
public class OssThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(OssThumbnailService.class);
    private static final int TIMEOUT_SECONDS = 120;

    private final OssProperties properties;
    private final OssStorage storage;
    private final OssDatabase database;

    private volatile String executable;
    private volatile boolean searched;

    public OssThumbnailService(OssProperties properties, OssStorage storage, OssDatabase database) {
        this.properties = properties;
        this.storage = storage;
        this.database = database;
    }

    /** 返回缓存缩略图路径；不存在则从原图生成。 */
    public Path getOrCreate(String storedName, String variant) {
        Path output = storage.thumbnailPath(storedName, variant);
        if (Files.isRegularFile(output)) {
            try {
                if (Files.size(output) > 0) {
                    return output;
                }
            } catch (Exception ignored) {
                // 重新生成
            }
        }
        Path source = storage.originalPath(storedName);
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("原图不存在");
        }
        String tool = executable();
        if (tool == null) {
            throw new IllegalStateException("未找到 ImageMagick magick 命令");
        }
        Dimensions dimensions = measure(source);
        String resize;
        if (dimensions != null && dimensions.width() > 0 && dimensions.height() > 0) {
            double ratio = "half".equals(variant) ? 0.5 : 0.25;
            int width = Math.max(1, (int) Math.round(dimensions.width() * ratio));
            int height = Math.max(1, (int) Math.round(dimensions.height() * ratio));
            resize = width + "x" + height + "!";
        } else {
            resize = "half".equals(variant) ? "50%" : "25%";
        }
        try {
            Files.createDirectories(output.getParent());
            ProcessResult result = run(List.of(tool, source.toString(),
                    "-auto-orient", "-background", "none",
                    "-resize", resize,
                    "-quality", String.valueOf(Math.max(1, Math.min(100, properties.getThumbnailQuality()))),
                    "-define", "webp:method=6", "-strip", output.toString()));
            if (result.exitCode() != 0 || !Files.isRegularFile(output)) {
                Files.deleteIfExists(output);
                throw new IllegalStateException("缩略图生成失败：" + result.output().trim());
            }
            Dimensions resultSize = measure(output);
            long size = Files.size(output);
            database.saveThumbnail(storedName, variant,
                    resultSize == null ? 0 : resultSize.width(),
                    resultSize == null ? 0 : resultSize.height(), size);
            return output;
        } catch (Exception exception) {
            try {
                Files.deleteIfExists(output);
            } catch (Exception ignored) {
                // ignore
            }
            throw new IllegalStateException("缩略图生成失败", exception);
        }
    }

    public Dimensions measure(Path source) {
        if (source == null || !Files.isRegularFile(source)) {
            return null;
        }
        try (InputStream input = Files.newInputStream(source)) {
            BufferedImage image = ImageIO.read(input);
            if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
                return new Dimensions(image.getWidth(), image.getHeight());
            }
        } catch (Exception ignored) {
            // SVG / WebP 继续交给 ImageMagick
        }
        String tool = executable();
        if (tool == null) {
            return null;
        }
        try {
            ProcessResult result = run(List.of(tool, "identify", "-quiet", "-format", "%w %h", source.toString()));
            if (result.exitCode() != 0) {
                return null;
            }
            String[] parts = result.output().trim().split("\\s+");
            int width = Integer.parseInt(parts[parts.length - 2]);
            int height = Integer.parseInt(parts[parts.length - 1]);
            return width > 0 && height > 0 ? new Dimensions(width, height) : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private String executable() {
        if (searched) {
            return executable;
        }
        synchronized (this) {
            if (searched) {
                return executable;
            }
            searched = true;
            String configured = properties.getMagick();
            if (configured != null && !configured.isBlank() && usable(configured)) {
                executable = configured;
                return executable;
            }
            for (String candidate : List.of("magick",
                    "C:\\Program Files\\ImageMagick-7.1.2-Q16-HDRI\\magick.exe",
                    "C:\\Program Files\\ImageMagick-7.1.0-Q16-HDRI\\magick.exe")) {
                if (usable(candidate)) {
                    executable = candidate;
                    return executable;
                }
            }
            log.warn("OSS 未找到 ImageMagick magick 命令");
            return null;
        }
    }

    private boolean usable(String command) {
        try {
            return run(List.of(command, "-version")).exitCode() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private ProcessResult run(List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return new ProcessResult(-1, "timeout");
        }
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        return new ProcessResult(process.exitValue(), output);
    }

    public record Dimensions(int width, int height) {
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
