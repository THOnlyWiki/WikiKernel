package cc.thonly.wikikernel.skin;

import cc.thonly.wikikernel.common.extension.Skin;
import cc.thonly.wikikernel.common.util.Strings;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 皮肤静态资源：{@code GET /skins/{skinId}/{path}}。
 * <p>
 * 统一从皮肤所属类加载器读取 {@code static/skins/<id>/<path>}——内置皮肤读 core classpath，
 * 插件皮肤读插件 jar；带路径穿越防护；资源内容随启动版本号缓存（immutable）。
 * 禁用的皮肤返回 404。
 */
@Controller
public class SkinAssetController {

    private static final Map<String, MediaType> MEDIA_TYPES = Map.ofEntries(
            Map.entry("css", MediaType.parseMediaType("text/css;charset=UTF-8")),
            Map.entry("js", MediaType.parseMediaType("text/javascript;charset=UTF-8")),
            Map.entry("svg", MediaType.parseMediaType("image/svg+xml")),
            Map.entry("png", MediaType.IMAGE_PNG),
            Map.entry("jpg", MediaType.IMAGE_JPEG),
            Map.entry("jpeg", MediaType.IMAGE_JPEG),
            Map.entry("gif", MediaType.IMAGE_GIF),
            Map.entry("webp", MediaType.parseMediaType("image/webp")),
            Map.entry("ico", MediaType.parseMediaType("image/x-icon")),
            Map.entry("woff", MediaType.parseMediaType("font/woff")),
            Map.entry("woff2", MediaType.parseMediaType("font/woff2")),
            Map.entry("ttf", MediaType.parseMediaType("font/ttf")));

    private final SkinRegistry skinRegistry;

    public SkinAssetController(SkinRegistry skinRegistry) {
        this.skinRegistry = skinRegistry;
    }

    @GetMapping("/skins/{skinId}/{*path}")
    public ResponseEntity<byte[]> asset(@PathVariable("skinId") String skinId,
                                        @PathVariable("path") String path) throws IOException {
        Skin skin = skinRegistry.byId(skinId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // PathPattern 的 {*path} 捕获值带前导 /，先去掉再校验
        String relative = Strings.trimToEmpty(path).replace('\\', '/');
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (relative.isEmpty() || relative.contains("..")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        String resource = "static/skins/" + skin.id() + "/" + relative;
        ClassLoader classLoader = skin.getClass().getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(mediaTypeOf(relative));
            headers.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
            return new ResponseEntity<>(input.readAllBytes(), headers, HttpStatus.OK);
        }
    }

    private static MediaType mediaTypeOf(String path) {
        int dot = path.lastIndexOf('.');
        String extension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MEDIA_TYPES.getOrDefault(extension, MediaType.APPLICATION_OCTET_STREAM);
    }
}
