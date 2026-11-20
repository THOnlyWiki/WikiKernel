package cc.thonly.wikikernel.plugin.news;

import cc.thonly.wikikernel.common.extension.ParserTag;
import cc.thonly.wikikernel.common.extension.ParserTagCall;

/** Explicit news embedding; each instance has its own item limit. */
public final class NewsListTag implements ParserTag {
    private final NewsService news;

    public NewsListTag(NewsService news) { this.news = news; }
    public String name() { return "news-list"; }

    public String render(ParserTagCall call) {
        call.context().setProperty("news-used", "1");
        int limit = NewsService.HOMEPAGE_LIMIT;
        try {
            String value = call.attributes().getOrDefault("limit", "");
            int requested = Integer.parseInt(value.strip());
            if (requested > 0) limit = Math.min(requested, NewsService.MAX_LIST_LIMIT);
        } catch (NumberFormatException ignored) {
            // Old tags and invalid attributes retain the original six-item list.
        }
        return news.enabled() ? "<div class=\"news-list news-embed\" data-news-list data-news-limit=\""
                + limit + "\" aria-live=\"polite\">正在载入活动资讯…</div>" : "";
    }
}
