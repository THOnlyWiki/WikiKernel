package cc.thonly.wikikernel.plugin.moderation.hook;

import cc.thonly.wikikernel.common.extension.EditContext;
import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.common.service.PageSnapshot;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.moderation.ModerationSupport;
import cc.thonly.wikikernel.plugin.moderation.service.ModerationService;

/**
 * 审核的**保存前钩子**（仿 MediaWiki Moderation 在 {@code EditFilterMergedContent} 阶段把编辑写进队列）：
 * 没有豁免权的用户的编辑 / 创建不落盘，而是进审核队列，并让 core 按 {@code redirect} 跳到
 * 「已提交，等待审核」提示（{@code ?moderation=pending}，与迁移前一致）。
 * <p>
 * 覆盖网页编辑表单与 {@code api.php?action=edit}（来源见 {@link EditContext#source()}）；
 * <b>AI 代理的提交（{@code source=ai}）不经审核</b>——迁移前 core 只在网页 / API 路径检查审核，
 * AI 内容由用户自己核对后直接保存（AI 界面把 {@code HANDLED} 当作「被阻止」，所以这里必须放行）。
 * <p>
 * 核心保存的正文是「实际会写入的正文」：段落编辑已经在 core 侧合并成整页内容后再交给钩子，
 * 所以通过审核后不会丢掉其它段落。
 */
public class ModerationEditGuard implements EditGuard {

    private final ModerationService service;
    private final ModerationSupport support;
    private final WikiPageStore wikiPageStore;

    public ModerationEditGuard(ModerationService service, ModerationSupport support, WikiPageStore wikiPageStore) {
        this.service = service;
        this.support = support;
        this.wikiPageStore = wikiPageStore;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public Outcome beforeSave(EditContext context) {
        if (!service.enabled() || !service.shouldModerate(context.viewer())) {
            return Outcome.allow();
        }
        if (EditContext.SOURCE_AI.equals(context.source())) {
            // AI 代理的提案由用户核对后直接保存（迁移前不进队列；AI 界面也不认 HANDLED）
            return Outcome.allow();
        }
        Long pageId = wikiPageStore.byTitle(context.title())
                .map(PageSnapshot::id)
                .orElse(null);
        boolean aiAssisted = "1".equals(context.parameters().getOrDefault("aiAssisted", ""))
                || "true".equalsIgnoreCase(context.parameters().getOrDefault("aiAssisted", ""));
        String aiModel = context.parameters().getOrDefault("aiModel", "");
        service.submitEdit(context.viewer(), pageId, context.baseRevisionId(), context.title(),
                context.content(), context.summary(), context.minor(), aiAssisted, aiModel);
        return Outcome.handled(ModerationSupport.pagePath(context.title()) + "?moderation=pending");
    }
}
