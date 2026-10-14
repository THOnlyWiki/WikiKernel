package cc.thonly.wikikernel.plugin.comments.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.plugin.comments.service.CommentSection;
import java.util.List;

/**
 * 评论区的脚本与样式（站点级注入：任何页面都可能出现评论区）：
 * <ul>
 *     <li>{@code /plugins/comments/comments.css}（论坛 / 贴吧式紧凑样式，原在 core 的 base.css）；</li>
 *     <li>{@code window.WIKI_CONFIG.comments = true}；</li>
 *     <li>{@code comments.js}（发表 / 回复 / 删除后只刷新评论区，保留已输入的草稿）。</li>
 * </ul>
 */
public class CommentsHeadProvider implements HtmlHeadProvider {

    private final CommentSection section;

    public CommentsHeadProvider(CommentSection section) {
        this.section = section;
    }

    @Override
    public int order() {
        return 650;
    }

    @Override
    public boolean siteWide() {
        return true;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (!section.enabled()) {
            return List.of();
        }
        return List.of(
                "<link rel=\"stylesheet\" href=\"/plugins/comments/comments.css\">",
                "<script>window.WIKI_CONFIG = window.WIKI_CONFIG || {};"
                        + "window.WIKI_CONFIG.comments = true;"
                        + "window.WIKI_MESSAGES = Object.assign(window.WIKI_MESSAGES || {}, {"
                        + "commentDeleteConfirm: " + json(section.message("comment.deleteConfirm")) + ","
                        + "commentEmpty: " + json(section.message("comment.contentEmpty")) + ","
                        + "commentPosted: " + json(section.message("comment.posted")) + ","
                        + "commentDeleted: " + json(section.message("comment.deleteSuccess"))
                        + "});</script>",
                "<script defer src=\"/plugins/comments/comments.js\"></script>");
    }

    private static String json(String value) {
        return "\"" + (value == null ? "" : value
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("<", "\\u003c").replace(">", "\\u003e")) + "\"";
    }
}
