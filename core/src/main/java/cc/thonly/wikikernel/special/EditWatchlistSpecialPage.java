package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.service.WatchService;
import cc.thonly.wikikernel.web.WikiUrls;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code 特殊:编辑监视列表} / {@code Special:EditWatchlist}（仿 MediaWiki）：
 * 文本域每行一个标题，保存后整体替换监视列表（{@code POST /api/watch/raw}）。
 */
@Component
public class EditWatchlistSpecialPage extends AbstractReportPage {

    private final WatchService watchService;

    public EditWatchlistSpecialPage(MessageSource messageSource, WatchService watchService) {
        super(messageSource);
        this.watchService = watchService;
    }

    @Override
    public String name() {
        return "编辑监视列表";
    }

    @Override
    public List<String> aliases() {
        return List.of("EditWatchlist");
    }

    @Override
    public String messageKey() {
        return "editWatchlist";
    }

    @Override
    public String category() {
        return SpecialPageCategories.CHANGES;
    }

    @Override
    public String render(SpecialPageRequest request) {
        WikiUser user = request.user();
        if (user == null) {
            return page("<p class=\"report-empty\">" + escape(message("watch.loginRequired")) + "</p>");
        }
        StringBuilder html = new StringBuilder();
        html.append("<p><a href=\"").append(escape(WikiUrls.pagePath("特殊:监视列表"))).append("\">")
                .append(escape(message("watch.title"))).append("</a></p>")
                .append("<form id=\"watchlist-raw-form\" class=\"wiki-form\">")
                .append("<div class=\"form-field\"><label for=\"watchlist-raw\"")
                .append(">").append(escape(message("preferences.watchlist.rawIntro"))).append("</label>")
                .append("<textarea id=\"watchlist-raw\" name=\"watchlist\" rows=\"12\">")
                .append(escape(String.join("\n", watchService.titles(user.getId()))))
                .append("</textarea></div>")
                .append("<div class=\"form-actions\"><button type=\"submit\" class=\"wiki-button\">")
                .append(escape(message("watch.save"))).append("</button></div>")
                .append("<p id=\"watchlist-message\" class=\"form-message\" hidden></p>")
                .append("</form>");
        return page(html.toString());
    }
}
