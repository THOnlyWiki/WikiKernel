package cc.thonly.wikikernel.plugin.socialprofile.service;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;

public final class GiftImage {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int SIZE = 128;
    private static final Set<String> ALLOWED = Set.of("png", "jpeg", "gif");

    private GiftImage() {}

    public static byte[] resize(InputStream source) throws IOException {
        try (var stream = new MemoryCacheImageInputStream(source)) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported gift image");
            }
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                if (!ALLOWED.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                    throw new IOException("Unsupported gift image");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 10_000_000) {
                    throw new IOException("Invalid gift image dimensions");
                }
                BufferedImage original = reader.read(0);
                double scale = (double) SIZE / Math.max(width, height);
                int w = Math.max(1, (int) Math.round(width * scale));
                int h = Math.max(1, (int) Math.round(height * scale));
                BufferedImage icon = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
                var graphics = icon.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(original, (SIZE - w) / 2, (SIZE - h) / 2, w, h, null);
                } finally {
                    graphics.dispose();
                }
                var output = new ByteArrayOutputStream();
                if (!ImageIO.write(icon, "png", output)) {
                    throw new IOException("PNG encoder unavailable");
                }
                return output.toByteArray();
            } finally {
                reader.dispose();
            }
        }
    }
}
