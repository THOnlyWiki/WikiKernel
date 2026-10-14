package cc.thonly.wikikernel.web;

import cc.thonly.wikikernel.wikitext.WikiSections;
import org.springframework.web.util.HtmlUtils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 段落编辑链接（仿 MediaWiki 的 {@code mw-editsection}）：
 * 在渲染完成的正文里，给**页面源码中真实存在**的标题注入「[编辑]」链接
 * （{@code ?action=edit&section=N}）；模板展开产生的标题匹配不到源码段，不注入。
 * <p>
 * 只处理渲染器生成的带 {@code id} 的标题（{@code <h2 id="…">}），不会误伤微件输出里的原始 HTML 标题。
 */
public final class SectionEditLinks {

    /** 渲染器生成的标题（必须有 id）。 */
    private static final Pattern RENDERED_HEADING =
            Pattern.compile("<h([1-6])\\s+id=\"[^\"]*\"[^>]*>.*?</h\\1>", Pattern.DOTALL);

    private SectionEditLinks() {
    }

    /**
     * 注入段落编辑链接。
     *
     * @param html         渲染完成的正文 HTML
     * @param title        页面标题（生成编辑链接用）
     * @param wikitext     该版本的源码（用于核对标题与段号）
     * @param label        链接文字（如「编辑」）
     * @param hintTemplate 链接 title 提示，{@code $1} 会替换成段落标题
     */
    public static String inject(String html, String title, String wikitext,
                                String label, String hintTemplate) {
        if (html == null || html.isEmpty() || wikitext == null || wikitext.isEmpty()) {
            return html;
        }
        List<WikiSections.Section> sections = WikiSections.parse(wikitext);
        if (sections.isEmpty()) {
            return html;
        }
        Matcher matcher = RENDERED_HEADING.matcher(html);
        StringBuilder out = new StringBuilder();
        int next = 0;
        while (matcher.find()) {
            String headingHtml = matcher.group(0);
            String replacement = headingHtml;
            if (next < sections.size()) {
                WikiSections.Section section = sections.get(next);
                int level = matcher.group(1).charAt(0) - '0';
                String text = plain(headingHtml);
                if (section.level() == level && plain(section.heading()).equals(text)) {
                    replacement = insertBeforeClose(headingHtml, link(title, section, label, hintTemplate));
                    next++;
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String link(String title, WikiSections.Section section, String label, String hintTemplate) {
        String href = WikiUrls.pagePath(title) + "?action=edit&section=" + section.index();
        String headingText = WikiSections.displayHeading(section.heading());
        if (headingText == null) {
            headingText = section.heading();
        }
        String hint = (hintTemplate == null ? label : hintTemplate).replace("$1", headingText);
        return "<span class=\"mw-editsection\">"
                + "<span class=\"mw-editsection-bracket\">[</span>"
                + "<a href=\"" + HtmlUtils.htmlEscape(href) + "\" title=\"" + HtmlUtils.htmlEscape(hint) + "\">"
                + HtmlUtils.htmlEscape(label) + "</a>"
                + "<span class=\"mw-editsection-bracket\">]</span></span>";
    }

    private static String insertBeforeClose(String headingHtml, String link) {
        int close = headingHtml.lastIndexOf("</h");
        return close < 0 ? headingHtml + link : headingHtml.substring(0, close) + link + headingHtml.substring(close);
    }

    /** 标题纯文本：去掉行内 wiki 标记、脚注上标与 HTML 标签、还原实体、压缩空白，用于源码 ↔ 渲染结果核对。 */
    private static String plain(String text) {
        String value = text == null ? "" : text;
        // 源码侧的脚注标签整体去掉（渲染侧只剩上标，同样要去掉）
        value = value.replaceAll("(?is)<ref[^>]*>.*?</ref>", "")
                .replaceAll("(?is)<ref[^>]*/?>", "")
                .replaceAll("(?is)<references?[^>]*/?>", "");
        value = value.replaceAll("(?s)<sup[^>]*class=\"[^\"]*reference[^\"]*\"[^>]*>.*?</sup>", "");
        value = value.replace("'''''", "").replace("'''", "").replace("''", "");
        value = value.replaceAll("\\[\\[([^\\[\\]|]*)\\|([^\\[\\]]*)\\]\\]", "$2");
        value = value.replaceAll("\\[\\[([^\\[\\]]*)\\]\\]", "$1");
        value = value.replaceAll("<[^>]*>", "");
        value = HtmlUtils.htmlUnescape(value);
        return value.replaceAll("\\s+", " ").trim();
    }
}
