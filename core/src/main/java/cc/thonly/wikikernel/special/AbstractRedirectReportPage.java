package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.web.WikiTitles;
import cc.thonly.wikikernel.wikitext.WikiRedirect;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 重定向报告（仿 MediaWiki）：{@code 特殊:重定向列表} / {@code 特殊:双重重定向} / {@code 特殊:损坏的重定向}。
 */
abstract class AbstractRedirectReportPage extends AbstractReportPage {

    /** 单次扫描上限。 */
    private static final int MAX_SCAN = 300;

    /** 报告种类。 */
    protected enum Mode {
        /** 全部重定向。 */
        LIST,
        /** 指向的重定向页本身也是重定向。 */
        DOUBLE,
        /** 目标页面不存在。 */
        BROKEN
    }

    private final WikiPageMapper wikiPageMapper;
    private final WikiPageService wikiPageService;
    private final AccessControlService accessControlService;

    protected AbstractRedirectReportPage(MessageSource messageSource,
                                         WikiPageMapper wikiPageMapper,
                                         WikiPageService wikiPageService,
                                         AccessControlService accessControlService) {
        super(messageSource);
        this.wikiPageMapper = wikiPageMapper;
        this.wikiPageService = wikiPageService;
        this.accessControlService = accessControlService;
    }

    protected abstract Mode mode();

    @Override
    public String messageKey() {
        return switch (mode()) {
            case DOUBLE -> "doubleRedirects";
            case BROKEN -> "brokenRedirects";
            default -> "listRedirects";
        };
    }

    @Override
    public String category() {
        return SpecialPageCategories.REDIRECTS;
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
            String content = wikiPageService.getLatestContent(page.getId()).orElse("");
            Optional<String> redirect = WikiRedirect.targetOf(content, page.getTitle());
            if (redirect.isEmpty()) {
                continue;
            }
            String target = WikiTitles.normalize(redirect.get());
            Optional<WikiPage> targetPage = wikiPageService.getByTitle(target);
            switch (mode()) {
                case LIST -> items.add(link(page.getTitle()) + " → " + link(target));
                case BROKEN -> {
                    if (targetPage.isEmpty()) {
                        items.add(link(page.getTitle()) + " → " + link(target));
                    }
                }
                case DOUBLE -> {
                    if (targetPage.isPresent()) {
                        String targetContent = wikiPageService.getLatestContent(targetPage.get().getId()).orElse("");
                        Optional<String> second = WikiRedirect.targetOf(targetContent, targetPage.get().getTitle());
                        if (second.isPresent()) {
                            items.add(link(page.getTitle()) + " → " + link(target)
                                    + " → " + link(WikiTitles.normalize(second.get())));
                        }
                    }
                }
            }
        }
        return page(list(items));
    }
}
