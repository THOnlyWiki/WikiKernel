package cc.thonly.wikikernel.plugin.news;

import cc.thonly.wikikernel.common.extension.*;
import cc.thonly.wikikernel.common.web.WikiUrls;
import org.springframework.web.util.HtmlUtils;
import java.util.List;

public class NewsPages implements PageDisplayHook, HtmlHeadProvider {
    private final NewsService news;
    public NewsPages(NewsService news) { this.news = news; }
    public boolean siteWide() { return true; }
    public int order() { return 650; }
    public List<String> headItems(HeadContext context) {
        if (!news.enabled()) return List.of();
        String title = context.pageTitle().replace('_', ' ');
        if (!title.equals("新闻") && !title.startsWith("新闻/")
                && !title.endsWith(":新闻管理") && !title.endsWith(":Newsroom")
                && !context.properties().containsKey("news-used")) return List.of();
        return List.of("<link rel=\"stylesheet\" href=\"/plugins/news/news.css?v=2\">",
                "<script defer src=\"/plugins/news/news.js?v=3\"></script>");
    }

    public String renderMissingPage(PageDisplayContext context) { return renderBody(context); }
    public String renderBody(PageDisplayContext context) {
        if (!news.enabled()) return null;
        if (context.title().equals("新闻")) {
            return "<section class=\"news-portal\" data-news-page><h2>活动资讯</h2>"
                    + "<p>活动定档、重要变更、报名与返图消息。</p>"
                    + "<a href=\"" + WikiUrls.pagePath("特殊:新闻管理") + "\">投稿与管理</a>"
                    + "<form data-news-filter><label>搜索<input name=\"q\" type=\"search\"></label>"
                    + "<label>地区<select name=\"region\"><option value=\"\">全部地区</option></select></label>"
                    + "<label>类型<select name=\"category\"><option value=\"\">全部</option>"
                    + NewsService.CATEGORIES.stream().map(c -> "<option>" + c + "</option>").collect(java.util.stream.Collectors.joining())
                    + "</select></label><button>查询</button></form><div data-news-list>正在载入…</div>"
                    + "<button type=\"button\" data-news-more>加载更多</button></section>";
        }
        if (!context.title().matches("新闻/[0-9]+")) return null;
        try {
            var content = news.article(context.viewer(), Long.parseLong(context.title().substring(3)));
            return "<article class=\"news-article\"><p><a href=\"" + WikiUrls.pagePath("新闻") + "\">← 活动资讯</a></p>"
                    + "<p class=\"news-meta\">" + escape(content.category()) + " · " + escape(content.region())
                    + " · <time data-news-time datetime=\"" + escape(content.publishAt()) + "\"></time></p>"
                    + "<h2>" + escape(content.title()) + "</h2>"
                    + (content.cover().isBlank() ? "" : "<img class=\"news-cover\" alt=\"\" src=\"" + escape(content.cover()) + "\">")
                    + "<p class=\"news-summary\">" + escape(content.summary()) + "</p><div class=\"news-body\">" + escape(content.body()) + "</div>"
                    + (content.sourceUrl().isBlank() ? "" : "<p>来源 <a rel=\"nofollow noopener\" href=\"" + escape(content.sourceUrl()) + "\">"
                    + escape(content.sourceName().isBlank() ? "查看原公告" : content.sourceName()) + "</a></p>")
                    + (content.aiAssisted() ? "<p class=\"news-meta\">本文经 AI 协助起草，并由编辑审核发布。</p>" : "")
                    + (content.activityId() == 0 ? "" : "<p><a href=\"" + WikiUrls.pagePath("线下活动速查表") + "?year="
                    + content.activityYear() + "&amp;id=" + content.activityId() + "\">查看关联活动</a></p>") + "</article>";
        } catch (IllegalArgumentException exception) {
            return "<p>新闻不存在、尚未发布或当前账号无权查看。</p>";
        }
    }
    private static String escape(String value) { return HtmlUtils.htmlEscape(value); }
}
