package cc.thonly.wikikernel.offline;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.skin.SkinRegistry;
import cc.thonly.wikikernel.user.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 离线导出 API。页面本身在 {@code 特殊:导出离线}，这里负责异步任务和 ZIP 下载。
 * <p>只允许行政员调用；所有任务、状态和下载都会再次校验发起者和当前用户。</p>
 */
@RestController
@RequestMapping("/api/offline-export")
public class OfflineExportController {

    private final OfflineExportService exportService;
    private final PermissionService permissionService;
    private final SkinRegistry skinRegistry;

    public OfflineExportController(OfflineExportService exportService,
                                   PermissionService permissionService,
                                   SkinRegistry skinRegistry) {
        this.exportService = exportService;
        this.permissionService = permissionService;
        this.skinRegistry = skinRegistry;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> start(
            HttpServletRequest request,
            @RequestParam(value = "limit", defaultValue = "0") int limit,
            @RequestParam(value = "page", required = false) String page) {
        WikiUser user = UserSession.current(request);
        if (!canExport(user)) {
            return forbidden();
        }
        String baseUrl = localBaseUrl(request);
        String skinId = skinRegistry.active(request).id();
        java.util.List<String> selected = page == null || page.isBlank()
                ? java.util.List.of() : java.util.List.of(page.trim());
        OfflineExportJob job = exportService.start(baseUrl, skinId, user, limit,
                request.getHeader(HttpHeaders.COOKIE), selected);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", job.id());
        body.put("state", job.state().name().toLowerCase(java.util.Locale.ROOT));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable("id") String id,
                                                      HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (!canExport(user)) {
            return forbidden();
        }
        Optional<OfflineExportJob> job = exportService.find(id);
        if (job.isEmpty() || job.get().ownerId() != user.getId()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(job.get().view());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@PathVariable("id") String id,
                                      HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (!canExport(user)) {
            return forbiddenResource();
        }
        Optional<OfflineExportJob> found = exportService.find(id);
        if (found.isEmpty() || found.get().ownerId() != user.getId()) {
            return ResponseEntity.notFound().build();
        }
        OfflineExportJob job = found.get();
        if (job.state() != OfflineExportJob.State.DONE) {
            return ResponseEntity.status(409).body(Map.of("message", "导出任务尚未完成"));
        }
        Optional<Path> archive = exportService.archive(id);
        if (archive.isEmpty() || !Files.isRegularFile(archive.get())) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(archive.get());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("wiki-offline-" + id + ".zip", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentLength(archive.get().toFile().length())
                .body(resource);
    }

    private boolean canExport(WikiUser user) {
        return user != null && user.getId() != null
                && permissionService.has(user.getId(), PermissionService.GROUP_BUREAUCRAT);
    }

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("message", "需要行政员权限"));
    }

    private static ResponseEntity<Map<String, Object>> forbiddenResource() {
        return ResponseEntity.status(403).body(Map.of("message", "需要行政员权限"));
    }

    private static String localBaseUrl(HttpServletRequest request) {
        String scheme = request.getScheme() == null ? "http" : request.getScheme();
        int port = request.getLocalPort() > 0 ? request.getLocalPort() : request.getServerPort();
        String context = request.getContextPath() == null ? "" : request.getContextPath();
        return scheme + "://127.0.0.1:" + port + context;
    }
}
