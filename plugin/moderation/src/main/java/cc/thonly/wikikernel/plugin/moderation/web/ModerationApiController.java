package cc.thonly.wikikernel.plugin.moderation.web;

import cc.thonly.wikikernel.plugin.moderation.data.WikiPendingChange;
import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.moderation.ModerationSupport;
import cc.thonly.wikikernel.plugin.moderation.service.ModerationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLConnection;
import java.util.Optional;

/**
 * 审核 API（插件自己的 {@code @RestController}，core 动态挂载；迁移前在 core 的
 * {@code controller/ModerationApiController}）。只有核查员 / 管理员可以通过 / 拒绝待审变更，
 * 或预览待审上传的文件。
 * <p>
 * 路由与迁移前一致：{@code POST /api/moderation/approve|reject}、
 * {@code GET /api/moderation/pending-file/{id}}；当前用户走 {@code common.service.CurrentUser} 桥
 * （插件不依赖 core 的 {@code UserSession}）。
 */
@RestController
@RequestMapping("/api/moderation")
public class ModerationApiController {

    private final ModerationService service;
    private final ModerationSupport support;
    private final CurrentUser currentUser;

    public ModerationApiController(ModerationService service, ModerationSupport support, CurrentUser currentUser) {
        this.service = service;
        this.support = support;
        this.currentUser = currentUser;
    }

    /** 通过一条待审变更：{@code id}（必填）、{@code comment}（审核意见，可空）。 */
    @PostMapping("/approve")
    public AjaxResult<Void> approve(@RequestParam("id") long id,
                                    @RequestParam(value = "comment", required = false) String comment) {
        Viewer reviewer = currentUser.viewer();
        AjaxResult<Void> denied = checkReviewer(reviewer);
        if (denied != null) {
            return denied;
        }
        return outcome(service.approve(reviewer, id, comment), true);
    }

    /** 拒绝一条待审变更：{@code id}（必填）、{@code comment}（理由，可空）。 */
    @PostMapping("/reject")
    public AjaxResult<Void> reject(@RequestParam("id") long id,
                                   @RequestParam(value = "comment", required = false) String comment) {
        Viewer reviewer = currentUser.viewer();
        AjaxResult<Void> denied = checkReviewer(reviewer);
        if (denied != null) {
            return denied;
        }
        return outcome(service.reject(reviewer, id, comment), false);
    }

    /** 预览待审上传的文件内容（审核员专用；文件通过审核后才会写入正式存储）。 */
    @GetMapping("/pending-file/{id}")
    public ResponseEntity<byte[]> pendingFile(@PathVariable("id") long id) {
        if (!ModerationSupport.isReviewer(currentUser.viewer())) {
            return ResponseEntity.status(403).build();
        }
        Optional<byte[]> data = service.pendingFileData(id);
        if (data.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String name = service.find(id)
                .map(WikiPendingChange::getFileName).map(Strings::trimToEmpty).orElse("");
        String guessed = URLConnection.guessContentTypeFromName(name);
        MediaType contentType = guessed == null
                ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(guessed);
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(data.get());
    }

    private AjaxResult<Void> checkReviewer(Viewer reviewer) {
        if (reviewer == null || !reviewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, support.message("permissions.loginRequired"));
        }
        if (!ModerationSupport.isReviewer(reviewer)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, support.message("moderation.forbidden"));
        }
        if (!service.enabled()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, support.message("moderation.disabled"));
        }
        return null;
    }

    private AjaxResult<Void> outcome(ModerationService.ReviewResult result, boolean approved) {
        return switch (result) {
            case OK -> AjaxResult.success(
                    support.message(approved ? "moderation.approved" : "moderation.rejected"), null);
            case NOT_FOUND -> AjaxResult.error(AjaxCode.NOT_FOUND,
                    support.message("moderation.notFound"));
            case ALREADY_REVIEWED -> AjaxResult.error(AjaxCode.BAD_REQUEST,
                    support.message("moderation.alreadyReviewed"));
            case APPLY_FAILED -> AjaxResult.error(AjaxCode.ERROR,
                    support.message("moderation.applyFailed"));
        };
    }
}
