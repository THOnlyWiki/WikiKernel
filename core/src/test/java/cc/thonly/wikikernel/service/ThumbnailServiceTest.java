package cc.thonly.wikikernel.service;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.config.FileStorageProperties;
import cc.thonly.wikikernel.config.ThumbnailProperties;
import cc.thonly.wikikernel.service.impl.ThumbnailServiceImpl;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 缩略图：真实调用 ImageMagick 生成 WebP（没有 magick 时跳过）。 */
class ThumbnailServiceTest {

    @TempDir
    Path temp;

    @Test
    void generatesHalfAndQuarterWebpWithoutTouchingOriginal() throws Exception {
        Assumptions.assumeTrue(imageMagickAvailable(), "需要 ImageMagick magick 命令");

        int width = 160;
        int height = 80;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(30, 120, 200));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        Path source = temp.resolve("source.png");
        ImageIO.write(image, "png", source.toFile());

        FileStorageProperties storage = new FileStorageProperties();
        storage.setRunPath(temp.toString());
        ThumbnailProperties thumbnail = new ThumbnailProperties();
        thumbnail.setMaxSizeMb(2048);
        ThumbnailServiceImpl service = new ThumbnailServiceImpl(thumbnail, storage);

        WikiFile file = new WikiFile();
        file.setName("示例.png");
        file.setStoredName("source.png");
        service.generate(file);

        assertTrue(service.exists("source.png", ThumbnailService.HALF));
        assertTrue(service.exists("source.png", ThumbnailService.QUARTER));
        assertTrue(Files.size(temp.resolve("source.png")) > 0);
        assertTrue(service.read("source.png", ThumbnailService.HALF).orElseThrow().length > 0);
        assertEquals(160, service.measure(source).width());
        assertEquals(80, service.measure(source).height());
        assertEquals("/files/thumbnail/source.png/half.webp",
                service.url("source.png", ThumbnailService.HALF));

        // 1×1 小图按百分比会得到 0×0；必须仍生成两档 1×1 WebP
        BufferedImage tinyImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Path tinySource = temp.resolve("tiny.png");
        ImageIO.write(tinyImage, "png", tinySource.toFile());
        WikiFile tiny = new WikiFile();
        tiny.setName("tiny.png");
        tiny.setStoredName("tiny.png");
        service.generate(tiny);
        assertTrue(service.exists("tiny.png", ThumbnailService.HALF));
        assertTrue(service.exists("tiny.png", ThumbnailService.QUARTER));
    }

    @Test
    void quotaDeletesOldestThumbnailsOnly() throws Exception {
        FileStorageProperties storage = new FileStorageProperties();
        storage.setRunPath(temp.toString());
        ThumbnailProperties thumbnail = new ThumbnailProperties();
        thumbnail.setMaxSizeMb(1);
        ThumbnailServiceImpl service = new ThumbnailServiceImpl(thumbnail, storage);

        Path root = temp.resolve("thumbnail");
        Path oldest = root.resolve("old/half.webp");
        Path middle = root.resolve("middle/half.webp");
        Path newest = root.resolve("new/half.webp");
        for (Path path : List.of(oldest, middle, newest)) {
            Files.createDirectories(path.getParent());
            Files.write(path, new byte[600 * 1024]);
        }
        Files.setLastModifiedTime(oldest, java.nio.file.attribute.FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(middle, java.nio.file.attribute.FileTime.fromMillis(2_000));
        Files.setLastModifiedTime(newest, java.nio.file.attribute.FileTime.fromMillis(3_000));
        Path original = temp.resolve("original.png");
        Files.write(original, new byte[10]);

        int deleted = service.enforceQuota();

        assertTrue(deleted >= 1);
        assertTrue(!Files.exists(oldest));
        assertTrue(Files.exists(original));
        long remaining = Files.walk(root).filter(Files::isRegularFile)
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (Exception exception) {
                        return 0;
                    }
                }).sum();
        assertTrue(remaining <= 1024L * 1024L);
    }

    private static boolean imageMagickAvailable() {
        try {
            Process process = new ProcessBuilder("magick", "-version")
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception exception) {
            return false;
        }
    }
}
