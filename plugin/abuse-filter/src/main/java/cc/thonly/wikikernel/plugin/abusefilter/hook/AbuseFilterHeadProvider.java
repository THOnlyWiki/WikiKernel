package cc.thonly.wikikernel.plugin.abusefilter.hook;

import cc.thonly.wikikernel.common.extension.HeadContext;
import cc.thonly.wikikernel.common.extension.HtmlHeadProvider;
import cc.thonly.wikikernel.plugin.abusefilter.AbuseFilterSupport;

import java.util.List;

/**
 * 滥用过滤器的前端资源（站点级注入，与迁移前 core 的 {@code fragments/head.html} 一致）：
 * <ul>
 *     <li>{@code /plugins/abuse-filter/abuse-filter.css}（隐藏 / 删除标记等样式，原在 core 的 base.css）；</li>
 *     <li>{@code /plugins/abuse-filter/abuse-filter.js}（条件编辑器 CodeMirror + 保存 / 恢复 / 测试 / 隐藏日志）；</li>
 *     <li>脚本文案 {@code window.WIKI_MESSAGES}（测试结果表格的表头，原在 core 的 head.html）。</li>
 * </ul>
 * 功能关闭时不注入（与迁移前一致：关闭时页面只显示「未启用」提示）。
 */
public final class AbuseFilterHeadProvider implements HtmlHeadProvider {

    private final AbuseFilterSupport support;

    public AbuseFilterHeadProvider(AbuseFilterSupport support) {
        this.support = support;
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
        if (!support.enabled()) {
            return List.of();
        }
        return List.of(
                "<link rel=\"stylesheet\" href=\"/plugins/abuse-filter/abuse-filter.css\">",
                "<script>window.WIKI_MESSAGES = Object.assign(window.WIKI_MESSAGES || {}, {"
                        + "abuseTestPage: " + AbuseFilterSupport.json(support.message("abusefilter.tablePage")) + ","
                        + "abuseTestUser: " + AbuseFilterSupport.json(support.message("abusefilter.tableUser")) + ","
                        + "abuseTestTime: " + AbuseFilterSupport.json(support.message("abusefilter.tableTime")) + ","
                        + "abuseTestSummary: " + AbuseFilterSupport.json(support.message("abusefilter.tableSummary")) + ","
                        + "abuseTestResult: " + AbuseFilterSupport.json(support.message("abusefilter.tableResult")) + ","
                        + "abusePreviewShow: " + AbuseFilterSupport.json(support.message("abusefilter.previewShow")) + ","
                        + "abusePreviewHide: " + AbuseFilterSupport.json(support.message("abusefilter.previewHide")) + ","
                        + "abusePreviewEmpty: " + AbuseFilterSupport.json(support.message("abusefilter.previewEmpty")) + ","
                        + "abusePreviewFailed: " + AbuseFilterSupport.json(support.message("abusefilter.previewFailed"))
                        + "});</script>",
                "<script defer src=\"/plugins/abuse-filter/abuse-filter.js\"></script>");
    }
}
