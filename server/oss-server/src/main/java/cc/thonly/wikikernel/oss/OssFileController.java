package cc.thonly.wikikernel.oss;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** OSS 写接口（密钥）与公开读取接口（无需密钥）。 */
@RestController
public class OssFileController {

    private final OssProperties properties;
    private final OssStorage storage;
    private final OssDatabase database;
    private final OssThumbnailService thumbnails;

    public OssFileController(OssProperties properties, OssStorage storage,
                             OssDatabase database, OssThumbnailService thumbnails) {
        this.properties = properties;
        this.storage = storage;
        this.database = database;
        this.thumbnails = thumbnails;
    }

    @PutMapping("/api/files/{storedName}")
    public Map<String, Object> upload(@PathVariable String storedName,
                                      @RequestHeader(value = "X-OSS-Key", required = false) String key,
                                      @RequestParam("name") String name,
                                      @RequestParam(value = "contentType", required = false) String contentType,
                                      @RequestParam(value = "fileId", defaultValue = "0") long fileId,
                                      @RequestParam(value = "version", defaultValue = "1") int version,
                                      @RequestParam(value = "current", defaultValue = "true") boolean current,
                                      @RequestBody(required = false) byte[] content) {
        requireKey(key);
        if (content == null || content.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "空文件");
        }
        try {
            storage.store(storedName, content);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "写入文件失败", exception);
        }
        String now = Instant.now().toString();
        String mediaType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
        OssFile existing = database.find(storedName).orElse(null);
        database.save(new OssFile(storedName, name, mediaType, content.length, fileId, version,
                current, false, existing == null ? now : existing.createdAt(), now));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("storedName", storedName);
        result.put("size", content.length);
        result.put("version", version);
        return result;
    }

    @DeleteMapping("/api/files/{storedName}")
    public Map<String, Object> delete(@PathVariable String storedName,
                                      @RequestHeader(value = "X-OSS-Key", required = false) String key) {
        requireKey(key);
        storage.delete(storedName);
        database.delete(storedName);
        return Map.of("deleted", storedName);
    }

    @PatchMapping("/api/files/by-id/{fileId}/deleted")
    public Map<String, Object> markDeleted(@PathVariable long fileId,
                                           @RequestParam boolean deleted,
                                           @RequestHeader(value = "X-OSS-Key", required = false) String key) {
        requireKey(key);
        database.markDeleted(fileId, deleted);
        return Map.of("fileId", fileId, "deleted", deleted);
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "time", Instant.now().toString());
    }

    @GetMapping("/files/{storedName}")
    public ResponseEntity<byte[]> original(@PathVariable String storedName) {
        OssFile file = requireVisible(storedName);
        try {
            byte[] content = storage.read(file.storedName());
            HttpHeaders headers = publicHeaders(file.contentType());
            headers.setContentLength(content.length);
            return new ResponseEntity<>(content, headers, HttpStatus.OK);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在", exception);
        }
    }

    @GetMapping("/thumbnails/{storedName}/{variantFile}")
    public ResponseEntity<byte[]> thumbnail(@PathVariable String storedName,
                                            @PathVariable String variantFile) {
        OssFile file = requireVisible(storedName);
        String variant = variantFile.endsWith(".webp")
                ? variantFile.substring(0, variantFile.length() - 5) : variantFile;
        if (!"half".equals(variant) && !"quarter".equals(variant)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "缩略图不存在");
        }
        try {
            java.nio.file.Path path = thumbnails.getOrCreate(file.storedName(), variant);
            byte[] content = Files.readAllBytes(path);
            HttpHeaders headers = publicHeaders("image/webp");
            headers.setContentLength(content.length);
            return new ResponseEntity<>(content, headers, HttpStatus.OK);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "缩略图不存在", exception);
        }
    }

    private OssFile requireVisible(String storedName) {
        OssFile file = database.find(storedName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在"));
        if (file.deleted()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
        }
        return file;
    }

    private HttpHeaders publicHeaders(String contentType) {
        HttpHeaders headers = new HttpHeaders();
        try {
            headers.setContentType(MediaType.parseMediaType(contentType));
        } catch (Exception exception) {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Access-Control-Allow-Origin", "*");
        headers.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        return headers;
    }

    private void requireKey(String supplied) {
        String expected = properties.getKey() == null ? "" : properties.getKey();
        String actual = supplied == null ? "" : supplied;
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "密钥错误");
        }
    }
}
