package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.web.WikiUrls;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.ThumbnailService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.FileTitles;
import cc.thonly.wikikernel.web.WikiTitles;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * MediaWiki 兼容入口：{@code index.php}、{@code load.php}、{@code thumb.php}。
 * <p>
 * 这些入口主要用于兼容从 MediaWiki 迁过来的外链、脚本和工具：
 * <ul>
 *     <li>{@code index.php?title=...} → 302 到本站 {@code /wiki/...}；{@code action=raw} 直接返回源码；</li>
 *     <li>{@code load.php?modules=site.styles|site} → 把本站的站点 CSS / JS 以 ResourceLoader 风格返回；</li>
 *     <li>{@code thumb.php?f=...&w=...} → 按需生成 WebP（有界队列，默认 2），流式返回。</li>
 * </ul>
 */
@Controller
public class MediaWikiCompatController {

    private final WikiPageService wikiPageService;
    private final AccessControlService accessControlService;
    private final FileService fileService;
    private final ThumbnailService thumbnailService;

    public MediaWikiCompatController(WikiPageService wikiPageService,
                                     AccessControlService accessControlService,
                                     FileService fileService,
                                     ThumbnailService thumbnailService) {
        this.wikiPageService = wikiPageService;
        this.accessControlService = accessControlService;
        this.fileService = fileService;
        this.thumbnailService = thumbnailService;
    }

    /** MediaWiki 页面入口兼容：保留 title/oldid/diff 等参数，跳到本站规范页面。 */
    @GetMapping("/index.php")
    public Object index(@RequestParam Map<String, String> params,
                        HttpServletRequest request) {
        String action = params.getOrDefault("action", "view");
        if ("search".equalsIgnoreCase(action) || params.containsKey("search")) {
            String query = firstNonBlank(params.get("search"), params.get("q"));
            return new ModelAndView("redirect:" + WikiUrls.pagePath("特殊:搜索")
                    + "?q=" + encode(query));
        }
        String title = params.get("title");
        if ((title == null || title.isBlank()) && params.get("curid") != null) {
            Long id = parseLong(params.get("curid"));
            if (id != null) {
                title = wikiPageService.getById(id).map(WikiPage::getTitle).orElse(null);
            }
        }
        if (title == null || title.isBlank()) {
            return new ModelAndView("redirect:/");
        }
        String normalized = WikiTitles.normalize(title);
        if ("raw".equalsIgnoreCase(action)) {
            WikiPage page = wikiPageService.getVisibleByTitle(normalized)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            WikiUser user = UserSession.current(request);
            if (!accessControlService.canRead(normalized, user)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
            String source = wikiPageService.getLatestContent(page.getId()).orElse("");
            // 与 /wiki/...?action=raw 保持同一白名单：只允许纯文本 / JS / CSS，避免 index.php 变成 text/html XSS 入口。
            String ctype = params.get("ctype") == null ? "" : params.get("ctype").trim().toLowerCase(java.util.Locale.ROOT);
            MediaType type = switch (ctype) {
                case "text/javascript", "application/javascript", "text/ecmascript" ->
                        MediaType.parseMediaType("text/javascript;charset=UTF-8");
                case "text/css" -> MediaType.parseMediaType("text/css;charset=UTF-8");
                default -> MediaType.parseMediaType("text/plain;charset=UTF-8");
            };
            return ResponseEntity.ok()
                    .contentType(type)
                    .cacheControl(CacheControl.noCache())
                    .body(source);
        }
        StringBuilder target = new StringBuilder(WikiUrls.pagePath(normalized));
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            if ("title".equals(key) || "curid".equals(key)) {
                continue;
            }
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(encode(key)).append('=').append(encode(entry.getValue()));
        }
        if (query.length() > 0) {
            target.append('?').append(query);
        }
        return new ModelAndView("redirect:" + target);
    }

    /**
     * ResourceLoader 兼容：site.styles / site（以及 user.*）返回本站站点样式 / 脚本。
     * 未知模块返回注释占位，不返回 404，避免旧 MediaWiki 页面因单个模块失败整页脚本中断。
     */
    @GetMapping("/load.php")
    public ResponseEntity<String> load(@RequestParam Map<String, String> params) {
        String modules = params.getOrDefault("modules", "");
        String only = params.getOrDefault("only", "");
        String skin = params.getOrDefault("skin", "");
        boolean styles = "styles".equalsIgnoreCase(only)
                || (only.isBlank() && (modules.contains("styles") || modules.endsWith(".css")));
        // modules 来自查询串：放进注释前去掉会闭合注释的序列，避免把注释变成可执行内容。
        String moduleLabel = modules.replace("*/", "* /")
                .replace('\r', ' ').replace('\n', ' ');
        StringBuilder body = new StringBuilder();
        body.append("/* WikiKernel ResourceLoader compatibility: ").append(moduleLabel).append(" */\n");
        try {
            if (styles) {
                for (String css : wikiPageService.getCSS("", skin)) {
                    body.append(css).append('\n');
                }
            } else {
                for (String jsTitle : wikiPageService.getJS("", skin)) {
                    wikiPageService.getVisibleByTitle(jsTitle)
                            .flatMap(page -> wikiPageService.getLatestContent(page.getId()))
                            .ifPresent(source -> body.append(source).append('\n'));
                }
            }
        } catch (RuntimeException ignored) {
            // 没有站点资源时返回注释占位；不能拖垮旧页面。
        }
        MediaType type = styles
                ? MediaType.parseMediaType("text/css;charset=UTF-8")
                : MediaType.parseMediaType("text/javascript;charset=UTF-8");
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(body.toString());
    }

    /**
     * 图片缩略图兼容：{@code f} 文件名、{@code w/width} 目标宽度。
     * 只生成 WebP；目标宽度不会超过原图（不放大）；响应从磁盘流式输出。
     */
    @GetMapping("/thumb.php")
    public void thumb(@RequestParam Map<String, String> params,
                      HttpServletRequest request,
                      jakarta.servlet.http.HttpServletResponse response) throws IOException {
        String rawName = params.get("f");
        if (rawName == null || rawName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing f");
        }
        String name = FileTitles.isFileTitle(rawName)
                ? FileTitles.normalizeName(FileTitles.fileNameOf(rawName))
                : FileTitles.normalizeName(rawName);
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid f");
        }
        WikiFile file = fileService.findByName(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        WikiUser user = UserSession.current(request);
        if (!accessControlService.canRead("文件:" + name, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        int width = parseInt(firstNonBlank(params.get("w"), params.get("width")), 0);
        java.util.Optional<Path> rendered = thumbnailService.ensureWidth(file, width);
        Path source;
        String contentType;
        if (rendered.isPresent()) {
            source = rendered.get();
            contentType = "image/webp";
        } else {
            // ImageMagick 不可用 / 非图片：回退原图；远端 OSS 原图则交给外部 URL。
            source = fileService.pathOf(file);
            contentType = file.getContentType();
            if (source == null || !Files.isRegularFile(source)) {
                String remote = fileService.urlOf(file);
                if (remote.startsWith("http://") || remote.startsWith("https://")) {
                    response.sendRedirect(remote);
                    return;
                }
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        }
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(contentType);
        response.setContentLengthLong(Files.size(source));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=604800");
        response.setHeader("X-Content-Type-Options", "nosniff");
        try (var output = response.getOutputStream()) {
            Files.copy(source, output);
            output.flush();
        }
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}


