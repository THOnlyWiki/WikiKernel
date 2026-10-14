package cc.thonly.wikikernel.plugin.socialprofile.web;

import cc.thonly.wikikernel.plugin.socialprofile.service.AvatarSupport;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 头像文件服务（插件自己的 {@code @Controller}）：{@code GET /avatars/<文件名>}。
 * <p>
 * 与迁移前 core 的 {@code AvatarController} 一致：只读取 {@code {run-path}/avatars/} 下的图片
 * （文件名白名单与路径穿越防护由 {@code AvatarStore.resolve} 负责），按扩展名推断类型并缓存 1 天。
 */
@Controller
public class AvatarFileController {

    private static final Map<String, MediaType> MEDIA_TYPES = Map.of(
            "png", MediaType.IMAGE_PNG,
            "jpg", MediaType.IMAGE_JPEG,
            "jpeg", MediaType.IMAGE_JPEG,
            "gif", MediaType.IMAGE_GIF,
            "webp", MediaType.parseMediaType("image/webp"));

    private final AvatarSupport avatars;

    public AvatarFileController(AvatarSupport avatars) {
        this.avatars = avatars;
    }

    @GetMapping("/avatars/{name}")
    public ResponseEntity<Resource> avatar(@PathVariable("name") String name) {
        Path path = avatars.resolve(name).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            return ResponseEntity.ok()
                    .contentType(mediaType(name))
                    .contentLength(Files.size(path))
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                    .body(new FileSystemResource(path));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static MediaType mediaType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String extension = dot < 0 ? "" : lower.substring(dot + 1);
        return MEDIA_TYPES.getOrDefault(extension, MediaType.APPLICATION_OCTET_STREAM);
    }
}
