package cc.thonly.wikikernel.plugin.oss;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.extension.FileUrlResolver;

/** core 文件 URL → OSS / CDN。 */
public final class OssResolver implements FileUrlResolver {

    private final OssSettings settings;
    private final OssClient client;

    public OssResolver(OssSettings settings, OssClient client) {
        this.settings = settings;
        this.client = client;
    }

    @Override
    public boolean handlesOriginals() {
        return settings.handlesOriginals();
    }

    @Override
    public boolean handlesThumbnails() {
        return true;
    }

    @Override
    public boolean available() {
        return settings.enabled() && client.available();
    }

    @Override
    public String originalUrl(WikiFile file) {
        return file == null ? null : originalUrl(file.getStoredName());
    }

    @Override
    public String originalUrl(String storedName) {
        if (!available() || storedName == null || storedName.isBlank()) {
            return null;
        }
        return settings.readBase() + "/files/" + pathSegment(storedName);
    }

    @Override
    public String thumbnailUrl(WikiFile file, String variant) {
        if (!available() || file == null || file.getStoredName() == null
                || variant == null || variant.isBlank()) {
            return null;
        }
        return settings.readBase() + "/thumbnails/" + pathSegment(file.getStoredName())
                + "/" + variant + ".webp";
    }

    private static String pathSegment(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
