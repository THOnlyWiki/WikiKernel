package cc.thonly.wikikernel.controller;

import cc.thonly.wikikernel.common.access.AccessAction;
import cc.thonly.wikikernel.common.data.WikiBlock;
import cc.thonly.wikikernel.common.data.WikiFile;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.service.ActionHookService;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.BlockService;
import cc.thonly.wikikernel.service.FileService;
import cc.thonly.wikikernel.service.PermissionService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.user.UserSession;
import cc.thonly.wikikernel.web.FileTitles;
import cc.thonly.wikikernel.web.RequestActionContext;
import cc.thonly.wikikernel.web.Viewers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 文件上传 / 删除 / 恢复 / 回滚 API（{@code @RestController}）。
 * <p>
 * 上传 / 回滚权限：{@code upload} 动作，目标 {@code 文件:<文件名>}（可用 {@code 文件:*} 前缀规则控制）；
 * 删除 / 恢复权限：{@code delete} 动作，目标 {@code 文件:<文件名>}。查看权限在 {@code GET /files/**} 校验。
 * <p>
 * 同名文件重复上传 = 新版本（旧版本进文件历史，可回滚）；删除默认软删除（可恢复），
 * {@code hard=true} 时彻底删除（含全部历史版本）。
 */
@RestController
@RequestMapping("/api/files")
public class FileApiController {

    private final FileService fileService;
    private final AccessControlService accessControlService;
    private final WikiPageService wikiPageService;
    private final BlockService blockService;
    private final PermissionService permissionService;
    private final ActionHookService actionHookService;
    private final MessageSource messageSource;

    public FileApiController(FileService fileService,
                             AccessControlService accessControlService,
                             WikiPageService wikiPageService,
                             BlockService blockService,
                             PermissionService permissionService,
                             ActionHookService actionHookService,
                             MessageSource messageSource) {
        this.fileService = fileService;
        this.accessControlService = accessControlService;
        this.wikiPageService = wikiPageService;
        this.blockService = blockService;
        this.permissionService = permissionService;
        this.actionHookService = actionHookService;
        this.messageSource = messageSource;
    }

    /**
     * 上传文件：multipart 字段 {@code file}（必填）、{@code name}（可选，展示 / 引用名）、
     * {@code description}（可选）。同名文件已存在时创建新版本；首次上传会顺带创建
     * {@code 文件:<名>} 说明页（说明文字作为初始内容）。
     */
    @PostMapping("/upload")
    public AjaxResult<Map<String, Object>> upload(@RequestParam("file") MultipartFile upload,
                                                  @RequestParam(value = "name", required = false) String name,
                                                  @RequestParam(value = "description", required = false) String description,
                                                  @RequestParam(value = "license", required = false) String license,
                                                  HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("files.loginRequired"));
        }
        Optional<WikiBlock> block = blockService.currentBlock(user);
        if (block.isPresent()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, blockService.notice(block.get()));
        }
        if (upload == null || upload.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("files.empty"));
        }
        String fileName = FileTitles.normalizeName(name);
        if (fileName.isEmpty()) {
            fileName = FileTitles.normalizeName(upload.getOriginalFilename());
        }
        if (!accessControlService.can(AccessAction.UPLOAD, "文件:" + fileName, user)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("files.forbidden"));
        }
        byte[] bytes;
        try {
            bytes = upload.getBytes();
        } catch (IOException exception) {
            return AjaxResult.error(AjaxCode.ERROR, message("files.writeFailed"));
        }
        // 动作前钩子（ActionGuard，如插件版滥用过滤器的 action=upload、审核的上传排队）：
        // 载荷是文件内容，钩子可以把它暂存起来（HANDLED = 已自行处理，core 不再写入正式存储）
        ActionHookService.Decision uploadDecision = actionHookService.beforeAction(RequestActionContext.builder(
                        request, Viewers.of(user, permissionService), "upload")
                .target("文件:" + fileName)
                .summary(description)
                .data("name", fileName)
                .data("size", upload.getSize())
                .data("contentType", upload.getContentType())
                .data("description", description)
                .data("license", license)
                .payload(bytes)
                .build());
        if (uploadDecision.blocked()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, uploadDecision.message());
        }
        if (uploadDecision.handled()) {
            // 钩子已自行处理（例如审核：文件先进队列，通过后才写入正式存储）
            Map<String, Object> handled = new LinkedHashMap<>(uploadDecision.data());
            return AjaxResult.success(uploadDecision.message(), handled);
        }
        try {
            WikiFile file = fileService.store(user, fileName, description, license, bytes);
            ensureDescriptionPage(user, file, description);
            Map<String, Object> data = fileData(file);
            addWarnings(data, uploadDecision);
            return AjaxResult.success(message("files.uploaded"), data);
        } catch (IllegalArgumentException exception) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message(exception.getMessage()));
        } catch (IllegalStateException exception) {
            return AjaxResult.error(AjaxCode.ERROR, message(exception.getMessage()));
        }
    }

    /** 删除文件：{@code id}；{@code hard=true} 彻底删除（含全部历史版本，不可恢复）。 */
    @PostMapping("/delete")
    public AjaxResult<Void> delete(@RequestParam("id") long id,
                                   @RequestParam(value = "hard", defaultValue = "false") boolean hard,
                                   HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("files.loginRequired"));
        }
        Optional<WikiFile> found = fileService.findById(id);
        if (found.isEmpty()) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("files.notFound"));
        }
        WikiFile file = found.get();
        // 软删除用 delete 权限；彻底删除用 purge 权限（默认管理员），可在 特殊:权限配置 里改
        AccessAction action = hard ? AccessAction.PURGE : AccessAction.DELETE;
        if (!accessControlService.can(action, "文件:" + file.getName(), user)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN,
                    message(hard ? "files.purgeForbidden" : "files.deleteForbidden"));
        }
        // 动作前钩子（ActionGuard，如插件版滥用过滤器的 action=delete）
        ActionHookService.Decision deleteDecision = actionHookService.beforeAction(RequestActionContext.builder(
                        request, Viewers.of(user, permissionService), "delete")
                .target("文件:" + file.getName())
                .data("id", id)
                .data("name", file.getName())
                .data("hard", hard)
                .build());
        if (deleteDecision.blocked()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, deleteDecision.message());
        }
        if (deleteDecision.handled()) {
            // 钩子已自行处理（例如审核：删除请求进队列，通过后才真正删除）
            return AjaxResult.success(deleteDecision.message(), null);
        }
        fileService.delete(user, id, hard);
        return AjaxResult.success(message(hard ? "files.hardDeleted" : "files.deleted"), null);
    }

    /** 恢复软删除的文件：{@code id}。 */
    @PostMapping("/restore")
    public AjaxResult<Void> restore(@RequestParam("id") long id, HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("files.loginRequired"));
        }
        Optional<WikiFile> found = fileService.findById(id);
        if (found.isEmpty()) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("files.notFound"));
        }
        WikiFile file = found.get();
        if (!accessControlService.can(AccessAction.DELETE, "文件:" + file.getName(), user)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("files.deleteForbidden"));
        }
        if (!fileService.restore(user, id)) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("files.notFound"));
        }
        return AjaxResult.success(message("files.restored"), null);
    }

    /** 回滚到历史版本：{@code name} + {@code version}（复制旧版本内容为新当前版本）。 */
    @PostMapping("/revert")
    public AjaxResult<Map<String, Object>> revert(@RequestParam("name") String name,
                                                  @RequestParam("version") int version,
                                                  HttpServletRequest request) {
        WikiUser user = UserSession.current(request);
        if (user == null) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("files.loginRequired"));
        }
        String fileName = FileTitles.normalizeName(name);
        if (fileName.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("files.nameRequired"));
        }
        if (!accessControlService.can(AccessAction.UPLOAD, "文件:" + fileName, user)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("files.forbidden"));
        }
        try {
            WikiFile file = fileService.revert(user, fileName, version);
            return AjaxResult.success(message("files.reverted"), fileData(file));
        } catch (IllegalArgumentException exception) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message(exception.getMessage()));
        } catch (IllegalStateException exception) {
            return AjaxResult.error(AjaxCode.ERROR, message(exception.getMessage()));
        }
    }

    /** 首次上传时创建 {@code 文件:<名>} 说明页（已有页面不覆盖）。 */
    private void ensureDescriptionPage(WikiUser user, WikiFile file, String description) {
        String title = FileTitles.titleOf(file.getName());
        if (wikiPageService.getByTitle(title).isEmpty()) {
            wikiPageService.editPage(user, title, Strings.trimToEmpty(description),
                    message("files.uploadComment"));
        }
    }

    private Map<String, Object> fileData(WikiFile file) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", file.getId());
        data.put("name", file.getName());
        data.put("version", file.getVersion() == null ? 1 : file.getVersion());
        data.put("url", fileService.urlOf(file));
        data.put("thumbUrl", fileService.thumbnailUrl(file, 320));
        data.put("pageUrl", fileService.pageUrlOf(file));
        data.put("license", file.getLicense());
        return data;
    }

    /** 动作前钩子的 {@code WARN} 提示放进响应数据（键 {@code warnings}），没有提示时不加。 */
    private static void addWarnings(Map<String, Object> data, ActionHookService.Decision decision) {
        if (!decision.warnings().isEmpty()) {
            data.put("warnings", decision.warnings());
        }
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
