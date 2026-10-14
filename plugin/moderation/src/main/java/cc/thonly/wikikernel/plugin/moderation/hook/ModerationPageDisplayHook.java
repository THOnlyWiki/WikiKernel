package cc.thonly.wikikernel.plugin.moderation.hook;

import cc.thonly.wikikernel.plugin.moderation.data.WikiPendingChange;
import cc.thonly.wikikernel.common.extension.PageDisplayContext;
import cc.thonly.wikikernel.common.extension.PageDisplayHook;
import cc.thonly.wikikernel.common.service.WikiText;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.plugin.moderation.ModerationSupport;
import cc.thonly.wikikernel.plugin.moderation.service.ModerationService;

import java.util.List;

/**
 * 审核的**页面显示钩子**（原 core 的 {@code WikiPageController} 里那几段审核提示 / 待审创建页）：
 * <ul>
 *     <li>{@link #beforeContent}：正文之前的提示条 ——
 *     「此页面有 N 条待审更改」（提交人 / 审核员可见，其他人看到的是已通过版本）与
 *     「你的更改已提交，正在等待审核」（保存后重定向带 {@code ?moderation=pending}）；</li>
 *     <li>{@link #renderMissingPage}：页面**不存在**但有待审创建时，提交人 / 审核员看到
 *     「待审创建」页（待审内容预览 + 队列链接），其他人照常 404。</li>
 * </ul>
 * 开关关闭时什么都不做（页面与没有插件时一致）。
 */
public class ModerationPageDisplayHook implements PageDisplayHook {

    private final ModerationService service;
    private final ModerationSupport support;
    private final WikiText wikiText;

    public ModerationPageDisplayHook(ModerationService service, ModerationSupport support, WikiText wikiText) {
        this.service = service;
        this.support = support;
        this.wikiText = wikiText;
    }

    @Override
    public int order() {
        return 150;
    }

    // ==================== 正文之前的提示条 ====================

    @Override
    public String beforeContent(PageDisplayContext context) {
        if (!service.enabled()) {
            return null;
        }
        StringBuilder html = new StringBuilder();
        // 保存成功后的一次性提示（提交后重定向带 ?moderation=pending）
        if (context.query().containsKey("moderation")) {
            html.append("<div class=\"wiki-warning moderation-submitted\">")
                    .append(ModerationSupport.escape(support.message("moderation.submitted")))
                    .append("</div>");
        }
        // 页面有待审更改（提交人 / 审核员可见）
        if (!context.oldRevision()) {
            List<WikiPendingChange> pendings = service.pendingForPage(context.title());
            if (!pendings.isEmpty() && canSee(context, pendings)) {
                html.append("<div class=\"wiki-warning moderation-note\">")
                        .append("<span>")
                        .append(ModerationSupport.escape(pendings.size() == 1
                                ? support.message("moderation.bannerOne")
                                : support.message("moderation.bannerMany", null,
                                pendings.size())))
                        .append("</span>")
                        .append("<a class=\"moderation-note-link\" href=\"")
                        .append(queueUrl(context.title()))
                        .append("\">")
                        .append(ModerationSupport.escape(support.message("moderation.viewQueue")))
                        .append("</a>")
                        .append("</div>");
            }
        }
        return html.isEmpty() ? null : html.toString();
    }

    // ==================== 待审创建页 ====================

    @Override
    public String renderMissingPage(PageDisplayContext context) {
        if (!service.enabled()) {
            return null;
        }
        List<WikiPendingChange> pendings = service.pendingForPage(context.title());
        if (pendings.isEmpty() || !canSee(context, pendings)) {
            return null;
        }
        WikiPendingChange latest = pendings.get(0);
        String previewHtml = latest == null ? ""
                : wikiText.toHtml(Strings.trimToEmpty(latest.getContent()), context.title());
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"wiki-warning moderation-note\">")
                .append("<p>")
                .append(ModerationSupport.escape(support.message("moderation.pendingCreate", null, pendings.size())))
                .append("</p>");
        if (!ModerationSupport.isReviewer(context.viewer())) {
            html.append("<p>")
                    .append(ModerationSupport.escape(support.message("moderation.pendingCreateAuthor")))
                    .append("</p>");
        }
        html.append("<p><a class=\"moderation-note-link\" href=\"")
                .append(queueUrl(context.title()))
                .append("\">")
                .append(ModerationSupport.escape(support.message("moderation.viewQueue")))
                .append("</a></p></div>")
                .append("<h2>")
                .append(ModerationSupport.escape(support.message("moderation.pendingPreview")))
                .append("</h2>")
                .append("<div class=\"wiki-content moderation-preview\">")
                .append(previewHtml)
                .append("</div>");
        return html.toString();
    }

    /** 提交人 / 审核员才能看到某标题的待审内容（其他人照常 404 / 无提示，不暴露存在性）。 */
    private boolean canSee(PageDisplayContext context, List<WikiPendingChange> pendings) {
        if (ModerationSupport.isReviewer(context.viewer())) {
            return true;
        }
        if (context.viewer() == null || !context.viewer().loggedIn()) {
            return false;
        }
        long viewerId = context.viewer().id();
        return pendings.stream()
                .anyMatch(change -> change.getUserId() != null && change.getUserId() == viewerId);
    }

    /** 特殊:审核 的「只看本页面」链接。 */
    private static String queueUrl(String title) {
        return ModerationSupport.pagePath("特殊:审核") + "?title=" + ModerationSupport.encodeQuery(title);
    }
}
