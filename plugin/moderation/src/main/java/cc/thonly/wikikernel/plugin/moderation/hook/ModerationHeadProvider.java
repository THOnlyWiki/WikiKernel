package cc.thonly.wikikernel.plugin.moderation.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.plugin.moderation.ModerationSupport;

import java.util.List;

/**
 * 审核的前端资源（站点级注入，原 core 的 {@code fragments/head.html} 脚本行 + {@code base.css} 样式）：
 * <ul>
 *     <li>{@code /plugins/moderation/moderation.css}（队列页样式，原在 core 的 base.css）；</li>
 *     <li>{@code /plugins/moderation/moderation.js}（通过 / 拒绝按钮，POST /api/moderation/*）。</li>
 * </ul>
 * 只有开启审核时才注入（关闭时队列页只显示「未启用」提示）。
 */
public final class ModerationHeadProvider implements HtmlHeadProvider {

    private final ModerationSupport support;

    public ModerationHeadProvider(ModerationSupport support) {
        this.support = support;
    }

    @Override
    public int order() {
        return 655;
    }

    @Override
    public boolean siteWide() {
        return true;
    }

    @Override
    public List<String> headItems(HeadContext context) {
        if (!support.enabled()) {
            return List.of();
        }
        return List.of(
                "<link rel=\"stylesheet\" href=\"/plugins/moderation/moderation.css\">",
                "<script>window.WIKI_MESSAGES = Object.assign(window.WIKI_MESSAGES || {}, {"
                        + "moderationApprove: " + ModerationSupport.json(support.message("moderation.approve")) + ","
                        + "moderationReject: " + ModerationSupport.json(support.message("moderation.reject")) + ","
                        // core 的上传 / 编辑脚本在收到 pending 标记时会用这两个键提示「已进队列」
                        + "moderationUploadPending: "
                        + ModerationSupport.json(support.message("moderation.uploadPendingBackend")) + ","
                        + "moderationEditPending: "
                        + ModerationSupport.json(support.message("moderation.editPendingBackend"))
                        + "});</script>",
                "<script defer src=\"/plugins/moderation/moderation.js\"></script>");
    }
}
