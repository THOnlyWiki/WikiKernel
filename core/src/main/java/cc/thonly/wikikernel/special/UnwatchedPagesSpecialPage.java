package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.data.WikiUser;
import cc.thonly.wikikernel.common.data.WikiWatch;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.db.mapper.WikiWatchMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PermissionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code 特殊:未被监视页面} / {@code Special:UnwatchedPages}：不在任何用户监视列表里的页面（仅管理员可见）。
 */
@Component
public class UnwatchedPagesSpecialPage extends AbstractReportPage {

    /** 单次扫描上限。 */
    private static final int MAX_SCAN = 2000;

    private final WikiPageMapper wikiPageMapper;
    private final WikiWatchMapper wikiWatchMapper;
    private final PermissionService permissionService;
    private final AccessControlService accessControlService;

    public UnwatchedPagesSpecialPage(MessageSource messageSource,
                                     WikiPageMapper wikiPageMapper,
                                     WikiWatchMapper wikiWatchMapper,
                                     PermissionService permissionService,
                                     AccessControlService accessControlService) {
        super(messageSource);
        this.wikiPageMapper = wikiPageMapper;
        this.wikiWatchMapper = wikiWatchMapper;
        this.permissionService = permissionService;
        this.accessControlService = accessControlService;
    }

    @Override
    public String name() {
        return "未被监视页面";
    }

    @Override
    public List<String> aliases() {
        return List.of("UnwatchedPages");
    }

    @Override
    protected String messageKey() {
        return "unwatchedPages";
    }

    @Override
    public String category() {
        return SpecialPageCategories.MAINTENANCE;
    }

    @Override
    public String render(SpecialPageRequest request) {
        WikiUser user = request.user();
        if (user == null || !permissionService.canManage(user.getId())) {
            return "<p class=\"wiki-error\">" + escape(message("report.unwatchedPages.forbidden")) + "</p>";
        }
        Set<String> watched = new HashSet<>();
        for (WikiWatch watch : wikiWatchMapper.selectList(Wrappers.<WikiWatch>lambdaQuery()
                .select(WikiWatch::getTitle))) {
            if (watch.getTitle() != null) {
                watched.add(watch.getTitle().toLowerCase(Locale.ROOT));
            }
        }
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false)
                .orderByAsc(WikiPage::getTitle)
                .last("LIMIT " + MAX_SCAN));
        List<String> items = new ArrayList<>();
        for (WikiPage page : accessControlService.filterReadable(pages, WikiPage::getTitle, user)) {
            if (items.size() >= REPORT_LIMIT) {
                break;
            }
            if (!watched.contains(page.getTitle().toLowerCase(Locale.ROOT))) {
                items.add(link(page.getTitle()));
            }
        }
        return page(list(items));
    }
}
