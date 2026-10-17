package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.MediaTypes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

/**
 * 上传文件访问：{@code GET /files/{storedName}}（当前版本与历史版本共用）。
 * <p>
 * 查看权限按 {@code 文件:<name>} 走访问规则（默认所有人可看）；软删除的文件仅删除权限者可见（其余 404）。
 * 图片内联展示（可缓存，存储名唯一所以 immutable），其它类型强制下载，避免同源执行。
 */
@Controller
public class FileController {

    private final FileService fileService;
    private final AccessControlService accessControlService;
    private final MessageSource messageSource;

    public FileController(FileService fileService,
                          AccessControlService accessControlService,
                          MessageSource messageSource) {
        this.fileService = fileService;
        this.accessControlService = accessControlService;
        this.messageSource = messageSource;
    }

    @GetMapping("/files/{storedName}")
    public ResponseEntity<byte[]> serve(@PathVariable("storedName") String storedName,
                                        HttpServletRequest request) throws IOException {
        FileService.StoredFile stored = fileService.resolveStored(storedName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        message("files.notFound")));
        WikiUser user = UserSession.current(request);
        if (stored.deleted()) {
            // 已删除文件：仅删除权限者可见；其余按 404 处理，不暴露存在性
            if (!accessControlService.can(AccessAction.DELETE, "文件:" + stored.name(), user)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, message("files.notFound"));
            }
        } else if (!accessControlService.canRead("文件:" + stored.name(), user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message("files.readForbidden"));
        }

        byte[] content = fileService.read(stored.storedName());
        boolean image = fileService.isImage(stored.name());
        boolean playable = MediaTypes.isPlayable(stored.name());
        HttpHeaders headers = new HttpHeaders();
        try {
            headers.setContentType(MediaType.parseMediaType(stored.contentType()));
        } catch (org.springframework.http.InvalidMediaTypeException exception) {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }
        if (!image && !playable) {
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(stored.name(), StandardCharsets.UTF_8)
                    .build());
        }
        // 所有上传文件禁止 MIME 嗅探；SVG 额外用 CSP sandbox 阻断直接打开时的脚本执行。
        headers.set("X-Content-Type-Options", "nosniff");
        if ("svg".equals(MediaTypes.extensionOf(stored.name()))) {
            headers.set("Content-Security-Policy",
                    "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; "
                            + "script-src 'none'; object-src 'none'; sandbox");
        }
        // 支持 HTTP Range（音视频拖动进度条需要 206 Partial Content）
        headers.set("Accept-Ranges", "bytes");
        headers.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        long[] range = parseRange(request.getHeader("Range"), content.length);
        if (range != null) {
            int start = (int) range[0];
            int end = (int) range[1];
            byte[] slice = Arrays.copyOfRange(content, start, end + 1);
            headers.set("Content-Range", "bytes " + start + "-" + end + "/" + content.length);
            headers.setContentLength(slice.length);
            return new ResponseEntity<>(slice, headers, HttpStatus.PARTIAL_CONTENT);
        }
        headers.setContentLength(content.length);
        return new ResponseEntity<>(content, headers, HttpStatus.OK);
    }

    /**
     * 解析单段 {@code Range: bytes=…} 请求；不合法 / 多段返回 null（按完整内容 200 返回）。
     * 支持 {@code start-end}、{@code start-}、{@code -suffix} 三种写法。
     */
    private static long[] parseRange(String header, long total) {
        String value = header == null ? "" : header.trim();
        if (total <= 0 || !value.startsWith("bytes=")) {
            return null;
        }
        String spec = value.substring("bytes=".length()).split(",", 2)[0].trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startText = spec.substring(0, dash).trim();
        String endText = spec.substring(dash + 1).trim();
        try {
            long start;
            long end;
            if (startText.isEmpty()) {
                long suffix = Long.parseLong(endText);
                if (suffix <= 0) {
                    return null;
                }
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(startText);
                end = endText.isEmpty() ? total - 1 : Long.parseLong(endText);
            }
            if (start < 0 || start >= total || end < start) {
                return null;
            }
            return new long[]{start, Math.min(end, total - 1)};
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
