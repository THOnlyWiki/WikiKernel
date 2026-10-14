package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import cc.thonly.wikikernel.service.WikiPageService;
import cc.thonly.wikikernel.wikitext.WikiRedirect;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code 特殊:断链页面} / {@code Special:DeadendPages}：正文里没有任何指向其他页面的链接的页面。
 * <p>
 * 重定向页不算断链（它们本身的作用就是跳转）；分类 / 文件 / 外部链接不算页面链接。
 */
@Component
public class DeadendPagesSpecialPage extends AbstractReportPage {

    /** 单次扫描上限。 */
    private static final int MAX_SCAN = 300;

    private final WikiPageMapper wikiPageMapper;
    private final WikiPageService wikiPageService;
    private final AccessControlService accessControlService;

    public DeadendPagesSpecialPage(MessageSource messageSource,
                                   WikiPageMapper wikiPageMapper,
                                   WikiPageService wikiPageService,
                                   AccessControlService accessControlService) {
        super(messageSource);
        this.wikiPageMapper = wikiPageMapper;
        this.wikiPageService = wikiPageService;
        this.accessControlService = accessControlService;
    }

    @Override
    public String name() {
        return "断链页面";
    }

    @Override
    public List<String> aliases() {
        return List.of("DeadendPages", "DeadEndPages");
    }

    @Override
    protected String messageKey() {
        return "deadendPages";
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
            String content = wikiPageService.getLatestContent(page.getId()).orElse("");
            if (WikiRedirect.targetOf(content).isPresent()) {
                continue;
            }
            if (ReportScans.pageLinks(content).isEmpty()) {
                items.add(link(page.getTitle()));
            }
        }
        return page(list(items));
    }
}
