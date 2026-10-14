package cc.thonly.wikikernel.special;

import cc.thonly.wikikernel.common.data.WikiPage;
import cc.thonly.wikikernel.common.extension.SpecialPageCategories;
import cc.thonly.wikikernel.common.extension.SpecialPageRequest;
import cc.thonly.wikikernel.db.mapper.WikiPageMapper;
import cc.thonly.wikikernel.service.AccessControlService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code 特殊:受保护页面} / {@code Special:ProtectedPages}：处于编辑保护状态的页面（只有管理员可编辑）。
 */
@Component
public class ProtectedPagesSpecialPage extends AbstractReportPage {

    /** 单次扫描上限。 */
    private static final int MAX_SCAN = 2000;

    private final WikiPageMapper wikiPageMapper;
    private final AccessControlService accessControlService;

    public ProtectedPagesSpecialPage(MessageSource messageSource,
                                     WikiPageMapper wikiPageMapper,
                                     AccessControlService accessControlService) {
        super(messageSource);
        this.wikiPageMapper = wikiPageMapper;
        this.accessControlService = accessControlService;
    }

    @Override
    public String name() {
        return "受保护页面";
    }

    @Override
    public List<String> aliases() {
        return List.of("ProtectedPages");
    }

    @Override
    protected String messageKey() {
        return "protectedPages";
    }

    @Override
    public String category() {
        return SpecialPageCategories.MAINTENANCE;
    }

    @Override
    public String render(SpecialPageRequest request) {
        List<WikiPage> pages = wikiPageMapper.selectList(Wrappers.<WikiPage>lambdaQuery()
                .eq(WikiPage::getProtectedPage, true)
                .eq(WikiPage::getDeleted, false)
                .eq(WikiPage::getHided, false)
                .orderByAsc(WikiPage::getTitle)
                .last("LIMIT " + MAX_SCAN));
        List<List<String>> rows = new ArrayList<>();
        for (WikiPage page : accessControlService.filterReadable(pages, WikiPage::getTitle, request.user())) {
            if (rows.size() >= REPORT_LIMIT) {
                break;
            }
            rows.add(List.of(link(page.getTitle()), escape(message("report.protectedPages.edit"))));
        }
        return page(table(List.of(message("report.col.page"), message("report.col.protection")), rows));
    }
}
