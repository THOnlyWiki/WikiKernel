package cc.thonly.wikikernel.plugin.comments.service;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.service.AccessCheck;
import cc.thonly.wikikernel.common.service.SiteConfig;
import cc.thonly.wikikernel.common.service.UserStore;
import cc.thonly.wikikernel.common.service.WikiText;
import cc.thonly.wikikernel.plugin.comments.CommentsPlugin;
import cc.thonly.wikikernel.plugin.comments.data.WikiComment;
import cc.thonly.wikikernel.plugin.comments.hook.CommentSectionHook;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * 评论区渲染（仿 MediaWiki Comments 扩展）：把评论列表 + 发表表单渲染成 HTML，
 * 由 {@link CommentSectionHook} 插到正文下方。
 * <p>
 * 输出的 class / data 属性与迁移前 core 的 {@code wiki/view.html} 完全一致，
 * 因此 {@code /plugins/comments/comments.js}（局部刷新）与迁出的 CSS 直接可用。
 */
@Component
public class CommentSection {

    /** 用户组：评论管理员（可以删除任何评论）。 */
    private static final String GROUP_COMMENT_ADMIN = "评论管理员";
    private static final String GROUP_ADMIN = "管理员";
    private static final String GROUP_BUREAUCRAT = "行政员";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CommentStore comments;
    private final UserStore users;
    private final WikiText wikiText;
    private final AccessCheck access;
    private final SiteConfig siteConfig;
    private final MessageSource messageSource;

    public CommentSection(CommentStore comments, UserStore users, WikiText wikiText,
                          AccessCheck access, SiteConfig siteConfig, MessageSource messageSource) {
        this.comments = comments;
        this.users = users;
        this.wikiText = wikiText;
        this.access = access;
        this.siteConfig = siteConfig;
        this.messageSource = messageSource;
    }

    /** 评论功能是否开启（站点设置 {@code features.comments}，默认开启）。 */
    public boolean enabled() {
        return siteConfig == null || siteConfig.getBoolean(CommentsPlugin.ENABLED_KEY, true);
    }

    /** 是否可以发表评论：登录 + 未被封禁 + 对该页面有查看权限。 */
    public boolean canComment(String pageTitle, Viewer viewer) {
        if (viewer == null || !viewer.loggedIn()) {
            return false;
        }
        if (access != null && access.blockNotice(viewer).isPresent()) {
            return false;
        }
        return access == null || access.canRead(pageTitle, viewer);
    }

    /** 是否可以删除：作者本人，或「评论管理员」/ 管理员 / 行政员。 */
    public boolean canDelete(Viewer viewer, WikiComment comment) {
        if (viewer == null || comment == null) {
            return false;
        }
        if (comment.getUserId() != null && comment.getUserId() == viewer.id()) {
            return true;
        }
        return viewer.inGroup(GROUP_COMMENT_ADMIN) || viewer.inGroup(GROUP_ADMIN)
                || viewer.inGroup(GROUP_BUREAUCRAT);
    }

