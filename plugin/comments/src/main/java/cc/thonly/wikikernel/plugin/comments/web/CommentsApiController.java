package cc.thonly.wikikernel.plugin.comments.web;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.CurrentUser;
import cc.thonly.wikikernel.common.service.LogWriter;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.util.AjaxCode;
import cc.thonly.wikikernel.common.util.AjaxResult;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.comments.data.WikiComment;
import cc.thonly.wikikernel.plugin.comments.service.CommentSection;
import cc.thonly.wikikernel.plugin.comments.service.CommentStore;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论 API（插件自己的 {@code @RestController}，由 core 动态挂载）：
 * 发表 / 回复（{@code POST /api/comments}）、删除（{@code POST /api/comments/delete}）。
 * <p>
 * 与迁移前 core 的 {@code CommentApiController} 语义一致：发表需要登录 + 对该页面有查看权限 + 未被封禁；
 * 删除限作者本人或「评论管理员」/ 管理员 / 行政员；发表 / 删除写通用日志
 * （类型 {@code comment} / {@code commentdelete}，由插件注册）。
 */
@RestController
@RequestMapping("/api/comments")
public class CommentsApiController {

    /** 日志里保留的评论摘要长度。 */
    private static final int EXCERPT_LIMIT = 120;

    private final CommentSection section;
    private final CommentStore comments;
    private final AccessCheck access;
    private final CurrentUser currentUser;
    private final SiteConfig siteConfig;
    private final LogWriter logWriter;
    private final MessageSource messageSource;

    public CommentsApiController(CommentSection section, CommentStore comments, AccessCheck access,
                                 CurrentUser currentUser, SiteConfig siteConfig, LogWriter logWriter,
                                 MessageSource messageSource) {
        this.section = section;
        this.comments = comments;
        this.access = access;
        this.currentUser = currentUser;
        this.siteConfig = siteConfig;
        this.logWriter = logWriter;
        this.messageSource = messageSource;
    }

    /** 发表评论 / 回复（{@code parentId} 为空 = 顶层评论）。 */
    @PostMapping
    public AjaxResult<Map<String, Object>> post(@RequestParam("page") String page,
                                                @RequestParam("content") String content,
                                                @RequestParam(value = "parentId", required = false) Long parentId) {
        if (!section.enabled()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("feature.commentsDisabled"));
        }
        Viewer viewer = currentUser.viewer();
        if (!viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        Optional<String> block = access.blockNotice(viewer);
        if (block.isPresent()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, block.get());
        }
        String title = Strings.trimToEmpty(page);
        if (title.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("error.titleRequired"));
        }
        if (!access.canRead(title, viewer)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("comment.readForbidden"));
        }
        String text = Strings.trimToEmpty(content);
        if (text.isEmpty()) {
            return AjaxResult.error(AjaxCode.BAD_REQUEST, message("comment.contentEmpty"));
        }
        if (parentId != null) {
            WikiComment parent = comments.find(parentId).orElse(null);
            if (parent == null || !title.equals(parent.getPageTitle())) {
                return AjaxResult.error(AjaxCode.BAD_REQUEST, message("comment.parentMissing"));
            }
        }
        WikiComment comment = new WikiComment();
        comment.setPageTitle(title);
        comment.setParentId(parentId);
        comment.setUserId(viewer.id());
        comment.setContent(text);
        comment.setDeleted(false);
        comment.setCreateAt(LocalDateTime.now());
        comments.insert(comment);
        logWriter.record("comment", viewer.id(), title, excerpt(text));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", comment.getId());
        return AjaxResult.success(message("comment.posted"), data);
    }

    /** 删除评论（软删除）。 */
    @PostMapping("/delete")
    public AjaxResult<Void> delete(@RequestParam("id") long id) {
        if (!section.enabled()) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("feature.commentsDisabled"));
        }
        Viewer viewer = currentUser.viewer();
        if (!viewer.loggedIn()) {
            return AjaxResult.error(AjaxCode.UNAUTHORIZED, message("permissions.loginRequired"));
        }
        WikiComment comment = comments.find(id).orElse(null);
        if (comment == null || Boolean.TRUE.equals(comment.getDeleted())) {
            return AjaxResult.error(AjaxCode.NOT_FOUND, message("comment.notFound"));
        }
        if (!section.canDelete(viewer, comment)) {
            return AjaxResult.error(AjaxCode.FORBIDDEN, message("comment.deleteForbidden"));
        }
        comment.setDeleted(true);
        comment.setDeletedBy(viewer.id());
        comment.setDeletedAt(LocalDateTime.now());
        comments.update(comment);
        logWriter.record("commentdelete", viewer.id(), comment.getPageTitle(), excerpt(comment.getContent()));
        return AjaxResult.success(message("comment.deleteSuccess"), null);
    }

    /** 用户交互开关（评论头像显示与否；插件 social-profile，旧键 features.user-interaction 仍兼容）。 */
    boolean userInteractionEnabled() {
        return siteConfig == null
                || siteConfig.getBoolean("features.social-profile",
                        siteConfig.getBoolean("features.user-interaction", true));
    }

    private static String excerpt(String content) {
        String text = Strings.trimToEmpty(content).replaceAll("\\s+", " ");
        return text.length() <= EXCERPT_LIMIT ? text : text.substring(0, EXCERPT_LIMIT) + "…";
    }

    private String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
