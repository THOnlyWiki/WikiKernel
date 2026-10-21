package cc.thonly.wikikernel.offline;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.ThumbnailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线包的资源下载器。
 *
 * <p>把页面引用的 CSS / JS / 图片 / 字体下载到 {@code site/assets/}，统一使用扁平哈希文件名；
 * CSS {@code url(...)} 会递归改写为本地相对路径。上传的 {@code /files} 图片通过
 * {@link ThumbnailService} 转为 WebP，宽度按原图一半生成（不放大）。</p>
 */
final class OfflineAssetStore {

    private static final Logger log = LoggerFactory.getLogger(OfflineAssetStore.class);
    private static final Pattern CSS_URL = Pattern.compile(
            "url\\(\\s*(['\"]?)(.*?)\\1\\s*\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CSS_IMPORT = Pattern.compile(
            "@import\\s+(?:url\\(\\s*)?(['\"])(.*?)\\1\\s*\\)?\\s*;?",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final String baseUrl;
    private final String cookie;
    private final URI baseUri;
    private final Path assetsRoot;
    private final Path workDir;
    private final FileService fileService;
    private final ThumbnailService thumbnailService;
    private final HttpClient http;

    private final Map<String, String> names = new LinkedHashMap<>();
    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();

    OfflineAssetStore(String baseUrl,
                     String cookie,
                     Path siteRoot,
                     Path workDir,
                     FileService fileService,
                     ThumbnailService thumbnailService) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.cookie = cookie;
        this.baseUri = URI.create(this.baseUrl + "/");
        this.assetsRoot = siteRoot.resolve("assets");
        this.workDir = workDir;
        this.fileService = fileService;
        this.thumbnailService = thumbnailService;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            Files.createDirectories(assetsRoot);
            Files.createDirectories(workDir.resolve("image-tmp"));
        } catch (IOException exception) {
            throw new IllegalStateException("创建离线导出资源目录失败", exception);
        }
    }

    int assetCount() {
        return names.size();
    }

    /**
     * 下载一个站内资源并返回本地文件名（位于 {@code assets/}）。
     * 外部链接、data URL 或无法读取的资源返回 {@code null}，调用方保留原地址。
     */
    String asset(String rawUrl, String contextUrl) {
        URI resolved = resolveInternal(rawUrl, contextUrl);
        if (resolved == null) {
            return null;
        }
        String key = resolved.toString();
        synchronized (names) {
            String existing = names.get(key);
            if (existing != null) {
                return existing;
            }
            if (!inProgress.add(key)) {
                return null;
            }
        }
        try {
            String path = resolved.getPath() == null ? "" : resolved.getPath();
            String name = download(resolved, path);
            if (name != null) {
                synchronized (names) {
                    names.put(key, name);
                }
            }
            return name;
        } finally {
            inProgress.remove(key);
        }
    }

    private String download(URI uri, String path) {
        if (path.startsWith("/files/")) {
            String fileAsset = downloadFileAsset(uri, path);
            if (fileAsset != null) {
                return fileAsset;
            }
        }

        Fetched fetched = fetch(uri);
        if (fetched == null) {
            return null;
        }
        String contentType = fetched.contentType == null
                ? "" : fetched.contentType.toLowerCase(Locale.ROOT);
        String extension = extensionOf(path, contentType);
        byte[] bytes = fetched.body;

        if (isCss(path, contentType)) {
            String name = localName(uri, "css");
            // 先占位，避免 CSS @import 自己 / 循环引用时无限递归。
            synchronized (names) {
                names.put(uri.toString(), name);
            }
            String css = new String(bytes, StandardCharsets.UTF_8);
            css = rewriteCss(css, uri, "");
            write(name, css.getBytes(StandardCharsets.UTF_8));
            return name;
        }

        if (isImage(path, contentType)) {
            byte[] webp = convertGenericImage(bytes, extension, path);
            if (webp != null) {
                String name = localName(uri, "webp");
                write(name, webp);
                return name;
            }
        }

        String name = localName(uri, extension);
        write(name, bytes);
        return name;
    }

    /**
     * 上传文件图片：定位原图，按原图宽度一半生成 WebP；非图片文件直接复制。
     */
    private String downloadFileAsset(URI uri, String path) {
        String decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
        String storedName = null;
        String thumbnailPrefix = "/files/thumbnail/";
        if (decoded.startsWith(thumbnailPrefix)) {
            String rest = decoded.substring(thumbnailPrefix.length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                storedName = rest.substring(0, slash);
            }
        } else {
            storedName = decoded.substring("/files/".length());
        }
        if (storedName == null || storedName.isBlank()) {
            return null;
        }

        FileService.StoredFile stored = fileService.resolveStored(storedName).orElse(null);
        if (stored == null) {
            return null;
        }
        WikiFile file = fileService.findByName(stored.name()).orElse(null);
        if (file != null && fileService.isImage(file)) {
            int width = file.getWidth() == null ? 0 : file.getWidth();
            if (width <= 0) {
                ThumbnailService.Dimensions dimensions = thumbnailService.measure(fileService.pathOf(file));
                width = dimensions == null ? 0 : dimensions.width();
            }
            if (width > 0) {
                Optional<Path> rendered = thumbnailService.ensureWidth(
                        file, Math.max(1, width / 2));
                if (rendered.isPresent()) {
                    try {
                        byte[] bytes = Files.readAllBytes(rendered.get());
                        String name = localName(uri, "webp");
                        write(name, bytes);
                        return name;
                    } catch (IOException exception) {
                        log.debug("读取离线导出 WebP 失败: {}", uri, exception);
                    }
                }
            }
        }

        // 非图片 / 无法生成半尺寸 WebP 时，保留原文件。
        try {
            byte[] bytes = fileService.read(file);
            String extension = extensionOf(path, stored.contentType());
            if (isImage(path, stored.contentType())) {
                byte[] webp = convertGenericImage(bytes, extension, path);
                if (webp != null) {
                    String name = localName(uri, "webp");
                    write(name, webp);
                    return name;
                }
            }
            String name = localName(uri, extension);
            write(name, bytes);
            return name;
        } catch (Exception exception) {
            log.debug("读取离线导出文件失败: {}", uri, exception);
            return null;
        }
    }

    /**
     * 对皮肤 / 插件里的普通图片也执行半尺寸 WebP；SVG 或 ImageMagick 不可用时回退原字节。
     */
    private byte[] convertGenericImage(byte[] bytes, String extension, String path) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(workDir.resolve("image-tmp"), "image-", "." + extension);
            Files.write(temporary, bytes);
            ThumbnailService.Dimensions dimensions = thumbnailService.measure(temporary);
            if (dimensions == null || dimensions.width() <= 0) {
                return null;
            }
            Optional<Path> rendered = thumbnailService.ensureWidth(
                    temporary, path, Math.max(1, dimensions.width() / 2));
            if (rendered.isPresent()) {
                return Files.readAllBytes(rendered.get());
            }
        } catch (Exception exception) {
            log.debug("通用图片转 WebP 失败: {}", path, exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响导出
                }
            }
        }
        return null;
    }

    /** 重写 CSS 内部 url(...) / @import；urlPrefix 为空表示 CSS 本身就在 assets/ 下。 */
    String rewriteCss(String css, URI context, String urlPrefix) {
        Matcher imports = CSS_IMPORT.matcher(css);
        StringBuffer afterImports = new StringBuffer();
        while (imports.find()) {
            String ref = imports.group(2);
            String asset = asset(ref, context.toString());
            String replacement = imports.group(0);
            if (asset != null) {
                replacement = "@import url(" + urlPrefix + asset + ");";
            }
            imports.appendReplacement(afterImports, Matcher.quoteReplacement(replacement));
        }
        imports.appendTail(afterImports);

        Matcher urls = CSS_URL.matcher(afterImports.toString());
        StringBuffer out = new StringBuffer();
        while (urls.find()) {
            String quote = urls.group(1);
            String ref = urls.group(2).trim();
            String asset = asset(ref, context.toString());
            String replacement = urls.group(0);
            if (asset != null) {
                replacement = "url(" + quote + urlPrefix + asset + quote + ")";
            }
            urls.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        urls.appendTail(out);
        return out.toString();
    }

    private Fetched fetch(URI uri) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(120))
                    .header("User-Agent", "WikiKernel-OfflineExport/1.0")
                    .GET();
            if (cookie != null && !cookie.isBlank()) {
                builder.header("Cookie", cookie);
            }
            HttpRequest request = builder.build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            String contentType = response.headers().firstValue("content-type").orElse("");
            return new Fetched(response.body(), contentType);
        } catch (Exception exception) {
            log.debug("离线导出下载失败: {}", uri, exception);
            return null;
        }
    }

    private URI resolveInternal(String rawUrl, String contextUrl) {
        if (rawUrl == null || rawUrl.isBlank()
                || rawUrl.startsWith("data:") || rawUrl.startsWith("blob:")
                || rawUrl.startsWith("mailto:") || rawUrl.startsWith("javascript:")) {
            return null;
        }
        try {
            URI base = URI.create(contextUrl == null || contextUrl.isBlank() ? baseUrl + "/" : contextUrl);
            URI resolved = base.resolve(rawUrl.trim());
            String scheme = resolved.getScheme();
            if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return null;
            }
            if (!sameOrigin(resolved)) {
                return null;
            }
            return resolved;
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean sameOrigin(URI uri) {
        if (uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost();
        String baseHost = baseUri.getHost();
        if (host.equalsIgnoreCase(baseHost)) {
            return true;
        }
        return ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
                && uri.getPort() == baseUri.getPort();
    }

    private String localName(URI uri, String extension) {
        String ext = extension == null || extension.isBlank() ? "bin" : extension;
        return "asset-" + sha256(uri.toString()).substring(0, 20) + "." + ext;
    }

    private void write(String name, byte[] bytes) {
        try {
            Path target = assetsRoot.resolve(name).normalize();
            if (!target.startsWith(assetsRoot)) {
                return;
            }
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException exception) {
            log.warn("写入离线资源失败: {}", name, exception);
        }
    }

    private static boolean isCss(String path, String contentType) {
        return contentType.contains("text/css") || path.toLowerCase(Locale.ROOT).endsWith(".css");
    }

    private static boolean isImage(String path, String contentType) {
        if (contentType.startsWith("image/")) {
            return true;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")
                || lower.endsWith(".avif") || lower.endsWith(".svg") || lower.endsWith(".ico");
    }

    private static String extensionOf(String path, String contentType) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && dot < lower.length() - 1 && lower.length() - dot <= 8) {
            String ext = lower.substring(dot + 1);
            if (ext.matches("[a-z0-9]+")) {
                return ext;
            }
        }
        if (contentType.contains("css")) {
            return "css";
        }
        if (contentType.contains("javascript")) {
            return "js";
        }
        if (contentType.contains("webp")) {
            return "webp";
        }
        if (contentType.contains("svg")) {
            return "svg";
        }
        if (contentType.contains("png")) {
            return "png";
        }
        if (contentType.contains("jpeg") || contentType.contains("jpg")) {
            return "jpg";
        }
        if (contentType.contains("font/woff2") || contentType.contains("woff2")) {
            return "woff2";
        }
        if (contentType.contains("font/woff") || contentType.contains("woff")) {
            return "woff";
        }
        return "bin";
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private record Fetched(byte[] body, String contentType) {
    }
}
