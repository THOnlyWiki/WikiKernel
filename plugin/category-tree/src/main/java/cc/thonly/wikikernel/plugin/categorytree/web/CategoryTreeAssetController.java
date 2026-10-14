package cc.thonly.wikikernel.plugin.categorytree.web;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/**
 * 插件静态资源端点（core 的 {@code PluginWebRegistrar} 会把插件里的 {@code @RestController}
 * 挂到主 MVC 上）：把插件 jar 里 {@code static/plugins/category-tree/} 下的 JS / CSS 暴露给浏览器。
 * <p>
 * 之所以不用 {@code /static/**}：插件 jar 的资源不在 core 的静态资源目录里，
 * 走一个显式端点最直接，也方便加 {@code Cache-Control}。
 */
@RestController
@RequestMapping("/plugins/category-tree")
public final class CategoryTreeAssetController {

    /** 插件 jar 内的资源前缀。 */
    private static final String RESOURCE_PREFIX = "static/plugins/category-tree/";

    /** 缓存一天（对应原版 ResourceLoader 的版本化 URL）。 */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofDays(1)).cachePublic();

    /** 脚本内容缓存（jar 内资源不可变，进程内读一次即可）。 */
    private volatile byte[] script;

    /** 样式内容缓存。 */
    private volatile byte[] stylesheet;

    @GetMapping(value = "/ext.categoryTree.js", produces = "application/javascript;charset=UTF-8")
    public ResponseEntity<byte[]> script() {
        byte[] cached = this.script;
        if (cached == null) {
            cached = read("ext.categoryTree.js");
            this.script = cached;
        }
        return response(cached, "application/javascript;charset=UTF-8");
    }

    @GetMapping(value = "/category-tree.css", produces = "text/css;charset=UTF-8")
    public ResponseEntity<byte[]> stylesheet() {
        byte[] cached = this.stylesheet;
        if (cached == null) {
            cached = read("category-tree.css");
            this.stylesheet = cached;
        }
        return response(cached, "text/css;charset=UTF-8");
    }

    private byte[] read(String name) {
        ClassLoader classLoader = getClass().getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(RESOURCE_PREFIX + name)) {
            return input == null ? new byte[0] : input.readAllBytes();
        } catch (IOException exception) {
            return new byte[0];
        }
    }

    private static ResponseEntity<byte[]> response(byte[] body, String contentType) {
        if (body.length == 0) {
            return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .cacheControl(CACHE)
                .body(body);
    }
}
