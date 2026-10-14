package cc.thonly.wikikernel.plugin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * 插件静态资源端点：{@code GET /plugins/{插件id}/{路径}} → 插件 jar 内
 * {@code static/plugins/{插件id}/{路径}}。
 * <p>
 * 插件把静态文件放在自己的 {@code src/main/resources/static/plugins/<id>/…}，
 * 代码里（如 {@code HtmlHeadProvider}）引用 {@code /plugins/<id>/文件} 即可；
 * 带防目录穿越、仅服务已加载插件、一天公共缓存。
 */
@RestController
public class PluginAssetController {

    private final PluginManager pluginManager;

    public PluginAssetController(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @GetMapping("/plugins/{pluginId}/{*path}")
    public ResponseEntity<byte[]> asset(@PathVariable("pluginId") String pluginId,
                                        @PathVariable("path") String path,
                                        HttpServletRequest request) {
        Optional<LoadedPlugin> loaded = pluginManager.find(pluginId);
        if (loaded.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "插件不存在: " + pluginId);
        }
        String relative = normalize(path);
        if (relative == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "非法资源路径");
        }
        String resource = "static/plugins/" + pluginId + "/" + relative;
        ClassLoader classLoader = loaded.get().classLoader() != null
                ? loaded.get().classLoader()
                : getClass().getClassLoader();
        byte[] content;
        try (InputStream input = classLoader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不存在: " + relative);
            }
            content = input.readAllBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "读取插件资源失败", exception);
        }
        String etag = "\"" + Integer.toHexString(java.util.Arrays.hashCode(content)) + "\"";
        String ifNoneMatch = request.getHeader("If-None-Match");
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).mustRevalidate().cachePublic())
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(contentType(relative))
                .eTag(etag)
                // 插件资源随插件 jar 更新（替换 jar 重启后应尽快生效）：短缓存 + 每次校验 ETag
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).mustRevalidate().cachePublic())
                .body(content);
    }

    /** 规范化路径：去掉前导斜杠，拒绝 {@code ..} 与空段；非法返回 null。 */
    private static String normalize(String path) {
        String value = path == null ? "" : path.trim().replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.isEmpty()) {
            return null;
        }
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return null;
            }
        }
        return value;
    }

    private static MediaType contentType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".js")) {
            return MediaType.parseMediaType("application/javascript");
        }
        if (lower.endsWith(".css")) {
            return MediaType.parseMediaType("text/css");
        }
        if (lower.endsWith(".json")) {
            return MediaType.APPLICATION_JSON;
        }
        if (lower.endsWith(".svg")) {
            return MediaType.parseMediaType("image/svg+xml");
        }
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (lower.endsWith(".woff2")) {
            return MediaType.parseMediaType("font/woff2");
        }
        if (lower.endsWith(".woff")) {
            return MediaType.parseMediaType("font/woff");
        }
        if (lower.endsWith(".wasm")) {
            return MediaType.parseMediaType("application/wasm");
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            return MediaType.TEXT_PLAIN;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
