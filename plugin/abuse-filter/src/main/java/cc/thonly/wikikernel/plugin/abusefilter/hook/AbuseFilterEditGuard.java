package cc.thonly.wikikernel.plugin.abusefilter.hook;

import cc.thonly.wikikernel.common.extension.EditContext;
import cc.thonly.wikikernel.common.extension.EditGuard;
import cc.thonly.wikikernel.common.service.WikiPageStore;
import cc.thonly.wikikernel.plugin.abusefilter.AbuseFilterSupport;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseFilterService;
import cc.thonly.wikikernel.plugin.abusefilter.service.AbuseVariables;

import java.util.Map;

/**
 * 滥用过滤器的**编辑前钩子**（仿 MediaWiki 的 {@code EditFilterMergedContent}）：
 * 网页编辑表单（{@code source=web}）、{@code api.php?action=edit}（{@code source=api}）与
 * AI 代理提交（{@code source=ai}）都走这里。
 * <ul>
 *     <li>{@code disallow} → {@link Outcome#block(String)}（网页回到编辑表单并保留内容，API 报错）；</li>
 *     <li>{@code warn} / {@code degrade} → 网页**第一次**提交时回到编辑表单要求再次确认
 *     （会话里记下已确认的过滤器），再次提交即放行并在保存后提示；API / AI 直接放行并回 warnings；</li>
 *     <li>{@code tag} → 经 {@link EditContext#addTag(String)} 把标签交给 core 写入版本；</li>
 *     <li>其余操作（{@code throttle} / {@code block} / {@code blockautopromote}）在求值时已经执行。</li>
 * </ul>
 * 功能开关关闭时直接放行（不评估任何过滤器，与迁移前一致）。
 */
public class AbuseFilterEditGuard implements EditGuard {

    /** 会话键：已经确认过警告的「页面 + 过滤器」标记。 */
    static final String SESSION_WARN_KEY = "wikikernel.abuseFilter.warnConfirmed";

    private final AbuseFilterService service;
    private final AbuseFilterSupport support;
    private final WikiPageStore pageStore;

    public AbuseFilterEditGuard(AbuseFilterService service, AbuseFilterSupport support, WikiPageStore pageStore) {
        this.service = service;
        this.support = support;
        this.pageStore = pageStore;
    }

    @Override
    public int order() {
        // 在验证码（confirm-edit，order 0）之后评估：先通过验证码，再看滥用过滤器
        return 50;
    }

    @Override
    public Outcome beforeSave(EditContext context) {
        if (!service.enabled()) {
            return Outcome.allow();
        }
        long pageId = pageId(context);
        String oldText = pageId > 0 && pageStore != null
                ? pageStore.content(pageId).orElse("") : "";
        Map<String, Object> variables = AbuseVariables.edit(pageId, context.title(), context.newPage(),
                oldText, context.content(), context.summary(), context.minor());
        // 与迁移前一致：创建与编辑都用 action=edit（用 page_is_new 区分）
        AbuseFilterService.Outcome outcome = service.evaluate("edit", context.viewer(),
                context.clientIp(), variables);
        if (outcome.disallowed()) {
            return Outcome.block(support.disallowedText(outcome.messages()));
        }
        if (!outcome.hasWarnings()) {
            outcome.tagList().forEach(context::addTag);
            return Outcome.allow();
        }
        String warning = support.warnText(outcome.warnings());
        if (context.web() && !confirmed(context, outcome)) {
            // 网页：记下「已经警告过」，回到编辑表单要求再次点击保存（仿 MediaWiki 的 warn 二次确认）
            context.session().setAttribute(SESSION_WARN_KEY, confirmToken(context, outcome));
            return Outcome.block(warning);
        }
        context.session().removeAttribute(SESSION_WARN_KEY);
        outcome.tagList().forEach(context::addTag);
        return Outcome.warn(warning);
    }

    /** 页面 ID（新页面 = 0；已有页面按标题查）。 */
    private long pageId(EditContext context) {
        if (context.newPage() || pageStore == null) {
            return 0;
        }
        return pageStore.byTitle(context.title()).map(snapshot -> snapshot.id()).orElse(0L);
    }

    /** 二次确认标记：页面标题 + 命中的过滤器编号（同一页面同一批过滤器只确认一次）。 */
    private static String confirmToken(EditContext context, AbuseFilterService.Outcome outcome) {
        return context.title() + "|" + outcome.filterIds();
    }

    private static boolean confirmed(EditContext context, AbuseFilterService.Outcome outcome) {
        Object value = context.session().getAttribute(SESSION_WARN_KEY);
        return value instanceof String token && token.equals(confirmToken(context, outcome));
    }
}
