package cc.thonly.wikikernel.plugin.comments.service;

import cc.thonly.wikikernel.common.extension.Viewer;
import cc.thonly.wikikernel.common.web.WikiUrls;
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
import java.util.LinkedHashMap;
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

    /** 评论功能是否自动显示（站点设置 {@code comments.auto-display}，默认开启）。 */
    public boolean autoDisplay() {
        return siteConfig == null || siteConfig.getBoolean(CommentsPlugin.AUTO_DISPLAY_KEY, true);
    }

    /** 整个评论区 HTML（列表 + 表单）；自动显示模式由调用方传入 {@code manual=false}。 */
    public String render(String pageTitle, Viewer viewer) {
        return render(pageTitle, viewer, false);
    }

    /** 整个评论区 HTML；{@code manual=true} 表示页面显式写了 {@code <comment />}。 */
    public String render(String pageTitle, Viewer viewer, boolean manual) {
        List<CommentRow> rows = rows(pageTitle, viewer);
        List<CommentNode> threads = threads(rows);
        boolean canComment = canComment(pageTitle, viewer);
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"wiki-comments flow-comments\" data-api=\"/api/comments\" data-page=\"")
                .append(escape(pageTitle)).append("\" data-mode=\"")
                .append(manual ? "manual" : "auto").append("\" data-auto=\"")
                .append(autoDisplay() ? "true" : "false").append("\">");
        html.append("<div class=\"comments-header\"><h2 class=\"comments-title\">")
                .append(escape(message("comment.title")))
                .append("</h2><span class=\"comments-count\">")
                .append(escape(messageArgs("comment.count", rows.size())))
                .append("</span></div>");
        if (canComment) {
            html.append(composer(null, true));
        } else {
            html.append("<p class=\"comment-intro\">")
                    .append(escape(message("comment.loginRequired")))
                    .append("</p>");
        }
        if (rows.isEmpty()) {
            html.append("<p class=\"comment-empty\">").append(escape(message("comment.empty")))
                    .append("</p>");
        } else {
            html.append("<ol class=\"comment-list comment-thread-list\">");
            for (CommentNode node : threads) {
                appendNode(html, node, canComment, new LinkedHashSet<>());
            }
            html.append("</ol>");
        }
        if (canComment) {
            html.append("<template id=\"comment-composer-template\">")
                    .append(composer(null, false))
                    .append("</template>");
        }
        return html.append("</div>").toString();
    }

    /** 递归渲染一个评论节点（子回复放在同一节点内部的嵌套列表里）。 */
    private void appendNode(StringBuilder html, CommentNode node, boolean canComment, Set<Long> path) {
        CommentRow row = node.row();
        if (!path.add(row.id())) {
            return;
        }
        html.append("<li class=\"comment-item comment-thread")
                .append(row.deleted() ? " comment-deleted" : "")
                .append(row.depth() > 0 ? " comment-reply" : "")
                .append("\" id=\"comment-").append(row.id())
                .append("\" data-id=\"").append(row.id())
                .append("\" data-depth=\"").append(row.depth()).append("\" data-parent-id=\"")
                .append(row.parentId() == null ? "" : row.parentId()).append("\">");
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
        html.append("<span class=\"comment-time\">").append(escape(row.timeText())).append("</span>")
                .append("<a class=\"comment-permalink\" href=\"#comment-").append(row.id())
                .append("\" aria-label=\"#\">#</a></div>");
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
        if (!node.replies().isEmpty()) {
            html.append("<ol class=\"comment-children\">");
            for (CommentNode child : node.replies()) {
                appendNode(html, child, canComment, path);
            }
            html.append("</ol>");
        }
        html.append("</div></li>");
        path.remove(row.id());
    }

    /** 评论输入表单；{@code top=true} 是页面顶部的主表单，否则是可克隆的就地回复表单。 */
    private String composer(Long parentId, boolean top) {
        String idAttr = top ? " id=\"comment-content\"" : "";
        return "<form class=\"wiki-form comment-form" + (top ? "" : " comment-inline-form")
                + "\" method=\"post\" action=\"#\">"
                + "<input type=\"hidden\" name=\"parentId\" value=\""
                + (parentId == null ? "" : parentId) + "\"/>"
                + "<div class=\"form-field\"><label" + (top ? " for=\"comment-content\"" : "")
                + ">" + escape(message("comment.placeholder")) + "</label>"
                + "<textarea" + idAttr + " name=\"content\" rows=\"3\" required=\"required\""
                + " placeholder=\"" + escapeAttribute(message("comment.placeholder")) + "\"></textarea></div>"
                + "<div class=\"comment-reply-note\" hidden=\"hidden\"><span>"
                + escape(message("comment.replyingTo")) + "</span><strong class=\"comment-reply-name\"></strong>"
                + "<button type=\"button\" class=\"comment-reply-cancel\">"
                + escape(message("comment.cancelReply")) + "</button></div>"
                + "<div class=\"form-message\" role=\"alert\" hidden=\"hidden\"></div>"
                + "<div class=\"form-actions\"><button type=\"submit\" class=\"wiki-button\">"
                + escape(message("comment.submit")) + "</button></div></form>";
    }

    /** 评论行（带父子关系，回复用嵌套列表展示）。 */
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
                    comment.getParentId(),
                    depth,
                    author,
                    comment.getUserId() == null ? null : WikiUrls.pagePrefix() + "用户:" + encodeSegment(author),
                    avatarUrl,
                    formatTime(comment.getCreateAt()),
                    deleted,
                    deleted ? "" : wikiText.toHtml(comment.getContent(), pageTitle),
                    !deleted && canDelete(viewer, comment)));
        }
        return List.copyOf(rows);
    }

    /** 拍平的评论行 → 树（父评论不存在时按根节点处理，保证任何评论都不会丢）。 */
    public List<CommentNode> threads(List<CommentRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<Long, CommentRow> byId = new LinkedHashMap<>();
        for (CommentRow row : rows) {
            byId.put(row.id(), row);
        }
        Map<Long, List<CommentRow>> children = new LinkedHashMap<>();
        List<CommentRow> roots = new ArrayList<>();
        for (CommentRow row : rows) {
            if (row.parentId() == null || !byId.containsKey(row.parentId())) {
                roots.add(row);
            } else {
                children.computeIfAbsent(row.parentId(), key -> new ArrayList<>()).add(row);
            }
        }
        List<CommentNode> result = new ArrayList<>();
        for (CommentRow root : roots) {
            result.add(node(root, children, new LinkedHashSet<>()));
        }
        return List.copyOf(result);
    }

    private CommentNode node(CommentRow row, Map<Long, List<CommentRow>> children, Set<Long> path) {
        if (!path.add(row.id())) {
            return new CommentNode(row, List.of());
        }
        List<CommentNode> replies = new ArrayList<>();
        for (CommentRow child : children.getOrDefault(row.id(), List.of())) {
            replies.add(node(child, children, path));
        }
        path.remove(row.id());
        return new CommentNode(row, List.copyOf(replies));
    }

    /** 头像 URL（空文件名 = 默认头像；与迁移前 AvatarStore.url 的行为一致）。 */
    private static String avatarUrl(String fileName) {
        return fileName == null || fileName.isBlank()
                ? UserStore.DEFAULT_AVATAR_URL : "/avatars/" + fileName;
    }

    /** 一行评论（渲染用）。 */
    public record CommentRow(long id, Long parentId, int depth, String author, String authorUrl, String avatarUrl,
                             String timeText, boolean deleted, String contentHtml, boolean canDelete) {
    }

    /** 评论树节点（Flow 风格的父子关系）。 */
    public record CommentNode(CommentRow row, List<CommentNode> replies) {
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

    /** 带参数文案（{@code {0}} 等）；缺键时显示键本身。 */
    public String messageArgs(String key, Object... args) {
        if (messageSource == null) {
            return key;
        }
        try {
            return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
        } catch (RuntimeException exception) {
            return key;
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