    /** 整个评论区 HTML（列表 + 表单）；调用方负责判断「页面含 {@code <comment />} 且功能开启」。 */
    public String render(String pageTitle, Viewer viewer) {
        List<CommentRow> rows = rows(pageTitle, viewer);
        boolean canComment = canComment(pageTitle, viewer);
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"wiki-comments\" data-api=\"/api/comments\" data-page=\"")
                .append(escape(pageTitle)).append("\">");
        html.append("<h2 class=\"comments-title\">").append(escape(message("comment.title")))
                .append("</h2>");
        if (canComment) {
            html.append("<form class=\"wiki-form comment-form\" method=\"post\" action=\"#\">")
                    .append("<input type=\"hidden\" name=\"parentId\" value=\"\"/>")
                    .append("<div class=\"form-field\"><label for=\"comment-content\">")
                    .append(escape(message("comment.placeholder")))
                    .append("</label><textarea id=\"comment-content\" name=\"content\" rows=\"3\" required=\"required\"></textarea></div>")
                    .append("<div class=\"comment-reply-note\" hidden=\"hidden\"><span>")
                    .append(escape(message("comment.replyingTo")))
                    .append("</span><strong class=\"comment-reply-name\"></strong>")
                    .append("<button type=\"button\" class=\"comment-reply-cancel\">")
                    .append(escape(message("comment.cancelReply")))
                    .append("</button></div>")
                    .append("<div class=\"form-message\" role=\"alert\" hidden=\"hidden\"></div>")
                    .append("<div class=\"form-actions\"><button type=\"submit\" class=\"wiki-button\">")
                    .append(escape(message("comment.submit")))
                    .append("</button></div></form>");
        } else {
            html.append("<p class=\"comment-intro\">")
                    .append(escape(message("comment.loginRequired")))
                    .append("</p>");
        }
        if (rows.isEmpty()) {
            html.append("<p class=\"comment-empty\">").append(escape(message("comment.empty")))
                    .append("</p>");
        } else {
            html.append("<ol class=\"comment-list\">");
            for (CommentRow row : rows) {
                html.append("<li class=\"comment-item")
                        .append(row.deleted() ? " comment-deleted" : "")
                        .append("\" data-id=\"").append(row.id())
                        .append("\" data-depth=\"").append(row.depth()).append("\">");
                if (row.avatarUrl() != null) {
                    html.append("<img class=\"comment-avatar\" src=\"").append(escapeAttribute(row.avatarUrl()))
                            .append("\" alt=\"\"/>");
                }
                html.append("<div class=\"comment-main\"><div class=\"comment-header\">");
                if (row.authorUrl() != null) {
                    html.append("<a class=\"comment-author\" href=\"").append(escapeAttribute(row.authorUrl()))
                            .append("\">").append(escape(row.author())).append("</a>");
                } else {
                    html.append("<span class=\"comment-author\">").append(escape(row.author())).append("</span>");
                }
                html.append("<span class=\"comment-time\">").append(escape(row.timeText())).append("</span></div>");
                if (row.deleted()) {
                    html.append("<div class=\"comment-body comment-tombstone\">")
                            .append(escape(message("comment.deleted"))).append("</div>");
                } else {
                    html.append("<div class=\"comment-body\">").append(row.contentHtml()).append("</div>");
                }
                if (!row.deleted()) {
                    html.append("<div class=\"comment-actions\">");
                    if (canComment) {
                        html.append("<button type=\"button\" class=\"comment-reply-link\" data-id=\"")
                                .append(row.id()).append("\" data-author=\"").append(escapeAttribute(row.author()))
                                .append("\">").append(escape(message("comment.reply"))).append("</button>");
                    }
                    if (row.canDelete()) {
                        html.append("<button type=\"button\" class=\"comment-delete-link\" data-id=\"")
                                .append(row.id()).append("\">")
                                .append(escape(message("comment.delete"))).append("</button>");
                    }
                    html.append("</div>");
                }
                html.append("</div></li>");
            }
            html.append("</ol>");
        }
        return html.append("</div>").toString();
    }

    /** 评论行（拍平成「深度 + 内容」，回复用缩进展示；已删除的显示占位、不渲染内容）。 */
    public List<CommentRow> rows(String pageTitle, Viewer viewer) {
        List<WikiComment> found = comments.list(pageTitle);
        if (found.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = new LinkedHashSet<>();
        for (WikiComment comment : found) {
            if (comment.getUserId() != null) {
                userIds.add(comment.getUserId());
            }
        }
        Map<Long, String> names = users.names(userIds);
        Map<Long, String> avatarFiles = users.avatars(userIds);
        // 头像开关跟随插件 social-profile：新键优先，旧键 features.user-interaction 仍兼容
        boolean showAvatars = siteConfig == null
                || siteConfig.getBoolean("features.social-profile",
                        siteConfig.getBoolean("features.user-interaction", true));
        Map<Long, Integer> depths = new HashMap<>();
        List<CommentRow> rows = new ArrayList<>();
        for (WikiComment comment : found) {
            int depth = comment.getParentId() == null
                    ? 0 : Math.min(4, depths.getOrDefault(comment.getParentId(), 0) + 1);
            depths.put(comment.getId(), depth);
            boolean deleted = Boolean.TRUE.equals(comment.getDeleted());
            String author = comment.getUserId() == null
                    ? message("common.anonymous")
                    : names.getOrDefault(comment.getUserId(), "#" + comment.getUserId());
            String avatarUrl = null;
            if (showAvatars) {
                // 头像 URL 是平台约定（{@code /avatars/<文件名>}）：文件名经 UserStore.avatars 批量取，
                // 空文件名 = 默认头像（头像落盘 / 上传属于 social-profile 插件，评论这边只读 URL）
                avatarUrl = avatarUrl(comment.getUserId() == null ? null : avatarFiles.get(comment.getUserId()));
            }
            rows.add(new CommentRow(
                    comment.getId(),
                    depth,
                    author,
                    comment.getUserId() == null ? null : "/wiki/用户:" + encodeSegment(author),
                    avatarUrl,
                    formatTime(comment.getCreateAt()),
                    deleted,
                    deleted ? "" : wikiText.toHtml(comment.getContent(), pageTitle),
                    !deleted && canDelete(viewer, comment)));
        }
        return List.copyOf(rows);
    }

    /** 头像 URL（空文件名 = 默认头像；与迁移前 AvatarStore.url 的行为一致）。 */
    private static String avatarUrl(String fileName) {
        return fileName == null || fileName.isBlank()
                ? UserStore.DEFAULT_AVATAR_URL : "/avatars/" + fileName;
    }

    /** 一行评论（渲染用）。 */
    public record CommentRow(long id, int depth, String author, String authorUrl, String avatarUrl,
                             String timeText, boolean deleted, String contentHtml, boolean canDelete) {
    }

    private String formatTime(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        String pattern = message("format.dateTime", "yyyy-MM-dd HH:mm");
        try {
            return time.format(DateTimeFormatter.ofPattern(pattern));
        } catch (RuntimeException exception) {
            return time.format(TIME_FORMAT);
        }
    }

    /** 插件文案（无参数、无兜底）：漏翻时显示翻译键本身，绝不回落中文。 */
    public String message(String key) {
        return message(key, key);
    }

    /** 插件文案；{@code fallback} 只在拿不到 MessageSource（独立运行 / 单元测试）或解析抛错时使用。 */
    public String message(String key, String fallback) {
        if (messageSource == null) {
            return fallback;
        }
        try {
            return messageSource.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static String encodeSegment(String value) {
        return URLEncoder.encode(value == null ? "" : value.replace(' ', '_'),
                StandardCharsets.UTF_8).replace("+", "%20").replace("%3A", ":");
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    private static String escapeAttribute(String value) {
        return escape(value).replace("\"", "&quot;");
    }
}
