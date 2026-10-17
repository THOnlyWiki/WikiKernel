package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.extension.SpecialPage;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.common.web.WikiUrls;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.util.HtmlUtils;

import java.util.List;

/**
 * 报告类特殊页基类（仿 MediaWiki 的 Special:LongPages / Special:WantedPages 等）：
 * 统一本地化、HTML 转义与简单的列表 / 表格输出；子类只需实现 {@link #name()}、{@link #messageKey()} 与
 * {@link #render(SpecialPageRequest)}。
 */
abstract class AbstractReportPage implements SpecialPage {

    /** 报告最多展示条数（扫描类报告另外有扫描上限）。 */
    protected static final int REPORT_LIMIT = 50;

    protected final MessageSource messageSource;

    protected AbstractReportPage(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    public String displayName() {
        return message("special." + messageKey());
    }

    /** 消息键后缀：{@code special.<key>} / {@code report.<key>.intro}。 */
    protected abstract String messageKey();

    protected String message(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    /** 顶部说明 + 主体 + 「最多 N 条」提示。 */
    protected String page(String body) {
        return "<p class=\"report-intro\">" + escape(message("report." + messageKey() + ".intro")) + "</p>"
                + body
                + "<p class=\"report-limit\">" + escape(message("report.limitNote", REPORT_LIMIT)) + "</p>";
    }

    protected String list(List<String> items) {
        if (items.isEmpty()) {
            return empty();
        }
        StringBuilder html = new StringBuilder("<ul class=\"link-list\">");
        for (String item : items) {
            html.append("<li>").append(item).append("</li>");
        }
        return html.append("</ul>").toString();
    }

    /** 表格：表头纯文本，单元格已是 HTML（调用方自行转义 / 拼链接）。 */
    protected String table(List<String> headers, List<List<String>> rows) {
        if (rows.isEmpty()) {
            return empty();
        }
        StringBuilder html = new StringBuilder("<table class=\"wikitable\"><thead><tr>");
        for (String header : headers) {
            html.append("<th>").append(escape(header)).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        for (List<String> row : rows) {
            html.append("<tr>");
            for (String cell : row) {
                html.append("<td>").append(Strings.trimToEmpty(cell)).append("</td>");
            }
            html.append("</tr>");
        }
        return html.append("</tbody></table>").toString();
    }

    protected String empty() {
        return "<p class=\"report-empty\">" + escape(message("report.empty")) + "</p>";
    }

    protected static String escape(String value) {
        return HtmlUtils.htmlEscape(Strings.trimToEmpty(value));
    }

    protected static String link(String title) {
        return link(title, title);
    }

    protected static String link(String title, String label) {
        return "<a href=\"" + escape(WikiUrls.pagePath(title)) + "\">" + escape(label) + "</a>";
    }
}
