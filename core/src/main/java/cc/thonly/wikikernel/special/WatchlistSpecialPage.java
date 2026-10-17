package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiRevision;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.common.util.Strings;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiRevisionMapper;
import cc.thonly.wikikernel.service.UserService;
import cc.thonly.wikikernel.service.WatchService;
import cc.thonly.wikikernel.time.WikiTime;
import cc.thonly.wikikernel.common.web.WikiUrls;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code 特殊:监视列表} / {@code Special:Watchlist}（仿 MediaWiki）：
 * 监视页面的最近更改 + 监视列表（可移除）+ 进入「编辑监视列表」。
 */
@Component
public class WatchlistSpecialPage extends AbstractReportPage {

    /** 最近更改统计窗口（天）。 */
    private static final int DAYS = 30;

    /** 最多显示多少条更改。 */
    private static final int MAX_REVISIONS = 200;

    private final WatchService watchService;
    private final WikiPageMapper wikiPageMapper;
    private final WikiRevisionMapper wikiRevisionMapper;
    private final UserService userService;
    private final WikiTime wikiTime;

    public WatchlistSpecialPage(MessageSource messageSource,
                                WatchService watchService,
                                WikiPageMapper wikiPageMapper,
                                WikiRevisionMapper wikiRevisionMapper,
                                UserService userService,
                                WikiTime wikiTime) {
        super(messageSource);
        this.watchService = watchService;
        this.wikiPageMapper = wikiPageMapper;
        this.wikiRevisionMapper = wikiRevisionMapper;
        this.userService = userService;
        this.wikiTime = wikiTime;
    }

    @Override
    public String name() {
        return "监视列表";
    }

    @Override
    public List<String> aliases() {
        return List.of("Watchlist");
    }

    @Override
    public String messageKey() {
        return "watchlist";
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
        List<String> watched = watchService.titles(user.getId());
        StringBuilder body = new StringBuilder();
        body.append("<p>")
                .append("<a href=\"").append(escape(WikiUrls.pagePath("特殊:编辑监视列表"))).append("\">")
                .append(escape(message("watch.edit"))).append("</a>")
                .append("</p>");
        if (!watched.isEmpty()) {
            body.append("<h2>").append(escape(message("watch.list", watched.size()))).append("</h2>")
                    .append("<ul class=\"link-list\">");
            for (String title : watched) {
                body.append("<li>").append(link(title))
                        .append(" <button type=\"button\" class=\"watch-remove\" data-title=\"")
                        .append(escape(title)).append("\">")
                        .append(escape(message("watch.remove"))).append("</button></li>");
            }
            body.append("</ul>");
        }
        body.append("<h2>").append(escape(message("watch.recentChanges", DAYS))).append("</h2>");
        body.append(changes(user, watched));
        return page(body.toString());
    }

    /** 监视页面的最近更改（时间 / 页面（差异） / 用户 / 摘要）。 */
    private String changes(WikiUser user, List<String> watched) {
        if (watched.isEmpty()) {
            return empty();
        }
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .in(WikiPage::getTitle, watched)
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false));
        if (pages.isEmpty()) {
            return empty();
        }
        Map<Long, String> titlesById = new LinkedHashMap<>();
        List<Long> pageIds = new ArrayList<>();
        for (WikiPage page : pages) {
            titlesById.put(page.getId(), page.getTitle());
            pageIds.add(page.getId());
        }
        List<WikiRevision> revisions = wikiRevisionMapper.selectList(Wrappers.<WikiRevision>lambdaQuery()
                .in(WikiRevision::getPageId, pageIds)
                .eq(WikiRevision::getDeleted, false)
                .ge(WikiRevision::getCreateAt, LocalDateTime.now().minusDays(DAYS))
                .orderByDesc(WikiRevision::getCreateAt)
                .last("LIMIT " + MAX_REVISIONS));
        Map<Long, String> names = userService.usernames(revisions.stream()
                .map(WikiRevision::getUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList());
        List<List<String>> rows = new ArrayList<>();
        for (WikiRevision revision : revisions) {
            String title = titlesById.getOrDefault(revision.getPageId(), "");
            if (title.isEmpty()) {
                continue;
            }
            String pageUrl = WikiUrls.pagePath(title);
            String pageCell = link(title)
                    + "（<a href=\"" + escape(pageUrl + "?diff=prev&oldid=" + revision.getId()) + "\">"
                    + escape(message("watch.diffLink")) + "</a>"
                    + " | <a href=\"" + escape(pageUrl + "?action=history") + "\">"
                    + escape(message("watch.histLink")) + "</a>）";
            String userCell;
            if (revision.getUserId() == null) {
                String source = Strings.trimToEmpty(revision.getUserText());
                userCell = source.isEmpty()
                        ? escape(message("pageInfo.anonymous"))
                        : "<span title=\"" + escape(message("common.importedAuthorHint")) + "\">"
                        + escape(source) + "</span>";
            } else {
                String name = names.getOrDefault(revision.getUserId(), "#" + revision.getUserId());
                userCell = link("用户:" + name, name);
            }
            rows.add(List.of(
                    escape(wikiTime.format(revision.getCreateAt())),
                    pageCell,
                    userCell,
                    escape(Strings.trimToEmpty(revision.getComment()))));
        }
        return table(List.of(message("report.col.time"), message("report.col.page"),
                message("report.col.user"), message("report.col.summary")), rows);
    }
}
