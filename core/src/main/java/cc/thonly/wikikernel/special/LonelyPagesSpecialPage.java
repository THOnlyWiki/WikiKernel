package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.PageLinkService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code 特殊:孤立页面} / {@code Special:LonelyPages}：没有任何页面链接到它的页面。
 */
@Component
public class LonelyPagesSpecialPage extends AbstractReportPage {

    /** 单次扫描上限。 */
    private static final int MAX_SCAN = 300;

    private final WikiPageMapper wikiPageMapper;
    private final PageLinkService pageLinkService;
    private final AccessControlService accessControlService;

    public LonelyPagesSpecialPage(MessageSource messageSource,
                                  WikiPageMapper wikiPageMapper,
                                  PageLinkService pageLinkService,
                                  AccessControlService accessControlService) {
        super(messageSource);
        this.wikiPageMapper = wikiPageMapper;
        this.pageLinkService = pageLinkService;
        this.accessControlService = accessControlService;
    }

    @Override
    public String name() {
        return "孤立页面";
    }

    @Override
    public List<String> aliases() {
        return List.of("LonelyPages");
    }

    @Override
    public String messageKey() {
        return "lonelyPages";
    }

    @Override
    public String category() {
        return SpecialPageCategories.MAINTENANCE;
    }

    @Override
    public String render(SpecialPageRequest request) {
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false)
                .orderByAsc(WikiPage::getTitle)
                .last("LIMIT " + MAX_SCAN));
        List<String> items = new ArrayList<>();
        for (WikiPage page : accessControlService.filterReadable(pages, WikiPage::getTitle, request.user())) {
            if (items.size() >= REPORT_LIMIT) {
                break;
            }
            if (pageLinkService.pagesLinkingTo(page.getTitle(), 1).isEmpty()) {
                items.add(link(page.getTitle()));
            }
        }
        return page(list(items));
    }
}
